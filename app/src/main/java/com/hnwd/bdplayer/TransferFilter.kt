package com.hnwd.bdplayer

/** 传输页「历史」内的二级状态筛选；一级 Tab 仍是「进行中 / 历史」。 */
internal enum class HistoryFilter { ALL, SUCCESS, FAILED }

/**
 * 历史筛选的纯逻辑：下载与上传两类终态记录共用同一套判决与计数。
 *
 * 与 [DownloadLogic] / [UploadRecordLogic] 一样不碰 Android API，可在 JVM 单测里钉死。
 * 「已完成」只认 SUCCESS，FAILED 单独一档，方便一眼找出需要重下/重传的条目。
 */
internal object TransferFilter {
    data class Counts(val all: Int, val success: Int, val failed: Int)

    fun counts(
        downloadStates: List<DownloadState>, uploadStates: List<UploadRecordState>,
    ): Counts {
        val downloadSuccess = downloadStates.count { it == DownloadState.SUCCESS }
        val downloadFailed = downloadStates.count { it == DownloadState.FAILED }
        val uploadSuccess = uploadStates.count { it == UploadRecordState.SUCCESS }
        val uploadFailed = uploadStates.count { it == UploadRecordState.FAILED }
        return Counts(
            all = downloadStates.size + uploadStates.size,
            success = downloadSuccess + uploadSuccess,
            failed = downloadFailed + uploadFailed,
        )
    }

    fun keepDownload(state: DownloadState, filter: HistoryFilter): Boolean = when (filter) {
        HistoryFilter.ALL -> true
        HistoryFilter.SUCCESS -> state == DownloadState.SUCCESS
        HistoryFilter.FAILED -> state == DownloadState.FAILED
    }

    fun keepUpload(state: UploadRecordState, filter: HistoryFilter): Boolean = when (filter) {
        HistoryFilter.ALL -> true
        HistoryFilter.SUCCESS -> state == UploadRecordState.SUCCESS
        HistoryFilter.FAILED -> state == UploadRecordState.FAILED
    }

    /** chip 文案里的计数是「筛选前」的全量，切换 chip 不应让数字跟着缩水。 */
    fun label(filter: HistoryFilter, counts: Counts): String = when (filter) {
        HistoryFilter.ALL -> "全部 ${counts.all}"
        HistoryFilter.SUCCESS -> "已完成 ${counts.success}"
        HistoryFilter.FAILED -> "失败 ${counts.failed}"
    }

    fun emptyText(filter: HistoryFilter): String = when (filter) {
        HistoryFilter.ALL -> "还没有传输记录"
        HistoryFilter.SUCCESS -> "没有已完成的记录"
        HistoryFilter.FAILED -> "没有失败的记录"
    }
}
