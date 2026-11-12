# 百度网盘 PDF 阅读：大文件报错、小文件正常 —— 原因分析（含真机复现）

> 分析对象：本 App（拾课）PDF 阅读链路，数据来自百度网盘个人盘（uk=`<UK>`）。
> 结论基于**当前源码 + 真机 `emulator-5554` 实测 + 宿主机按 App 同款参数直连下载**。
> 签名 / `uk` / `fs_id` 一律以 `<UK>` / `<FS_ID>` / `<SIGN>` 占位。

---

## 0. 结论（先看这里）

**你昨天真机测到的"100M 提示下载失败"可以稳定复现，根因不是文件大，而是下载时用错了 User-Agent。**

`/rest/2.0/docview/dlink` 返回的下载地址，其**签名绑定了生成它时的 User-Agent（隐藏 WebView 的 UA）**。而 `PdfFileFetcher` 下载时写死用 `User-Agent: pan.baidu.com`：

- 当实际要下载的体积 **≲ 50–64MB** 时，`pan.baidu.com` 这个 UA 也能过（CDN 走宽松节点）；
- 当实际体积 **> ~50–64MB** 时，CDN 严格校验签名与 UA，`pan.baidu.com` 直接返回 **HTTP 403 `{"error_code":31362,"error_msg":"sign error"}`** → App 抛 `InvalidPdfLink("文档下载失败（HTTP 403）…")` → 你看到的"下载失败"。

**换成 WebView 的 UA，同样的链接立刻 200，且完整下完**（实测 96MB、113MB 全量校验通过）。所以：
- "小的没问题" = 小文件体积小，`pan.baidu.com` UA 走宽松通道，侥幸能下；
- "大的失败" = 体积超过阈值后签名/UA 严格校验，写死的 UA 过不了；
- **后来加的 50MB 限制，本质是把"会踩到这个 UA 阈值"的文件提前挡掉，是个对症但不对因的补丁。**

此外还有两层次要事实（详见后文）：docview 对**极少数超大/超复杂扫描书会转码失败 `-31326`**（无任何预览副本）；下载是**整包单连接**且要求 `Content-Length` 严格相等。

---

## 1. 直接证据：同一 dlink，两种 UA，两种结果

对同一批 docview dlink，用 `Range: bytes=0-1023` 做鉴权探针（省流量，只看签名是否通过）：

| 实际下载体积(real_size) | `User-Agent: pan.baidu.com`（App 现用） | `User-Agent: <WebView UA>`（App 应使用） |
|---|---|---|
| 17 KB | 206 ✅ | 206 ✅ |
| 17.2 MB | 206 ✅ | 206 ✅ |
| 27.7 MB | 206 ✅ | 206 ✅ |
| 29.6 MB | 206 ✅ | 206 ✅ |
| 34.9 MB | 206 ✅ | 206 ✅ |
| 41.6 / 41.9 / 43.8 MB | 206 ✅ | 206 ✅ |
| **48.2 MB** | 206 ✅ | 206 ✅ |
| **50.6 MB** | 206 ✅ | 206 ✅ |
| **61.8 MB** | **403 ❌ sign error** | 206 ✅ |
| **71.5 MB** | **403 ❌** | 206 ✅ |
| **91.9 MB（96.4MB 原件直出）** | **403 ❌** | 206 ✅ |
| **108.3 MB（113.6MB 原件直出）** | **403 ❌** | 206 ✅ |
| **109.1 MB** | **403 ❌** | 206 ✅ |

阈值落在 **48.2 MiB（可过）与 61.8 MiB（403）之间**，约 **50–64MB**，与"100M 失败、加 50M 限制"的经验完全吻合。

### 完整下载校验（不是 Range，是全量）

| 文件 | 体积 | UA | HTTP | 实收字节 | 头/尾 | 速度 |
|---|---|---|---|---|---|---|
| MACOS软件安全…（type1 原件） | 96,366,817 | `pan.baidu.com` | **403** | 94 | `{"error_code":31362...}` | — |
| 同左 | 96,366,817 | WebView UA | **200** | **96,366,817** | `%PDF-1.5` | 13.4 MB/s / 7.2s |
| 细说PHP（type1 原件） | 113,612,768 | `pan.baidu.com` | **403** | 94 | `sign error` | — |
| 同左 | 113,612,768 | WebView UA | **200** | **113,612,768** | `%PDF-1.6` + `startxref` 完整 | 22.9 MB/s / 5.0s |
| A4版中高级英语（type2 转码） | 29,638,474 | `pan.baidu.com` | 200 | 29,638,474 | `%PDF-1.4` | 9–10 MB/s |

> 结论：**链接没问题、网络没问题、CDN 不慢**，只有 UA 不对。WebView UA 下 100MB 级 PDF 5–7 秒下完。

---

## 2. 代码定位（改哪里）

`app/src/main/java/com/hnwd/bdplayer/PdfFileFetcher.kt`：

```kotlin
private fun download(source: Uri, file: File): File {
    val connection = URL(source.toString()).openConnection() as HttpsURLConnection
    connection.setRequestProperty("User-Agent", PanApi.DLINK_USER_AGENT)   // ← 这里：写死 "pan.baidu.com"
    connection.setRequestProperty("Referer", "https://pan.baidu.com/disk/main")
    ...
}
```

`PanApi.kt:159`：`const val DLINK_USER_AGENT = "pan.baidu.com"`。

而 dlink 是在隐藏 WebView 里同源 `fetch` 生成的，UA 就是 WebView 的 UA：
`MainActivity.PdfPreviewHostAdapter.userAgent() = web.settings.userAgentString`（`MainActivity.kt:1353`）。
`MediaLinkSection.requestDiskLink()` 里做 302 探测时**也正确用了 `host.userAgent()`**，但最后交给 `PdfFileFetcher` 下载时又退回 `pan.baidu.com` —— 所以**主取址和回退取址两条路都会在大文件上 403**。

**最小修复（一行）**：把 `PdfFileFetcher.download()` 的 UA 改成 WebView UA（构造 `PdfFileFetcher` 时把 `web.settings.userAgentString` 传进来，或复用 `host.userAgent()`）。

> 同类隐患：`ImageFileFetcher.kt:112` 也用了 `DLINK_USER_AGENT`；大原图大概率同样 403，只是图片通常较小不易触发。

---

## 3. 为什么加 50MB 能"看起来解决问题"

- 50MB 以内 → 多数 docview 结果要么原文件直出且 <50MB，要么转码后远小于 50MB → `pan.baidu.com` UA 走宽松通道 → 能下；
- 100MB 原件直出（`dlink_type=1`，如 96MB / 113MB）→ UA 严格校验 → 403 → "下载失败"。

所以 50MB 限制**用"原始文件大小"近似了"会不会踩到 UA 阈值"**——但两者并不等价，会误伤/漏防（见下）。

---

## 4. 第二层：docview 是"转码服务"，个别文件转码失败 `-31326`

`/rest/2.0/docview/dlink` 不是原文件下载，而是"把文档转成预览副本"。响应含 `dlink_type(1=原文件,2=转码副本)`、`real_size`、`src_size`（本 App 目前只读 `dlink`，其余全忽略）。

实测（同账号）：

| 原始大小 | errno | dlink_type | real_size（实际下载） |
|---|---|---|---|
| 41.8 MB | 0 | 1 原文件 | 41.8 MB |
| 52.2 MB | 0 | 2 转码 | 18.4 MB |
| 96.4 MB | 0 | 1 原文件 | 96.4 MB |
| 205 MB | 0 | 2 转码 | 109 MB |
| **208 MB** | **-31326** | — | 无 dlink |
| 220 MB | 0 | 2 转码 | 19.8 MB |
| 240 MB | 0 | 2 转码 | 29.6 MB |
| **536 / 557 MB** | **-31326** | — | 无 dlink |

- 转码副本是**按页栅格化**的（240MB→29.6MB 实测 210 页、220MB→19.8MB 实测 170 页，页数与原件一致，文字不可选）。
- `-31326` 与体积**非单调**：208MB 失败，205/220/240MB 成功 → 更像**页数/单页像素**限制，精确阈值未确定。
- 这类文件列表里虽有 `docpreview` 页图字段，但访问返回 `{"error_code":31064,"error_msg":"doc type:[] is null"}` —— **页图兜底也不可用**，只能整本下载原件或明确提示不支持。

---

## 5. 第三层：下载实现偏"一次性"

- 整包单连接下载，必须整份下完才渲染；`connectTimeout=10s`、`readTimeout=30s`。
- 强校验 `total == Content-Length`（`savePdf`），分块/多段 CDN 场景可能误判"下载不完整"。
- 但实测 CDN 支持 `Accept-Ranges: bytes`，**完全可做 Range 分段/断点续传**（目前没用）。

---

## 6. 失败分支映射（用户看到的话从哪来）

```
open(entry)
 ├─ pdfTooLarge(entry.size)  >50MB(原始) → Toast「文件超过 50MB，暂不支持在 App 内打开。」  ← 现在大PDF平时的报错
 └─ 取址 docview → downloadPdf
      └─ download() UA=pan.baidu.com
           ├─ 体积>~50–64MB → HTTP 403 sign error → InvalidPdfLink「文档下载失败（HTTP 403）…」
           │     └─ pdfFailurePlan → RETRY_FALLBACK → /api/download(带 Cookie/sign)
           │           └─ 拿到的 CDN 链接仍用 pan.baidu.com 下 → 再次 403 → 「文档打开失败，请重试。」
           └─ errno=-31326(无 dlink) → 「网盘未返回文档地址。」→ 回退同样失败
```

相关 errno：

| errno | 场景 |
|---|---|
| `31362 sign error` | **UA 与生成 dlink 时不一致**（本次核心）；大体积时严格校验 |
| `-31326` | docview 转码失败，无 dlink |
| `21061` | 用 `/api/search` 的 path 直接调 docview（应用 `/api/list` 规范 path+fsid）|
| `210117/210118` | docview 缺 `scene`/`fuk` 参数 |
| `31064` | 失败文件的 `docpreview` 页图缓存不存在 |

---

## 7. 建议（按优先级）

1. **【根因修复】`PdfFileFetcher.download()` 的 UA 改为 WebView UA**（`web.settings.userAgentString`）。这一条就能让 100MB 级 PDF 正常下载。`ImageFileFetcher` 同步处理。
2. **【阈值策略】50MB 硬拦改为按"实际下载体积(real_size)"判定**：先取 docview 读 `real_size`（或 dlink 的 `size=`）再决定是否放行，并预留设备可用空间；原始 `size` 只做预估。这样 240MB→29.6MB 这类能开，96MB 原件直出也能量力提示。
3. **`-31326` 明确降级**：提示"该文档服务端无法转码，需下载原件（约 XXX MB）"或"暂不支持在线预览"，不要落到笼统"打开失败"。
4. **放宽 `total == Content-Length`**：改校验 `total>0` + `%PDF-` 头 + 末尾含 `%%EOF`，避免分块 CDN 误判。
5. **大原件走 Range 分段 + 进度**（CDN 已支持），弱网更稳、可断点续传。
6. **（可选）** 大 PDF 直接交给隐藏 WebView 打开百度官方文档预览页，由百度分页渲染，App 不必整包下载（确切 URL 待抓，本次未验证）。

> 验证方式：改完 UA 后，直接打开上面 96MB / 113MB 两个文件，应能 5–7 秒下完并进入阅读页；或按第 8 节命令复测。

---

## 8. 复现命令（可直接重跑）

1. 启动 App，取 WebView 调试口：
   ```bash
   adb -s emulator-5554 shell pidof com.hnwd.bdplayer
   adb -s emulator-5554 forward tcp:9411 localabstract:webview_devtools_remote_<PID>
   ```
2. 在 `https://pan.baidu.com/__shike_api` 内同源 `fetch` 取 dlink（`fuk=<UK>&path=<PATH>&fsid=<FS_ID>&file_type=pdf&channel=chunlei&scene=3`）。
   注意：`path`/`fsid` 要用 `/api/list` 的规范值，别用 `/api/search` 的 path（否则 `21061`）。
3. 两种 UA 对比（`<WEBVIEW_UA>` 取 `navigator.userAgent`）：
   ```bash
   curl -s -o /dev/null -r 0-1023 -A 'pan.baidu.com' "<DLINK>" -w '%{http_code}\n'   # 大文件应 403
   curl -s -o out.pdf            -A '<WEBVIEW_UA>'  "<DLINK>" -w '%{http_code} %{size_download}\n'  # 应 200 全量
   ```

---

## 9. 待确认

- UA 严格校验的**精确字节阈值**（落在 48.2–61.8 MiB 之间，约 50–64MB，未细分到 1MB）。
- CDN 为何对大小分档校验 UA（疑似大文件走防链节点）——不影响修复结论。
- `-31326` 的精确触发条件（页数/像素）未定位。
