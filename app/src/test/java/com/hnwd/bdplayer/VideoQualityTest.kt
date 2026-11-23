package com.hnwd.bdplayer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VideoQualityTest {
    @Test
    fun `480p 只取 480 档`() {
        assertEquals(listOf(StreamType.H264_480), qualityCandidates(EntryOrigin.GROUP, VideoQuality.SD))
        assertEquals(listOf(StreamType.H264_480), qualityCandidates(EntryOrigin.DISK, VideoQuality.SD))
    }

    @Test
    fun `720p 先试 720 再降 480`() {
        assertEquals(
            listOf(StreamType.HEVC_720, StreamType.H264_480),
            qualityCandidates(EntryOrigin.GROUP, VideoQuality.HD),
        )
    }

    @Test
    fun `1080p 先试 1080 再降 480`() {
        assertEquals(
            listOf(StreamType.HEVC_1080, StreamType.H264_480),
            qualityCandidates(EntryOrigin.GROUP, VideoQuality.FHD),
        )
    }

    @Test
    fun `自动挡群组试三档网盘只试两档`() {
        assertEquals(
            listOf(StreamType.HEVC_1080, StreamType.HEVC_720, StreamType.H264_480),
            qualityCandidates(EntryOrigin.GROUP, VideoQuality.AUTO),
        )
        // 网盘侧 720 与 1080 是同一份转码产物，多要一档只是多一次请求。
        assertEquals(
            listOf(StreamType.HEVC_1080, StreamType.H264_480),
            qualityCandidates(EntryOrigin.DISK, VideoQuality.AUTO),
        )
    }

    @Test
    fun `每档候选链都以 480 兜底`() {
        EntryOrigin.entries.forEach { origin ->
            VideoQuality.entries.forEach { quality ->
                val candidates = qualityCandidates(origin, quality)
                assertTrue("$origin/$quality", candidates.isNotEmpty())
                assertEquals("$origin/$quality", StreamType.H264_480, candidates.last())
                assertEquals("$origin/$quality 有重复档位", candidates.size, candidates.distinct().size)
            }
        }
    }

    @Test
    fun `档位选择：31023 与 31304 判定为可换档`() {
        assertTrue(isUnsupportedTier(31023, """{"errno":31023}"""))
        assertTrue(isUnsupportedTier(31304, """{"error_code":31304}"""))
        assertTrue(isUnsupportedTier(0, """{"error_code":31023,"show_msg":"参数错误"}"""))
    }

    @Test
    fun `档位选择：状态错误不换档`() {
        // 133 广告门 / 31341 转码排队 / 31360 直链过期 / 31362 签名错误都不是档位问题。
        assertFalse(isUnsupportedTier(133, """{"errno":133,"adToken":"x"}"""))
        assertFalse(isUnsupportedTier(31341, """{"errno":31341}"""))
        assertFalse(isUnsupportedTier(31360, """{"error_code":31360}"""))
        assertFalse(isUnsupportedTier(31362, """{"error_code":31362}"""))
    }

    @Test
    fun `档位选择：非 JSON 与空响应体不换档`() {
        assertFalse(isUnsupportedTier(200, ""))
        assertFalse(isUnsupportedTier(200, "#EXTM3U\n#EXT-X-ENDLIST\n"))
        assertFalse(isUnsupportedTier(200, "<html>busy</html>"))
    }

    @Test
    fun `档位标签覆盖全部枚举且互不相同`() {
        val labels = VideoQuality.entries.map { it.label }
        assertEquals(labels.size, labels.distinct().size)
        VideoQuality.entries.forEach { assertTrue(it.label.isNotBlank()) }
        VideoQuality.entries.forEach { assertTrue(it.hint.isNotBlank()) }
    }
}
