package com.hnwd.bdplayer

import android.app.Dialog
import android.view.View
import android.widget.EditText
import android.widget.TextView
import androidx.appcompat.app.AlertDialog

/**
 * 网盘页右下角 FAB 的「添加」面板。
 *
 * 提供上传文件、分享下载与新建文件夹。
 * 真实传输由前台服务接管。
 */
internal fun MainActivity.showAddSheet() {
    if (screen != Screen.DISK || !hasActiveSession()) return
    val quark = diskProvider == SourceProvider.QUARK
    val wide = resources.configuration.screenWidthDp >= 600
    val view = layoutInflater.inflate(R.layout.dialog_add_sheet, null, false)
    val dialog = Dialog(this).apply { requestWindowFeature(android.view.Window.FEATURE_NO_TITLE) }
    val dir = currentDiskDir()
    val target = if (dir.isBlank() || dir == "/" || dir == QuarkPersonalDrive.ROOT) "根目录" else dir
    view.findViewById<TextView>(R.id.addTarget).text =
        "将保存到 ${if (quark) "夸克网盘" else "百度网盘"} · $target"
    // 百度 / 夸克共用同一个多选入口，UploadCenter 按入队时快照的 provider 分派上传器。
    view.findViewById<View>(R.id.addUpload).setOnClickListener {
        dialog.dismiss()
        chooseUploadFiles()
    }
    view.findViewById<View>(R.id.addCollector).setOnClickListener {
        dialog.dismiss()
        showCollectorSheet()
    }
    view.findViewById<View>(R.id.addFolder).setOnClickListener {
        dialog.dismiss()
        if (quark) promptQuarkFolder() else diskWrites.promptNewFolder()
    }
    view.findViewById<View>(R.id.addCancel).setOnClickListener { dialog.dismiss() }
    view.findViewById<View>(R.id.addClose).setOnClickListener { dialog.dismiss() }
    showCollectorSheetDialog(dialog, view, wide, widthDp = 504)
}

/** 夸克网盘新建目录走 QuarkPersonalDrive.create()，不能走百度的 /api/create。 */
private fun MainActivity.promptQuarkFolder() {
    val input = EditText(this).apply {
        hint = "文件夹名称"
        isSingleLine = true
    }
    AlertDialog.Builder(this)
        .setTitle("新建文件夹")
        .setMessage("将创建到夸克网盘 ${currentDiskDir()}")
        .setView(padded(input))
        .setNegativeButton("取消", null)
        .setPositiveButton("创建") { _, _ ->
            val name = input.text.toString().trim()
            if (name.isEmpty()) {
                toast("请输入文件夹名称")
                return@setPositiveButton
            }
            val parent = folders.lastOrNull()?.fileId ?: QuarkPersonalDrive.ROOT
            status.text = "正在新建文件夹…"
            AppIo.execute {
                val result = runCatching {
                    val session = QuarkPersonalDrive.openSession() ?: throw QuarkLoginRequired()
                    session.create(parent, name)
                }
                runOnUiThread {
                    if (isFinishing || isDestroyed) return@runOnUiThread
                    result.onSuccess {
                        status.text = ""
                        toast("已创建文件夹")
                        refresh()
                    }.onFailure { error ->
                        status.text = ""
                        toast("创建失败：${error.message ?: "请重试"}")
                    }
                }
            }
        }.show()
}
