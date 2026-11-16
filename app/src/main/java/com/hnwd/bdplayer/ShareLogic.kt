package com.hnwd.bdplayer

import java.util.Locale

/**
 * 分享入口的纯逻辑：可分享判定、MIME 归一化与提示文案。
 * 只读记录字段，Android 的 chooser / uri 授权接线在 [DownloadShare]。
 */
internal object ShareLogic {
    /** 与 [DownloadLogic] 的默认值一致：记录里没有可信类型时的兜底。 */
    const val DEFAULT_TYPE = "application/octet-stream"

    const val CHOOSER_TITLE = "分享文件"
    const val NO_TARGET_MESSAGE = "没有可以接收此文件的应用"

    /**
     * 只有下载成功、并且能定位到具体文件的记录才可分享：
     * 新记录有 MediaStore 输出 uri；历史遗留记录退回系统下载队列的 downloadId。
     * 失败记录、进行中的记录、刚清空过暂存的记录都不满足。
     */
    fun canShare(state: DownloadState, outputUri: String, downloadId: Long): Boolean =
        state == DownloadState.SUCCESS && (outputUri.isNotBlank() || downloadId >= 0)

    /**
     * 分享用的 MIME。记录里的类型在取址时按扩展名给定，可能只是通用兜底；
     * 带 `*` 的通配类型部分接收方会拒绝，所以先归一化，再按文件名扩展名兜底推断。
     */
    fun typeOf(mediaType: String, name: String, byExtension: (String) -> String?): String {
        val declared = mediaType.trim()
        if (declared.isNotEmpty() && '*' !in declared && declared != DEFAULT_TYPE) return declared
        val extension = name.substringAfterLast('.', "").lowercase(Locale.US)
        if (extension.isEmpty()) return DEFAULT_TYPE
        return byExtension(extension)?.takeIf { it.isNotBlank() && '*' !in it } ?: DEFAULT_TYPE
    }

    /** 长按后没弹出分享面板时的说明，按记录状态区分「还在进行」还是「文件已经没了」。 */
    fun unavailableMessage(state: DownloadState, publishing: Boolean): String = when {
        publishing -> "文件正在保存到下载目录，稍后再试"
        state == DownloadState.SUCCESS -> "找不到文件，可能已被清理，请重新下载"
        state == DownloadState.FAILED -> "下载失败的记录不能分享"
        else -> "下载完成后才能分享"
    }
}
