# 群组播放

Android 验证版，包名 `com.hnwd.bdplayer`，支持 Android 8.0 及以上。

## 构建调试包

使用 JDK 17、Gradle 8.14 和 Android SDK 36。在 Android Studio 打开项目根目录，或设置 `ANDROID_HOME` 后运行：

```sh
gradle :app:assembleDebug
```

调试包位于 `app/build/outputs/apk/debug/app-debug.apk`。

## 真机验证

1. 打开「登录」，在百度页面完成登录，回到网盘后点「返回 App」。
2. 打开「设置群 ID」，输入要访问的群的数字 ID。若手上有 `session_id`，使用 `04_` 后面的部分。
3. 查看「最近更新」，再切到「群目录」并逐级进入目录。点击 MP4，验证同一会话下的百度网页播放器能否播放。

最近更新和群根目录先显示首批结果。子目录支持通过「加载下一页」请求后续内容，实际多页行为需真机验证。

点击 MP3 可在线播放。最近更新首页显示「继续收听」和「最近播放」。音频播放时会向系统发布媒体控件，可在通知栏和允许显示媒体控件的锁屏上暂停或继续。
