# 夸克网盘 PDF 阅读：大文件报错、小文件正常 —— 原因分析

> 分析对象：本 App 的夸克 PDF 预览链路（`PdfFileFetcher.startQuark` + `QuarkPersonalDrive.pdfLink`）。
> 结论基于**源码 + 夸克接口实测（`emulator-5554` 上 App 的 Cookie 导出后直连 `drive-pc.quark.cn`）**。
> Cookie / `fid` 一律不落库，实测用后即删。

---

## 0. 一句话结论

**夸克和百度"同样报错"的底层原因并不相同：**

- **百度**：下载 UA 与签名不匹配（写死 `pan.baidu.com`），体积一大就 403 `sign error`；
- **夸克**：**夸克自己就不让非会员下载大文件** —— 官方 `file/download` 对 >约 50MB 直接返回 `code=23018 "download file size limit"`；而 App 的 PDF 路径是**下载整份原文件**（`pdf/preview` 返回的是原件直链，不像百度会转码成 10%~60% 的小副本），再叠加 **OkHttp 默认 10s 超时、无 Range 续传**，大文件必然失败。

**所以 50MB 这条线对夸克基本是对的（≈夸克非会员下载上限），对百度则是"误伤 + 掩盖 UA bug"。**

---

## 1. 夸克 PDF 与百度 PDF 的关键差异

| | 百度 | 夸克 |
|---|---|---|
| 取址接口 | `/rest/2.0/docview/dlink` | `/1/clouddrive/pdf/preview` |
| 返回内容 | **转码副本**（`dlink_type=2`，240MB→29.6MB）或原文件 | **原文件直链**（`size` == 原件大小） |
| 大 PDF 下载量 | 常被压到原件 10%~60% | **等于原件**（70.6MB 就是 70.6MB） |
| 逐页图片 | 无（只有 `docpreview`，失败文件还不可用） | 有 `page_imgs`，但**只有 3 页样例**（page 1/2/3） |
| 官方下载大小限制 | 未观测到明确 errno | **`file/download`：>约50MB 返回 `23018 download file size limit`** |

---

## 2. 实测证据

### 2.1 `pdf/preview` 返回的是原件，不是小副本

| 文件 | 原大小 | `page_number` | `pdf/preview.code` | 是否有 download_url |
|---|---|---|---|---|
| 卫星运行时间.pdf | 244,541 (239KB) | 4 | 0 | 有 |
| g-预测之书.pdf | 25,404,448 (24.2MB) | **592** | 0 | 有 |
| 生财AI宝典.pdf | 70,675,789 (67.4MB) | 315 | 0 | 有 |
| 稻草人-第1册.pdf | 81,085,546 (77.3MB) | 64 | 0 | 有 |

即：**`pdf/preview` 对多大都给 url，且 url 指向的是原始文件**（`size` 字段等于原件字节数）。App 拿到后是整份下载再交给原生阅读器渲染 —— 大 PDF = 大下载。

`page_imgs` 实测**固定只有 3 项（page 1、2、3）**，换参数（`page_start/page_end/page/page_num/img_type/need_all`）都不变，**不能当作整篇逐页阅读的数据源**（接口文档写"逐页图片"，实测不符）。

### 2.2 夸克官方 `file/download` 有硬性大小限制 `23018`

`POST /1/clouddrive/file/download {fids:[fid]}` 实测：

| 文件大小 | 返回 |
|---|---|
| 28.5 MB | `code=0`，返回 OSS 预签名 url |
| 37.0 MB | `code=0` |
| 41.8 MB | `code=0` |
| 44.2 MB | `code=0` |
| **46.7 MB** | `code=0` |
| **67.4 MB** | **HTTP400 `code=23018 "download file size limit[...]"`** |
| **77.3 MB** | **HTTP400 `code=23018 "download file size limit[...]"`** |

→ 限制在 **46.7MB（可）与 67.4MB（拒）之间，约 50MB**（非会员）。这与你"50MB"的经验值高度吻合，**很可能当初 50MB 就是踩到夸克这个限制定下来的**。

### 2.3 原文件下载直链在本机 egress 被 412 拦截（无法在本机测到 >50MB 的真实行为）

- `pdf/preview` 的 `download_url`（`dl-c-*.drive.quark.cn`）在本机（公网出口为 AWS 香港 `18.167.65.193`）**所有体积全部返回 Tengine `412 Precondition Failed`**（22KB~70MB 都一样），换 UA / Referer / Cookie / Range 均无效；
- 但同一接口返回的 **页图 URL（`page_imgs`）能 200 下载到 JPEG**；
- `file/download` 的 OSS 预签名 url（`dl-pc-*.pds.quark.cn`）返回 `403 RequestDeniedByCallback: require login [auth not found]`。

说明：**原文件下载链路对"登录态/地域"有额外校验**，本机导出的 Cookie 只有 `__pus`（缺 `__puus`），且出口在境外，所以**真实设备（大陆、完整登录态）上 >50MB 的下载行为在本机无法复现**，只能以官方 `23018` 作为"夸克有约 50MB 下载上限"的硬证据。

> 附带发现（当前代码隐患）：`QuarkPersonalDrive.openSession()` 用 `SESSION_COOKIES = {__pus, __puus}` 判断登录，要求两者都在。实测该机 Cookie 库**只有 `__pus` 没有 `__puus`**，按现逻辑 `openSession()` 会返回 null → 夸克 PDF 会直接报「请登录夸克网盘后重试」。若真机也如此，需复核 `SESSION_COOKIES` 是否应放宽。

---

## 3. 代码层问题（夸克 PDF 链路）

`PdfFileFetcher.startQuark()`：

```kotlin
val userAgent = WebSettings.getDefaultUserAgent(context)
val drive = QuarkPersonalDrive.openSession(userAgent)          // 需 __pus+__puus
val uri = drive.pdfLink(fileId)                                // POST pdf/preview -> 原件直链
val request = QuarkPersonalDrive.fileRequest(uri, userAgent)   // UA/Referer
QuarkPersonalDrive.fileClient.newCall(request).execute()       // ← OkHttp 默认 10s 超时
```

1. **两道尺寸限制同样生效**：入口 `pdfTooLarge(entry.size)`（>50MB 直接 Toast）+ 下载 `savePdf` 的 80MB 校验 → 夸克 >50MB 也在"发请求前就被挡"。
2. **整份原件下载**：`pdf/preview` 不降采样，70MB PDF 就是 70MB 下载 + 本地渲染（`PdfViewerFragment`），比百度重得多。
3. **OkHttp 无显式超时**：`QuarkPersonalDrive.fileClient` 只设了 `followSslRedirects(false)` 和一个 interceptor，**没有 connect/read/call timeout → 用 OkHttp 默认 connect 10s / read 10s**。夸克非会员限速明显，大文件只要出现 >10s 停顿就 `SocketTimeoutException` → "下载失败/文档打开失败"。
4. **无 Range / 断点续传**：失败一次就整包重来。
5. **错误码未细分**：`checkStatus()` 只把 400/401/403/404/410 归为 `InvalidPdfLink`；**412 不在其中** → 抛普通异常 → `pdfFailurePlan(quark=true)` 直接 `SHOW_ERROR`，用户看到「文档下载失败（HTTP 412），请重新打开。」；`23018` 结构上也不会被识别成"大小超限"。

---

## 4. 为什么"夸克也存在这样的问题"

- **表现相同**：>50MB 的 PDF，入口同一句「文件超过 50MB，暂不支持在 App 内打开。」→ 小的能开、大的报错。
- **本质不同**：百度是 UA 签名错（有救：换 UA 就能下），**夸克是官方就限制非会员下载大文件（`23018`，约50MB）**，且 App 走的是"下载整份原件"而非"服务端分页预览"，所以更大/更慢、更容易超时。
- 换言之：**50MB 对夸克是"贴合平台限制"，对百度是"用错 UA 后的兜底"**。

---

## 5. 建议

1. **夸克 >50MB 直接给准确提示**，别进入注定失败的下载：如「夸克网盘非会员单个文件下载上限约 50MB，请开通会员或到夸克网页版查看」。区分 `23018` → 大小超限。
2. **大 PDF 优先走"服务端渲染的在线预览"**：夸克网页版文档查看器由服务端出页图，App 不必整份下载。用隐藏 WebView 打开夸克在线预览页（URL 需按网页版实际抓一次，列为待办）。注意：`pdf/preview.page_imgs` 只有 3 页样例，**不足以做整篇阅读**，不要拿它当完整阅读器。
3. **给 OkHttp 设显式超时并加 Range 续传**（如 read 30s、支持 `Accept-Ranges` 断点）。夸克 `file/download` 的 OSS url 也支持 Range。
4. **错误码细分**：`412 → 链接失效/需重新登录`、`401/21001 → 登录失效`、`23018 → 大小超限`、`26001 → 文档不支持预览`；`checkStatus` 把 412 纳入可重取或明确提示。
5. **复核 `SESSION_COOKIES`**：若平台已改为只种 `__pus`，登录判定需放宽，否则夸克整体不可用。
6. **可选**：`doc/convert`（夸克文档转换，源码存在、参数未实测）可能拿到更小的转换副本，值得验证后作为大 PDF 的服务端降采样途径。

---

## 6. 待确认

- 真机（大陆、完整 `__puus` 登录态）下，`pdf/preview.download_url` 对 >50MB 究竟是"能下但慢"还是"被限/412"——本机受地域+缺 `__puus` 无法验证。
- `file/download` 的 50MB 限制是否随 VIP 提升（未测 VIP 账号）。
- `doc/convert` 参数与产物大小（未实测）。
