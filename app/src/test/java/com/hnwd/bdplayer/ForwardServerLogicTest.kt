package com.hnwd.bdplayer

import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ForwardServerLogicTest {

    @Test
    fun normalizeBaseUrl_acceptsHttpOrHttpsWithValidStructure() {
        assertEquals("http://fnos.hnwd.cn:19011", ForwardServerLogic.normalizeBaseUrl(" http://FNOS.HNWD.CN:19011/ "))
        assertEquals("http://192.168.1.10:19011", ForwardServerLogic.normalizeBaseUrl("http://192.168.1.10:19011"))
        assertEquals("http://127.0.0.1:19011", ForwardServerLogic.normalizeBaseUrl(" http://127.0.0.1:19011/ "))
        assertEquals("http://localhost", ForwardServerLogic.normalizeBaseUrl("http://LOCALHOST/"))
        assertEquals("http://[::1]:19011", ForwardServerLogic.normalizeBaseUrl("http://[::1]:19011"))
        assertEquals("https://parse.example.com/prefix", ForwardServerLogic.normalizeBaseUrl("HTTPS://PARSE.EXAMPLE.COM/prefix/"))
        assertEquals("https://192.168.1.10:19011", ForwardServerLogic.normalizeBaseUrl("https://192.168.1.10:19011"))
        listOf(
            "http://", "https:///example.com", "ftp://parse.example.com",
            "https://user:pass@parse.example.com", "https://@parse.example.com",
            "https://example.com:", "https://example.com:0", "https://example.com:65536",
            "https://example.com?", "https://example.com#", "https://example.com/?key=1",
            "http://127.0.0.1\\@example.com/",
        ).forEach { assertNull(it, ForwardServerLogic.normalizeBaseUrl(it)) }
    }

    @Test
    fun displayHost_dropsSchemeAndTrailingSlash() {
        assertEquals("192.168.1.10:19011", ForwardServerLogic.displayHost("http://192.168.1.10:19011/"))
        assertEquals("parse.example.com", ForwardServerLogic.displayHost("https://parse.example.com"))
    }

    @Test
    fun parseResponse_readsMetubeResolveFormat() {
        val ok = ForwardServerLogic.parseResponse(
            """{"status":"ok","url":"https://video.twimg.com/a/v.mp4","title":"标题","id":"123","duration":33.9,"ext":"mp4"}""",
        )
        assertEquals("标题", ok.title)
        assertEquals("https://video.twimg.com/a/v.mp4", ok.videoUrl)
        assertEquals(33.9, ok.duration, 0.001)
        assertEquals("mp4", ok.ext)

        val failure = org.junit.Assert.assertThrows(IllegalStateException::class.java) {
            ForwardServerLogic.parseResponse("""{"status":"error","msg":"Fresh cookies needed"}""")
        }
        assertTrue(failure.message.orEmpty().contains("Fresh cookies"))
        org.junit.Assert.assertThrows(IllegalStateException::class.java) {
            ForwardServerLogic.parseResponse("""{"status":"unknown"}""")
        }
    }

    @Test
    fun extensionFromUrl_usesPathAndFallsBackToMp4() {
        assertEquals("mp4", ForwardServerLogic.extensionFromUrl("https://cdn.example.com/a/b/video.mp4?token=1"))
        assertEquals("webm", ForwardServerLogic.extensionFromUrl("https://cdn.example.com/video.webm"))
        assertEquals("mp4", ForwardServerLogic.extensionFromUrl("https://cdn.example.com/play?id=1"))
    }

    @Test
    fun mediaUrlAndRedirect_allowHttpsOrExplicitLocalOriginWithoutDowngrade() {
        val server = "http://localhost:19011/prefix".toHttpUrl()
        val local = "http://localhost:19011/media/first".toHttpUrl()
        val cdn = "https://cdn.example.com/next?id=1".toHttpUrl()
        assertEquals(local, ForwardServerLogic.mediaUrl(local.toString(), server))
        assertEquals(cdn, ForwardServerLogic.mediaUrl(cdn.toString(), server))
        assertEquals(cdn, ForwardServerLogic.mediaRedirect(local, cdn.toString(), server))
        assertEquals("http://localhost:19011/media/second", ForwardServerLogic.mediaRedirect(local, "second", server).toString())
        assertNull(ForwardServerLogic.mediaRedirect(cdn, local.toString(), server))
        listOf("http://localhost:9999/video", "http://127.0.0.1:19011/video", "http://cdn.example.com/video").forEach {
            assertNull(ForwardServerLogic.mediaUrl(it, server))
            assertNull(ForwardServerLogic.mediaRedirect(local, it, server))
        }
        listOf("//user@cdn.example.com/video", "https://@cdn.example.com/video", "file:///tmp/a", "///other/video").forEach {
            assertNull(ForwardServerLogic.mediaRedirect(cdn, it, server))
        }
    }

    @Test
    fun referer_usesRecognizedHostBoundariesOnly() {
        assertEquals("https://www.bilibili.com/", ForwardServerLogic.mediaReferer("https://a.bilivideo.com/media".toHttpUrl()))
        assertEquals("https://www.xiaohongshu.com/", ForwardServerLogic.mediaReferer("https://a.xhscdn.com/media".toHttpUrl()))
        assertEquals("https://x.com/", ForwardServerLogic.mediaReferer("https://video.twimg.com/media".toHttpUrl()))
        assertEquals("https://www.douyin.com/", ForwardServerLogic.mediaReferer("https://a.douyinvod.com/media".toHttpUrl()))
        listOf("other.example/x.com?host=bilivideo.com", "fakex.com/media", "x.com.example/media").forEach {
            assertNull(ForwardServerLogic.mediaReferer("https://$it".toHttpUrl()))
        }
    }

    @Test
    fun mediaCheck_requiresCompatibleMimeAndSupportedContainerPrefix() {
        val mp4 = bmff("isom")
        val mov = bmff("qt  ")
        val webm = byteArrayOf(0x1a, 0x45, 0xdf.toByte(), 0xa3.toByte(), 0x87.toByte(), 0x42, 0x82.toByte(), 0x84.toByte()) + "webm".toByteArray()
        assertTrue(ForwardServerLogic.hasSupportedMedia("video/mp4", mp4))
        assertTrue(ForwardServerLogic.hasSupportedMedia("Video/QuickTime; charset=binary", mov))
        assertTrue(ForwardServerLogic.hasSupportedMedia("application/octet-stream", webm))
        assertTrue(ForwardServerLogic.hasSupportedMedia(null, mp4))
        assertFalse(ForwardServerLogic.hasSupportedMedia("text/html", mp4))
        assertFalse(ForwardServerLogic.hasSupportedMedia("application/json", mp4))
        assertFalse(ForwardServerLogic.hasSupportedMedia("video/mp4", "<html>Error</html>".toByteArray()))
        assertFalse(ForwardServerLogic.hasSupportedMedia("video/mp4", "{\"error\":true}".toByteArray()))
        assertFalse(ForwardServerLogic.hasSupportedMedia("video/mp4", "#EXTM3U\n".toByteArray()))
        assertFalse(ForwardServerLogic.hasSupportedMedia("video/mp4", bmff("avif")))
        assertFalse(ForwardServerLogic.hasSupportedMedia("video/mp4", mp4.copyOf(12)))
        assertFalse(ForwardServerLogic.hasSupportedMedia("video/webm", webm.copyOf(10)))
    }
}

internal fun bmff(brand: String): ByteArray =
    byteArrayOf(0, 0, 0, 16) + "ftyp$brand".toByteArray(Charsets.US_ASCII) + ByteArray(4)
