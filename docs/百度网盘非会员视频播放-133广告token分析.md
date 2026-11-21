# 百度网盘 · 非会员视频不能播（App）但网页能播 —— 抓包分析与修复

> 证据文件：`/Volumes/mac/download/download222.devtools`（Chrome WebInspector 导出的 HAR 1.2，52MB，348 条请求）
> 抓包账号：`小胖的天空6`（uk `3073382768`，`vip_identity=0`、`vip_type=0` → **非会员**）
> 抓包对象：我的网盘个人视频播放页 `https://pan.baidu.com/pfile/video?path=/周易/.../黄金宝-六爻初级班13-4.avi`
> 分析时间：2026-11-17

---

## 0. 一句话结论

**`errno=133` 不是「非会员不能在线播」的永久限制，而是「非会员先看广告换播放」的广告门。**
网页版拿到 133 响应体里的 `adToken` 后，**把同一个 token 带回 `/api/streaming` 再请求一次**，就拿到了可播放的 HLS（m3u8）。
App 当前把 133 当成永久受限，直接退回「原始文件 dlink」，而原始文件是 `.avi`，ExoPlayer 默认解不了 —— 于是 App 播不了。

---

## 1. 关键抓包证据（按时间线）

| # | 时间(UTC) | 请求 | 参数要点 | 响应 |
|---:|---|---|---|---|
| 8 | 07:55:40.174 | `GET /api/streaming` | `type=M3U8_FLV_264_480`、`nom3u8=1`、`vip=0`（无 isplayer/check_blue/adToken） | **`errno=133` + `adTime=8` + `adToken=<172字节>` + `ltime=5`** |
| 46 | 07:55:41.471 | `GET issuepcdn.baidupcs.com/issue/netdisk/vip/video/web/01/vp.m3u8` | 广告视频清单（302 跳 `*.antpcdn.com`） | 广告分片 `vp_000..003.ts` |
| 145/192/231/291 | 07:55:43~49 | `GET .../vp_00x.ts` | 4 个广告分片 | 200 |
| **254** | **07:55:48.487** | **`GET /api/streaming`** | **`type=M3U8_AUTO_480`、`isplayer=1`、`check_blue=1`、`vip=0`、`adToken=<与 #8 完全相同的那个>`** | **200 `application/x-mpegurl`，可播 HLS，142678 字节** |
| 290/317/328/344/346 | 07:55:49~57 | `GET xacm06.baidupcs.com/video/netdisk-videotran-xian/.../xxx.ts` | 正片 HLS 分片（H.264/TS） | 200 |

**两条铁证：**

1. **#8 的 `adToken` 与 #254 请求里的 `adToken` 完全相同**（已逐字节比对，`token identical: True`）。
2. #8 → #254 之间，抓包记录了广告清单和分片请求，未观察到发往 `pan.baidu.com` 的独立广告完成回调。

> #254 与 #8 的间隔约 **8.3 秒**，与响应里的 `adTime=8` 接近。该样本只能确认网页在播放前置广告后取得了正片 HLS，服务端的校验条件仍未知。

---

## 2. 网页版完整播放链路（还原）

```
① GET /api/streaming  type=M3U8_FLV_264_480 & nom3u8=1 & vip=0
      → {"errno":133,"adTime":8,"adToken":"clUu...nJ4=","ltime":5}
② 播放前置广告  vp.m3u8 + vp_000..003.ts（约 adTime 秒）
③ GET /api/streaming  type=M3U8_AUTO_480 & isplayer=1 & check_blue=1 & vip=0 & adToken=clUu...nJ4=
      → 200  #EXTM3U ... 正片 m3u8（xacm06.baidupcs.com 的 H.264/TS 分片）
④ 播放正片 HLS 分片
```

要点：
- **`nom3u8=1` 的那次是「预检」**：只要 JSON（尤其是 133+adToken），不要 m3u8。
- **真正播放用 `type=M3U8_AUTO_480` + `adToken`**。
- `vip=0` 是当前账号档位；对 VIP 账号，`adToken` 可以为空，`M3U8_AUTO_480` 直接返回 m3u8（见 `docs/已转存客户与网盘目录-技术验证.md` §4.2 的旧抓包：当时账号 480p 不依赖 adToken）。

---

## 3. App 现状与根因

代码位置：`app/src/main/java/com/hnwd/bdplayer/`

- `PanApi.diskStreaming()`（约 654 行）拼的正是「播放」请求：`type=M3U8_AUTO_480 & isplayer=1 & check_blue=1 & adToken="" & src_id=1`，**`adToken` 硬编码为空**。
- `VideoStreamResolver.playlistError()` 旧逻辑把 `errno==133` 一律判成 `StreamingRestricted`（"非会员或版权限制"）。
- `MainActivityPlayback.kt`（约 1324 行）：遇到 `StreamingRestricted` 就 `requestDiskLink` 取 **原始文件 dlink**，用 `progressiveSource()` 直接渐进播放。

对本例 `.avi` 的后果：
1. App 第一次 `/api/streaming`（AUTO_480，空 adToken）→ 服务端回 133；
2. App 把 133 当永久受限 → 退回原始文件 `.avi` 的 dlink；
3. 项目使用的 Media3 1.8.0 默认含有 `AviExtractor`。这个 AVI 原文件能否播放还取决于具体编码、设备解码能力和直链响应。需要结合播放器错误定位此文件的失败原因。 [Media3 1.8.0 源码](https://github.com/androidx/media/blob/1.8.0/libraries/extractor/src/main/java/androidx/media3/extractor/DefaultExtractorsFactory.java)

而网页能播，是因为网页走的是 **HLS 转码流**（H.264/TS），容器和编码在任何播放器都能播。

---

## 4. 修复（已实现）

核心：**把 `errno=133` 从「永久受限」改成「广告 token 门」，用 `adToken` 重试取 HLS**。改动集中在 `VideoStreamResolver.kt`：

1. 新增 `AdGate(adToken, adTimeSeconds)`：`playlistError` 遇到 `errno/error_code==133` 且响应体带 `adToken` 时返回它（可恢复），只有**不带 adToken** 才返回 `StreamingRestricted`（真受限 → 才降级原始文件）。
2. `fetch()` 改为多轮：
   - 第 1 轮：原 URL（空 adToken）请求；
   - 若 133+adToken：**立即**带 `adToken=<token>` 重试（多数情况可跳过广告片直接播）；
   - 若再 133：说明服务端按 `adTime` 计时，**等够 `adTime` 秒**再带（可能刷新的）token 重试一次；
   - 若 133 不带 adToken：用 `deriveProbeUrl()` 发一次 `nom3u8=1` 预检补拿 token，再重试。
   - 成功后 `VideoStream.uri` 为带 token 的 URL，`HlsMediaSource` 链路无需改。
3. `withAdToken(url, token)`：替换 query 里的 `adToken=`，并对 base64 的 `+ / =` 做 URL 编码（否则 `+` 被当空格，校验必失败）。
4. 总超时 15s→30s（要给 adTime 留时间）。

**参数对照（App 现在发的 = 网页「播放」请求，只差 adToken 的值）：**

```
GET https://pan.baidu.com/api/streaming
  ?clienttype=0&app_id=250528&web=1
  &path=<urlencoded>&fsid=<fsid>&channel=chunlei&bdstoken=<bdstoken>
  &type=M3U8_AUTO_480&isplayer=1&check_blue=1&adToken=<从133响应填这里>&src_id=1
```

---

## 5. 残余未知（需真机二次验证）

- **空 adToken 的 `M3U8_AUTO_480` 是否也会在 133 响应里直接带 adToken**：抓包里网页版是先用 `nom3u8=1` 预检拿的 token；App 若拿不到 token，代码已用 `nom3u8` 预检兜底。
- **服务端是否严格要求「距 token 下发 ≥ adTime 秒」**：代码策略是「先立即重试（能过就不等、跳过广告片），被拦再等」。
- **`ltime=5` 的语义**（疑似 token 有效期/最小间隔，单位未知）。
- 若服务端还要求别的隐藏参数（如 `jsToken`、`dp-logid`），需在新抓包上补齐；本次旧文档已证 `jsToken`/`vip` 非必需。

## 6. 验证

```
# 编译 + 单测（VideoStreamResolverLogicTest 已覆盖：133+adToken=AdGate、无 token=受限、withAdToken 编码、deriveProbeUrl）
gradle :app:testDebugUnitTest --tests "com.hnwd.bdplayer.VideoStreamResolverLogicTest"
```
真机：用非会员账号打开 `.avi` / `.mp4` 冷文件，观察日志中 `/api/streaming` 先 133、随后带 adToken 拿到 m3u8；能起播即成功。

---

## 7. 账号隔离与播放验证

已移除按文件共享的广告令牌缓存。切换账号后，新的取流请求使用当前登录会话。

日志中的 `playlist received` 表示已收到通过格式检查的 HLS 清单。实际起播需要在播放器确认首帧或播放进度，抓包中的约 8 秒只是本次网页样本的观测值。

---

## 8. 真机验证（2026-11-17 18:0x，T700 平板 · 非会员账号 小胖的天空6）

**先修正一个关键事实**：之前那台“又是 133、跳过广告不行”的平板，装的是**不含本修复的旧包**。把该包 pull 下来查 dex：
- 有 `StreamingRestricted` / `TranscodingPending`（老逻辑：133→判受限→退回原始文件）
- **没有** `AdGate` / `M3U8_FLV_264_480` / `ShikeStream`

即跑的根本不是修复代码。重新 `assembleRelease`（同签名、`adb install -r` 覆盖、保留登录）后，dex 已含 `AdGate`/`M3U8_FLV_264_480`/`ShikeStream`，实机结果：

| 场景 | 日志 | 结果 |
|---|---|---|
| 群组视频 `第一章1-4.mp4` | `round=0 code=200 err=AdGate adTime=8` → `round=1 playlist received`（≈0.5s）→ `ExoPlayerImpl: Init` | 首帧已出，无广告、无等待 |
| 我的网盘 `天道EP03.mkv`（867MB） | `round=0 … AdGate adTime=8` → `round=1 playlist received`（≈0.3s）→ Init | 首帧已出，无广告、无等待 |

**结论（真机证实）**：
1. `133 → 立即带 adToken 重试` 即拿到 m3u8；**服务端并不强制等满 `adTime`**，所以「跳过广告」实为**零广告、零等待**，且首帧已渲染（截图可证）。
2. 之前失败＝旧包（无该逻辑），与代码逻辑无关。
3. 覆盖安装用 release 同签名包，登录态保留（仍为 小胖的天空6）。

> 若要用 Android Studio 的 **debug** 包测试：务必从**本目录**（`/Users/jianboguo/Work/ai_prodjct/baidu_player`）构建，否则会用旧源码；debug 与 release 签名不同，覆盖安装需先卸载（会清登录）。也可直接用同签名 release 包测。
