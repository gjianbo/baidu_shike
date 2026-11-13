package com.hnwd.bdplayer

import android.view.View
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/**
 * App 内自更新的 UI 接线：启动时静默检查、设置页手动检查、更新弹窗与下载进度。
 *
 * 判定与 IO 分别在 UpdateLogic / UpdateManager，这里只做线程切换与界面状态机。
 * 下载完成的 APK 不会自动安装：必须由用户点「安装」，且没开「安装未知应用」权限时
 * 先引导去设置页，回来后弹窗仍在、再点一次即可。
 */
internal fun MainActivity.checkAppUpdate(force: Boolean = false) {
    if (isFinishing || isDestroyed) return
    UpdateManager.check(this, force) { result ->
        runOnUiThread {
            if (isFinishing || isDestroyed) return@runOnUiThread
            when (result) {
                is UpdateManager.CheckResult.Available -> {
                    val info = result.info
                    if (shouldNotify(info.version, UpdateManager.ignoredVersion(this@checkAppUpdate), force)) {
                        showUpdateDialog(info)
                    }
                }
                UpdateManager.CheckResult.UpToDate -> if (force) toast("已是最新版本")
                UpdateManager.CheckResult.Skipped -> Unit
                is UpdateManager.CheckResult.Failed -> if (force) toast("检查更新失败：${result.message}")
            }
        }
    }
}

private enum class UpdateState { IDLE, DOWNLOADING, READY, FAILED }

private fun MainActivity.showUpdateDialog(info: UpdateManager.ReleaseInfo) {
    val cancelled = AtomicBoolean(false)
    var state = UpdateState.IDLE
    var downloaded: File? = null

    val progress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
        max = 100
        isIndeterminate = true
        visibility = View.GONE
    }
    val status = TextView(this).apply {
        textSize = 11.5f
        setTextColor(color(R.color.ink3))
        visibility = View.GONE
    }
    val panel = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(20), 0, dp(20), 0)
        addView(
            progress,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ),
        )
        addView(
            status,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ),
        )
    }
    val notes = info.notes.ifBlank { "修复若干问题并优化体验。" }
    val dialog = AlertDialog.Builder(this)
        .setTitle("发现新版本 ${info.version}")
        .setMessage(notes)
        .setView(panel)
        .setNegativeButton("下次再说", null)
        .setNeutralButton("忽略此版本", null)
        .setPositiveButton("下载并安装", null)
        .create()
    dialog.setOnDismissListener { cancelled.set(true) }
    dialog.show()

    val positive = dialog.getButton(AlertDialog.BUTTON_POSITIVE)
    val neutral = dialog.getButton(AlertDialog.BUTTON_NEUTRAL)

    fun startDownload() {
        state = UpdateState.DOWNLOADING
        downloaded = null
        positive.isEnabled = false
        positive.text = "下载中…"
        neutral.isEnabled = false
        neutral.text = "忽略此版本"
        progress.visibility = View.VISIBLE
        progress.isIndeterminate = true
        progress.progress = 0
        status.visibility = View.VISIBLE
        status.text = "正在下载安装包…"
        UpdateManager.download(
            this@showUpdateDialog, info, cancelled::get,
            onProgress = { done, total ->
                runOnUiThread {
                    if (isFinishing || isDestroyed || cancelled.get()) return@runOnUiThread
                    val percent = downloadPercent(done, total)
                    if (percent < 0) {
                        progress.isIndeterminate = true
                        status.text = "已下载 ${formatDownloadSize(done)}"
                    } else {
                        progress.isIndeterminate = false
                        progress.progress = percent
                        status.text = "已下载 ${formatDownloadSize(done)} / ${formatDownloadSize(total)} · $percent%"
                    }
                }
            },
            onDone = { result ->
                runOnUiThread {
                    if (isFinishing || isDestroyed || cancelled.get()) return@runOnUiThread
                    positive.isEnabled = true
                    neutral.isEnabled = true
                    when (result) {
                        is UpdateManager.DownloadResult.Success -> {
                            downloaded = result.file
                            state = UpdateState.READY
                            progress.visibility = View.GONE
                            status.text = "下载完成，签名校验通过"
                            positive.text = "安装"
                        }
                        UpdateManager.DownloadResult.Cancelled -> Unit
                        is UpdateManager.DownloadResult.Failed -> {
                            state = UpdateState.FAILED
                            progress.visibility = View.GONE
                            status.text = result.message
                            positive.text = "重试"
                            neutral.text = "浏览器打开"
                        }
                    }
                }
            },
        )
    }

    positive.setOnClickListener {
        when (state) {
            UpdateState.IDLE, UpdateState.FAILED -> startDownload()
            UpdateState.READY -> {
                val file = downloaded ?: return@setOnClickListener
                when (val install = UpdateManager.install(this@showUpdateDialog, file)) {
                    UpdateManager.InstallResult.Launched -> dialog.dismiss()
                    UpdateManager.InstallResult.NeedPermission -> {
                        toast("请先允许「安装未知应用」，回来后继续点安装")
                        UpdateManager.openInstallPermissionSettings(this@showUpdateDialog)
                    }
                    is UpdateManager.InstallResult.Failed -> {
                        status.text = install.message
                        positive.text = "重试"
                        state = UpdateState.FAILED
                    }
                }
            }
            UpdateState.DOWNLOADING -> Unit
        }
    }

    neutral.setOnClickListener {
        if (state == UpdateState.FAILED) {
            UpdateManager.openReleasePage(this@showUpdateDialog, info.tag)
        } else {
            UpdateManager.ignoreVersion(this@showUpdateDialog, info.version)
            toast("已忽略 ${info.version}，之后的新版本仍会提示")
            dialog.dismiss()
        }
    }
}
