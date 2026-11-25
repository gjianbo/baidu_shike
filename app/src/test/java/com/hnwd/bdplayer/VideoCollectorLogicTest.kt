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
        assertTrue(parsed.apiUrl.contains("bvid=BV1xx411c7mD"))
        assertEquals("B站-BV1xx411c7mD", parsed.suggestedName)
    }

    @Test
    fun parse_recognizesAvAndShortLink() {
        val av = VideoCollectorLogic.parse("http://m.bilibili.com/video/av12345678?from=share")
        assertEquals("av12345678", av?.id)
        assertEquals("https://api.bilibili.com/x/web-interface/view?aid=12345678", av?.apiUrl)

        val short = VideoCollectorLogic.parse("https://b23.tv/BV1GJ411x7h7?share_source=copy_web")
        assertEquals("BV1GJ411x7h7", short?.id)
    }

    @Test
    fun parse_recognizesXStatusFromShareText() {
        val parsed = VideoCollectorLogic.parse("看看这个 https://x.com/user/status/1791234567890123456?s=20 精彩")
        assertNotNull(parsed)
        assertEquals(VideoCollectorLogic.Platform.X, parsed!!.platform)
        assertEquals("1791234567890123456", parsed.id)
        assertEquals("https://x.com/i/status/1791234567890123456", parsed.canonicalUrl)
        assertTrue(parsed.apiUrl.startsWith("https://api.vxtwitter.com/"))
    }

    @Test
    fun parse_recognizesMobileTwitterLink() {
        val parsed = VideoCollectorLogic.parse("https://mobile.twitter.com/foo/status/1234567890123456789")
        assertEquals(VideoCollectorLogic.Platform.X, parsed?.platform)
        assertEquals("1234567890123456789", parsed?.id)
    }

    @Test
    fun parse_rejectsUnrelatedOrEmptyText() {
        assertNull(VideoCollectorLogic.parse(""))
        assertNull(VideoCollectorLogic.parse("这是一段普通文字，没有链接"))
        assertNull(VideoCollectorLogic.parse("https://example.com/video/BV1xx411c7mD"))
        assertNull(VideoCollectorLogic.parse("https://x.com/user/media/12345"))
    }

    @Test
    fun resolveBilibili_picksHighestBandwidthVideo() {
        val json = """
            {"code":0,"data":{"title":"测试课程 第1讲","dash":{
              "video":[
                {"id":64,"bandwidth":100000,"url":"https://upos.example.com/360p.m4v"},
                {"id":80,"bandwidth":900000,"url":"https://upos.example.com/1080p.m4v"}
              ],
              "audio":[{"id":30216,"bandwidth":64000,"url":"https://upos.example.com/audio.m4a"}]
            }}}
        """.trimIndent()
        val r = VideoCollectorLogic.resolveBilibili(json)
        assertNotNull(r)
        assertEquals("https://upos.example.com/1080p.m4v", r!!.url)
        assertEquals("测试课程 第1讲.mp4", r.filename)
        assertEquals("video/mp4", r.mediaType)
        assertTrue(r.needReferer)
    }

    @Test
    fun resolveBilibili_fallsBackToAudioOnly() {
        val json = """
            {"code":0,"data":{"title":"纯音频","dash":{"audio":[{"bandwidth":128000,"backupUrl":"https://backup.example.com/a.m4a,https://other.example.com/a.m4a"}]}}}
        """.trimIndent()
        val r = VideoCollectorLogic.resolveBilibili(json)
        assertNotNull(r)
        assertEquals("https://backup.example.com/a.m4a", r!!.url)
        assertEquals("纯音频.m4a", r.filename)
        assertEquals("audio/mp4", r.mediaType)
    }

    @Test
    fun resolveBilibili_rejectsFailureAndMissingStreams() {
        assertNull(VideoCollectorLogic.resolveBilibili("""{"code":-404,"data":{"title":"无"}}"""))
        assertNull(VideoCollectorLogic.resolveBilibili("not json"))
        assertNull(VideoCollectorLogic.resolveBilibili("""{"code":0,"data":{"title":"空","dash":{}}}"""))
        assertNull(VideoCollectorLogic.resolveBilibili("""{"code":0,"data":{"title":"无直链","dash":{"video":[{"bandwidth":1}]}}}"""))
    }

    @Test
    fun resolveX_picksVideoVariantAndBuildsSafeName() {
        val json = """
            {"user_name":"Teacher","media":[
              {"type":"photo","variants":[{"url":"https://pbs.example.com/photo.jpg","bitrate":0}]},
              {"type":"video","width":1280,"variants":[
                {"url":"https://video.example.com/480.mp4","bitrate":832000},
                {"url":"https://video.example.com/1080.mp4","bitrate":2176000}
              ]}
            ]}
        """.trimIndent()
        val r = VideoCollectorLogic.resolveX(json)
        assertNotNull(r)
        assertEquals("https://video.example.com/1080.mp4", r!!.url)
        assertTrue(r.filename.startsWith("X-Teacher-"))
        assertTrue(r.filename.endsWith(".mp4"))
        assertFalse(r.filename.contains("/"))
        assertEquals("video/mp4", r.mediaType)
        assertFalse(r.needReferer)
    }

    @Test
    fun resolveX_rejectsTweetWithoutVideo() {
        assertNull(VideoCollectorLogic.resolveX("""{"media":[{"type":"photo","variants":[{"url":"https://example.com/p.jpg"}]}]}"""))
        assertNull(VideoCollectorLogic.resolveX("broken"))
        assertNull(VideoCollectorLogic.resolveX("""{}"""))
    }
}
