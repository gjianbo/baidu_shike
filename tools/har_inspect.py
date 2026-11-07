#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
HAR 脱敏 + 接口候选打分（百度网盘 IM 文件库抓包用）

只输出：接口路径 / 参数名 / 响应字段名 / 结构 / 命中特征
不输出：任何 header 值、query 值、请求体值、响应体值、Cookie、Token

重要：点开视频时百度会在「新标签页」打开 pfile/mboxvideo 播放器，
那份请求不在原页面的 HAR 里。请在新标签单独开 DevTools 导出第二份 HAR，
并把两份一起传给脚本。

用法：
    python3 tools/har_inspect.py ~/Downloads/filelist.har
    python3 tools/har_inspect.py filelist.har player.har -o docs/抓包接口清单.md
"""
from __future__ import annotations

import argparse
import base64
import json
import re
import sys
from pathlib import Path
from urllib.parse import parse_qsl, urlparse

NOISE_PATH = re.compile(
    r"(/api/log/|/log/report|/report|/stat|/beacon|/trace|/metrics|/perf|/heatmap"
    r"|/api/analytics|/ztbox|/aiknowledgeapi|/record/notice"
    r"|\.(js|css|png|jpe?g|gif|svg|woff2?|ico|map)(\?|$))",
    re.I,
)
HOST_OK = re.compile(r"baidu|bcebos|pcs", re.I)
# HLS 分片请求量大且无接口信息，单独计数排除
TS_NOISE = re.compile(r"\.ts(\?|$)", re.I)
# 播放链路：IM 附件视频播放页 / mbox 转码流 / 自己盘内播放页
PLAY_PATH = re.compile(
    r"pfile|streampage|play/video|/mbox/msg/(streaming|mediainfo)|streaming|/api/stream|playurl",
    re.I,
)

LIST_KEYS = {"fs_id", "server_filename", "filename", "path", "isdir", "size", "category", "md5"}
TIME_KEYS = {"server_mtime", "local_mtime", "server_ctime", "local_ctime", "mtime", "ctime", "create_time", "share_time"}
LINK_KEYS = {"dlink", "playurl", "streaming", "m3u8", "download_url", "shareid", "fsids"}
# 「最近更新」聚合接口与分页/游标字段（10.10）
# 目录树：单库根目录列表 + 逐级下钻（10.14）
TREE_PATH = re.compile(r"/mbox/group/listshare|/mbox/msg/shareinfo", re.I)
RECENT_PATH = re.compile(r"/imbox/|recentv2|/recent|filelist|/file/list", re.I)
CURSOR_KEYS = {"has_more", "next_cursor", "cursor", "page", "total", "list", "records", "session_id", "server_mtime"}
# 播放凭据 / 归一化易踩坑字段（10.15 真实 HAR 实证）
CRED_KEYS = {"dlink", "msg_id", "gid", "fuk", "from_uk", "to", "fsid", "fs_id",
             "parent_path", "share_root_path", "hasmore", "has_more", "newno",
             "last_msg_time", "timestamp", "file_key", "videotag", "black_tag"}
PATH_HINT = re.compile(r"mbox|imbox|listshare|shareinfo|filelist|/list|group|share|metas|download|sharedir|pfile|recent", re.I)

# 输出自检：出现「凭据字段名 + 具体值」即拒绝写文件
LEAK_PATTERNS = [
    re.compile(r"(?i)(BDUSS|STOKEN|bdstoken|access_token|refresh_token|sign|token|pwd|password)\s*[=:]\s*(?!<REDACTED>)[A-Za-z0-9_\-]{8,}"),
    re.compile(r"(?i)cookie\s*:\s*(?!<REDACTED>)\S+"),
    re.compile(r"\b[A-Za-z0-9_\-]{48,}\b"),  # 长 token / 签名
]


def parse_json(text: str | None, encoding: str | None = None):
    if not text:
        return None
    if encoding == "base64":
        try:
            text = base64.b64decode(text).decode("utf-8", "replace")
        except Exception:
            return None
    try:
        return json.loads(text)
    except Exception:
        return None


def shape(obj, depth: int = 0, max_depth: int = 5):
    """只返回字段名 + 值类型，绝不返回值本身。"""
    if depth > max_depth:
        return "..."
    if isinstance(obj, dict):
        return {k: shape(v, depth + 1, max_depth) for k, v in list(obj.items())[:40]}
    if isinstance(obj, list):
        return [shape(obj[0], depth + 1, max_depth)] if obj else []
    return type(obj).__name__


def collect_keys(obj, depth: int = 0, max_depth: int = 8, out=None) -> set:
    if out is None:
        out = set()
    if depth > max_depth:
        return out
    if isinstance(obj, dict):
        for k, v in obj.items():
            out.add(k)
            collect_keys(v, depth + 1, max_depth, out)
    elif isinstance(obj, list):
        for v in obj[:5]:
            collect_keys(v, depth + 1, max_depth, out)
    return out


def redact_path(path: str) -> str:
    return re.sub(
        r"(?i)(BDUSS|STOKEN|bdstoken|access_token|sign|token)=[^&/]+",
        r"\1=<REDACTED>",
        path,
    )


def main() -> int:
    ap = argparse.ArgumentParser(description="HAR 脱敏 + 文件列表/播放链路接口候选打分")
    ap.add_argument("har", type=Path, nargs="+", help="浏览器导出的 .har 文件，可传多个（如列表页 + 播放器新标签）")
    ap.add_argument("-o", "--output", type=Path, default=None, help="报告输出路径（默认与第一个 HAR 同目录）")
    ap.add_argument("--top", type=int, default=12, help="明细最多列几个接口，默认 12")
    args = ap.parse_args()

    entries, har_names = [], []
    for hp in args.har:
        if not hp.is_file():
            print(f"找不到文件: {hp}", file=sys.stderr)
            return 2
        try:
            data = json.loads(hp.read_text(encoding="utf-8", errors="replace"))
        except Exception as e:
            print(f"HAR 解析失败（{hp.name}，请确认是 'Save all as HAR with content' 导出的 JSON）: {e}", file=sys.stderr)
            return 2
        part = data.get("log", {}).get("entries", [])
        if not part:
            print(f"⚠️ {hp.name} 里没有 entries（导出时勾选 with content）", file=sys.stderr)
        entries.extend(part)
        har_names.append(hp.name)
    if not entries:
        print("HAR 里没有 entries，重新导出时勾选 'with content'", file=sys.stderr)
        return 2

    total, noise, offhost, ts_chunks = len(entries), 0, 0, 0
    rows, play_rows, list_rows, tree_rows = [], [], [], []
    banned: set[str] = set()  # HAR 中出现过的敏感值，用于输出自检

    for e in entries:
        req = e.get("request", {}) or {}
        resp = e.get("response", {}) or {}
        url = req.get("url", "") or ""
        u = urlparse(url)

        # 先把所有敏感值收进黑名单；这些值本身永不进入报告
        for h in (req.get("headers") or []):
            hv = h.get("value", "") or ""
            if len(hv) >= 8:
                banned.add(hv)
            if (h.get("name", "") or "").lower() in ("cookie", "set-cookie"):
                for part in hv.split(";"):
                    if "=" in part:
                        pv = part.split("=", 1)[1].strip()
                        if len(pv) >= 8:
                            banned.add(pv)
        for _k, qv in parse_qsl(u.query, keep_blank_values=True):
            if len(qv) >= 8:
                banned.add(qv)
        raw_post = (req.get("postData") or {}).get("text") or ""
        for part in re.split(r"[&\s]+", raw_post):
            if "=" in part:
                pv = part.split("=", 1)[1].strip()
                if len(pv) >= 8:
                    banned.add(pv)

        if not HOST_OK.search(u.hostname or ""):
            offhost += 1
            continue
        if TS_NOISE.search(u.path):
            ts_chunks += 1
            continue
        if NOISE_PATH.search(u.path):
            noise += 1
            continue

        qnames = sorted({k for k, _ in parse_qsl(u.query, keep_blank_values=True)})
        hnames = sorted({h.get("name", "") for h in (req.get("headers") or [])})
        post = req.get("postData") or {}
        pj = parse_json(post.get("text"))
        post_shape = shape(pj) if pj is not None else None

        content = resp.get("content") or {}
        resp_text = content.get("text") or ""
        if content.get("encoding") == "base64":
            try:
                resp_text = base64.b64decode(resp_text).decode("utf-8", "replace")
            except Exception:
                pass
        # HLS 播放列表是纯文本，不是 JSON；单独识别，绝不输出文本内容
        is_hls = resp_text.lstrip().startswith("#EXTM3U")
        rj = parse_json(resp_text)
        rkeys = collect_keys(rj) if rj is not None else set()
        rshape = shape(rj) if rj is not None else None
        top_keys = sorted(list(rj.keys()))[:14] if isinstance(rj, dict) else (["<array>"] if isinstance(rj, list) else [])

        score, hits = 0, []
        if rkeys & LIST_KEYS:
            score += 3
            hits.append("文件列表字段:" + "/".join(sorted(rkeys & LIST_KEYS)))
        if rkeys & TIME_KEYS:
            score += 2
            hits.append("时间字段:" + "/".join(sorted(rkeys & TIME_KEYS)))
        if rkeys & LINK_KEYS:
            score += 2
            hits.append("直链/播放字段:" + "/".join(sorted(rkeys & LINK_KEYS)))
        if PATH_HINT.search(u.path):
            score += 2
            hits.append("路径关键词")
        if isinstance(rj, dict) and rj.get("errno") == 0:
            score += 1
            hits.append("errno=0")
        if TREE_PATH.search(u.path):
            score += 4
            hits.append("目录树接口(根目录/下钻)")
        if RECENT_PATH.search(u.path):
            score += 4
            hits.append("最近更新/列表聚合接口")
        if rkeys & CURSOR_KEYS:
            score += 1
            hits.append("分页/时间字段:" + "/".join(sorted(rkeys & CURSOR_KEYS)))
        if PLAY_PATH.search(u.path):
            score += 3
            hits.append("播放链路路径")
        if is_hls:
            score += 3
            hits.append("HLS 播放列表(#EXTM3U)")
        if "stream_type" in qnames or "type" in qnames and PLAY_PATH.search(u.path):
            score += 2
            hits.append("转码档位参数")
        if req.get("method", "GET").upper() == "POST":
            score += 1
        if not rj and not is_hls:
            score -= 1

        rows.append({
            "score": score,
            "method": req.get("method", "GET").upper(),
            "path": redact_path(u.path),
            "qnames": qnames,
            "hnames": hnames,
            "post_shape": post_shape,
            "rshape": rshape,
            "top_keys": top_keys,
            "rkeys": sorted(rkeys),
            "hits": hits,
            "has_json": rj is not None,
            "is_hls": is_hls,
        })
        if PLAY_PATH.search(u.path) or is_hls or "stream_type" in qnames:
            play_rows.append({
                "method": req.get("method", "GET").upper(),
                "path": redact_path(u.path),
                "qnames": qnames,
                "resp_kind": "HLS(m3u8)" if is_hls else ("JSON" if rj is not None else "其他"),
                "score": score,
            })
        if RECENT_PATH.search(u.path):
            list_rows.append({
                "method": req.get("method", "GET").upper(),
                "path": redact_path(u.path),
                "qnames": qnames,
                "resp_fields": sorted(rkeys & (LIST_KEYS | TIME_KEYS | CURSOR_KEYS))[:12],
                "resp_kind": "JSON" if rj is not None else "其他",
                "score": score,
            })
        if TREE_PATH.search(u.path):
            tree_rows.append({
                "method": req.get("method", "GET").upper(),
                "path": redact_path(u.path),
                "qnames": qnames,
                "resp_fields": sorted(rkeys & (LIST_KEYS | TIME_KEYS | CURSOR_KEYS | LINK_KEYS))[:14],
                "score": score,
            })

    rows.sort(key=lambda r: (-r["score"], r["path"]))
    top = rows[: args.top]
    high = [r for r in rows if r["score"] >= 3]

    out = []
    out.append("# HAR 接口清单（本地脱敏，可安全转发）\n")
    out.append(f"- 源文件：`{'`、`'.join(har_names)}`（仅在本机解析，未上传）")
    out.append(f"- 总请求 **{total}** ｜ 非目标域排除 **{offhost}** ｜ 埋点/静态资源排除 **{noise}** ｜ HLS 分片排除 **{ts_chunks}** ｜ 候选接口 **{len(rows)}** ｜ 高候选 **{len(high)}**")
    out.append("- 报告只含接口路径、参数名、字段名与结构；**不含任何取数结果或凭据值**\n")
    out.append("> 数据源已定案为**网页版 IM 群组文件库**（`imbox` / `sharedir` / `mbox` / `pfile`），**不是分享链接**。"
               "报告里若出现 `shareid` / `uk` 等字段名，只表示响应结构，不代表要走 `/share/*` 或开放平台 `xpan/share` 体系"
               "（见文档 2.1 / 2.2 / 10.12）。`gid` 是本项目网页私有接口里的**库稳定 ID**（Cookie 鉴权），"
               "不是开放平台那套 `access_token` + 商务采购的 gid 服务（见文档 10.14）。\n")

    if not high:
        out.append("> ⚠️ 未发现「文件列表特征」接口。多半是导出时没停在列表页，或没勾选 with content。\n"
                   "> 请按文档 10.7 / 10.10：勾选 Preserve log → 停在通讯录 / 文件库列表页刷新 → 再导出一次。\n")

    out.append("## 最近更新 / 列表接口（首屏数据源）\n")
    if not list_rows:
        out.append("> ⚠️ 未发现 `/imbox/file/recentv2` 或文件列表接口。请停在通讯录 / 文件库列表页刷新后再导出，"
                   "并确认过滤框没有把 `imbox` / `recent` 排掉。\n")
    else:
        out.append("| 方法 | 接口路径 | Query 参数名 | 响应命中的字段 | 响应类型 | 得分 |")
        out.append("|---|---|---|---|---|---:|")
        for p in list_rows[:20]:
            out.append("| {} | `{}` | {} | {} | {} | {} |".format(
                p["method"], p["path"],
                ", ".join(f"`{q}`" for q in p["qnames"][:14]) or "—",
                ", ".join(f"`{k}`" for k in p["resp_fields"]) or "—",
                p["resp_kind"], p["score"],
            ))
        out.append("\n> 验收要点：响应里应有 `server_mtime` / `fs_id`、播放凭据（`msg_id` / `to` / `from_uk` 或 dlink）"
                   "与分页字段（`has_more` / `cursor`）。**库主键定案为 `gid`**：`session_id` 的数字部分就是 `gid`，"
                   "前缀是否随重新登录变化未验证，因此不要用 `session_id` 全串当主键（见文档 10.10 / 10.14）。\n")
    out.append("")

    out.append("## 目录树接口（逐级下钻 / 选择目录）\n")
    if not tree_rows:
        out.append("> ⚠️ 未发现 `listshare` / `shareinfo` 请求。若要验证目录浏览，需进到某个文件库 → 停在根目录 → "
                   "连续点开 2-3 级子目录，再导出 HAR（见文档 10.7.3 第 5b 步）。\n")
    else:
        grouped = {}
        for t in tree_rows:
            key = (t["method"], t["path"])
            if key not in grouped:
                grouped[key] = {"qnames": t["qnames"], "fields": set(), "count": 0}
            grouped[key]["count"] += 1
            grouped[key]["fields"].update(t["resp_fields"])
        out.append("| 方法 | 接口路径 | Query 参数名 | 响应命中的字段 | 出现次数 | 得分 |")
        out.append("|---|---|---|---|---:|---:|")
        for (method, path), g in grouped.items():
            out.append("| {} | `{}` | {} | {} | {} | {} |".format(
                method, path,
                ", ".join(f"`{q}`" for q in g["qnames"][:16]) or "—",
                ", ".join(f"`{k}`" for k in sorted(g["fields"])) or "—",
                g["count"],
                max(t["score"] for t in tree_rows if t["path"] == path),
            ))
        si = [t for t in tree_rows if "shareinfo" in t["path"]]
        si_same = len(si) > 1 and all(t["qnames"] == si[0]["qnames"] for t in si)
        out.append("\n> **下钻判定**：`shareinfo` 出现 **{}** 次，各次 Query 参数名{} —— {}。".format(
            len(si), "完全一致" if si_same else "不完全一致（需人工核对）",
            "符合「同一棵树共享 `from_uk` / `msg_id` / `gid`，**只换 `fs_id`**」的实证" if si_same
            else "不能据此断定只换了 `fs_id`"))
        out.append("> `GET /mbox/group/listshare` 是**单库根目录列表**（`limit` / `desc`），与 `/imbox/file/recentv2` 的"
                   "**跨库最近更新**是并行两个视图，不是谁取代谁。")
        out.append("> 库稳定 ID 用 **`gid`**；`session_id = \"04_\" + gid`（见文档 10.14）。"
                   "若 `shareinfo` 响应出现 `dlink` / `msg_id` / `to` / `from_uk`，也是 V6 音频取址的候选线索。\n")
    out.append("")

    cred_map = {}
    for r in rows:
        for k in CRED_KEYS & set(r["rkeys"]):
            cred_map.setdefault(k, set()).add(r["path"])
    out.append("## 播放凭据 / 字段归一化（V6 线索）\n")
    if cred_map:
        out.append("| 命中字段 | 出现的接口 |")
        out.append("|---|---|")
        for k in sorted(cred_map):
            out.append("| `{}` | {} |".format(k, ", ".join("`" + p + "`" for p in sorted(cred_map[k]))))
        if "dlink" in cred_map:
            out.append("\n> 🔑 **`dlink` 已出现在列表 / 目录接口的响应字段里 —— V6 优先级重排**："
                       "先用 WebView 同源代发直接取该字段，带 `User-Agent: pan.baidu.com` + Cookie 直连验证"
                       "（200 + 正确 Content-Type 即可喂 ExoPlayer）。**不要先去试 streaming 音频档或抓官方 App**。"
                       "网页版「暂不支持播放」是前端不给入口，后端仍返回 dlink 与此判定一致。"
                       "注意 dlink 可能是空串、或只有文件项才有，须逐条实测（文档 10.15）。")
        else:
            out.append("\n> ⚠️ 响应字段里未发现 `dlink`，V6 仍需按 10.13.3 的 V6-a / b / c 继续验证。")
        out.append("> **字段名不一致，归一化层必须处理**：`recentv2` 用 `fsid` / `fuk` / `hasmore`（字符串），"
                   "`shareinfo` / `listshare` 用 `fs_id` / `from_uk` / `has_more`（整数）—— **别直接共用同一个 DTO**。"
                   "分页游标候选是 `newno` / `last_msg_time` / `timestamp` / `page`，不是之前假设的 `cursor`。\n")
    else:
        out.append("> ⚠️ 未发现播放凭据字段，多半是导出时没勾选 with content 或没进到文件级目录。\n")
    out.append("")

    out.append("## 播放链路接口（本次重点）\n")
    if not play_rows:
        out.append("> ⚠️ 未发现播放链路请求。若点开的是**视频**，百度会在**新标签页**打开 "
                   "`pfile/mboxvideo` 播放器 —— 那份请求不在原页面的 HAR 里。"
                   "必须在那个新标签**单独开 DevTools** 再导出一份 HAR，并把两份一起传给脚本。\n")
    else:
        out.append("| 方法 | 接口路径 | Query 参数名 | 响应类型 | 得分 |")
        out.append("|---|---|---|---|---:|")
        for p in play_rows[:20]:
            out.append("| {} | `{}` | {} | {} | {} |".format(
                p["method"], p["path"],
                ", ".join(f"`{q}`" for q in p["qnames"][:14]) or "—",
                p["resp_kind"], p["score"],
            ))
        out.append("\n> 若表里出现 `stream_type` / `mediainfo` / `streampage`，说明视频走的是 HLS 转码流而非原始 mp4 直链，"
                   "Android 侧需要 `media3-exoplayer-hls`。\n")
    out.append("")

    out.append("## 候选接口（按得分）\n")
    out.append("| 得分 | 方法 | 接口路径 | Query 参数名 | 响应顶层字段 | 命中特征 |")
    out.append("|---:|---|---|---|---|---|")
    for r in rows[:30]:
        out.append("| {} | {} | `{}` | {} | {} | {} |".format(
            r["score"], r["method"], r["path"],
            ", ".join(f"`{q}`" for q in r["qnames"][:14]) or "—",
            ", ".join(f"`{k}`" for k in r["top_keys"][:10]) or "—",
            "；".join(r["hits"]) or "—",
        ))
    out.append("")

    out.append("## 高候选明细\n")
    for i, r in enumerate([r for r in top if r["score"] >= 3] or top[:5], 1):
        out.append(f"### {i}. {r['method']} `{r['path']}`（得分 {r['score']}）\n")
        out.append(f"- Query 参数名：{', '.join('`'+q+'`' for q in r['qnames']) or '—'}")
        out.append(f"- 请求头名（不含值）：{', '.join('`'+h+'`' for h in r['hnames']) or '—'}")
        if r["post_shape"] is not None:
            out.append("- 请求体结构（只含字段与类型）：")
            out.append("```json\n" + json.dumps(r["post_shape"], ensure_ascii=False, indent=2) + "\n```")
        else:
            out.append("- 请求体：无 / 非 JSON")
        if r["rshape"] is not None:
            out.append("- 响应字段结构（只含字段与类型，值已全部剔除）：")
            out.append("```json\n" + json.dumps(r["rshape"], ensure_ascii=False, indent=2) + "\n```")
        else:
            out.append("- 响应：无 JSON 或未导出响应体（重新导出时勾选 with content）")
        out.append("")

    out.append("---\n")
    out.append("隐私自检：脚本从不读取/输出 header 值、query 值、请求体值、响应体值；"
               "如发现输出中仍有凭据形态，会中止写文件。\n")

    text = "\n".join(out)
    for pat in LEAK_PATTERNS:
        m = pat.search(text)
        if m:
            print(f"❌ 自检失败：输出疑似含凭据（{m.group(0)[:40]}...），已中止写文件。", file=sys.stderr)
            return 3
    for b in banned:
        if b in text:
            print(f"❌ 自检失败：报告里出现了 HAR 中的敏感值（长度 {len(b)}），已中止写文件。", file=sys.stderr)
            return 3

    dest = args.output or args.har[0].with_name("har_report.md")
    dest.parent.mkdir(parents=True, exist_ok=True)
    dest.write_text(text, encoding="utf-8")
    print(f"✅ 已生成脱敏报告: {dest}")
    print(f"   总请求 {total} ｜ 排除埋点/静态 {noise} ｜ 候选 {len(rows)} ｜ 高候选 {len(high)}")
    if high:
        print("   高候选接口：")
        for r in high[:8]:
            print(f"     [{r['score']}] {r['method']} {r['path']}")
    else:
        print("   ⚠️ 没有高候选，确认导出时已点进文件库并勾选 with content")
    return 0


if __name__ == "__main__":
    sys.exit(main())
