package com.hnwd.bdplayer

import android.app.Activity
import android.app.Dialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.ListView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.core.graphics.drawable.toDrawable
import org.json.JSONObject

/**
 * 夸克网盘的批量删除 / 移动 / 分享。
 *
 * 与 [DiskMutationSection] 平行但完全独立：夸克是 fid + pdir_fid 模型、纯 Cookie 鉴权，
 * 写操作是独立端点且同步返回 data.finish，没有 bdstoken、path -> fs_id 核对与百度安全验证。
 * 所以只复用 [WriteGuards] 的互斥语义、[DiskMutationHost] 的最小宿主能力，以及百度侧
 * 已经抽好的纯逻辑（删除标签 / 目录阻断 / 目录选择器按钮态 / 分享结果文案），不复用它的请求通道。
 *
 * 两条不变式（与百度侧一致）：
 * 1. 同一时刻只允许一笔写操作；迟到回调必须带回 token 才能释放锁（[WriteGuards]）。
 * 2. 全程绑定 [DiskMutationHost.probeId]，账号切换 / Activity 销毁后不把结果写回旧 UI。
 */
internal class QuarkDiskWrites(private val host: DiskMutationHost) {
    private val ui: MainActivity = host.hostActivity as MainActivity
    private val activity: Activity get() = host.hostActivity
    private val guards = WriteGuards()

    /** 账号失效时清掉写锁，否则后续写操作会被「已有操作在处理」永久挡住。 */
    fun resetGuards() = guards.reset()

    // ===================== 删除 =====================

    fun confirmDelete(entries: List<PanEntry>) {
        val fids = quarkMutationIds(entries)
        if (fids.isEmpty()) {
            host.say("请先选择条目")
            return
        }
        val session = host.probeId
        AlertDialog.Builder(activity)
            .setTitle("删除")
            .setMessage(quarkDeleteConfirmMessage(deleteLabel(entries)))
            .setNegativeButton("取消", null)
            .setPositiveButton("删除") { _, _ ->
                if (session != host.probeId) {
                    host.say("网盘会话已变更，请重试")
                    return@setPositiveButton
                }
                submit("删除", { drive -> drive.deleteFiles(fids); null }) {
                    if (host.selecting() && host.onDiskScreen()) host.leaveSelection()
                    afterMutation(quarkDeleteSuccess(fids.size))
                }
            }.show()
    }

    // ===================== 移动 =====================

    fun promptMove(entries: List<PanEntry>) {
        val fids = quarkMutationIds(entries)
        if (fids.isEmpty()) {
            host.say("请先选择条目")
            return
        }
        showFolderPicker("移动 ${fids.size} 项到", quarkMoveBlockedPaths(entries)) { targetFid, targetName ->
            submit("移动", { drive -> drive.moveFiles(fids, targetFid); null }) {
                if (host.selecting() && host.onDiskScreen()) host.leaveSelection()
                afterMutation(quarkMoveSuccess(fids.size, targetName))
            }
        }
    }

    // ===================== 分享 =====================

    fun promptShare(entries: List<PanEntry>) {
        val fids = quarkMutationIds(entries)
        if (fids.isEmpty()) {
            host.say("请先选择条目")
            return
        }
        val session = host.probeId
        host.pickFromSheet(
            title = "创建分享链接",
            subtitle = if (entries.size == 1) entries[0].name else "${entries.size} 项",
            choices = quarkSharePeriodChoices(),
            onSelect = { choice ->
                if (session != host.probeId) return@pickFromSheet
                val expiredType = choice.tag as Int
                val title = quarkShareTitle(entries)
                submit("创建分享", { drive -> drive.createShare(fids, title, expiredType) }) { json ->
                    val info = parseQuarkShare(json ?: JSONObject(), "")
                    if (info == null) host.say("分享已创建，但未取到链接")
                    else showShareResult(info, "")
                }
            },
        )
    }

    private fun showShareResult(info: QuarkShareInfo, fallbackPwd: String) {
        val pwd = sharePwd(info.pwd, fallbackPwd)
        AlertDialog.Builder(activity)
            .setTitle("分享已创建")
            .setMessage(shareResultText(info.link, pwd))
            .setNegativeButton("关闭", null)
            .setPositiveButton("复制链接") { _, _ ->
                val cm = activity.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                cm.setPrimaryClip(ClipData.newPlainText("分享链接", shareClipText(info.link, pwd)))
                host.say("已复制到剪贴板")
            }.show()
    }

    // ===================== 请求通道 =====================

    /**
     * 提交一笔夸克写操作。work 运行在 [AppIo] 后台线程，onSuccess 回到主线程。
     * share 会把原始响应透传出来；删除 / 移动返回 null（成功与否只看是否抛异常）。
     */
    private fun submit(
        op: String,
        work: (QuarkPersonalDrive) -> JSONObject?,
        onSuccess: (JSONObject?) -> Unit,
    ) {
        val lock = guards.beginWrite() ?: run {
            host.say("已有网盘操作正在处理，请稍候")
            return
        }
        val session = host.probeId
        host.showStatus("正在${op}…")
        AppIo.execute {
            val result = runCatching {
                val drive = QuarkPersonalDrive.openSession() ?: throw QuarkLoginRequired()
                work(drive)
            }
            activity.runOnUiThread {
                // 旧回调（锁已被 reset / 换了持有者）不能碰当前锁，更不能清掉后来者的状态。
                if (!guards.endWrite(lock)) return@runOnUiThread
                // 状态栏无条件收尾：会话已变更时也要把「正在…」清掉，但不能再回写旧 UI。
                host.showStatus("")
                if (session != host.probeId || host.activityDestroyed()) return@runOnUiThread
                result.onSuccess(onSuccess).onFailure { error ->
                    host.say(quarkWriteErrorMessage(error))
                }
            }
        }
    }

    private fun afterMutation(message: String) {
        host.invalidateDiskCaches()
        if (host.onDiskScreen()) {
            host.resetCourseList()
            host.reloadPage()
        }
        host.say(message)
    }

    // ===================== 目录选择器（按 fid 遍历） =====================

    private data class PickerFolder(val fid: String, val name: String, val path: String)

    /**
     * 夸克没有「路径树」概念，目录用 pdir_fid 下钻；但列表条目仍带层级 path
     * （[quarkEntry] 用 PanApi.diskPath 拼出），所以「自身/子目录不可选」仍复用
     * [isBlockedFolder]，与百度口径一致。
     */
    private fun showFolderPicker(
        title: String,
        blocked: Set<String>,
        onPick: (String, String) -> Unit,
    ) {
        val drive = QuarkPersonalDrive.openSession()
        if (drive == null) {
            host.say("请登录夸克网盘")
            return
        }
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
            text = host.diskDir()
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
        root.addView(list, android.widget.LinearLayout.LayoutParams(-1, 0, 1f))
        root.addView(more)
        root.addView(up)
        root.addView(pick)
        dialog.setContentView(root)

        val startFid = ui.currentDiskFolderId()
        val startName = ui.folders.lastOrNull()?.name ?: "根目录"
        val startPath = host.diskDir()
        var current = PickerFolder(startFid, startName, startPath)
        val trail = mutableListOf<PickerFolder>()
        var page = 0
        var hasMore = false
        var loading = false
        val session = host.probeId
        val dirs = linkedMapOf<String, PickerFolder>()

        fun isBlocked(folder: PickerFolder) = isBlockedFolder(folder.path, blocked)

        fun updateButtons() {
            val state = folderPickerUi(loading, if (trail.isEmpty()) "/" else current.path, page, hasMore)
            up.isEnabled = state.upEnabled
            pick.isEnabled = state.pickEnabled
            more.visibility = if (state.moreVisible) View.VISIBLE else View.GONE
            more.text = state.moreText
            more.isEnabled = state.moreEnabled
            list.isEnabled = state.listEnabled
        }

        fun loadDir(folder: PickerFolder, nextPage: Int = 1) {
            if (loading || session != host.probeId || !dialog.isShowing) return
            if (nextPage == 1) {
                current = folder
                page = 0
                hasMore = false
                dirs.clear()
                list.adapter = null
                pathLabel.text = folder.path
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
            AppIo.execute {
                val result = runCatching { drive.execute(drive.list(folder.fid, nextPage, 100)) }
                activity.runOnUiThread {
                    if (session != host.probeId || host.activityDestroyed()) {
                        // 会话变了就别把弹窗留在「读取中」的禁用态，直接关掉。
                        loading = false
                        dialog.dismiss()
                        return@runOnUiThread
                    }
                    if (!dialog.isShowing) {
                        loading = false
                        return@runOnUiThread
                    }
                    result.onFailure { failed() }.onSuccess { resultPage ->
                        resultPage.items.filter { it.directory }
                            .map { PickerFolder(it.id, it.name, PanApi.diskPath(folder.path, it.name)) }
                            .filterNot(::isBlocked)
                            .forEach { dirs[it.fid] = it }
                        page = nextPage
                        hasMore = resultPage.hasMore
                        loading = false
                        val items = dirs.values.toList()
                        val firstVisible = list.firstVisiblePosition
                        list.adapter = folderAdapter(items.map { it.name }, hasMore)
                        if (nextPage > 1) list.setSelection(firstVisible)
                        list.setOnItemClickListener { _, _, position, _ ->
                            items.getOrNull(position)?.let { picked ->
                                trail.add(current)
                                loadDir(picked)
                            }
                        }
                        updateButtons()
                    }
                }
            }
        }

        up.setOnClickListener {
            trail.removeLastOrNull()?.let { previous -> loadDir(previous) }
        }
        more.setOnClickListener { loadDir(current, page + 1) }
        pick.setOnClickListener {
            if (session != host.probeId) {
                dialog.dismiss()
                host.say("网盘会话已变更，请重试")
                return@setOnClickListener
            }
            if (isBlocked(current)) {
                host.say("不能移动到自身或其子目录")
                return@setOnClickListener
            }
            dialog.dismiss()
            onPick(current.fid, current.name)
        }
        dialog.show()
        dialog.window?.apply {
            setBackgroundDrawable(Color.TRANSPARENT.toDrawable())
            setLayout((activity.resources.displayMetrics.widthPixels * 0.92f).toInt(), activity.dp(460))
        }
        loadDir(current)
    }

    private fun folderAdapter(names: List<String>, hasMore: Boolean): ArrayAdapter<String> =
        object : ArrayAdapter<String>(activity, android.R.layout.simple_list_item_1, folderPickerNames(names, hasMore)) {
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
}
