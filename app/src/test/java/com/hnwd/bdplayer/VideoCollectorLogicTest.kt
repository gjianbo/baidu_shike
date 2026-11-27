package com.hnwd.bdplayer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VideoCollectorLogicTest {

    @Test
    fun parse_recognizesBilibiliShareTextWithBv() {
        val parsed = VideoCollectorLogic.parse("【bilibili】我在B站看视频：https://www.bilibili.com/video/BV1xx411c7mD/?spm_id_from=333 复制打开")
        assertNotNull(parsed)
        assertEquals(VideoCollectorLogic.Platform.BILIBILI, parsed!!.platform)
        assertEquals("BV1xx411c7mD", parsed.id)
        assertEquals("https://www.bilibili.com/video/BV1xx411c7mD/", parsed.canonicalUrl)
        assertEquals("B站-BV1xx411c7mD", parsed.suggestedName)
    }

    @Test
    fun parse_preservesBilibiliPageWithoutTrackingParameters() {
        val parsed = VideoCollectorLogic.parse("https://www.bilibili.com/video/BV1ms411Q7vw/?spm_id_from=1&p=4")
        assertEquals("https://www.bilibili.com/video/BV1ms411Q7vw/?p=4", parsed?.canonicalUrl)
        assertEquals(parsed, VideoCollectorLogic.parse(parsed!!.canonicalUrl))
    }

    @Test
    fun parse_recognizesAvAndShortLink() {
        val av = VideoCollectorLogic.parse("http://m.bilibili.com/video/av12345678?from=share")
        assertEquals("av12345678", av?.id)
        assertEquals("https://www.bilibili.com/video/av12345678/", av?.canonicalUrl)

        val short = VideoCollectorLogic.parse("https://b23.tv/BV1GJ411x7h7?share_source=copy_web")
        assertEquals("BV1GJ411x7h7", short?.id)

        val opaque = VideoCollectorLogic.parse("【B站】我在看 https://b23.tv/aB3xYz7，打开看看")
        assertEquals(VideoCollectorLogic.Platform.BILIBILI, opaque?.platform)
        assertEquals("https://b23.tv/aB3xYz7", opaque?.canonicalUrl)
        assertEquals(opaque, VideoCollectorLogic.parse(opaque!!.canonicalUrl))
    }

    @Test
    fun parse_matchesRealHostsAndTakesFirstSupportedLink() {
        assertNull(VideoCollectorLogic.parse("https://fakebilibili.com/video/BV1xx411c7mD"))
        assertNull(VideoCollectorLogic.parse("https://notx.com/name/status/123456789"))
        assertNull(VideoCollectorLogic.parse("https://b23.tv.example.org/abc123"))
        assertNull(VideoCollectorLogic.parse("https://www.bilibili.com/foo?next=bilibili.com/video/BV1xx411c7mD"))
        val parsed = VideoCollectorLogic.parse("链接：https://example.org/page；视频：https://x.com/name/status/123456789/video/1！再看 https://v.douyin.com/abc123/")
        assertEquals("https://x.com/i/status/123456789", parsed?.canonicalUrl)
    }

    @Test
    fun parse_recognizesXStatusFromShareText() {
        val parsed = VideoCollectorLogic.parse("看看这个 https://x.com/user/status/1791234567890123456?s=20 精彩")
        assertNotNull(parsed)
        assertEquals(VideoCollectorLogic.Platform.X, parsed!!.platform)
        assertEquals("1791234567890123456", parsed.id)
        assertEquals("https://x.com/i/status/1791234567890123456", parsed.canonicalUrl)
    }

    @Test
    fun parse_recognizesMobileTwitterLink() {
        val parsed = VideoCollectorLogic.parse("https://mobile.twitter.com/foo/status/1234567890123456789")
        assertEquals(VideoCollectorLogic.Platform.X, parsed?.platform)
        assertEquals("1234567890123456789", parsed?.id)
    }

    @Test
    fun parse_recognizesDouyinLongAndShortLink() {
        val long = VideoCollectorLogic.parse("https://www.douyin.com/video/7123456789012345678?modal_id=1")
        assertEquals(VideoCollectorLogic.Platform.DOUYIN, long?.platform)
        assertEquals("7123456789012345678", long?.id)

        val short = VideoCollectorLogic.parse("8.86 复制打开抖音，看看 https://v.douyin.com/iRxyAbCd/ 的视频")
        assertEquals(VideoCollectorLogic.Platform.DOUYIN, short?.platform)
        assertEquals("iRxyAbCd", short?.id)
        assertTrue(short!!.canonicalUrl.contains("v.douyin.com"))
    }

    @Test
    fun parse_resolvesDouyinJingxuanModalToVideo() {
        val parsed = VideoCollectorLogic.parse("看看 https://www.douyin.com/jingxuan?from=share&modal_id=7702043484349320482")
        assertEquals(VideoCollectorLogic.Platform.DOUYIN, parsed?.platform)
        assertEquals("7702043484349320482", parsed?.id)
        assertEquals("https://www.douyin.com/video/7702043484349320482", parsed?.canonicalUrl)
        assertNull(VideoCollectorLogic.parse("https://www.douyin.com.evil.example/jingxuan?modal_id=7702043484349320482"))
        assertNull(VideoCollectorLogic.parse("https://www.douyin.com/jingxuan?modal_id=7702043484349320482x"))
        assertNull(VideoCollectorLogic.parse("https://www.douyin.com/jingxuan?modal_id=7702043484349320482&modal_id=7702043484349320483"))
    }

    @Test
    fun parse_recognizesXiaohongshuShortAndNoteLinks() {
        val short = VideoCollectorLogic.parse("米克和月半比帅 https://xhslink.cn/o/3dk4TLf0GUb 保留这段，去【小红书】瞧瞧笔记~")
        assertEquals(VideoCollectorLogic.Platform.XIAOHONGSHU, short?.platform)
        assertEquals("3dk4TLf0GUb", short?.id)
        assertEquals("https://xhslink.cn/o/3dk4TLf0GUb", short?.canonicalUrl)
        assertTrue(short!!.suggestedName.startsWith("小红书-"))

        val note = VideoCollectorLogic.parse("https://www.xiaohongshu.com/explore/6b02eee2000000002a03be8e?xsec_token=ABC123")
        assertEquals(VideoCollectorLogic.Platform.XIAOHONGSHU, note?.platform)
        assertEquals("6b02eee2000000002a03be8e", note?.id)
        assertTrue(note!!.canonicalUrl.contains("xsec_token=ABC123"))
        assertNull(VideoCollectorLogic.parse("https://xhslink.cn.example.org/o/abc123"))
    }

    @Test
    fun parse_recognizesYouTubeLinksAndRemovesPlaylistParameters() {
        val expected = "https://www.youtube.com/watch?v=jNQXAC9IVRw"
        val links = listOf(
            "https://youtu.be/jNQXAC9IVRw?si=tracking",
            "https://m.youtube.com/shorts/jNQXAC9IVRw",
            "https://www.youtube.com/watch?v=jNQXAC9IVRw&list=PLexample",
            "https://www.youtube-nocookie.com/embed/jNQXAC9IVRw",
            "https://www.youtube.com/live/jNQXAC9IVRw",
        )
        links.forEach { url ->
            val parsed = VideoCollectorLogic.parse("分享 $url")
            assertEquals(VideoCollectorLogic.Platform.YOUTUBE, parsed?.platform)
            assertEquals(expected, parsed?.canonicalUrl)
        }
        assertNull(VideoCollectorLogic.parse("https://www.youtube.com/playlist?list=PLexample"))
        assertNull(VideoCollectorLogic.parse("https://www.youtube.com/watch?v=jNQXAC9IVRw&v=ABCDEFGHIJK"))
        assertNull(VideoCollectorLogic.parse("https://youtu.be.example.org/jNQXAC9IVRw"))
    }

    @Test
    fun parse_keepsSignedXiaohongshuQueryWhenNormalizingMobileNote() {
        val note = VideoCollectorLogic.parse(
            "http://m.xiaohongshu.com/discovery/item/6B02EEE2000000002A03BE8E?xsec_token=abc%3D&xsec_source=app_share",
        )
        assertEquals("https://www.xiaohongshu.com/discovery/item/6b02eee2000000002a03be8e?xsec_token=abc%3D&xsec_source=app_share",
            note?.canonicalUrl)
    }

    @Test
    fun parse_preservesLongSignedNoteAndRejectsTruncatedCandidates() {
        val note = "https://www.xiaohongshu.com/explore/6b02eee2000000002a03be8e"
        val url = "$note?padding=${"a".repeat(3_000)}&xsec_token=signed%3D"
        assertEquals(url, VideoCollectorLogic.parse("发布分享 $url 【小红书】")?.canonicalUrl)
        assertNull(VideoCollectorLogic.parse("$note?xsec_token=${"a".repeat(17_000)}"))
        assertNull(VideoCollectorLogic.parse(" ".repeat(65_000) + url))
    }

    @Test
    fun parse_rejectsUnrelatedOrEmptyText() {
        assertNull(VideoCollectorLogic.parse(""))
        assertNull(VideoCollectorLogic.parse("这是一段普通文字，没有链接"))
        assertNull(VideoCollectorLogic.parse("https://example.com/video/BV1xx411c7mD"))
        assertNull(VideoCollectorLogic.parse("https://x.com/user/media/12345"))
        assertNull(VideoCollectorLogic.parse("https://www.douyin.com/user/self"))
    }

    @Test
    fun collectorSubDirValid_acceptsEmptyAndSingleName() {
        assertTrue(VideoCollectorLogic.collectorSubDirValid(""))
        assertTrue(VideoCollectorLogic.collectorSubDirValid("教育"))
        assertTrue(VideoCollectorLogic.collectorSubDirValid("哲学-随笔"))
    }

    @Test
    fun collectorSubDirValid_rejectsEscapeAndIllegalChars() {
        assertFalse(VideoCollectorLogic.collectorSubDirValid("/"))
        assertFalse(VideoCollectorLogic.collectorSubDirValid("教育/哲学"))
        assertFalse(VideoCollectorLogic.collectorSubDirValid(".."))
        assertFalse(VideoCollectorLogic.collectorSubDirValid("a/../b"))
        assertFalse(VideoCollectorLogic.collectorSubDirValid("a:b"))
        assertFalse(VideoCollectorLogic.collectorSubDirValid("a?b"))
    }

    @Test
    fun collectorBaiduDir_joinsUnderRootAndFallsBackOnIllegal() {
        assertEquals("/我的收集/教育", VideoCollectorLogic.collectorBaiduDir("/我的收集", "教育"))
        assertEquals("/教育", VideoCollectorLogic.collectorBaiduDir("/", "教育"))
        assertEquals("/我的收集", VideoCollectorLogic.collectorBaiduDir("/我的收集/", ""))
        assertEquals("/我的收集", VideoCollectorLogic.collectorBaiduDir("/我的收集", "../越界"))
        assertEquals("/", VideoCollectorLogic.collectorBaiduDir("", "/"))
    }

    @Test
    fun collectorRelativeDir_preservesNestedSelectionAndRejectsOtherBranches() {
        assertEquals("教育/哲学", VideoCollectorLogic.collectorRelativeDir("/收集/", "/收集/教育/哲学"))
        assertEquals("/收集/教育/哲学", VideoCollectorLogic.collectorBaiduDir("/收集", "教育/哲学"))
        assertEquals("", VideoCollectorLogic.collectorRelativeDir("/收集", "/收集/"))
        assertEquals("课程/教育", VideoCollectorLogic.collectorRelativeDir("/", "/课程/教育"))
        assertNull(VideoCollectorLogic.collectorRelativeDir("/收集", "/收集备份/教育"))
        assertNull(VideoCollectorLogic.collectorRelativeDir("/收集", "/收集/../教育"))
        assertNull(VideoCollectorLogic.collectorRelativeDir("/收集", "/收集/教育//哲学"))
    }

    @Test
    fun collectorDownloadedName_preservesTitleAndFallsBackToShareId() {
        val url = "https://www.bilibili.com/video/BV1xx411c7mD/"
        assertEquals("X-课程 2.0", VideoCollectorLogic.collectorDownloadedName(null, "B站-X-课程 2.0.mp4", "BILIBILI", url))
        assertEquals("B站-BV1xx411c7mD", VideoCollectorLogic.collectorDownloadedName(null, "B站-.mp4", "BILIBILI", url))
        assertEquals("B站-BV1xx411c7mD", VideoCollectorLogic.collectorDownloadedName(null, "B站-NA.mp4", "BILIBILI", url))
        assertEquals("我的视频", VideoCollectorLogic.collectorDownloadedName("我的视频.mp4", "B站-.mp4", "BILIBILI", url))
        assertEquals("分享视频", VideoCollectorLogic.collectorDownloadedName(null, "视频-.mp4", "UNKNOWN", ""))
    }

    @Test
    fun collectorFileName_prefersCustomThenTitleAndTruncates() {
        assertEquals("我的课件", VideoCollectorLogic.collectorFileName("我的课件.mp4", "分享标题"))
        assertEquals("分享标题", VideoCollectorLogic.collectorFileName("", "分享标题"))
        assertEquals("视频", VideoCollectorLogic.collectorFileName(null, null))
        val long = "标题".repeat(100)
        assertEquals(60, VideoCollectorLogic.collectorFileName(null, long).length)
        val cleaned = VideoCollectorLogic.collectorFileName("a/b:c", null)
        assertFalse(cleaned.contains("/"))
        assertFalse(cleaned.contains(":"))
    }
}
