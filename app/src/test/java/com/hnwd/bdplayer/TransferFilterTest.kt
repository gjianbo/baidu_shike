package com.hnwd.bdplayer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TransferFilterTest {

    @Test
    fun counts_mergesDownloadAndUploadTerminalStates() {
        val downloads = listOf(
            DownloadState.SUCCESS, DownloadState.FAILED, DownloadState.SUCCESS,
        )
        val uploads = listOf(
            UploadRecordState.SUCCESS, UploadRecordState.FAILED, UploadRecordState.FAILED,
        )
        val counts = TransferFilter.counts(downloads, uploads)
        assertEquals(6, counts.all)
        assertEquals(3, counts.success)
        assertEquals(3, counts.failed)
        assertEquals(TransferFilter.Counts(0, 0, 0), TransferFilter.counts(emptyList(), emptyList()))
    }

    @Test
    fun keep_downloadOnlyKeepsRequestedTerminalState() {
        assertTrue(TransferFilter.keepDownload(DownloadState.SUCCESS, HistoryFilter.ALL))
        assertTrue(TransferFilter.keepDownload(DownloadState.FAILED, HistoryFilter.ALL))
        assertTrue(TransferFilter.keepDownload(DownloadState.SUCCESS, HistoryFilter.SUCCESS))
        assertFalse(TransferFilter.keepDownload(DownloadState.FAILED, HistoryFilter.SUCCESS))
        assertTrue(TransferFilter.keepDownload(DownloadState.FAILED, HistoryFilter.FAILED))
        assertFalse(TransferFilter.keepDownload(DownloadState.SUCCESS, HistoryFilter.FAILED))
        // 进行中/排队的记录根本不该出现在历史列表里，但即便误入也不能被筛选命中。
        assertTrue(TransferFilter.keepDownload(DownloadState.RUNNING, HistoryFilter.ALL))
        assertFalse(TransferFilter.keepDownload(DownloadState.RUNNING, HistoryFilter.SUCCESS))
    }

    @Test
    fun keep_uploadOnlyKeepsRequestedTerminalState() {
        assertTrue(TransferFilter.keepUpload(UploadRecordState.SUCCESS, HistoryFilter.SUCCESS))
        assertFalse(TransferFilter.keepUpload(UploadRecordState.FAILED, HistoryFilter.SUCCESS))
        assertTrue(TransferFilter.keepUpload(UploadRecordState.FAILED, HistoryFilter.FAILED))
        assertFalse(TransferFilter.keepUpload(UploadRecordState.SUCCESS, HistoryFilter.FAILED))
        assertFalse(TransferFilter.keepUpload(UploadRecordState.QUEUED, HistoryFilter.SUCCESS))
        assertFalse(TransferFilter.keepUpload(UploadRecordState.RUNNING, HistoryFilter.FAILED))
    }

    @Test
    fun labelsAndEmptyText_matchSelectedFilter() {
        val counts = TransferFilter.Counts(5, 4, 1)
        assertEquals("全部 5", TransferFilter.label(HistoryFilter.ALL, counts))
        assertEquals("已完成 4", TransferFilter.label(HistoryFilter.SUCCESS, counts))
        assertEquals("失败 1", TransferFilter.label(HistoryFilter.FAILED, counts))
        assertEquals("还没有传输记录", TransferFilter.emptyText(HistoryFilter.ALL))
        assertEquals("没有已完成的记录", TransferFilter.emptyText(HistoryFilter.SUCCESS))
        assertEquals("没有失败的记录", TransferFilter.emptyText(HistoryFilter.FAILED))
    }
}
