package com.hnwd.bdplayer

import android.app.Activity
import android.app.AlertDialog
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ClipboardManager
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.net.ConnectivityManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.IBinder
import android.os.StatFs
import android.provider.MediaStore
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import androidx.core.app.NotificationCompat
import androidx.core.content.FileProvider
import com.google.android.material.materialswitch.MaterialSwitch
import com.yausername.ffmpeg.FFmpeg
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.File
import java.util.Locale
import kotlin.math.roundToInt

/** 一次收集的完整落点：默认网盘 + 默认根目录 + 本次选中的子目录。 */
internal data class CollectorTarget(
    val provider: SourceProvider,
    val accountId: String,
    val rootDir: String,
    val rootFolderId: String,
    val subDir: String,
    val subFolderId: String,
) {
    /** 百度按完整路径上传；夸克按 fid 上传，dir 仅作展示。 */
    val uploadDir: String
        get() = if (provider == SourceProvider.BAIDU)
            VideoCollectorLogic.collectorBaiduDir(rootDir, subDir) else rootDir

    val uploadFolderId: String
        get() = if (provider == SourceProvider.QUARK)
            subFolderId.ifBlank { rootFolderId } else ""
}

/**
 * 默认网盘 / 默认根目录的持久化。存在 SettingsHost.settings()（即 account_scope），
 * 键按 AccountScope.key() 分桶，避免串账号。子目录是"本次选择"，不进这里。
 */
internal object CollectorTargetStore {
    // verifiedAccount 在冷启动校验完成前为空；读取已持久化的账号命名空间，避免临时切到另一默认网盘。
    private fun bucket(): String = AccountScope.key("collector_")
    private const val KEY_PROVIDER = "provider"
    private const val KEY_ROOT_PATH = "root_path"
    private const val KEY_ROOT_FID = "root_fid"

    fun defaultProvider(host: SettingsHost): SourceProvider {
        val saved = host.settings().getString(bucket() + KEY_PROVIDER, "").orEmpty()
        if (saved == "BAIDU" || saved == "QUARK") return SourceProvider.valueOf(saved)
        return when {
            host.accountVerified() -> SourceProvider.BAIDU
            host.quarkLoggedIn() -> SourceProvider.QUARK
            else -> SourceProvider.BAIDU
        }
    }

    fun load(host: SettingsHost, accountId: String): CollectorTarget {
        val prefs = host.settings()
        val b = bucket()
        val provider = defaultProvider(host)
        val rootPath = prefs.getString(b + KEY_ROOT_PATH, "/").orEmpty().ifBlank { "/" }
        val rootFid = prefs.getString(b + KEY_ROOT_FID, "").orEmpty()
            .ifBlank { if (provider == SourceProvider.QUARK) QuarkPersonalDrive.ROOT else "" }
        return CollectorTarget(provider, accountId, rootPath, rootFid, "", "")
    }

    fun saveProvider(host: SettingsHost, provider: SourceProvider) {
        if (provider == defaultProvider(host)) return
        val key = bucket()
        host.settings().edit().putString(key + KEY_PROVIDER, provider.name)
            .remove(key + KEY_ROOT_PATH).remove(key + KEY_ROOT_FID).apply()
    }

    fun saveRoot(host: SettingsHost, provider: SourceProvider, rootPath: String, rootFid: String) {
        host.settings().edit()
            .putString(bucket() + KEY_PROVIDER, provider.name)
            .putString(bucket() + KEY_ROOT_PATH, rootPath)
            .putString(bucket() + KEY_ROOT_FID, rootFid)
            .apply()
    }

    fun summary(host: SettingsHost): String {
        val b = bucket()
        val provider = defaultProvider(host)
        val root = host.settings().getString(b + KEY_ROOT_PATH, "/").orEmpty().ifBlank { "/" }
        return "${TransferSession.providerName(provider)} · $root"
    }
}

/* ---------------------------------------------------------------------------
 * 收集身份：和 MainActivity.chooseUploadFiles() 完全一致的口径，
 * 但作为 MainActivity 扩展写在独立文件里，MainActivity 主体不增加成员。
 * ------------------------------------------------------------------------- */

internal fun MainActivity.collectorAccount(provider: SourceProvider): String = when (provider) {
    SourceProvider.BAIDU -> verifiedAccount
    SourceProvider.QUARK -> TransferSession.capture(SourceProvider.QUARK).quarkAccount()
}

internal fun MainActivity.collectorCanUse(provider: SourceProvider): Boolean = when (provider) {
    SourceProvider.BAIDU -> hasVerifiedAccount()
    SourceProvider.QUARK -> QuarkPersonalDrive.openSession() != null
}

/* ---------------------------------------------------------------------------
 * 收集弹窗（唯一入口：showCollectorSheet）。布局在 dialog_collector_sheet.xml，
 * 宽屏为右侧浮层，窄屏为底部弹层；各入口（首页 chip / FAB 行）都只调这一个函数。
 * ------------------------------------------------------------------------- */

internal fun MainActivity.showCollectorSheet() {
    val host = SettingsHostAdapter()
    val available = listOf(SourceProvider.BAIDU, SourceProvider.QUARK).filter { collectorCanUse(it) }
    if (available.isEmpty()) {
        toast("请先登录百度或夸克网盘")
        return
    }
    val provider = CollectorTargetStore.defaultProvider(host)
    if (provider !in available) {
        toast("默认网盘未登录，请登录或在设置中更换默认网盘")
        return
    }
    val accountId = runCatching { collectorAccount(provider) }.getOrDefault("")
    if (accountId.isBlank()) { toast("无法确认默认网盘账号"); return }
    var target = CollectorTargetStore.load(host, accountId)

    val ctx = this
    val wide = resources.configuration.screenWidthDp >= 600
    val sheet = layoutInflater.inflate(R.layout.dialog_collector_sheet, null, false)
    val panel = sheet.findViewById<LinearLayout>(R.id.collectorSheet)
    val targetLabel = sheet.findViewById<TextView>(R.id.collectorTarget)
    val linkInput = sheet.findViewById<EditText>(R.id.collectorLink)
    val pasteButton = sheet.findViewById<TextView>(R.id.collectorPaste)
    val nameInput = sheet.findViewById<EditText>(R.id.collectorName)
    val fetchTitle = sheet.findViewById<TextView>(R.id.collectorFetchTitle)
    val folderRow = sheet.findViewById<View>(R.id.collectorFolder)
    val folderTitle = sheet.findViewById<TextView>(R.id.collectorFolderTitle)
    val folderSub = sheet.findViewById<TextView>(R.id.collectorFolderSub)
    val keepBox = sheet.findViewById<MaterialSwitch>(R.id.collectorKeep)
    val cancel = sheet.findViewById<TextView>(R.id.collectorCancel)
    val start = sheet.findViewById<TextView>(R.id.collectorStart)
    val close = sheet.findViewById<View>(R.id.collectorClose)

    fun refreshTarget() {
        val providerLabel = if (target.provider == SourceProvider.BAIDU) "百度网盘" else "夸克网盘"
        targetLabel.text = "保存到 $providerLabel"
    }
    fun refreshFolder() {
        val path = VideoCollectorLogic.collectorBaiduDir(target.rootDir, target.subDir)
        folderTitle.text = path.ifBlank { "/" }
        folderSub.text = if (target.subDir.isBlank())
            "默认根目录 · 点击选择或新建" else "本次子目录 · 点击更换"
    }
    refreshTarget()
    refreshFolder()

    pasteButton.setOnClickListener {
        val clipboard = getSystemService(ClipboardManager::class.java)
        val clip = clipboard?.primaryClip
        val text = if (clip != null && clip.itemCount > 0)
            clip.getItemAt(0).coerceToText(ctx).toString().trim() else ""
        if (text.isBlank()) {
            toast("剪贴板里没有可粘贴的内容")
            return@setOnClickListener
        }
        linkInput.setText(text)
        linkInput.setSelection(linkInput.text.length)
    }

    fetchTitle.setOnClickListener {
        val input = linkInput.text.toString()
        val parsed = VideoCollectorLogic.parse(input)
        if (parsed == null) {
            toast("请先粘贴可识别的视频链接")
            return@setOnClickListener
        }
        val previousName = nameInput.text.toString()
        fetchTitle.isEnabled = false
        fetchTitle.alpha = 0.45f
        AppIo.execute {
            val title = runCatching {
                val engine = CollectorEngine.youtubeDl(ctx)
                engine.getInfo(YoutubeDLRequest(parsed.canonicalUrl).apply {
                    addOption("--no-playlist")
                    addOption("--socket-timeout", "15")
                }).title
            }
            val failure = title.exceptionOrNull()?.let { error ->
                logCollectorFailure("读取标题失败", error)
                collectorError(error)
            }
            ctx.runOnUiThread {
                fetchTitle.isEnabled = true
                fetchTitle.alpha = 1f
                if (!fetchTitle.isAttachedToWindow || linkInput.text.toString() != input ||
                    nameInput.text.toString() != previousName) return@runOnUiThread
                val fetched = title.getOrNull()
                if (failure != null) {
                    AlertDialog.Builder(ctx).setTitle("未读到视频标题")
                        .setMessage("$failure\n\n也可以手动填写文件名。")
                        .setPositiveButton("知道了", null).show()
                } else if (fetched.isNullOrBlank()) toast("未读到视频标题，可以手动填写")
                else nameInput.setText(VideoCollectorLogic.collectorFileName(null, fetched))
            }
        }
    }

    folderRow.setOnClickListener {
        ctx.openCollectorFolderPicker(host, target) { path, fid ->
            val relative = VideoCollectorLogic.collectorRelativeDir(target.rootDir, path)
                ?: return@openCollectorFolderPicker
            target = target.copy(subDir = relative, subFolderId = if (relative.isEmpty()) "" else fid)
            refreshFolder()
        }
    }

    val frame = FrameLayout(ctx)
    if (wide) frame.setPadding(ctx.dp(12), ctx.dp(12), ctx.dp(12), ctx.dp(12))
    val sheetHeight = if (wide) ViewGroup.LayoutParams.MATCH_PARENT
        else ctx.dp(minOf((resources.configuration.screenHeightDp * 0.94f).roundToInt(), 780))
    frame.addView(sheet, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, sheetHeight))
    panel.setBackgroundResource(if (wide) R.drawable.bg_sheet_floating else R.drawable.bg_search_sheet)
    panel.elevation = if (wide) ctx.dp(18).toFloat() else 0f

    val dialog = android.app.Dialog(ctx).apply {
        requestWindowFeature(android.view.Window.FEATURE_NO_TITLE)
        setContentView(frame)
    }
    dialog.window?.apply {
        setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        addFlags(android.view.WindowManager.LayoutParams.FLAG_DIM_BEHIND)
        setDimAmount(if (wide) 0.26f else 0.42f)
        setGravity(if (wide) Gravity.RIGHT or Gravity.TOP else Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL)
        @Suppress("DEPRECATION")
        setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE or
            android.view.WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN)
        if (!wide) setWindowAnimations(R.style.SearchSheetAnimation)
    }
    dialog.setCanceledOnTouchOutside(true)
    close.setOnClickListener { dialog.dismiss() }
    cancel.setOnClickListener { dialog.dismiss() }
    start.setOnClickListener {
        val parsed = VideoCollectorLogic.parse(linkInput.text?.toString().orEmpty())
        if (parsed == null) {
            toast("没找到可识别的 B站 / X / 抖音 视频链接")
            return@setOnClickListener
        }
        val account = runCatching { collectorAccount(target.provider) }.getOrDefault("")
        if (account.isBlank() || account != target.accountId || !collectorCanUse(target.provider)) {
            toast("网盘账号已变更或失效，请重新打开分享下载")
            return@setOnClickListener
        }
        val finalTarget = target.copy(accountId = account)
        dialog.dismiss()
        CollectorForegroundService.start(
            ctx, parsed.canonicalUrl, parsed.platform.name, finalTarget,
            keepBox.isChecked, nameInput.text?.toString().orEmpty(),
        )
        val snackbar = com.google.android.material.snackbar.Snackbar.make(
            shell, "已开始分享下载，可在传输中心查看进度", com.google.android.material.snackbar.Snackbar.LENGTH_LONG,
        )
        // 底栏在根布局的最后绘制；把 Snackbar 锚到它上方，避免操作按钮被底栏吞掉点击。
        snackbar.setAnchorView(findViewById(R.id.tabBar))
        snackbar.setAction("查看进度") { openCollectorDownloads() }
        snackbar.show()
    }

    dialog.show()
    dialog.window?.setLayout(
        if (wide) ctx.dp(504) else ViewGroup.LayoutParams.MATCH_PARENT,
        if (wide) ViewGroup.LayoutParams.MATCH_PARENT else ViewGroup.LayoutParams.WRAP_CONTENT,
    )
    if (wide && android.animation.ValueAnimator.areAnimatorsEnabled()) {
        panel.translationX = ctx.dp(56).toFloat()
        panel.animate().translationX(0f).setDuration(220).start()
    }
}

/* ---------------------------------------------------------------------------
 * 子目录选择器：从默认根起步，只列根下目录；可"保存到此处"与"＋新建子文件夹"。
 * ------------------------------------------------------------------------- */

private fun MainActivity.openCollectorFolderPicker(
    host: SettingsHost,
    target: CollectorTarget,
    choosingRoot: Boolean = false,
    onPick: (String, String) -> Unit,
) {
    val ctx = this
    val stack = mutableListOf(target.rootDir to target.rootFolderId)
    val wide = resources.configuration.screenWidthDp >= 600
    val sheet = layoutInflater.inflate(R.layout.dialog_collector_folder, null, false)
    val title = sheet.findViewById<TextView>(R.id.collectorFolderDialogTitle)
    val pathLabel = sheet.findViewById<TextView>(R.id.collectorFolderPath)
    val status = sheet.findViewById<TextView>(R.id.collectorFolderStatus)
    val list = sheet.findViewById<ListView>(R.id.collectorFolderList)
    val up = sheet.findViewById<TextView>(R.id.collectorFolderUp)
    val create = sheet.findViewById<TextView>(R.id.collectorFolderCreate)
    val confirm = sheet.findViewById<TextView>(R.id.collectorFolderConfirm)
    val cancel = sheet.findViewById<TextView>(R.id.collectorFolderCancel)
    val close = sheet.findViewById<View>(R.id.collectorFolderClose)
    title.text = if (choosingRoot) "选择默认上传根目录" else "选择上传目录"
    confirm.text = if (choosingRoot) "设为默认根目录" else "保存到此处"

    val frame = FrameLayout(ctx).apply {
        setPadding(ctx.dp(12), ctx.dp(12), ctx.dp(12), ctx.dp(12))
        addView(sheet, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
        ))
    }
    val dialog = android.app.Dialog(ctx).apply {
        requestWindowFeature(android.view.Window.FEATURE_NO_TITLE)
        setContentView(frame)
    }
    dialog.window?.apply {
        setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        addFlags(android.view.WindowManager.LayoutParams.FLAG_DIM_BEHIND)
        setDimAmount(0.32f)
        setGravity(Gravity.CENTER)
    }
    dialog.setCanceledOnTouchOutside(true)
    close.setOnClickListener { dialog.dismiss() }
    cancel.setOnClickListener { dialog.dismiss() }
    dialog.show()
    dialog.window?.setLayout(
        if (wide) ctx.dp(584) else ViewGroup.LayoutParams.MATCH_PARENT,
        ViewGroup.LayoutParams.WRAP_CONTENT,
    )
    var revision = 0
    fun select(path: String, fid: String) {
        if (runCatching { collectorAccount(target.provider) }.getOrNull() != target.accountId ||
            !collectorCanUse(target.provider)) {
            toast("网盘账号已变更，请重新打开目录选择")
            dialog.dismiss()
            return
        }
        if (VideoCollectorLogic.collectorRelativeDir(target.rootDir, path) == null ||
            (target.provider == SourceProvider.QUARK && fid.isBlank())) {
            toast("无法确认目录，请刷新重试")
            return
        }
        dialog.dismiss()
        onPick(path, fid)
    }
    fun render() {
        val version = ++revision
        val (path, fid) = stack.last()
        pathLabel.text = "当前位置：$path"
        status.text = "正在读取目录…"
        up.isEnabled = stack.size > 1
        up.alpha = if (stack.size > 1) 1f else 0.45f
        confirm.isEnabled = false
        create.isEnabled = false
        create.alpha = 0.55f
        list.adapter = null
        status.setOnClickListener(null)
        AppIo.execute {
            val result = runCatching { collectorListDirs(target.provider, path, fid, host) }
            ctx.runOnUiThread {
                if (!dialog.isShowing || version != revision) return@runOnUiThread
                result.onFailure {
                    status.text = "读取失败：${collectorError(it)}\n点此重试"
                    status.setOnClickListener { render() }
                }.onSuccess { rows ->
                    status.text = if (rows.isEmpty()) "这里还没有子目录" else "点目录进入，再确认保存位置"
                    confirm.isEnabled = true
                    create.isEnabled = true
                    create.alpha = 1f
                    (list.layoutParams as? LinearLayout.LayoutParams)?.let {
                        it.height = ctx.dp(if (rows.isEmpty()) 96 else 228)
                        list.layoutParams = it
                    }
                    list.adapter = ArrayAdapter(ctx, android.R.layout.simple_list_item_1, rows.map { it.first })
                    list.setOnItemClickListener { _, _, position, _ ->
                        val (_, childPath, childFid) = rows[position]
                        if (VideoCollectorLogic.collectorRelativeDir(path, childPath).isNullOrEmpty() ||
                            (target.provider == SourceProvider.QUARK && childFid.isBlank())) {
                            toast("无效的子目录")
                        } else {
                            stack.add(childPath to childFid)
                            render()
                        }
                    }
                }
            }
        }
    }
    confirm.setOnClickListener { val (path, fid) = stack.last(); select(path, fid) }
    up.setOnClickListener { if (stack.size > 1) { stack.removeAt(stack.lastIndex); render() } }
    create.setOnClickListener {
        val (path, fid) = stack.last()
        val input = EditText(ctx).apply { hint = "文件夹名称"; isSingleLine = true }
        val naming = AlertDialog.Builder(ctx).setTitle("新建文件夹").setView(input)
            .setNegativeButton("取消", null).setPositiveButton("创建", null).show()
        naming.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val name = input.text.toString().trim()
            val error = diskNameError(name)
            if (error != null) { input.error = error; return@setOnClickListener }
            if (runCatching { collectorAccount(target.provider) }.getOrNull() != target.accountId) {
                toast("网盘账号已变更，请重新打开目录选择")
                naming.dismiss(); dialog.dismiss(); return@setOnClickListener
            }
            naming.dismiss()
            val version = ++revision
            for (view in listOf(up, create, confirm, list)) view.isEnabled = false
            status.text = "正在新建文件夹…"
            AppIo.execute {
                val result = runCatching { collectorCreateDir(target.provider, path, fid, name, host) }
                ctx.runOnUiThread {
                    if (!dialog.isShowing || version != revision) return@runOnUiThread
                    list.isEnabled = true
                    result.onSuccess { (createdPath, createdFid) ->
                        if (choosingRoot) { stack.add(createdPath to createdFid); render() }
                        else select(createdPath, createdFid)
                    }
                        .onFailure { toast("新建失败：${collectorError(it)}"); render() }
                }
            }
        }
    }
    render()
}

/** 只列一层子目录；返回 (显示名, 完整路径, fid)。百度 fid 不用，传空。 */
private fun collectorListDirs(
    provider: SourceProvider, dir: String, fid: String, host: SettingsHost,
): List<Triple<String, String, String>> = when (provider) {
    SourceProvider.BAIDU -> {
        val session = TransferSession.capture(SourceProvider.BAIDU)
        val client = collectorClient()
        val tokenJson = JSONObject(client.newCall(Request.Builder().url(PanApi.groupListSession())
            .header("Cookie", session.cookie).header("User-Agent", WEB_UA).build()).execute()
            .use { it.body?.string().orEmpty() })
        val token = tokenJson.optJSONObject("result")?.optString("bdstoken").orEmpty()
        check(token.isNotBlank()) { "未取到 bdstoken，请重新登录" }
        val request = BaiduPersonalDrive(token).list(dir, 1, 100)
        val json = JSONObject(client.newCall(Request.Builder().url(request.url)
            .header("Cookie", session.cookie).header("User-Agent", WEB_UA).build()).execute()
            .use { it.body?.string().orEmpty() })
        check(json.optInt("errno", -1) == 0) { PanApi.writeError("读取目录", json.optInt("errno", -1), json) }
        request.decode(json).items.filter { it.directory }
            .map { Triple(it.name, it.path, "") }
    }
    SourceProvider.QUARK -> {
        val drive = QuarkPersonalDrive.openSession() ?: throw QuarkLoginRequired()
        val page = drive.execute(drive.list(fid, 1, 100))
        page.items.filter { it.directory }
            .map { Triple(it.name, PanApi.diskPath(dir, it.name), it.id) }
    }
}

/** 在默认根下新建一层子目录；返回 (完整路径, fid)。 */
private fun collectorCreateDir(
    provider: SourceProvider, dir: String, fid: String, name: String, host: SettingsHost,
): Pair<String, String> = when (provider) {
    SourceProvider.BAIDU -> {
        val session = TransferSession.capture(SourceProvider.BAIDU)
        val client = collectorClient()
        val tokenJson = JSONObject(client.newCall(Request.Builder().url(PanApi.groupListSession())
            .header("Cookie", session.cookie).header("User-Agent", WEB_UA).build()).execute()
            .use { it.body?.string().orEmpty() })
        val token = tokenJson.optJSONObject("result")?.optString("bdstoken").orEmpty()
        check(token.isNotBlank()) { "未取到 bdstoken，请重新登录" }
        val write = PanApi.createFolder(token, PanApi.diskPath(dir, name))
        val body = write.form.toByteArray().toRequestBody("application/x-www-form-urlencoded".toMediaType())
        val json = JSONObject(client.newCall(Request.Builder().url(write.url).post(body)
            .header("Cookie", session.cookie).header("User-Agent", WEB_UA).build()).execute()
            .use { it.body?.string().orEmpty() })
        check(json.optInt("errno", -1) == 0) { PanApi.writeError("新建文件夹", json.optInt("errno", -1), json) }
        PanApi.diskPath(dir, name) to ""
    }
    SourceProvider.QUARK -> {
        val drive = QuarkPersonalDrive.openSession() ?: throw QuarkLoginRequired()
        drive.create(fid, name)
        PanApi.diskPath(dir, name) to runCatching {
            drive.execute(drive.list(fid, 1, 100)).items.first { it.name == name && it.directory }.id
        }.getOrElse { throw IllegalStateException("文件夹已创建，但未找到目录。请返回后重新选择。", it) }
    }
}

private const val WEB_UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
    "(KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"
private fun collectorClient() = OkHttpClient.Builder().build()

private val collectorUrlPattern = Regex("https?://\\S+", RegexOption.IGNORE_CASE)

private fun collectorDiagnostic(error: Throwable): String =
    error.message.orEmpty().replace(collectorUrlPattern, "[链接]")

private fun collectorError(error: Throwable): String {
    val lines = collectorDiagnostic(error).lineSequence().map(String::trim).filter(String::isNotBlank)
    var first: String? = null
    var lastEngineError: String? = null
    for (line in lines) {
        if (first == null) first = line
        if (line.startsWith("ERROR:")) lastEngineError = line.removePrefix("ERROR:").trim()
    }
    return (lastEngineError?.takeIf(String::isNotBlank) ?: first)?.take(240) ?: "未返回错误详情"
}

private fun logCollectorFailure(stage: String, error: Throwable) {
    generateSequence(error) { it.cause?.takeUnless { cause -> cause === it } }.take(4).forEach { cause ->
        val details = collectorDiagnostic(cause).ifBlank { "未返回错误详情" }
        details.chunked(800).forEach { android.util.Log.w("VideoCollector", "$stage (${cause.javaClass.simpleName})：$it") }
    }
}

private fun isNetworkAvailable(context: Context): Boolean {
    val manager = context.getSystemService(ConnectivityManager::class.java) ?: return true
    val network = manager.activeNetwork ?: return false
    val capabilities = manager.getNetworkCapabilities(network) ?: return true
    return capabilities.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET)
}

// 这是启动门槛，不代表任意大小的视频都一定能下载、合成和暂存。
private const val COLLECTOR_MIN_FREE_BYTES = 128L * 1024 * 1024
internal const val COLLECTOR_LOCAL_COPY_FAILED = "本机副本保存失败"

private object CollectorEngine {
    private var youtubeReady = false
    private var ffmpegReady = false

    @Synchronized
    fun youtubeDl(context: Context): YoutubeDL {
        val engine = YoutubeDL.getInstance()
        if (!youtubeReady) {
            try {
                engine.init(context.applicationContext)
                youtubeReady = true
            } catch (error: Exception) {
                throw IllegalStateException("下载引擎初始化失败", error)
            }
        }
        return engine
    }

    @Synchronized
    fun forDownload(context: Context): YoutubeDL {
        val engine = youtubeDl(context)
        if (!ffmpegReady) {
            try {
                FFmpeg.getInstance().init(context.applicationContext)
                ffmpegReady = true
            } catch (error: Exception) {
                throw IllegalStateException("音视频合成组件初始化失败", error)
            }
        }
        return engine
    }
}

/* ---------------------------------------------------------------------------
 * 设置页两行绑定：只依赖 SettingsHost 现有能力，不扩接口、不碰 MainActivity。
 * ------------------------------------------------------------------------- */

internal fun SettingsHost.bindCollectorRows(page: View) {
    val entry = page.findViewById<View>(R.id.setCollectorSettingsRow) ?: return
    val value = page.findViewById<TextView>(R.id.setCollectorSettingsValue)
    fun refresh() { value.text = CollectorTargetStore.summary(this) }
    refresh()
    entry.setOnClickListener {
        val panel = hostActivity.layoutInflater.inflate(R.layout.dialog_collector_settings, null)
        bindCollectorPanel(panel) { refresh() }
        AlertDialog.Builder(hostActivity).setTitle("分享下载")
            .setView(panel).setPositiveButton("完成", null).show()
    }
}

private fun SettingsHost.bindCollectorPanel(page: View, onChanged: () -> Unit) {
    val providerRow = page.findViewById<View>(R.id.setCollectorProviderRow) ?: return
    val providerValue = page.findViewById<TextView>(R.id.setCollectorProviderValue)
    val rootRow = page.findViewById<View>(R.id.setCollectorRootRow)
    val rootValue = page.findViewById<TextView>(R.id.setCollectorRootValue)
    fun refresh() {
        providerValue?.text = TransferSession.providerName(CollectorTargetStore.defaultProvider(this))
        rootValue?.text = CollectorTargetStore.load(this, "").rootDir
        onChanged()
    }
    refresh()
    providerRow.setOnClickListener {
        val all = arrayOf("百度网盘", "夸克网盘")
        AlertDialog.Builder(hostActivity).setTitle("默认上传网盘").setItems(all) { _, which ->
            val chosen = if (which == 0) SourceProvider.BAIDU else SourceProvider.QUARK
            CollectorTargetStore.saveProvider(this, chosen)
            refresh()
        }.show()
    }
    rootRow?.setOnClickListener {
        val provider = CollectorTargetStore.defaultProvider(this)
        val activity = hostActivity as? MainActivity ?: return@setOnClickListener
        activity.openCollectorRootPicker(this, provider) { path, fid ->
            CollectorTargetStore.saveRoot(this, provider, path, fid)
            refresh()
        }
    }
}

private fun MainActivity.openCollectorRootPicker(
    host: SettingsHost, provider: SourceProvider, onPick: (String, String) -> Unit,
) {
    val account = runCatching { collectorAccount(provider) }.getOrDefault("")
    if (account.isBlank() || !collectorCanUse(provider)) { toast("请先登录当前网盘"); return }
    val target = CollectorTarget(provider, account, "/", QuarkPersonalDrive.ROOT, "", "")
    openCollectorFolderPicker(host, target, choosingRoot = true, onPick = onPick)
}

/* ---------------------------------------------------------------------------
 * 前台服务：yt-dlp 下载 → 私有缓存 → 入 UploadCenter → 成功后再决定本机副本去留。
 * ------------------------------------------------------------------------- */

internal class CollectorForegroundService : Service() {
    companion object {
        private const val CHANNEL = "video_collector"
        private const val NOTIFY_ID = 4101
        private const val EXTRA_URL = "url"
        private const val EXTRA_PLATFORM = "platform"
        private const val EXTRA_PROVIDER = "provider"
        private const val EXTRA_ACCOUNT = "account"
        private const val EXTRA_DIR = "dir"
        private const val EXTRA_FID = "fid"
        private const val EXTRA_KEEP = "keep"
        private const val EXTRA_NAME = "name"
        private const val EXTRA_TASK_ID = "task_id"

        fun start(
            context: Context, url: String, platform: String, target: CollectorTarget,
            keepLocal: Boolean, customName: String,
        ): Long {
            val taskId = System.currentTimeMillis()
            CollectorTaskStore.begin(
                context.applicationContext,
                CollectorTask(
                    id = taskId,
                    name = customName.ifBlank { "分享视频" },
                    url = url,
                    platform = platform,
                    provider = target.provider,
                    accountId = target.accountId,
                    dir = target.uploadDir,
                    folderId = target.uploadFolderId,
                    keepLocal = keepLocal,
                    createdAt = taskId,
                ),
            )
            val intent = Intent(context, CollectorForegroundService::class.java).apply {
                putExtra(EXTRA_URL, url)
                putExtra(EXTRA_PLATFORM, platform)
                putExtra(EXTRA_PROVIDER, target.provider.name)
                putExtra(EXTRA_ACCOUNT, target.accountId)
                putExtra(EXTRA_DIR, target.uploadDir)
                putExtra(EXTRA_FID, target.uploadFolderId)
                putExtra(EXTRA_KEEP, keepLocal)
                putExtra(EXTRA_NAME, customName)
                putExtra(EXTRA_TASK_ID, taskId)
            }
            context.startForegroundService(intent)
            return taskId
        }
    }

    @Volatile private var cancelled = false
    private val main = android.os.Handler(android.os.Looper.getMainLooper())

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        createChannel()
        startForeground(NOTIFY_ID, notify("准备收集视频…", 0))
        if (intent == null) { stopSelf(); return START_NOT_STICKY }
        val url = intent.getStringExtra(EXTRA_URL).orEmpty()
        val platform = intent.getStringExtra(EXTRA_PLATFORM).orEmpty()
        val provider = runCatching { SourceProvider.valueOf(intent.getStringExtra(EXTRA_PROVIDER).orEmpty()) }
            .getOrDefault(SourceProvider.BAIDU)
        val account = intent.getStringExtra(EXTRA_ACCOUNT).orEmpty()
        val dir = intent.getStringExtra(EXTRA_DIR).orEmpty().ifBlank { "/" }
        val fid = intent.getStringExtra(EXTRA_FID).orEmpty()
        val keep = intent.getBooleanExtra(EXTRA_KEEP, false)
        val customName = intent.getStringExtra(EXTRA_NAME).orEmpty()
        val taskId = intent.getLongExtra(EXTRA_TASK_ID, 0L)
        if (url.isBlank() || account.isBlank()) {
            if (taskId > 0) CollectorTaskStore.finish(this, taskId, false, "链接或网盘账号无效")
            finish("链接或网盘账号无效", 0); return START_NOT_STICKY
        }
        AppIo.execute { runCollect(taskId, url, platform, provider, account, dir, fid, keep, customName) }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        cancelled = true
        CollectorTaskRuntime.clear()
        super.onDestroy()
    }

    private fun runCollect(
        taskId: Long, url: String, platform: String, provider: SourceProvider,
        account: String, dir: String, fid: String, keep: Boolean, customName: String,
    ) {
        var temporaryBucket: File? = null
        try {
            check(isNetworkAvailable(this)) { "当前无可用网络，请连接网络后重试" }
            val availableBytes = StatFs(cacheDir.absolutePath).availableBytes
            check(availableBytes >= COLLECTOR_MIN_FREE_BYTES) { "本机空间不足，请至少腾出 128 MB 后重试" }
            updateTask(taskId, "初始化下载引擎…", 0)
            val engine = CollectorEngine.forDownload(this)
            val parent = File(cacheDir, "collector/${AccountScope.key("v", account)}")
            check(parent.mkdirs() || parent.isDirectory) { "无法创建收集目录" }
            val bucket = java.nio.file.Files.createTempDirectory(parent.toPath(), "job-").toFile()
            temporaryBucket = bucket
            val prefix = VideoCollectorLogic.collectorOutputPrefix(platform)
            val outTemplate = File(bucket, "$prefix%(title)s.%(ext)s").absolutePath
            val format = when (platform) {
                "BILIBILI" -> "bv*[ext=mp4]+ba[ext=m4a]/b[ext=mp4]/best"
                else -> "bv*+ba/b"
            }
            val request = YoutubeDLRequest(url).apply {
                addOption("-f", format)
                addOption("--merge-output-format", "mp4")
                addOption("-o", outTemplate)
                addOption("--no-playlist")
                addOption("--no-update")
                addOption("--socket-timeout", "20")
            }
            val processId = "collector-$taskId"
            var lastPercent = -1
            updateTask(taskId, "正在下载视频…", 0)
            val response = engine.execute(request, processId) { progress, _, line ->
                if (cancelled) return@execute
                val percent = progress.coerceIn(0f, 100f).roundToInt()
                if (percent != lastPercent) {
                    lastPercent = percent
                    updateTask(taskId, "正在下载视频 ${percent}%", percent)
                }
                if (!line.isNullOrBlank() && percent <= 0) updateTask(taskId, "正在解析视频…", 0)
            }
            check(!cancelled) { "分享下载已中断" }
            if (response.exitCode != 0) {
                throw IllegalStateException("下载引擎返回错误（exit ${response.exitCode}）\n${response.err.orEmpty()}")
            }
            val downloaded = bucket.listFiles()?.filter { it.isFile && it.length() > 0 }?.maxByOrNull { it.lastModified() }
                ?: throw IllegalStateException("下载完成但未找到视频文件")
            val baseName = VideoCollectorLogic.collectorDownloadedName(customName, downloaded.name, platform, url)
            val ext = downloaded.extension.ifBlank { "mp4" }
            val renamed = File(bucket, "$baseName.$ext")
            val video = if (renamed != downloaded && !renamed.exists() && downloaded.renameTo(renamed)) renamed else downloaded
            CollectorTaskStore.updateName(this, taskId, video.name)
            val uri = FileProvider.getUriForFile(applicationContext, "$packageName.fileprovider", video)
            val copyResult = if (keep) {
                runCatching { publishLocalCopy(video) }
                    .onFailure { logCollectorFailure("保存本机副本失败", it) }
            } else null
            updateTask(taskId, "已下载，正在加入上传队列…", 100)
            // UploadCenter 的队列只在主线程修改；先请求启动服务，避免启动被拒绝后留下排队任务。
            main.post {
                try {
                    check(!cancelled) { "分享下载已中断" }
                    UploadForegroundService.start(applicationContext)
                    UploadCenter.enqueue(applicationContext, listOf(uri), dir, fid, provider, account)
                } catch (error: Exception) {
                    val copyStatus = when {
                        copyResult?.isSuccess == true -> " · 本机副本已保留"
                        copyResult?.isFailure == true -> " · $COLLECTOR_LOCAL_COPY_FAILED"
                        else -> ""
                    }
                    AppIo.execute {
                        failCollect(taskId, "已下载，但未能加入上传队列", error, bucket, copyStatus)
                    }
                    return@post
                }
                // 上传端仍需读取 FileProvider URI，不能在交接完成时删除源文件。
                val message = when {
                    copyResult?.isFailure == true -> "已加入上传队列 · $COLLECTOR_LOCAL_COPY_FAILED"
                    copyResult?.isSuccess == true -> "已加入上传队列 · 本机副本已保留"
                    else -> "已加入上传队列"
                }
                CollectorTaskStore.finish(this, taskId, true, message)
                if (copyResult?.isFailure == true) {
                    android.widget.Toast.makeText(this, message, android.widget.Toast.LENGTH_LONG).show()
                }
                finish(message, 100)
            }
        } catch (error: Exception) {
            failCollect(taskId, "收集失败", error, temporaryBucket)
        }
    }

    private fun failCollect(taskId: Long, stage: String, error: Exception, bucket: File?, suffix: String = "") {
        if (bucket != null) {
            runCatching { check(bucket.deleteRecursively()) { "未能删除分享下载暂存目录" } }
                .onFailure { logCollectorFailure("清理暂存文件失败", it) }
        }
        logCollectorFailure(stage, error)
        val message = "$stage：${collectorError(error)}$suffix"
        if (taskId > 0) CollectorTaskStore.finish(this, taskId, false, message)
        main.post {
            if (cancelled) return@post
            stopForeground(STOP_FOREGROUND_DETACH)
            runCatching { manager.notify(NOTIFY_ID, notify(message, -1)) }
            android.widget.Toast.makeText(this, message, android.widget.Toast.LENGTH_LONG).show()
            stopSelf()
        }
    }

    /** "保留本机"是一次性选择：上传到 MediaStore Downloads/拾课，用系统规范写入。 */
    private fun publishLocalCopy(file: File) {
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, file.name)
            val mimeType = android.webkit.MimeTypeMap.getSingleton()
                .getMimeTypeFromExtension(file.extension.lowercase(Locale.ROOT))
            put(MediaStore.Downloads.MIME_TYPE, mimeType ?: "application/octet-stream")
            put(MediaStore.Downloads.RELATIVE_PATH, "${Environment.DIRECTORY_DOWNLOADS}/拾课")
            put(MediaStore.Downloads.IS_PENDING, 1)
        }
        val resolver = contentResolver
        val uri = checkNotNull(resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)) {
            "无法创建本机副本"
        }
        try {
            val stream = checkNotNull(resolver.openOutputStream(uri)) { "无法打开本机副本写入流" }
            stream.use { out -> file.inputStream().use { it.copyTo(out) } }
            val done = ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) }
            check(resolver.update(uri, done, null, null) > 0) { "无法发布本机副本" }
        } catch (error: Exception) {
            runCatching { resolver.delete(uri, null, null) }
                .onFailure { logCollectorFailure("清理未完成的本机副本失败", it) }
            throw error
        }
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = getSystemService(NotificationManager::class.java)
            if (manager.getNotificationChannel(CHANNEL) == null) {
                manager.createNotificationChannel(
                    NotificationChannel(CHANNEL, "视频收集", NotificationManager.IMPORTANCE_LOW),
                )
            }
        }
    }

    private fun notify(text: String, percent: Int): Notification {
        val intent = Intent(this, DownloadActivity::class.java).apply {
            putExtra(DownloadActivity.EXTRA_SHOW_COLLECTOR, true)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }
        val pending = PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_download)
            .setContentTitle("分享下载")
            .setContentText(text)
            .setOngoing(percent in 0..99)
            .setAutoCancel(percent < 0)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .apply {
                if (pending != null) setContentIntent(pending)
                if (percent in 0..100) setProgress(100, percent, percent == 0)
            }
            .build()
    }

    private fun notifyOnUi(text: String, percent: Int) {
        android.os.Handler(android.os.Looper.getMainLooper()).post {
            if (!cancelled) runCatching { manager.notify(NOTIFY_ID, notify(text, percent)) }
        }
    }

    private fun updateTask(taskId: Long, text: String, percent: Int) {
        if (taskId > 0) CollectorTaskStore.progress(this, taskId, percent, text)
        notifyOnUi(text, percent)
    }

    private val manager: NotificationManager get() = getSystemService(NotificationManager::class.java)

    private fun finish(text: String, percent: Int) {
        notifyOnUi(text, percent)
        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({ stopSelf() }, 1200)
    }
}
