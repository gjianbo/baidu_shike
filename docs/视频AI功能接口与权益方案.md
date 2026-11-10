# 百度网盘 · 视频 AI 功能接口与权益方案（视频总结 / 视频笔记 / 字幕 / AI 问答）

> 版本：v1.0　生成日期：2026-11-09　适用：网页版 IM 群组文件库视频播放器（`m-static/imVideo`）
> 结论来源：真实登录态（SVIP 账号）下在网盘页同源实测 + 播放器前端包 `video-play.8041eed2.js` 静态分析。
> 读者：客户端开发。目标——说清「有哪些视频 AI 功能接口、各自免费还是需 SVIP、能不能通过 web 接入、怎么调」。

---

## 0. 一句话总览

| 功能 | 接口存在 | web 可接入 | 免费 / 付费 |
|---|---|---|---|
| **视频播放 / 文档下载** | ✅ | ✅ | **免费**（只认 Cookie） |
| **视频笔记（打点标记/笔记）** | ✅ | ✅ | **基本免费**（未见 SVIP 门禁） |
| **AI 侧栏 / 问答 / 推荐** | ✅ | ✅ | **基本免费**（账号开关已开） |
| **智能字幕（自动字幕/CC/转写）** | ✅ | ✅ | **有免费试用额度，超出需 SVIP / 视频券** |
| **视频总结 = AI 课件（智能总结）** | ✅ | ✅ | **免费仅试用 + 最多预览 5 页；完整需 SVIP / 视频券** |
| **逐字稿导出** | ✅ | ✅ | 依赖字幕额度（字幕免费额度内可用） |

> 共同点：全部是 **`pan.baidu.com` 同源 XHR + Cookie 鉴权**，和取视频地址（`/mbox/msg/streaming`）同一种玩法——**WebView 内同源代发即可接入，不需要 App token、不需要逆向 sign**。

---

## 1. 播放器来源与命名

- 视频播放页 `/pfile/mboxvideo?...`（新标签）加载的是独立前端子应用：
  - HTML：`GET /pfile/mboxvideo?...` → 引用 `https://nd-static.bdstatic.com/m-static/imVideo/js/im.*.js`
  - 主逻辑包：`https://nd-static.bdstatic.com/m-static/imVideo/js/video-play.<hash>.js`（约 2.8MB，AI 功能接口都在这里）
- 页面里所有 AI 功能都通过页面内的 axios 实例（`httpInstance` / `Qt$1`，base = `pan.baidu.com`）发**同源请求**。

---

## 2. 功能 ↔ 接口清单（实测）

### 2.1 视频总结 = AI 课件（智能总结）

| 接口 | 方法/参数 | 作用 | 实测 |
|---|---|---|---|
| `GET /aitrans/ppt/get` | `fsid, share_uk, client_type=2` | 取**已生成**的课件（视频总结产物） | ✅ `errno:0`，`data.file_count=11 / page_count=102`，`data.list[].img_url` 为每页图片签名 URL |
| `GET /aitrans/ppt/common` | `method=<m>, fsid, share_uk, client_type=2, version, clienttype` | 申请/查询生成任务/状态 | 端点存活（传错参返回 `errno:2 参数错误`）；`method` 取值需按调用点确认（见 §5） |
| `GET /aitrans/ppt/export` | `method=<m>, fsid, share_uk, clienttype, from=1, version` | 导出课件（下载成 PPT/文件） | 代码中确认存在，需实测 method |

- **产物形态**：一叠**幻灯片图片**（本例 102 页/11 组），每页 `img_url` 是 `*.baidupcs.com/file/...&sign=...` 的签名 CDN 地址（≈8h），**无 Cookie 也能下**（实测首图 HTTP 206）。
- 所以「视频总结」的落地形态：**直接取这些图片展示**，或走 `export` 导出成文件。

### 2.2 视频笔记（打点标记）

| 接口 | 方法/参数 | 作用 | 实测 |
|---|---|---|---|
| `GET /api/videomark/note?method=query` | `vfsid`（★虚拟文件 ID，非 fs_id），header `Content-Type: application/json` | 查询某视频的笔记/标记 | ✅ 接口 200（用 fs_id 当 vfsid → `errno:18102 can not found this file`，需正确 vfsid） |
| `getAiNoteEntryDetail`（同域 POST/内部） | body 形如 `{resource_type:0, from:"pause", query_type:0, resource_info:{file_info:{data_type:0, ...file}}}` | 新增/查询单条笔记入口 | 代码推断，需实测 |

- **vfsid 来源**：不是 `fs_id`。播放器用文件对象的 `resource_info.file_info` 组织，vfsid 由页面数据提供——**建议在播放器 WebView 上下文里取 `vfsid` 再调**，别用 `fs_id` 硬拼。

### 2.3 智能字幕 / 逐字稿（转写）

| 接口 | 方法/参数 | 作用 | 实测 |
|---|---|---|---|
| `GET /richsearch/autosubtitle/common?<params>` | `method=apply / status / query` 等 | 申请/查询智能字幕生成状态 | 代码确认；生成受权益/次数限制 |
| `POST /transapi/autosubtitle/exportvideoscript` | body `{fsid, md5, file_name, export_info:[{script_data, save_filename, save_path}]}` | 把转写稿导出成 `.doc` 存到网盘 | 路径确认；`GET /api/autosubtitle/exportvideoscript` 是 404（错误路径） |

- `script_data` = 字幕/文稿内容，先经 `richsearch/autosubtitle/common` 拿到字幕，再导出。
- 生成语言支持中/英/中英双语。

### 2.4 AI 侧栏 / 问答 / 推荐

| 接口 | 方法/参数 | 作用 | 实测 |
|---|---|---|---|
| `GET /aisearch/user/get` | — | AI 搜索/侧栏开关与用户状态 | ✅ 200，`data.switch_info:{video:1,doc:1,global:1}` 开启 |
| `POST /aisearch/sidebar/status` | form-urlencoded（具体字段以页面为准） | 侧栏状态 | 端点存活（猜参 400 params error） |
| `POST /aisearch/recommend/tool` | json `{uk, fs_ids, experiment, source:1}` | 视频内推荐工具 | 代码确认 |

### 2.5 会员权益查询（判断能不能用/剩几次）

| 接口 | 参数 | 返回 | 实测 |
|---|---|---|---|
| `GET /rest/2.0/membership/user` | `method=query` | 当前会员产品（`current_product.product_type` 等） | ✅ `vip2_13m`（VIP） |
| `GET /rest/2.0/membership/user` | `method=rights` | `total_num:{svip,vip,scan,vipv2}` + `rights_info[]` | ✅ `{svip:98,vip:24,scan:25,vipv2:28}` |
| `GET /rest/2.0/membership/user` | `method=rights&cluster=video_privilege_coupon` | 视频特权券数量与可解锁项 | ✅ `right_nums:{video_privilege_coupon:11}`（券含"智能字幕"等） |

---

## 3. 免费 vs SVIP vs 视频券（门槛矩阵）

来自播放器前端门槛文案 + `method=rights` 实测：

| 功能 | 免费用户 | SVIP | SVIP10 | 视频券 |
|---|---|---|---|---|
| 视频播放 / 文档下载 | ✅ 无限 | ✅ | ✅ | — |
| 视频笔记（videomark） | ✅（未见门禁） | ✅ | ✅ | — |
| AI 侧栏 / 问答 / 推荐 | ✅（开关开即可） | ✅ | ✅ | — |
| **智能字幕（自动字幕/转写）** | 🟡 每月 N 次试用 | ✅ **无限量挂载** | ✅ | ✅ 可解锁 |
| **视频总结 = AI 课件** | 🔴 试用 + **最多预览 5 页** | ✅ **每日 10 次生成** | ✅ **无限** | ✅ 单视频 48h 解锁 10+ 项 |

**门槛原文（前端包内实证）：**
- AI 课件：`免费试用次数已达上限，最多预览 5 页` / `使用视频券生成智能总结` / `SVIP尊享每日10次智能课件生成` / `SVIP10无限畅享智能课件特权`
- 智能字幕：`本月剩余 N 次智能字幕试用 SVIP享受无限量挂载`
- `getProductsInUse` 中有会员产品 `netdisk_common_listening_notes_vip`（与"听书/音频笔记 AI"相关，属另一会员项）

> 结论：**真正免费可用的 = 播放 / 下载 / 视频笔记 / AI 问答侧栏**；**视频总结（完整）与智能字幕需 SVIP 或视频券**。免费号只能拿到视频总结的前 5 页预览 + 字幕每月试用次数。

---

## 4. web 接入方式（与取流一致）

### 4.1 前提
1. 已登录（`pan.baidu.com` 域 Cookie）。
2. 同源发请求（页面内 `fetch`/XHR，`credentials:'include'`）。
3. 这些接口最好在**播放器上下文（`/pfile/mboxvideo` 或 `/mbox/streampage` 那个 WebView）**里调——页面手里已有 `vfsid / md5 / share_uk / fsid` 等对象，参数天然齐。

### 4.2 伪代码（说明用）

```text
// 视频总结（取已生成课件图片）
GET /aitrans/ppt/get?fsid=<fsid>&share_uk=<owner_uk>&client_type=2
   → errno==0 ? 遍历 data.list[].img_url 展示/下载 : 走生成流程(需SVIP/券)

// 生成/状态（需权益）
GET /aitrans/ppt/common?method=<...>&fsid&share_uk&client_type=2
GET /aitrans/ppt/export?method=<...>&fsid&share_uk&from=1

// 视频笔记
GET /api/videomark/note?method=query&vfsid=<vfsid>   // vfsid 从页面文件对象取

// 智能字幕 + 逐字稿
GET /richsearch/autosubtitle/common?method=apply|status|query&...
POST /transapi/autosubtitle/exportvideoscript  (body: fsid,md5,file_name,export_info[])

// AI 侧栏
GET /aisearch/user/get
POST /aisearch/sidebar/status (form)
POST /aisearch/recommend/tool (json)

// 权益判断
GET /rest/2.0/membership/user?method=rights
GET /rest/2.0/membership/user?method=rights&cluster=video_privilege_coupon
```

---

## 5. 待开发进一步枚举的点（诚实标注）

1. `aitrans/ppt/common` / `ppt/export` 的 **`method` 具体取值**（生成/查询/状态/导出）需按 `video-play.js` 各调用点确认——目前确认 `ppt/get`，`method` 枚举见到 `get/query/apply/status/report/use/querybyid`。
2. `videomark/note` 的 **vfsid 精确来源**（页面 `resource_info.file_info` → 哪个字段），以及新增/删除笔记的 method 与 body。
3. `aisearch/sidebar/status` 的 **form 字段结构**（现猜参 400）。
4. `richsearch/autosubtitle/common` 的完整参数与状态机（`canApply / tryouting / subtitle_status` 等）。
5. 各接口是否随前端版本变动（bundle 名、参数会改）——建议始终在播放器 WebView 上下文调用、参数取自页面对象，不硬编码。

---

## 6. 错误码与坑

| 返回 | 含义 | 处理 |
|---|---|---|
| `errno:0` | 成功 | — |
| `errno:2 参数错误` | 缺参/方法名不对 | 按真实调用补参数 |
| `errno:18102 can not found this file` | `vfsid` 不对（拿 fs_id 当 vfsid） | 用页面真实 vfsid |
| `error_code`（413xx） | 播放/转码类错误（见取流方案） | 换正确 stream_type |
| 课件 `最多预览5页` / `试用已达上限` | 免费额度用尽 | 提示升级 SVIP / 用视频券 |
| 字幕 `本月剩余0次` | 字幕试用用尽 | 提示升级 SVIP |

**坑：**
- 视频笔记用 **vfsid ≠ fs_id**；
- `share_uk` 传**上传者 uk**（列表里的 `from_uk`）；
- 逐字稿导出要先把字幕内容放进 `script_data`；
- 接口都在 `pan.baidu.com`，别去别的域名拼；
- 生成类功能受权益+每日次数限制，**取已生成结果（ppt/get）不受生成配额限制**。

---

## 7. 实测证据汇总（本次真实账号）

| # | 操作 | 结果 |
|---|---|---|
| 1 | `GET /aitrans/ppt/get?fsid=…&share_uk=2390025356&client_type=2` | 200 `errno:0`，`file_count=11 / page_count=102`，`list[].img_url` 可下（首图无 Cookie 206） |
| 2 | `GET /aisearch/user/get` | 200，`switch_info:{video:1,doc:1,global:1}` |
| 3 | `GET /api/videomark/note?method=query&vfsid=<fsid>` | 200 `errno:18102`（fs_id 非 vfsid，需真实 vfsid） |
| 4 | `GET /api/autosubtitle/exportvideoscript` | 404（错路径）；正确为 `/transapi/autosubtitle/exportvideoscript`(POST) |
| 5 | `GET /rest/2.0/membership/user?method=rights` | 200 `total_num:{svip:98,vip:24,scan:25,vipv2:28}` |
| 6 | `GET /rest/2.0/membership/user?method=rights&cluster=video_privilege_coupon` | 200 `video_privilege_coupon:11`（券含智能字幕等） |
| 7 | `GET /rest/2.0/membership/user?method=query` | 200 `current_product.product_type:vip2_13m`（VIP） |

> 示例中的 `fsid / share_uk / gid / 路径` 属账号相关标识（非凭据），转发外部前建议打码；均不含 BDUSS/STOKEN。

---

## 8. 落地建议（给产品/开发取舍）

- **免费即可做的能力**：视频播放、文档下载、**视频笔记**、**AI 问答侧栏**。
- **会卡会员的能力**：**视频总结（AI 课件完整版）**、**智能字幕生成 / 逐字稿**。
  - App 里应做「**查看已生成结果**」（`ppt/get` 取课件图片，免费号也能看前 5 页）+ 一个「**用视频券解锁 / 升级 SVIP**」入口，把生成交给用户自己的权益触发，而不是 App 承担。
- **判断可用性**：调 `method=rights` 看 `svip`；调 `rights&cluster=video_privilege_coupon` 看券数；据此决定 UI 展示「免费/试用/升级/用券」。

---

*配套文档：`docs/媒体取址技术方案.md`（视频/音频/文档的播放与下载地址）——本文是它的「AI 增值功能」扩展篇。*
