package com.hnwd.bdplayer

import android.app.Activity

internal interface DownloadHost {
    val hostActivity: Activity

    fun account(): String
    fun userAgent(): String
    fun sessionReady(provider: SourceProvider): Boolean
    fun isVisible(): Boolean
    fun isCurrent(): Boolean
    fun say(message: String)
    fun onAdded(message: String)
}

/** Entry actions enqueue file identities. Only an interactive captcha needs the Activity. */
internal class DownloadSection(
    private val host: DownloadHost,
    private val mediaLinks: MediaLinkSection,
) {
    fun start(entry: PanEntry) = start(listOf(entry))

    fun start(entries: List<PanEntry>) {
        val files = entries.filter(DownloadLogic::canDownload)
        if (files.isEmpty()) {
            host.say("请选择可下载的文件")
            return
        }
        val ready = files.filter { host.sessionReady(it.provider) }
        if (ready.isEmpty()) {
            host.say("请先登录${DownloadLogic.sourceLabel(DownloadLogic.routeOf(files.first()))}")
            return
        }
        val viewer = host.account()
        val userAgent = host.userAgent()
        val quarkAccount = if (ready.any { it.provider == SourceProvider.QUARK }) {
            runCatching { TransferSession.capture(SourceProvider.QUARK).quarkAccount() }.getOrDefault("")
        } else ""
        val allowed = ready.filter {
            if (it.provider == SourceProvider.QUARK) quarkAccount.isNotBlank() else viewer.isNotBlank()
        }
        if (allowed.isEmpty()) {
            host.say("无法确认网盘账号，请检查登录后重试")
            return
        }

        val now = System.currentTimeMillis()
        val queued = DownloadCenter.add(host.hostActivity, allowed.map { entry ->
            DownloadTask(
                id = DownloadCenter.newId(),
                name = entry.name,
                sizeBytes = entry.size.toLongOrNull() ?: 0,
                provider = entry.provider,
                sourceLabel = DownloadLogic.sourceLabel(DownloadLogic.routeOf(entry)),
                createdAt = now,
                userAgent = userAgent,
                mediaType = DownloadLogic.mimeType(entry.name),
                entry = entry.copy(previewUrl = ""),
                viewer = if (entry.provider == SourceProvider.BAIDU) viewer else quarkAccount,
            )
        })
        if (!queued) {
            host.say("无法启动下载队列，请稍后重试")
            return
        }
        val message = if (allowed.size == 1 && entries.size == 1) {
            "已加入下载队列：${allowed.single().name}"
        } else {
            val skipped = entries.size - allowed.size
            "已加入下载队列（${allowed.size} 个文件）" +
                if (skipped > 0) "，跳过 $skipped 项" else ""
        }
        host.onAdded(message)
    }

    fun verify(id: Long) {
        val task = DownloadCenter.tasks(host.hostActivity).firstOrNull {
            it.id == id && it.state == DownloadState.FAILED && it.needsVerification
        } ?: return
        if (task.provider != SourceProvider.BAIDU || !DownloadLogic.hasAccountBinding(task)) return
        val session = runCatching { TransferSession.capture(task.provider) }.getOrElse {
            host.say(it.message ?: "请先登录百度网盘")
            return
        }
        verify(task, session, "")
    }

    private fun verify(task: DownloadTask, session: TransferSession, verification: String) {
        val activity = host.hostActivity
        val app = activity.applicationContext
        AppIo.execute {
            val link = runCatching { DownloadLinkResolver.resolve(app, task, session, verification) }
            val error = link.exceptionOrNull()
            val captcha = (error as? DownloadLinkResolver.VerificationRequired)?.let {
                runCatching { DownloadLinkResolver.captcha(task, session, it) }.getOrNull()
            }
            activity.runOnUiThread {
                if (!host.isCurrent() || !host.isVisible()) return@runOnUiThread
                val resolved = link.getOrNull()
                when {
                    resolved != null -> {
                        if (DownloadCenter.submitVerified(activity, task.id, resolved)) {
                            host.onAdded("已重新加入下载队列：${task.name}")
                        } else host.say("下载任务已变更或队列已满，请稍后重试")
                    }
                    captcha != null -> mediaLinks.showCaptcha(
                        RequestLane.DOWNLOAD, captcha, captchaPrompt(verification.isNotEmpty()),
                        host::isCurrent, host::say, cookie = session.cookie,
                    ) { answer ->
                        verify(task, session, "&" + PanApi.mediaCaptchaForm(captcha, answer))
                    }
                    else -> host.say(when (error) {
                        is TransferSession.Failure, is DownloadLinkResolver.Failure -> error.message.orEmpty()
                        else -> "未取得下载地址，请重新验证"
                    })
                }
            }
        }
    }
}
