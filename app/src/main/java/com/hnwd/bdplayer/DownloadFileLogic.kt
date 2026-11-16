package com.hnwd.bdplayer

import java.util.Locale

/** 长按已完成/失败记录弹出的菜单项。顺序即展示顺序。 */
internal enum class MoreAction { SHARE, RENAME, COPY_NAME, COPY_PATH, INFO, REVEAL, DELETE }

/**
 * 下载记录长按菜单的纯逻辑：菜单项、改名校验、删除/信息文案。
 * 真正动 MediaStore 的接线在 [DownloadFileOps]，分享在 [DownloadShare]。
 */
internal object DownloadFileLogic {

    /**
     * 菜单项。
     * - 成功：分享 / 重命名 / 复制文件名 / 复制路径 / 文件信息 / 打开所在文件夹 / 删除；
     * - 失败：只有「移除记录」（没有文件可删），重试与验证仍在行内按钮上；
     * - 进行中与排队中不挂长按（取消按钮已经足够）。
     *
     * 「移除记录」与「删除文件」合并成一个 [MoreAction.DELETE]：是否连文件一起删，
     * 由弹窗里的勾选项决定，不给用户两个长得几乎一样的入口。
     */
    fun actions(state: DownloadState): List<MoreAction> = when (state) {
        DownloadState.SUCCESS -> listOf(
            MoreAction.SHARE, MoreAction.RENAME, MoreAction.COPY_NAME, MoreAction.COPY_PATH,
            MoreAction.INFO, MoreAction.REVEAL, MoreAction.DELETE,
        )
        DownloadState.FAILED -> listOf(MoreAction.DELETE)
        DownloadState.RUNNING, DownloadState.QUEUED -> emptyList()
    }

    /** 同一个入口在成功记录上叫「删除」，在失败记录上叫「移除记录」。 */
    fun label(action: MoreAction, state: DownloadState): String = when (action) {
        MoreAction.SHARE -> "分享"
        MoreAction.RENAME -> "重命名"
        MoreAction.COPY_NAME -> "复制文件名"
        MoreAction.COPY_PATH -> "复制路径"
        MoreAction.INFO -> "文件信息"
        MoreAction.REVEAL -> "打开所在文件夹"
        MoreAction.DELETE -> if (state == DownloadState.FAILED) "移除记录" else "删除"
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

    /** 「复制路径」用的相对路径，与「文件信息」里显示的「位置」同一口径。 */
    fun locationOf(name: String): String = "Download/${DownloadLogic.FOLDER}/$name"

    fun copiedNameMessage(): String = "已复制文件名"

    fun copiedPathMessage(path: String): String = "已复制路径：$path"

    /** 「打开所在文件夹」没有可用文件管理器时的兜底：路径已经放进剪贴板。 */
    fun revealFallbackMessage(path: String): String = "没有支持定位的文件管理器，已复制路径：$path"

    /**
     * 删除弹窗文案。
     * - 成功记录：默认只移除记录，勾选后才删「下载 / 拾课」里的文件；
     * - 失败记录：没有文件，文案直接说清只动列表。
     */
    fun deleteTitle(state: DownloadState): String =
        if (state == DownloadState.FAILED) "移除记录" else "删除记录"

    fun deleteMessage(state: DownloadState, name: String): String =
        if (state == DownloadState.FAILED) {
            "这条失败记录会从列表移除，不涉及本地文件。"
        } else {
            "「$name」会从列表移除。记录移除后文件仍留在「下载 / ${DownloadLogic.FOLDER}」里，勾选下面的选项才会连文件一起删。"
        }

    /** 批量删除的标题与文案：条数写在标题里，避免用户误以为只删一条。 */
    fun batchDeleteTitle(count: Int): String = "删除 $count 项记录"

    fun batchDeleteMessage(count: Int): String =
        "选中的 $count 条记录会从列表移除。记录移除后文件仍留在「下载 / ${DownloadLogic.FOLDER}」里，勾选下面的选项才会连文件一起删。"

    /** 弹窗里的勾选项。默认不勾——公共下载目录里的文件不该被顺手删掉。 */
    const val DELETE_FILE_OPTION = "同时删除本地文件（删除后无法恢复）"

    /**
     * 单条删除的结果文案。
     * 失败记录本来就没有文件，不能说「文件仍在下载目录」；勾了删文件却定位不到 uri 时，
     * 也不能谎报「已删除文件」。
     */
    fun deleteResultMessage(state: DownloadState, withFile: Boolean, fileDeleted: Boolean): String = when {
        state == DownloadState.FAILED -> "已移除这条失败记录"
        withFile && fileDeleted -> "已删除文件和记录"
        withFile -> "文件已不在，记录已移除"
        else -> "已移除记录，文件仍在下载目录"
    }

    /** 批量删除的结果文案：删除失败与「文件本来就没了」要分开说，条数必须对得上。 */
    fun batchDeleteResultMessage(
        count: Int,
        withFile: Boolean,
        failed: Int,
        missingFiles: Int,
    ): String = when {
        failed > 0 -> "有 $failed 个文件删除失败，这些记录已保留"
        !withFile -> "已移除 $count 条记录，文件仍在下载目录"
        missingFiles > 0 -> "已删除 ${count - missingFiles} 个文件和记录，另有 $missingFiles 个文件已不在"
        else -> "已删除 $count 个文件和记录"
    }

    /** 批量分享的结果文案：被跳过的条数要让用户看见。 */
    fun multiShareMessage(shared: Int, skipped: Int): String =
        if (skipped > 0) "已分享 $shared 个文件，$skipped 个文件已不在" else "已分享 $shared 个文件"

    /** 批量操作栏：未选任何项时的提示。 */
    const val SELECTION_EMPTY_HINT = "点选要处理的下载记录"

    fun selectionHint(count: Int): String =
        if (count <= 0) SELECTION_EMPTY_HINT else "已选 $count 项"

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
