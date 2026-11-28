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
import android.content.SharedPreferences
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
import android.widget.ListView
import android.widget.TextView
import androidx.core.app.NotificationCompat
import androidx.core.content.FileProvider
import com.google.android.material.materialswitch.MaterialSwitch
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
        get() = VideoCollectorLogic.collectorBaiduDir(rootDir, subDir)

    val uploadFolderId: String
        get() = if (provider == SourceProvider.QUARK)
            subFolderId.ifBlank { rootFolderId } else ""
}

internal interface CollectorPreferencesHost {
    fun settings(): SharedPreferences
    fun accountVerified(): Boolean
    fun quarkLoggedIn(): Boolean
}

/**
 * 默认网盘 / 默认根目录的持久化。存在 account_scope，
 * 键按 AccountScope.key() 分桶，避免串账号。子目录是"本次选择"，不进这里。
 */
internal object CollectorTargetStore {
    // verifiedAccount 在冷启动校验完成前为空；读取已持久化的账号命名空间，避免临时切到另一默认网盘。
    private fun bucket(): String = AccountScope.key("collector_")
    private const val KEY_PROVIDER = "provider"
    private const val KEY_ROOT_PATH = "root_path"
    private const val KEY_ROOT_FID = "root_fid"

    fun defaultProvider(host: CollectorPreferencesHost): SourceProvider {
        val saved = host.settings().getString(bucket() + KEY_PROVIDER, "").orEmpty()
        if (saved == "BAIDU" || saved == "QUARK") return SourceProvider.valueOf(saved)
        return when {
            host.accountVerified() -> SourceProvider.BAIDU
            host.quarkLoggedIn() -> SourceProvider.QUARK
            else -> SourceProvider.BAIDU
        }
    }

    fun load(host: CollectorPreferencesHost, accountId: String): CollectorTarget {
        val prefs = host.settings()
        val b = bucket()
        val provider = defaultProvider(host)
        val rootPath = prefs.getString(b + KEY_ROOT_PATH, "/").orEmpty().ifBlank { "/" }
        val rootFid = prefs.getString(b + KEY_ROOT_FID, "").orEmpty()
            .ifBlank { if (provider == SourceProvider.QUARK) QuarkPersonalDrive.ROOT else "" }
        return CollectorTarget(provider, accountId, rootPath, rootFid, "", "")
    }

    fun saveProvider(host: CollectorPreferencesHost, provider: SourceProvider) {
        if (provider == defaultProvider(host)) return
        val key = bucket()
        host.settings().edit().putString(key + KEY_PROVIDER, provider.name)
            .remove(key + KEY_ROOT_PATH).remove(key + KEY_ROOT_FID).apply()
    }

    fun saveRoot(host: CollectorPreferencesHost, provider: SourceProvider, rootPath: String, rootFid: String) {
        host.settings().edit()
            .putString(bucket() + KEY_PROVIDER, provider.name)
            .putString(bucket() + KEY_ROOT_PATH, rootPath)
            .putString(bucket() + KEY_ROOT_FID, rootFid)
            .apply()
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

private fun collectorUnavailableReason(context: Context, platform: String): String? {
    if (ExtensionDownload.available(context, platform)) return null
    val server = ForwardServerStore.load(context)?.baseUrl?.let(ForwardServerLogic::normalizeBaseUrl)
    if (!ExtensionDownload.requiresLocalExtension(platform) && server != null) return null
    return if (ExtensionDownload.supports(platform)) {
        "请先在扩展管理中安装或更新分享下载扩展"
    } else {
        val label = VideoCollectorLogic.platformLabel(VideoCollectorLogic.Platform.valueOf(platform))
        "当前分享下载扩展暂不支持$label"
    }
}

/* ---------------------------------------------------------------------------
 * 收集弹窗（唯一入口：showCollectorSheet）。布局在 dialog_collector_sheet.xml，
 * 宽屏为右侧浮层，窄屏为底部弹层；各入口（首页 chip / FAB 行）都只调这一个函数。
 * ------------------------------------------------------------------------- */

internal fun MainActivity.showCollectorSheet(sharedUrl: String? = null): Boolean {
    val existing = collectorSheetDialog?.takeIf { it.isShowing }
    if (existing != null && sharedUrl == null) return false
    if (sharedUrl != null) {
        val link = VideoCollectorLogic.parse(sharedUrl) ?: return false
        val reason = collectorUnavailableReason(this, link.platform.name)
        if (reason != null) {
            toast(reason)
            return false
        }
    }
    val host = SettingsHostAdapter()
    val available = listOf(SourceProvider.BAIDU, SourceProvider.QUARK).filter { collectorCanUse(it) }
    if (available.isEmpty()) {
        toast("请先登录百度或夸克网盘")
        return false
    }
    val provider = CollectorTargetStore.defaultProvider(host)
    if (provider !in available) {
        toast("默认网盘未登录，请登录或在设置中更换默认网盘")
        return false
    }
    val accountId = runCatching { collectorAccount(provider) }.getOrDefault("")
    if (accountId.isBlank()) { toast("无法确认默认网盘账号"); return false }
    var target = CollectorTargetStore.load(host, accountId)

    val ctx = this
    val wide = resources.configuration.screenWidthDp >= 600
    val sheet = layoutInflater.inflate(R.layout.dialog_collector_sheet, null, false)
    val targetLabel = sheet.findViewById<TextView>(R.id.collectorTarget)
    val linkInput = sheet.findViewById<EditText>(R.id.collectorLink)
    if (sharedUrl != null) {
        linkInput.setText(sharedUrl)
        linkInput.setSelection(linkInput.text.length)
    }
    val pasteButton = sheet.findViewById<TextView>(R.id.collectorPaste)
    val nameInput = sheet.findViewById<EditText>(R.id.collectorName)
    val fetchTitle = sheet.findViewById<TextView>(R.id.collectorFetchTitle)
    val folderRow = sheet.findViewById<View>(R.id.collectorFolder)
    val folderTitle = sheet.findViewById<TextView>(R.id.collectorFolderTitle)
    val folderSub = sheet.findViewById<TextView>(R.id.collectorFolderSub)
    val keepBox = sheet.findViewById<MaterialSwitch>(R.id.collectorKeep)
    val supportHint = sheet.findViewById<TextView>(R.id.collectorSupportHint)
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
    fun refreshSupportedPlatforms() {
        val config = ForwardServerStore.load(ctx)
        val serverAvailable = config?.let { ForwardServerLogic.normalizeBaseUrl(it.baseUrl) != null } == true
        val extra = if (serverAvailable) " · 小红书使用已保存的解析服务" else ""
        supportHint.text = when {
            ExtensionDownload.available(ctx, "YOUTUBE") ->
                "B站 / 抖音 / 微博 / X / TikTok / 小红书 / YouTube 可在本机下载"
            ExtensionDownload.available(ctx, "WEIBO") ->
                "B站 / 抖音 / 微博 / X / TikTok 可在本机下载$extra"
            ExtensionDownload.installed(ctx) -> "B站 / X / 抖音可在本机下载$extra"
            config == null -> "请在扩展管理中安装分享下载扩展，并使用支持的链接"
            ForwardServerLogic.normalizeBaseUrl(config.baseUrl) == null -> "已保存的解析服务地址无效，请使用扩展支持的链接"
            else -> "B站 / X / 抖音 / 小红书由 ${config.displayHost} 解析"
        }
    }
    refreshTarget()
    refreshFolder()
    refreshSupportedPlatforms()

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
        if (ExtensionDownload.available(ctx, parsed.platform.name)) {
            toast("本机下载完成后会自动使用视频标题，也可以先填写文件名")
            return@setOnClickListener
        }
        if (ExtensionDownload.requiresLocalExtension(parsed.platform.name)) {
            toast("请先在设置 → 扩展管理中安装或更新分享下载扩展")
            return@setOnClickListener
        }
        val previousName = nameInput.text.toString()
        fetchTitle.isEnabled = false
        fetchTitle.alpha = 0.45f
        AppIo.execute {
            val title = runCatching {
                val config = checkNotNull(ForwardServerStore.load(ctx)) {
                    "请在扩展管理中安装分享下载扩展，并使用支持的链接"
                }
                ForwardServerClient(config).parse(parsed.canonicalUrl).title
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

    var folderTrail: List<Pair<String, String>>? = null
    folderRow.setOnClickListener {
        val provider = target.provider
        ctx.openCollectorFolderPicker(
            target, currentAccount = { if (collectorCanUse(provider)) collectorAccount(provider) else "" },
            folderTrail = folderTrail, onTrail = { folderTrail = it },
        ) { path, fid ->
            val relative = VideoCollectorLogic.collectorRelativeDir(target.rootDir, path)
                ?: return@openCollectorFolderPicker
            target = target.copy(subDir = relative, subFolderId = if (relative.isEmpty()) "" else fid)
            refreshFolder()
        }
    }

    val dialog = android.app.Dialog(ctx).apply {
        requestWindowFeature(android.view.Window.FEATURE_NO_TITLE)
    }
    close.setOnClickListener { dialog.dismiss() }
    cancel.setOnClickListener { dialog.dismiss() }
    start.setOnClickListener {
        val parsed = VideoCollectorLogic.parse(linkInput.text?.toString().orEmpty())
        if (parsed == null) {
            toast("没找到可识别的视频链接")
            return@setOnClickListener
        }
        collectorUnavailableReason(ctx, parsed.platform.name)?.let { reason ->
            toast(reason)
            return@setOnClickListener
        }
        val account = runCatching { collectorAccount(target.provider) }.getOrDefault("")
        if (account.isBlank() || account != target.accountId || !collectorCanUse(target.provider)) {
            toast("网盘账号已变更或失效，请重新打开分享下载")
            return@setOnClickListener
        }
        val finalTarget = target.copy(accountId = account)
        try {
            CollectorForegroundService.start(
                ctx, parsed.canonicalUrl, parsed.platform.name, finalTarget,
                keepBox.isChecked, nameInput.text?.toString().orEmpty(),
            )
        } catch (error: Exception) {
            toast("无法启动分享下载：${collectorError(error)}")
            return@setOnClickListener
        }
        dialog.dismiss()
        val snackbar = com.google.android.material.snackbar.Snackbar.make(
            shell, "已开始分享下载，可在传输中心查看进度", com.google.android.material.snackbar.Snackbar.LENGTH_LONG,
        )
        // 底栏在根布局的最后绘制；把 Snackbar 锚到它上方，避免操作按钮被底栏吞掉点击。
        snackbar.setAnchorView(findViewById(R.id.tabBar))
        snackbar.setAction("查看进度") { openCollectorDownloads() }
        snackbar.show()
    }

    existing?.dismiss()
    collectorSheetDialog = dialog
    dialog.setOnDismissListener { if (collectorSheetDialog === dialog) collectorSheetDialog = null }
    ctx.showCollectorSheetDialog(dialog, sheet, wide, 504, 0.94f, 780)
    return dialog.isShowing
}

/* ---------------------------------------------------------------------------
 * 子目录选择器：从默认根起步，只列根下目录；可"保存到此处"与"＋新建子文件夹"。
 * ------------------------------------------------------------------------- */

/* 分享相关弹窗共用容器：手机底部，平板右侧。 */
internal fun Activity.showCollectorSheetDialog(
    dialog: android.app.Dialog,
    sheet: View,
    wide: Boolean,
    widthDp: Int,
    phoneHeightFraction: Float? = null,
    phoneMaxHeightDp: Int = 780,
) {
    val preferredPhoneHeight = phoneHeightFraction?.let {
        dp(minOf((resources.configuration.screenHeightDp * it).roundToInt(), phoneMaxHeightDp))
    }
    val frame = if (!wide && preferredPhoneHeight != null) {
        object : FrameLayout(this) {
            override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
                // ADJUST_RESIZE 缩小窗口时，也缩小面板本身，避免固定高度的子视图被键盘裁住。
                val height = if (MeasureSpec.getMode(heightMeasureSpec) == MeasureSpec.UNSPECIFIED) {
                    preferredPhoneHeight
                } else {
                    minOf(preferredPhoneHeight, MeasureSpec.getSize(heightMeasureSpec))
                }
                getChildAt(0).layoutParams.height = height
                super.onMeasure(widthMeasureSpec, heightMeasureSpec)
            }
        }
    } else {
        FrameLayout(this)
    }
    if (wide) frame.setPadding(dp(12), dp(12), dp(12), dp(12))
    val height = if (wide) ViewGroup.LayoutParams.MATCH_PARENT
        else preferredPhoneHeight ?: ViewGroup.LayoutParams.WRAP_CONTENT
    frame.addView(sheet, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, height))
    if (!wide && preferredPhoneHeight != null) {
        sheet.addOnLayoutChangeListener { _, _, top, _, bottom, _, oldTop, _, oldBottom ->
            if (bottom - top < oldBottom - oldTop) {
                val input = sheet.findFocus() as? EditText ?: return@addOnLayoutChangeListener
                input.post {
                    if (input.hasFocus() && input.isShown) {
                        input.requestRectangleOnScreen(android.graphics.Rect(0, 0, input.width, input.height))
                    }
                }
            }
        }
    }
    sheet.setBackgroundResource(if (wide) R.drawable.bg_sheet_floating else R.drawable.bg_search_sheet)
    sheet.clipToOutline = true
    sheet.elevation = if (wide) dp(18).toFloat() else 0f
    dialog.setContentView(frame)
    dialog.window?.apply {
        setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        addFlags(android.view.WindowManager.LayoutParams.FLAG_DIM_BEHIND)
        setDimAmount(if (wide) 0.26f else 0.42f)
        setGravity(if (wide) Gravity.RIGHT or Gravity.TOP else Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL)
        @Suppress("DEPRECATION")
        setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE or
            android.view.WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN)
        setWindowAnimations(if (wide) R.style.RightSheetAnimation else R.style.SearchSheetAnimation)
    }
    dialog.setCanceledOnTouchOutside(true)
    dialog.show()
    dialog.window?.setLayout(
        if (wide) dp(widthDp) else ViewGroup.LayoutParams.MATCH_PARENT,
        if (wide) ViewGroup.LayoutParams.MATCH_PARENT else ViewGroup.LayoutParams.WRAP_CONTENT,
    )
}

private fun Activity.openCollectorFolderPicker(
    target: CollectorTarget,
    currentAccount: () -> String,
    choosingRoot: Boolean = false,
    folderTrail: List<Pair<String, String>>? = null,
    onTrail: (List<Pair<String, String>>) -> Unit = {},
    onPick: (String, String) -> Unit,
) {
    val ctx = this
    val root = target.rootDir to target.rootFolderId
    val initial = folderTrail ?: (listOf(root) + if (target.subDir.isBlank()) emptyList() else listOf(
        VideoCollectorLogic.collectorBaiduDir(target.rootDir, target.subDir) to target.uploadFolderId,
    ))
    var stack = listOf(root)
    var hasLoaded = false
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
    list.adapter = ArrayAdapter(ctx, android.R.layout.simple_list_item_1, emptyList<String>())
    title.text = if (choosingRoot) "选择默认上传根目录" else "选择上传目录"
    confirm.text = if (choosingRoot) "设为默认根目录" else "保存到此处"

    val dialog = android.app.Dialog(ctx).apply {
        requestWindowFeature(android.view.Window.FEATURE_NO_TITLE)
    }
    dialog.setCanceledOnTouchOutside(true)
    close.setOnClickListener { dialog.dismiss() }
    cancel.setOnClickListener { dialog.dismiss() }
    ctx.showCollectorSheetDialog(
        dialog, sheet, wide, widthDp = 584,
        phoneHeightFraction = 0.9f, phoneMaxHeightDp = 740,
    )
    var revision = 0
    fun select(path: String, fid: String) {
        if (runCatching(currentAccount).getOrNull() != target.accountId) {
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
        onTrail(stack)
        onPick(path, fid)
    }
    fun setFolderControls(enabled: Boolean) {
        up.isEnabled = enabled && stack.size > 1
        up.alpha = if (up.isEnabled) 1f else 0.45f
        confirm.isEnabled = enabled && hasLoaded
        create.isEnabled = enabled && hasLoaded
        create.alpha = if (create.isEnabled) 1f else 0.55f
        list.isEnabled = enabled && hasLoaded
    }
    fun render(next: List<Pair<String, String>> = stack) {
        val version = ++revision
        val (path, fid) = next.last()
        status.text = "正在读取目录…"
        status.setOnClickListener(null)
        setFolderControls(false)
        AppIo.execute {
            val result = runCatching { collectorListDirs(target.provider, path, fid) }
            ctx.runOnUiThread {
                if (!dialog.isShowing || version != revision) return@runOnUiThread
                result.onFailure {
                    status.text = "读取失败：${collectorError(it)}\n点此重试"
                    status.setOnClickListener { render(next) }
                    setFolderControls(true)
                    if (!hasLoaded && next != stack) {
                        up.isEnabled = true
                        up.alpha = 1f
                    }
                }.onSuccess { rows ->
                    stack = next
                    hasLoaded = true
                    pathLabel.text = "当前位置：$path"
                    status.text = if (rows.isEmpty()) "这里还没有子目录" else "点目录进入，再确认保存位置"
                    list.adapter = ArrayAdapter(ctx, android.R.layout.simple_list_item_1, rows.map { it.first })
                    list.setOnItemClickListener { _, _, position, _ ->
                        val (_, childPath, childFid) = rows[position]
                        if (VideoCollectorLogic.collectorRelativeDir(path, childPath).isNullOrEmpty() ||
                            (target.provider == SourceProvider.QUARK && childFid.isBlank())) {
                            toast("无效的子目录")
                        } else {
                            render(stack + (childPath to childFid))
                        }
                    }
                    setFolderControls(true)
                }
            }
        }
    }
    pathLabel.text = "当前位置：${root.first}"
    confirm.setOnClickListener { val (path, fid) = stack.last(); select(path, fid) }
    up.setOnClickListener { render(if (stack.size > 1) stack.dropLast(1) else stack) }
    create.setOnClickListener {
        val (path, fid) = stack.last()
        val input = EditText(ctx).apply { hint = "文件夹名称"; isSingleLine = true }
        val naming = AlertDialog.Builder(ctx).setTitle("新建文件夹").setView(input)
            .setNegativeButton("取消", null).setPositiveButton("创建", null).show()
        naming.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val name = input.text.toString().trim()
            val error = diskNameError(name)
            if (error != null) { input.error = error; return@setOnClickListener }
            if (runCatching(currentAccount).getOrNull() != target.accountId) {
                toast("网盘账号已变更，请重新打开目录选择")
                naming.dismiss(); dialog.dismiss(); return@setOnClickListener
            }
            naming.dismiss()
            val version = ++revision
            setFolderControls(false)
            status.setOnClickListener(null)
            status.text = "正在新建文件夹…"
            AppIo.execute {
                val result = runCatching { collectorCreateDir(target.provider, path, fid, name) }
                ctx.runOnUiThread {
                    if (!dialog.isShowing || version != revision) return@runOnUiThread
                    result.onSuccess { (createdPath, createdFid) ->
                        render(stack + (createdPath to createdFid))
                    }
                        .onFailure { toast("新建失败：${collectorError(it)}"); render() }
                }
            }
        }
    }
    render(initial)
}

/** 只列一层子目录；返回 (显示名, 完整路径, fid)。百度 fid 不用，传空。 */
private fun collectorListDirs(
    provider: SourceProvider, dir: String, fid: String,
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
    provider: SourceProvider, dir: String, fid: String, name: String,
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

// 这是启动门槛，不代表任意大小的视频都一定能下载和暂存。
private const val COLLECTOR_MIN_FREE_BYTES = 128L * 1024 * 1024
internal const val COLLECTOR_LOCAL_COPY_FAILED = "本机副本保存失败"

/* ---------------------------------------------------------------------------
 * 分享下载设置：默认网盘和保存目录。
 * ------------------------------------------------------------------------- */

internal fun Activity.showCollectorSettings(
    settingsHost: CollectorPreferencesHost,
    accountFor: (SourceProvider) -> String,
) {
    val panel = layoutInflater.inflate(R.layout.dialog_collector_settings, null)
    val dialog = android.app.Dialog(this)
    dialog.requestWindowFeature(android.view.Window.FEATURE_NO_TITLE)
    bindCollectorPanel(settingsHost, panel, dialog, accountFor)
    val wide = resources.configuration.screenWidthDp >= 600
    showCollectorSheetDialog(dialog, panel, wide, widthDp = 504)
}

private fun Activity.bindCollectorPanel(
    settingsHost: CollectorPreferencesHost,
    page: View,
    dialog: android.app.Dialog,
    accountFor: (SourceProvider) -> String,
) {
    val hostActivity = this
    val providerRow = page.findViewById<View>(R.id.setCollectorProviderRow) ?: return
    val providerValue = page.findViewById<TextView>(R.id.setCollectorProviderValue)
    val rootRow = page.findViewById<View>(R.id.setCollectorRootRow)
    val rootValue = page.findViewById<TextView>(R.id.setCollectorRootValue)
    page.findViewById<View>(R.id.collectorSettingsClose).setOnClickListener { dialog.dismiss() }

    fun refresh() {
        providerValue?.text = TransferSession.providerName(CollectorTargetStore.defaultProvider(settingsHost))
        rootValue?.text = CollectorTargetStore.load(settingsHost, "").rootDir
    }
    refresh()

    providerRow.setOnClickListener {
        val all = arrayOf("百度网盘", "夸克网盘")
        AlertDialog.Builder(hostActivity).setTitle("默认上传网盘").setItems(all) { _, which ->
            val chosen = if (which == 0) SourceProvider.BAIDU else SourceProvider.QUARK
            CollectorTargetStore.saveProvider(settingsHost, chosen)
            refresh()
        }.show()
    }
    rootRow?.setOnClickListener {
        val provider = CollectorTargetStore.defaultProvider(settingsHost)
        val account = runCatching { accountFor(provider) }.getOrDefault("")
        if (account.isBlank()) {
            toast("请先登录当前网盘")
            return@setOnClickListener
        }
        val target = CollectorTarget(provider, account, "/", QuarkPersonalDrive.ROOT, "", "")
        openCollectorFolderPicker(target, currentAccount = { accountFor(provider) }, choosingRoot = true) { path, fid ->
            CollectorTargetStore.saveRoot(settingsHost, provider, path, fid)
            refresh()
        }
    }
}

/* ---------------------------------------------------------------------------
 * 前台服务：解析服务给出直链 → 私有缓存 → 入 UploadCenter → 成功后再决定本机副本去留。
 * ------------------------------------------------------------------------- */

internal class CollectorForegroundService : Service() {
    companion object {
        private const val CHANNEL = "video_collector"
        private const val NOTIFY_ID = 4101
        private const val EXTRA_URL = "url"
        private const val EXTRA_PROVIDER = "provider"
        private const val EXTRA_ACCOUNT = "account"
        private const val EXTRA_DIR = "dir"
        private const val EXTRA_FID = "fid"
        private const val EXTRA_KEEP = "keep"
        private const val EXTRA_NAME = "name"
        private const val EXTRA_TASK_ID = "task_id"
        private const val EXTRA_SERVER_URL = "server_url"
        private const val EXTRA_PLATFORM = "platform"
        private const val EXTRA_SITE_COOKIES = "site_cookies"
        private val taskSequence = java.util.concurrent.atomic.AtomicLong()
        private var runningService: CollectorForegroundService? = null

        fun cancel(taskId: Long): Boolean = runningService?.cancelTask(taskId) == true

        fun start(
            context: Activity, url: String, platform: String, target: CollectorTarget,
            keepLocal: Boolean, customName: String, siteCookies: String = "", requireExtension: Boolean = false,
            retryTaskId: Long? = null,
        ): Boolean {
            val local = ExtensionDownload.available(context, platform)
            check((!requireExtension && !ExtensionDownload.requiresLocalExtension(platform) && siteCookies.isEmpty()) || local) { "请安装分享下载扩展后重试" }
            val serverUrl = if (local) null else ForwardServerStore.load(context)?.baseUrl?.let(ForwardServerLogic::normalizeBaseUrl)
                ?: error("此链接暂无可用的下载方式")
            val now = System.currentTimeMillis()
            val taskId = retryTaskId ?: taskSequence.updateAndGet { maxOf(now, it + 1) }
            val queued = CollectorTaskStore.begin(
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
                    createdAt = now,
                    customName = customName,
                    localExtension = local,
                ),
                retry = retryTaskId != null,
            )
            if (!queued) return false
            val intent = Intent(context, CollectorForegroundService::class.java).apply {
                putExtra(EXTRA_URL, url)
                putExtra(EXTRA_PROVIDER, target.provider.name)
                putExtra(EXTRA_ACCOUNT, target.accountId)
                putExtra(EXTRA_DIR, target.uploadDir)
                putExtra(EXTRA_FID, target.uploadFolderId)
                putExtra(EXTRA_KEEP, keepLocal)
                putExtra(EXTRA_NAME, customName)
                putExtra(EXTRA_TASK_ID, taskId)
                putExtra(EXTRA_SERVER_URL, serverUrl)
                putExtra(EXTRA_PLATFORM, platform)
                if (siteCookies.isNotEmpty()) putExtra(EXTRA_SITE_COOKIES, siteCookies)
            }
            try {
                if (local) ExtensionDownload.activate(context)
                context.startForegroundService(intent)
            } catch (error: Exception) {
                CollectorTaskStore.finish(context, taskId, false, "无法启动分享下载：${collectorError(error)}")
                throw error
            }
            return true
        }
    }

    @Volatile private var cancelled = false
    private val main = android.os.Handler(android.os.Looper.getMainLooper())

    // 队列和通知都由主线程串行驱动；后台 worker 只执行队首任务。
    private val waiting = LinkedHashMap<Long, () -> Unit>()
    @Volatile private var runningId: Long? = null
    @Volatile private var cancelledId: Long? = null
    private val running: Boolean get() = runningId != null
    private val acceptedIds = mutableSetOf<Long>()
    private var latestStartId = 0
    private val stopWhenIdle = Runnable {
        if (!running && waiting.isEmpty()) stopSelfResult(latestStartId)
    }

    private fun pump() {
        if (running || cancelled) return
        val nextId = waiting.keys.firstOrNull() ?: return
        runningId = nextId
        checkNotNull(waiting.remove(nextId)).invoke()
    }

    private fun completeJob(taskId: Long, text: String, success: Boolean) {
        acceptedIds.remove(taskId)
        if (cancelled) return
        runningId = null
        cancelledId = null
        if (waiting.isNotEmpty()) {
            pump()
        } else {
            if (!success) stopForeground(STOP_FOREGROUND_DETACH)
            runCatching { manager.notify(NOTIFY_ID, notify(text, if (success) 100 else -1)) }
            main.postDelayed(stopWhenIdle, 1200)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        runningService = this
    }

    private fun cancelTask(taskId: Long): Boolean {
        if (taskId == runningId && cancelledId != taskId) {
            cancelledId = taskId
            updateTask(taskId, "正在取消…", 0)
            return true
        }
        if (waiting.remove(taskId) == null) return false
        acceptedIds.remove(taskId)
        CollectorTaskStore.finish(this, taskId, false, "已取消分享下载")
        return true
    }

    private fun checkTaskActive(taskId: Long) {
        check(!cancelled && cancelledId != taskId) { "分享下载已中断" }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        latestStartId = startId
        main.removeCallbacks(stopWhenIdle)
        if (!running) {
            createChannel()
            startForeground(NOTIFY_ID, notify("准备收集视频…", 0))
        }
        if (intent == null) {
            if (!running) stopSelfResult(startId)
            return START_NOT_STICKY
        }
        val url = intent.getStringExtra(EXTRA_URL).orEmpty()
        val provider = runCatching { SourceProvider.valueOf(intent.getStringExtra(EXTRA_PROVIDER).orEmpty()) }
            .getOrDefault(SourceProvider.BAIDU)
        val account = intent.getStringExtra(EXTRA_ACCOUNT).orEmpty()
        val dir = intent.getStringExtra(EXTRA_DIR).orEmpty().ifBlank { "/" }
        val fid = intent.getStringExtra(EXTRA_FID).orEmpty()
        val keep = intent.getBooleanExtra(EXTRA_KEEP, false)
        val customName = intent.getStringExtra(EXTRA_NAME).orEmpty()
        val taskId = intent.getLongExtra(EXTRA_TASK_ID, 0L)
        if (taskId in acceptedIds) return START_NOT_STICKY
        val serverUrl = intent.getStringExtra(EXTRA_SERVER_URL)?.let(ForwardServerLogic::normalizeBaseUrl)
        val platform = intent.getStringExtra(EXTRA_PLATFORM).orEmpty()
        val siteCookies = intent.getStringExtra(EXTRA_SITE_COOKIES).orEmpty()
        intent.removeExtra(EXTRA_SITE_COOKIES)
        if (taskId <= 0 || url.isBlank() || account.isBlank() ||
            (serverUrl == null && !ExtensionDownload.supports(platform))
        ) {
            val reason = "链接、解析服务器或网盘账号无效"
            if (taskId > 0) CollectorTaskStore.finish(this, taskId, false, reason)
            if (!running) completeJob(taskId, reason, false)
            return START_NOT_STICKY
        }
        acceptedIds.add(taskId)
        if (running) CollectorTaskStore.progress(this, taskId, 0, "等待收集…")
        waiting[taskId] = {
            AppIo.execute { runCollect(taskId, url, serverUrl, platform, siteCookies, provider, account, dir, fid, keep, customName) }
        }
        pump()
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        cancelled = true
        if (runningService === this) runningService = null
        main.removeCallbacks(stopWhenIdle)
        waiting.clear()
        acceptedIds.forEach(CollectorTaskRuntime::markStopped)
        acceptedIds.clear()
        super.onDestroy()
    }

    private fun runCollect(
        taskId: Long, url: String, serverUrl: String?, platform: String, siteCookies: String, provider: SourceProvider,
        account: String, dir: String, fid: String, keep: Boolean, customName: String,
    ) {
        var temporaryBucket: File? = null
        try {
            checkTaskActive(taskId)
            check(isNetworkAvailable(this)) { "当前无可用网络，请连接网络后重试" }
            val availableBytes = StatFs(cacheDir.absolutePath).availableBytes
            check(availableBytes >= COLLECTOR_MIN_FREE_BYTES) { "本机空间不足，请至少腾出 128 MB 后重试" }
            // 不把已排队的链接发送给用户后来配置的另一台服务器。
            val currentServer = ForwardServerStore.load(this)?.baseUrl?.let(ForwardServerLogic::normalizeBaseUrl)
            check(serverUrl == null || currentServer == serverUrl) { "解析服务器配置已变更，请重新收集" }
            updateTask(taskId, "准备收集视频…", 0)
            val parent = File(cacheDir, "collector/${AccountScope.key("v", account)}")
            check(parent.mkdirs() || parent.isDirectory) { "无法创建收集目录" }
            val bucket = java.nio.file.Files.createTempDirectory(parent.toPath(), "job-").toFile()
            temporaryBucket = bucket
            val video = if (serverUrl == null) {
                val pending = File(bucket, "pending.mp4")
                val session = siteCookies.ifEmpty {
                    CollectorSite.fromPlatform(platform)?.let { CollectorSiteSessions.read(this, it)?.cookies }.orEmpty()
                }
                val engineName = ExtensionDownload.download(this, taskId, platform, url, session, pending, {
                    checkTaskActive(taskId)
                }) { label, percent -> updateTask(taskId, label, percent) }
                val base = VideoCollectorLogic.collectorFileName(customName, engineName.removeSuffix(".mp4"))
                File(bucket, "$base.mp4").also { destination ->
                    check(destination == pending || pending.renameTo(destination)) { "无法保存扩展交接的视频" }
                }
            } else downloadViaForwardServer(ForwardServerConfig(serverUrl), url, bucket, taskId, customName)
            checkTaskActive(taskId)
            CollectorTaskStore.updateName(this, taskId, video.name)
            val uri = FileProvider.getUriForFile(applicationContext, "$packageName.fileprovider", video)
            val copyResult = if (keep) {
                runCatching { publishLocalCopy(taskId, video) }
                    .onFailure { logCollectorFailure("保存本机副本失败", it) }
            } else null
            updateTask(taskId, "已下载，正在加入上传队列…", 100)
            // 先持久化交接，后台服务启动受限时也能直接重试已下载的文件。
            main.post {
                val uploadId = try {
                    checkTaskActive(taskId)
                    UploadCenter.enqueue(applicationContext, listOf(uri), dir, fid, provider, account, ownedSourceFile = video).single()
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
                val uploadStarted = runCatching { UploadForegroundService.start(applicationContext) }
                    .onFailure { error ->
                        logCollectorFailure("无法启动后台上传", error)
                        UploadCenter.failQueued(applicationContext, uploadId, "无法启动后台上传，请返回应用后重试")
                    }.isSuccess
                val status = if (uploadStarted) "已加入上传队列" else "已下载，请在上传列表重试"
                val message = when {
                    copyResult?.isFailure == true -> "$status · $COLLECTOR_LOCAL_COPY_FAILED"
                    copyResult?.isSuccess == true -> "$status · 本机副本已保留"
                    else -> status
                }
                CollectorTaskStore.finish(this, taskId, true, message)
                if (!uploadStarted || copyResult?.isFailure == true) {
                    android.widget.Toast.makeText(this, message, android.widget.Toast.LENGTH_LONG).show()
                }
                completeJob(taskId, message, true)
            }
        } catch (error: Exception) {
            failCollect(taskId, "收集失败", error, temporaryBucket)
        }
    }

    private fun downloadViaForwardServer(
        config: ForwardServerConfig, url: String, bucket: File, taskId: Long, customName: String,
    ): File {
        updateTask(taskId, "正在连接解析服务…", 0)
        val client = ForwardServerClient(config)
        checkTaskActive(taskId)
        updateTask(taskId, "正在解析视频…", 0)
        val parsed = client.parse(url)
        val title = parsed.title.ifBlank { "分享视频" }
        val baseName = VideoCollectorLogic.collectorFileName(customName, title)
        val ext = parsed.ext.ifBlank { ForwardServerLogic.extensionFromUrl(parsed.videoUrl) }
            .lowercase().takeIf { it in setOf("mp4", "mov", "m4v", "webm") } ?: "mp4"
        val destination = File(bucket, "$baseName.$ext")
        updateTask(taskId, "正在下载视频…", 0)
        client.download(parsed.videoUrl, destination, checkActive = {
            checkTaskActive(taskId)
        }) { percent ->
            updateTask(taskId, "正在下载视频 $percent%", percent)
        }
        checkTaskActive(taskId)
        return destination
    }

    private fun failCollect(taskId: Long, stage: String, error: Exception, bucket: File?, suffix: String = "") {
        if (bucket != null) {
            runCatching { check(bucket.deleteRecursively()) { "未能删除分享下载暂存目录" } }
                .onFailure { logCollectorFailure("清理暂存文件失败", it) }
        }
        logCollectorFailure(stage, error)
        main.post {
            if (cancelled) return@post
            val message = if (cancelledId == taskId) "已取消分享下载" else "$stage：${collectorError(error)}$suffix"
            if (taskId > 0) CollectorTaskStore.finish(this, taskId, false, message)
            android.widget.Toast.makeText(this, message, android.widget.Toast.LENGTH_LONG).show()
            completeJob(taskId, message, false)
        }
    }

    /** "保留本机"是一次性选择：上传到 MediaStore Downloads/拾课，用系统规范写入。 */
    private fun publishLocalCopy(taskId: Long, file: File) {
        checkTaskActive(taskId)
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
            stream.use { out ->
                file.inputStream().use { input ->
                    val buffer = ByteArray(128 * 1024)
                    while (true) {
                        checkTaskActive(taskId)
                        val size = input.read(buffer)
                        if (size < 0) break
                        out.write(buffer, 0, size)
                    }
                }
            }
            checkTaskActive(taskId)
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

    private fun updateTask(taskId: Long, text: String, percent: Int) {
        main.post {
            if (cancelled || runningId != taskId) return@post
            if (cancelledId == taskId && text != "正在取消…") return@post
            CollectorTaskStore.progress(this, taskId, percent, text)
            runCatching { manager.notify(NOTIFY_ID, notify(text, percent)) }
        }
    }

    private val manager: NotificationManager get() = getSystemService(NotificationManager::class.java)

}
