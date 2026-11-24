# 拾课

拾课是一款支持百度网盘和夸克网盘的 Android 播放器。可以浏览百度群文件库、夸克监控目录和个人网盘，在 App 内听音频、看视频、阅读 PDF 和预览图片。

## 功能

- **共享来源**：关联百度群组或配置夸克监控目录，在首页查看最近更新，按目录浏览或收藏课程。
- **音频与视频**：支持倍速和续播。音频可后台播放，视频支持竖屏、横屏全屏和「只听声音」。
- **图片预览**：百度与夸克列表自带的免登录看图地址可直接查看，页内左右滑动切换、相邻预取，不下载原文件；支持双击与双指缩放，也可交给系统应用打开。没有预览地址时才回落下载原文件（单张上限 30 MB）。
- **课程排序**：在播放器的课程列表中切换名称升序或降序。选择按目录保存，上一节、下一节和自动连播使用同一顺序。
- **在线取址**：百度音频优先使用 HLS，夸克音频使用原文件地址。百度取址遇到网盘验证码时，可在弹窗中手动验证。
- **文档阅读**：百度和夸克的 PDF 使用 App 内阅读器。Office、压缩包等格式仍需在对应网盘中打开，夸克也提供「下载原文件」入口。
- **手动备份**：将群组来源、收藏、播放进度和偏好导出为本机 JSON 文件。

## 安装与使用

支持 **Android 10（API 29）及以上**。如 GitHub 的 **Releases** 页面已提供安装包，日常使用请选择签名的 Release APK。

1. 打开「设置」，登录要使用的网盘。
2. 在首页「群最新更新」或群组页打开文件库列表，从「添加 / 管理来源」勾选百度群组或夸克监控目录。
3. 从首页、群组或网盘页打开文件。音视频播放器的课程列表标题旁可以切换排序，视频横屏时先打开课程列表面板。

拾课使用相应网盘的登录会话访问文件，播放和阅读取决于该网盘的服务与账号权限。

## 备份与恢复

在「设置 → 备份与恢复」中点「立即备份」。JSON 文件保存在手机的 **下载 / 拾课备份**，成功后会显示完整路径。「从文件恢复」会尝试打开同一个目录，也可以浏览其他位置选取旧版导出的 JSON。

Debug 和 Release 的签名不同。切换版本时，先备份并确认文件已写入共享下载目录，再卸载当前版本并安装另一版本。随后可从该目录恢复，并登录原百度网盘账号。备份文件包含本地记录和偏好，登录凭据、播放地址和媒体文件不在其中。

## 开发与测试

`JDK 17`、`Gradle 8.14.3` 和 Android SDK 36.1 与 CI 的配置一致。Android SDK 36.1 提供 Extension 20，满足 PDF 依赖的编译要求。

```
sdkmanager --channel=3 "platforms;android-36.1" "build-tools;35.0.0"
gradle :app:assembleDebug          # 打包
gradle :app:testDebugUnitTest      # 单元测试（首次需要联网拉 junit / org.json）
```

PR 和推送到 `main` 会运行发布脚本回归、夸克登录消息回归、Android 单测与 lint，无需正式包签名凭据。仓库如使用 PR 合并，可把 `Android checks and build` 设为必需检查。

单测只覆盖不碰 Android 框架的纯逻辑：条目的编解码与排序、网盘响应解析、备份文件校验。
以 `android.net.Uri` 为出入参的解析（媒体取址、验证码、文档直链）需要 Robolectric
或设备上的 instrumented test，目前没有覆盖，改动这部分要真机验证。

### 正式包与版本号门禁

`app/build.gradle` 的 `versionCode` 在发布新 tag 时必须严格递增，tag 必须为 `v<versionName>`。正式 APK 只从对应 tag 的提交构建。推送新 tag 会先运行单测、lint 和版本门禁，再签名发布。发布任务在创建 Release 或补传 APK 前核对远端 tag 是否仍指向构建时记录的 Git 对象。

在 Actions 手动指定已有 tag 会重建并保留 APK 构建产物。该 tag 没有 Release 时，按新版本门禁检查，通过后创建 Release。已有 Release 缺少 APK 时会尝试补传，已上传的同名 APK 会保留。
尚无 Release 的旧 tag 会在历史已有相同或更高 `versionCode` 时被拒绝。

本地打包用 `bash build-release.sh --tag vX.Y.Z`，也兼容 `--version X.Y.Z`。已发布版本可用 `bash build-release.sh --rebuild --tag vX.Y.Z` 按原版本号重建。脚本会在临时检出中构建，APK 输出到 `build/releases/`。本地 SDK 和签名文件应使用绝对路径。重建声明 API 37 的历史 tag 时还需安装 `platforms;android-37.0`。不带参数时要求当前提交恰好有发布 tag。

本地发版从调用目录的 `HEAD` 读取已提交的版本门禁，CI 从运行工作流的提交读取门禁。重建历史 tag 时，APK 源码仍取自该 tag。

历史 tag `v0.1.24`、`v1.0.1` 和 `v1.2.4` 的源码版本名分别是 `0.1.23`、`0.1.23` 和 `1.2.3`，本地和 Actions 的重建门禁都会拒绝这三个 tag。需要这些历史版本时，可使用原 Release 中仍可用的 APK。

建议为 GitHub 的 `v*` tag 配置 ruleset，限制更新和删除，防止远端 tag 在校验与附件写入之间被移动。

```
bash .github/scripts/check-version-code.sh        # 校验当前版本号
bash .github/scripts/test-check-version-code.sh   # 门禁自身的场景自测
bash .github/scripts/test-publish-android-release.sh
node --test .github/scripts/test-quark-login-hook.mjs
actionlint .github/workflows/android-release.yml
```

发布脚本测试使用 `jq` 和模拟的 GitHub CLI。登录消息测试使用 Node.js 24。已安装 Go 时，可以用 `go run github.com/rhysd/actionlint/cmd/actionlint@v1.7.12 .github/workflows/android-release.yml` 运行同版本的工作流静态检查。

脚本依赖完整的 tag 历史，CI 的 `actions/checkout` 因此使用 `fetch-depth: 0`。
