package com.hnwd.bdplayer

import java.util.Locale

/** 长按已完成/失败记录弹出的菜单项。顺序即展示顺序。 */
internal enum class MoreAction { SHARE, RENAME, INFO, REMOVE_RECORD, DELETE_FILE }

/**
 * 下载记录长按菜单的纯逻辑：菜单项、改名校验、文件信息文案。
 * 真正动 MediaStore 的接线在 [DownloadFileOps]。
 */
internal object DownloadFileLogic {

    /**
     * 菜单项。
     * - 成功：分享 / 重命名 / 文件信息 / 移除记录 / 删除文件；
     * - 失败：只有「移除记录」，重试与验证仍在行内按钮上；
     * - 进行中与排队中不挂长按（取消按钮已经足够）。
     */
    fun actions(state: DownloadState): List<MoreAction> = when (state) {
        DownloadState.SUCCESS -> listOf(
            MoreAction.SHARE, MoreAction.RENAME, MoreAction.INFO,
            MoreAction.REMOVE_RECORD, MoreAction.DELETE_FILE,
        )
        DownloadState.FAILED -> listOf(MoreAction.REMOVE_RECORD)
        DownloadState.RUNNING, DownloadState.QUEUED -> emptyList()
    }

    fun label(action: MoreAction): String = when (action) {
        MoreAction.SHARE -> "分享"
        MoreAction.RENAME -> "重命名"
        MoreAction.INFO -> "文件信息"
        MoreAction.REMOVE_RECORD -> "移除记录"
        MoreAction.DELETE_FILE -> "删除文件"
    }

    fun extensionOf(name: String): String = name.substringAfterLast('.', "").lowercase(Locale.US)

    /**
     * 改名只带扩展名的校验：接收方（尤其微信、系统打开方式）靠扩展名判断类型，
     * 而 MediaStore 的 DISPLAY_NAME 改了不会自动改 MIME_TYPE，所以扩展名必须保持。
     */
    fun nameError(name: String, original: String): String? {
        val trimmed = name.trim()
        return when {
            trimmed.isEmpty() -> "请输入文件名"
            trimmed == "." || trimmed == ".." -> "文件名不能是 . 或 .."
            trimmed.any { it in "/\\:*?\"<>|" || it.isISOControl() } -> "文件名包含不支持的字符"
            trimmed.length > 120 -> "文件名太长"
            extensionOf(original).isNotEmpty() && extensionOf(trimmed) != extensionOf(original) ->
                "请保留扩展名 .${extensionOf(original)}"
            else -> null
        }
    }

    /** 移除记录只影响列表，不动文件——文案要说清楚，否则用户会以为文件也删了。 */
    const val REMOVE_RECORD_CONFIRM = "只从列表移除这条记录，已下载的文件会保留在「下载 / 拾课」里。"

    fun deleteFileConfirm(name: String): String =
        "会删除「下载 / 拾课」里的文件并移除这条记录，删除后无法恢复。\n\n$name"

    /** 文件信息面板：名称 / 大小 / 位置 / 完成时间 / 来源 / 类型，一行一项。 */
    fun infoText(
        name: String,
        size: String,
        location: String,
        finished: String,
        source: String,
        mime: String,
    ): String = listOf(
        "名称：$name",
        "大小：$size",
        "位置：$location",
        "完成：$finished",
        "来源：$source",
        "类型：$mime",
    ).joinToString("\n")
}
