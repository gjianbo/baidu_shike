package com.hnwd.bdplayer

import android.app.Activity
import android.app.Dialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import org.json.JSONObject

/**
 * 网盘写操作（新建 / 重命名 / 移动 / 复制 / 删除 / 分享 / 转存）的宿主能力。
 *
 * 只暴露这一块真正需要的东西：一次 JSON 请求、bdstoken、状态栏、刷新与选中态。
 * 不把 Activity 整个递进来，是为了让这块逻辑能独立演进（后续换 UI 框架时只改实现）。
 */
internal interface DiskMutationHost {
    /** 弹窗、对话框、布局解析需要的最底层的 Context。 */
    val hostActivity: Activity

    /** 当前账号探测代次；写操作过程中账号变化则结果作废。 */
    val probeId: Int

    fun onDiskScreen(): Boolean
    fun selecting(): Boolean
    fun activityDestroyed(): Boolean
    fun diskDir(): String
    fun diskCacheKey(): String
    fun selection(): List<PanEntry>
    fun sessionReady(): Boolean
    fun accountVerified(): Boolean

    /** 走网盘请求通道取一次 JSON。 */
    fun fetchJson(
        url: String,
        post: Boolean = false,
        form: String? = null,
        lane: RequestLane,
        acceptAnyErrno: Boolean = false,
        onCancelled: () -> Unit = {},
        onFailure: () -> Unit = {},
        onResult: (JSONObject) -> Unit,
    )

    fun withBdstoken(onReady: (String) -> Unit, onFailure: () -> Unit)
    fun showStatus(message: String)
    fun say(message: String)
    fun reloadPage()
    fun resetCourseList()
    fun bumpCourseRequest()
    fun invalidateDiskCaches()
    fun leaveSelection()
    fun openWeb(url: String)
    fun pickFromSheet(title: String, subtitle: String, choices: List<SheetChoice>, onSelect: (SheetChoice) -> Unit)
    fun delay(millis: Long, action: () -> Unit)
}

/**
 * 网盘文件管理：所有对网盘产生副作用的操作集中在这里。
 *
 * 三条不变式（搬出时原样保留，改动前请先确认）：
 * 1. 同一时刻只允许一笔写操作或一笔删除核对，由 [guards] 的 token 锁统一把关；
 *    迟到回调必须带回自己的 token 才能释放，不能清掉后来者的锁。
 * 2. 写操作全程绑定 [DiskMutationHost.probeId]，账号变了就丢弃结果，不写回旧账号的 UI。
 * 3. 删除先核对 `path -> fs_id` 未被改动（[checkDeleteTargets]），提交后还要回读列表确认
 *    （[reconcileDeletedFiles]）；两步都不能靠接口返回值就宣告成功。
 */
internal class DiskMutationSection(private val host: DiskMutationHost) {
    private val activity: Activity get() = host.hostActivity
    private val guards = WriteGuards()

    /** 账号失效时清掉写锁，否则后续所有写操作会被「已有操作在处理」永久挡住。 */
    fun resetGuards() = guards.reset()

    /** 需要 bdstoken 的网盘写操作统一入口。 */
    private fun runDiskWrite(block: (token: String) -> Unit) {
        if (!host.sessionReady()) {
            host.say("请先登录百度网盘")
            return
        }
        host.withBdstoken(onReady = { token -> block(token) }, onFailure = { host.say("网盘会话未就绪，请刷新重试") })
    }

    /** 提交写请求；acceptAnyErrno 让我们拿到网盘具体错误码做友好提示。 */
    private fun submitWrite(op: String, request: PanApi.WriteRequest, onSuccess: (JSONObject) -> Unit) {
        val lock = guards.beginWrite() ?: run {
            host.say("已有网盘操作正在处理，请稍候")
            return
        }
        host.showStatus("正在${op}…")
        val session = host.probeId
        fun finish(message: String) {
            // 账号变了也要释放自己那把锁，否则要等 invalidateAccount() 的 reset() 兜底。
            if (!guards.endWrite(lock)) return
            if (session != host.probeId) return
            host.showStatus(message)
            host.say(message)
        }
        host.fetchJson(
            request.url, post = true, form = request.form, lane = RequestLane.WRITE,
            acceptAnyErrno = true,
            onCancelled = { finish("${op}结果未确认，请刷新查看") },
            onFailure = { finish("${op}结果未确认，请刷新查看") },
        ) { json ->
            // 旧回调（锁已被 reset / 换了持有者）不能碰当前锁，更不能把结果写回旧账号的 UI。
            if (!guards.endWrite(lock) || session != host.probeId) return@fetchJson
            val errno = PanApi.writeErrno(json)
            if (errno == 132) {
                showSecurityVerify(op)
            } else if (errno != 0) {
                val message = PanApi.writeError(op, errno, json)
                if (json.getInt("errno") == 0) {
                    afterDiskMutation("部分项目可能已处理，请查看刷新后的列表。$message")
                } else {
                    host.showStatus(message)
                    host.say(message)
                }
            } else {
                host.showStatus("")
                onSuccess(json)
            }
        }
    }

    /** errno 132：百度对删除等敏感操作强制安全验证，API 无法直接完成，引导去网页验证。 */
    private fun showSecurityVerify(op: String) {
        host.showStatus("百度网盘要求安全验证，「$op」未执行")
        AlertDialog.Builder(activity)
            .setTitle("需要百度安全验证")
            .setMessage("百度网盘对部分敏感操作开启了安全验证，本次「$op」未执行。\n\n可打开网盘网页完成一次验证，再回到 App 重试。")
            .setNegativeButton("知道了", null)
            .setPositiveButton("打开网盘网页") { _, _ ->
                host.openWeb("https://pan.baidu.com/disk/main#/index?category=all")
            }
            .show()
    }

    private fun randomSharePwd(): String {
        val chars = "abcdefghijkmnpqrstuvwxyz23456789"
        return (1..4).map { chars.random() }.joinToString("")
    }

    /** 写操作成功后：清网盘缓存与「已转存」索引，刷新当前页。 */
    private fun afterDiskMutation(message: String = "操作成功") {
        host.invalidateDiskCaches()
        if (host.onDiskScreen()) {
            host.resetCourseList()
            host.reloadPage()
        }
        host.say(message)
    }

    private fun promptDiskName(title: String, action: String, initial: String, onSave: (String) -> Unit) {
        val session = host.probeId
        val input = EditText(activity).apply {
            hint = "名称"
            isSingleLine = true
            setText(initial)
            setSelection(initial.length)
        }
        val dialog = AlertDialog.Builder(activity)
            .setTitle(title)
            .setView(activity.padded(input))
            .setNegativeButton("取消", null)
            .setPositiveButton(action, null)
            .show()
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val name = input.text.toString().trim()
            when {
                name.isEmpty() -> input.error = "请输入名称"
                name == "." || name == ".." || name.any { it in "/\\:*?\"<>|" || it.isISOControl() } ->
                    input.error = "名称包含网盘不支持的字符"
                else -> {
                    dialog.dismiss()
                    if (session == host.probeId && name != initial) onSave(name)
                }
            }
        }
    }

    fun promptNewFolder() {
        if (!host.onDiskScreen() || !host.sessionReady()) return
        val dir = host.diskDir()
        promptDiskName("新建文件夹", "创建", "") { name ->
            runDiskWrite { token ->
                submitWrite("新建文件夹", PanApi.createFolder(token, PanApi.diskPath(dir, name))) {
                    afterDiskMutation("已创建文件夹")
                }
            }
        }
    }

    fun promptRename(entry: PanEntry) {
        promptDiskName("重命名", "保存", entry.name) { name ->
            runDiskWrite { token ->
                submitWrite("重命名", PanApi.renameItem(token, entry, name)) {
                    afterDiskMutation("已重命名")
                }
            }
        }
    }

    fun promptMoveCopy(entry: PanEntry, copy: Boolean) {
        val verb = if (copy) "复制" else "移动"
        openFolderPicker("选择${verb}到", blocked = if (entry.directory) setOf(entry.path) else emptySet()) { dest ->
            if (isSelfOrDescendant(dest, listOf(entry))) {
                host.say("不能${verb}到自身或其子目录")
                return@openFolderPicker
            }
            runDiskWrite { token ->
                val request = if (copy) PanApi.copyItem(token, entry, dest, entry.name)
                else PanApi.moveItem(token, entry, dest)
                submitWrite(verb, request) { afterDiskMutation("已${verb}到 $dest") }
            }
        }
    }

    fun batchMoveSelected() {
        val entries = host.selection()
        if (entries.isEmpty()) {
            host.say("请先选择条目")
            return
        }
        openFolderPicker("移动 ${entries.size} 项到", blocked = entries.filter { it.directory }.map { it.path }.toSet()) { dest ->
            if (isSelfOrDescendant(dest, entries)) {
                host.say("不能移动到自身或其子目录")
                return@openFolderPicker
            }
            runDiskWrite { token ->
                submitWrite("移动", PanApi.moveItems(token, entries, dest)) {
                    host.leaveSelection()
                    afterDiskMutation("已移动 ${entries.size} 项")
                }
            }
        }
    }

    fun batchCopySelected() {
        val entries = host.selection()
        if (entries.isEmpty()) {
            host.say("请先选择条目")
            return
        }
        openFolderPicker("复制 ${entries.size} 项到", blocked = entries.filter { it.directory }.map { it.path }.toSet()) { dest ->
            if (isSelfOrDescendant(dest, entries)) {
                host.say("不能复制到自身或其子目录")
                return@openFolderPicker
            }
            runDiskWrite { token ->
                submitWrite("复制", PanApi.copyItems(token, entries, dest)) {
                    host.leaveSelection()
                    afterDiskMutation("已复制 ${entries.size} 项")
                }
            }
        }
    }

    fun batchDeleteSelected() {
        val entries = host.selection()
        if (entries.isEmpty()) {
            host.say("请先选择条目")
            return
        }
        confirmDelete(entries)
    }

    /** 二次确认后提交删除，并检查网盘是否实际移除了原文件。 */
    fun confirmDelete(entries: List<PanEntry>) {
        if (entries.isEmpty()) return
        val label = if (entries.size == 1) "「${entries[0].name}」" else "${entries.size} 项"
        val wasSelecting = host.selecting()
        val session = host.probeId
        AlertDialog.Builder(activity)
            .setTitle("删除")
            .setMessage("确定删除 $label 吗？删除后可在网盘回收站找回。")
            .setNegativeButton("取消", null)
            .setPositiveButton("删除") { _, _ ->
                if (session != host.probeId) {
                    host.say("网盘会话已变更，请重试")
                } else if (guards.deleteInFlight) {
                    host.say("正在确认上一笔删除，请稍候")
                } else if (host.onDiskScreen() && entries.all { it.isDisk && PanApi.parentPath(it.path) == host.diskDir() }) {
                    val dir = host.diskDir()
                    val cacheKey = host.diskCacheKey()
                    runDiskWrite { token ->
                        checkDeleteTargets(token, dir, entries) {
                            submitWrite("删除", PanApi.deleteItems(token, entries)) {
                                if (wasSelecting && host.onDiskScreen() && host.diskCacheKey() == cacheKey) {
                                    host.leaveSelection()
                                }
                                reconcileDeletedFiles(token, dir, cacheKey, entries.map { it.fileId }.toSet())
                            }
                        }
                    }
                }
            }
            .show()
    }

    /** 删除接口按路径操作，先确认缓存里的路径仍指向原来的文件。 */
    private fun checkDeleteTargets(token: String, dir: String, entries: List<PanEntry>, onVerified: () -> Unit) {
        val lock = guards.beginDelete() ?: run {
            host.say("已有网盘操作正在处理，请稍候")
            return
        }
        val session = host.probeId
        val remaining = entries.associate { it.path to it.fileId }.toMutableMap()
        host.say("正在核对要删除的文件")
        fun finish(verified: Boolean) {
            // 会话变更 / Activity 销毁时同样要释放，且只释放自己那把锁。
            if (!guards.endDelete(lock)) return
            if (session != host.probeId || host.activityDestroyed()) return
            if (verified) onVerified()
            else host.say("无法确认文件仍在原位置，请刷新后重新选择")
        }
        fun scan(page: Int) {
            host.fetchJson(
                PanApi.diskList(token, dir, page), lane = RequestLane.DELETE_CHECK,
                onCancelled = { finish(false) }, onFailure = { finish(false) },
            ) { json ->
                val files = json.getJSONArray("list")
                for (index in 0 until files.length()) {
                    val file = files.getJSONObject(index)
                    val path = PanApi.readablePath(file.getString("path"))
                    val expected = remaining[path] ?: continue
                    if (file.getString("fs_id") != expected) {
                        finish(false)
                        return@fetchJson
                    }
                    remaining.remove(path)
                }
                when {
                    remaining.isEmpty() -> finish(true)
                    files.length() == 0 || page >= 100 || !PanApi.diskHasMore(json, 100) -> finish(false)
                    else -> scan(page + 1)
                }
            }
        }
        scan(1)
    }

    /** 入队后从目录第一页开始检查；只有完整读完且找不到原文件 ID 才确认成功。 */
    private fun reconcileDeletedFiles(token: String, dir: String, cacheKey: String, ids: Set<String>) {
        val session = host.probeId
        // 这是删除链路的下半段：核对已在 checkDeleteTargets 里释放、提交已成功，
        // 此刻抢占删除通道理论上必然成功；抢不到就中止，绝不在未持锁的情况下回读确认。
        val lock = guards.beginDelete() ?: run {
            host.say("删除结果未确认，请刷新查看")
            // 删除请求已经提交，刷新当前目录让用户看到网盘真实状态，而不是停在旧列表上。
            if (host.onDiskScreen() && host.diskCacheKey() == cacheKey) host.reloadPage()
            return
        }
        host.say("删除请求已提交，正在等待网盘确认")

        val seenPages = mutableSetOf<List<String>>()
        /** 仍有效才继续；一旦失效立刻释放自己那把锁，不再依赖账号失效时的 reset()。 */
        fun active(): Boolean {
            if (!host.activityDestroyed() && guards.deleteToken == lock &&
                session == host.probeId && host.accountVerified()) return true
            guards.endDelete(lock)
            return false
        }
        fun finish(confirmed: Boolean) {
            val live = active()
            if (!guards.endDelete(lock)) return
            if (!live) return
            if (confirmed) {
                host.bumpCourseRequest()
                host.invalidateDiskCaches()
            }
            if (host.onDiskScreen() && host.diskCacheKey() == cacheKey) host.reloadPage()
            host.say(if (confirmed) "已删除 ${ids.size} 项" else "删除尚未确认，请稍后刷新查看")
        }

        fun scan(attempt: Int, page: Int) {
            if (!active()) return
            if (page == 1) seenPages.clear()
            fun retry() {
                if (!active()) return
                if (attempt >= 8) finish(false)
                else host.delay(1_000L) { scan(attempt + 1, 1) }
            }
            host.fetchJson(
                PanApi.diskList(token, dir, page), lane = RequestLane.DELETE_CHECK,
                onCancelled = { finish(false) }, onFailure = ::retry,
            ) { json ->
                val files = json.getJSONArray("list")
                val pageIds = List(files.length()) { files.getJSONObject(it).getString("fs_id") }
                when {
                    pageIds.any { it in ids } -> retry()
                    !seenPages.add(pageIds) -> finish(false)
                    !PanApi.diskHasMore(json, 100) -> finish(true)
                    files.length() == 0 || page >= 100 -> finish(false)
                    else -> scan(attempt, page + 1)
                }
            }
        }
        scan(1, 1)
    }

    fun promptShare(entries: List<PanEntry>) {
        if (entries.isEmpty()) return
        val session = host.probeId
        val periods = listOf(0 to "永久有效", 7 to "7 天有效", 1 to "1 天有效")
        host.pickFromSheet(
            title = "创建分享链接",
            subtitle = if (entries.size == 1) entries[0].name else "${entries.size} 项",
            choices = periods.map { SheetChoice(it.second, tag = it.first) },
            onSelect = { choice ->
                if (session != host.probeId) return@pickFromSheet
                val period = choice.tag as Int
                val pwd = randomSharePwd()
                runDiskWrite { token ->
                    submitWrite("创建分享", PanApi.shareSet(token, entries.map { it.fileId }, period, pwd)) { json ->
                        val info = PanApi.parseShare(json)
                        if (info == null) host.say("分享已创建，但未取到链接") else showShareResult(info, pwd)
                    }
                }
            },
        )
    }

    private fun showShareResult(info: PanApi.ShareInfo, fallbackPwd: String = "") {
        val pwd = info.pwd.ifEmpty { fallbackPwd }
        val text = buildString {
            append("链接：").append(info.link)
            if (pwd.isNotEmpty()) append("\n提取码：").append(pwd)
        }
        AlertDialog.Builder(activity)
            .setTitle("分享已创建")
            .setMessage(text)
            .setNegativeButton("关闭", null)
            .setPositiveButton("复制链接") { _, _ ->
                val clip = if (pwd.isNotEmpty()) "${info.link} 提取码 ${pwd}" else info.link
                val cm = activity.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                cm.setPrimaryClip(ClipData.newPlainText("分享链接", clip))
                host.say("已复制到剪贴板")
            }.show()
    }

    fun promptTransfer(entry: PanEntry) {
        openFolderPicker("转存到") { dest ->
            runDiskWrite { token ->
                submitWrite("转存", PanApi.transferFromGroup(token, entry, dest)) {
                    afterDiskMutation("转存请求已提交，请稍后在 $dest 查看")
                }
            }
        }
    }

    private fun openFolderPicker(
        title: String,
        blocked: Set<String> = emptySet(),
        onPick: (String) -> Unit,
    ) {
        // 从当前所在目录起步：深层目录里移动/复制不必每次从根目录重新点进来
        val start = if (host.onDiskScreen()) host.diskDir() else "/"
        host.withBdstoken(
            onReady = { token -> showFolderPickerDialog(title, token, blocked, start, onPick) },
            onFailure = { host.say("网盘会话未就绪，请刷新重试") },
        )
    }

    private fun folderAdapter(names: List<String>): ArrayAdapter<String> =
        object : ArrayAdapter<String>(activity, android.R.layout.simple_list_item_1, names) {
            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                val view = super.getView(position, convertView, parent)
                (view as? TextView)?.apply {
                    setTextColor(activity.color(R.color.ink))
                    textSize = 15f
                    setPadding(activity.dp(8), activity.dp(12), activity.dp(8), activity.dp(12))
                }
                return view
            }
        }

    private fun showFolderPickerDialog(
        title: String,
        token: String,
        blocked: Set<String>,
        startDir: String,
        onPick: (String) -> Unit,
    ) {
        val dialog = Dialog(activity)
        val root = activity.column().apply {
            setBackgroundColor(activity.color(R.color.surface))
            setPadding(activity.dp(16), activity.dp(16), activity.dp(16), activity.dp(12))
        }
        val hint = TextView(activity).apply {
            text = title
            textSize = 13f
            setTypeface(null, Typeface.BOLD)
            setTextColor(activity.color(R.color.ink))
        }
        val pathLabel = TextView(activity).apply {
            text = "/"
            textSize = 12f
            setTextColor(activity.color(R.color.ink3))
            setPadding(0, activity.dp(4), 0, activity.dp(8))
        }
        val list = ListView(activity)
        val more = activity.headAction("加载更多目录") {}
        val up = activity.headAction("↑ 上一级") {}
        val pick = activity.headAction("选择当前目录") {}
        root.addView(hint)
        root.addView(pathLabel)
        root.addView(list, LinearLayout.LayoutParams(-1, 0, 1f))
        root.addView(more)
        root.addView(up)
        root.addView(pick)
        dialog.setContentView(root)
        var current = startDir
        var page = 0
        var hasMore = false
        var loading = false
        val session = host.probeId
        val dirs = linkedMapOf<String, PanEntry>()
        fun isBlocked(dir: String) = blocked.any { path ->
            val base = path.trimEnd('/')
            base.isNotEmpty() && (dir.trimEnd('/') == base || dir.startsWith("$base/"))
        }
        fun updateButtons() {
            up.isEnabled = !loading && current != "/"
            pick.isEnabled = !loading && page > 0
            more.visibility = if (hasMore || page == 0) View.VISIBLE else View.GONE
            more.text = if (page == 0) "重试读取目录" else "加载更多目录"
            more.isEnabled = !loading
            list.isEnabled = !loading
        }
        fun loadDir(dir: String, nextPage: Int = 1) {
            if (loading || session != host.probeId || !dialog.isShowing) return
            if (nextPage == 1) {
                current = dir
                page = 0
                hasMore = false
                dirs.clear()
                list.adapter = null
                pathLabel.text = dir
            }
            loading = true
            updateButtons()
            fun failed() {
                loading = false
                if (dialog.isShowing) {
                    updateButtons()
                    host.say("读取目录失败")
                }
            }
            val request = BaiduPersonalDrive(token).list(dir, nextPage, 100)
            host.fetchJson(
                request.url, lane = RequestLane.COURSE,
                onCancelled = ::failed, onFailure = ::failed,
            ) { json ->
                if (!dialog.isShowing || session != host.probeId) return@fetchJson
                val result = request.decode(json)
                result.items.filter { it.directory && !isBlocked(it.path) }
                    .forEach { dirs[it.path] = it }
                page = nextPage
                hasMore = result.hasMore
                loading = false
                val items = dirs.values.toList()
                val firstVisible = list.firstVisiblePosition
                list.adapter = folderAdapter(if (items.isEmpty() && !hasMore) listOf("（无可选子目录）") else items.map { it.name })
                if (nextPage > 1) list.setSelection(firstVisible)
                list.setOnItemClickListener { _, _, position, _ ->
                    items.getOrNull(position)?.let { loadDir(it.path) }
                }
                updateButtons()
            }
        }
        up.setOnClickListener { loadDir(PanApi.parentPath(current)) }
        more.setOnClickListener { loadDir(current, page + 1) }
        pick.setOnClickListener {
            if (session != host.probeId) {
                dialog.dismiss()
                host.say("网盘会话已变更，请重试")
                return@setOnClickListener
            }
            if (isBlocked(current)) {
                host.say("不能选择被操作文件夹自身或其子目录")
                return@setOnClickListener
            }
            dialog.dismiss()
            onPick(current)
        }
        dialog.show()
        dialog.window?.apply {
            setBackgroundDrawable(ColorDrawable(android.graphics.Color.TRANSPARENT))
            setLayout((activity.resources.displayMetrics.widthPixels * 0.92f).toInt(), activity.dp(460))
        }
        loadDir(startDir)
    }
}
