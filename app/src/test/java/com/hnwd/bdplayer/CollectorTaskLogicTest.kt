package com.hnwd.bdplayer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CollectorTaskLogicTest {
    private val task = CollectorTask(
        id = 1, name = "分享视频", url = "https://example.com/video",
        platform = "XIAOHONGSHU", provider = SourceProvider.BAIDU,
        accountId = "account", dir = "/", folderId = "", keepLocal = false, createdAt = 1,
    )

    @Test
    fun retryKeepsAutomaticNamingEvenAfterDownloadUpdatedDisplayName() {
        val saved = roundTrip(task.copy(name = "原视频标题.mp4", customName = ""))
        assertEquals("", saved.retryName)
        assertEquals("小红书", saved.platformLabel)
    }

    @Test
    fun retryKeepsDottedCustomName() {
        val saved = roundTrip(task.copy(name = "课程.v2.mp4", customName = "课程.v2"))
        assertEquals("课程.v2", saved.retryName)
    }

    @Test
    fun legacyRetryIgnoresPlaceholderAndOnlyStripsKnownExtensions() {
        assertNull(roundTrip(task).customName)
        assertEquals("", roundTrip(task).retryName)
        assertEquals("课程.v2", roundTrip(task.copy(name = "课程.v2")).retryName)
        assertEquals("课程.v2", roundTrip(task.copy(name = "课程.v2.MP4")).retryName)
    }

    @Test
    fun retryRestartsOriginalRecordAndClearsFailedAttemptState() {
        val failed = task.copy(
            state = CollectorTaskState.FAILED, percent = 80, message = "收集失败", finishedAt = 2,
        )
        val next = task.copy(createdAt = 3, updatedAt = 3)
        val restarted = requireNotNull(CollectorTaskLogic.prepareAttempt(next, failed, retry = true))
        assertEquals(task.copy(updatedAt = 3), restarted)
    }

    @Test
    fun staleRetryCannotReplaceRunningCompletedOrDeletedRecord() {
        assertNull(CollectorTaskLogic.prepareAttempt(task, task, retry = true))
        assertNull(CollectorTaskLogic.prepareAttempt(task, task.copy(state = CollectorTaskState.SUCCESS), retry = true))
        assertNull(CollectorTaskLogic.prepareAttempt(task, null, retry = true))
    }

    @Test
    fun mediaValidationAllowsSilentXiaohongshuVideoButRequiresAudioElsewhere() {
        val silentVideo = listOf("video/avc")
        val videoWithAudio = listOf("video/avc", "audio/mp4a-latm")
        assertTrue(ExtensionDownload.hasRequiredTracks(silentVideo, requireAudio = false))
        assertFalse(ExtensionDownload.hasRequiredTracks(silentVideo, requireAudio = true))
        assertTrue(ExtensionDownload.hasRequiredTracks(videoWithAudio, requireAudio = true))
        assertFalse(ExtensionDownload.hasRequiredTracks(listOf("audio/mp4a-latm"), requireAudio = false))
    }

    private fun roundTrip(record: CollectorTask) =
        CollectorTaskLogic.decode(CollectorTaskLogic.encode(listOf(record))).single()
}
