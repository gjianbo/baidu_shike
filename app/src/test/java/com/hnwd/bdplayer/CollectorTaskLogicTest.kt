package com.hnwd.bdplayer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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

    private fun roundTrip(record: CollectorTask) =
        CollectorTaskLogic.decode(CollectorTaskLogic.encode(listOf(record))).single()
}
