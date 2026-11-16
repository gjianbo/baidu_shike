package com.hnwd.bdplayer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 播放清单取址的纯逻辑：把「可恢复（应自动重试）」与「永久失败（立即报错）」钉死。
 *
 * 真机现象：冷文件首次取址，百度可能 HTTP 200 返回占位内容 / 尚未生成完的 m3u8，
 * 再请求一次才给完整清单。这类失败必须归到 [TranscodingPending] 由播放链路重试，
 * 否则就会弹「无法原生播放」，而用户手动点「重试」却立刻正常。
 */
class VideoStreamResolverLogicTest {

    // ---------- 可恢复：交给调用方重试 ----------

    @Test
    fun `31341 两种错误码字段都判定为转码未就绪`() {
        assertTrue(playlistError(400, """{"errno":31341}""") is TranscodingPending)
        assertTrue(playlistError(200, """{"error_code":31341}""") is TranscodingPending)
    }

    @Test
    fun `200 但返回体不是可播放清单判定为未就绪`() {
        assertTrue(playlistError(200, "") is TranscodingPending)
        assertTrue(playlistError(200, "#EXTM3U\n#EXT-X-VERSION:3\n") is TranscodingPending)
        assertTrue(playlistError(200, """{"errmsg":"转码中"}""") is TranscodingPending)
        assertTrue(playlistError(200, "<html>busy</html>") is TranscodingPending)
    }

    @Test
    fun `非 200 且无明确错误码也判定为未就绪`() {
        assertTrue(playlistError(400, "") is TranscodingPending)
        assertTrue(playlistError(502, "") is TranscodingPending)
    }

    // ---------- 永久失败：立即报错，不重试 ----------

    @Test
    fun `登录失效 -6 立即失败`() {
        val error = playlistError(200, """{"errno":-6}""")
        assertFalse(error is TranscodingPending)
        assertEquals("请重新登录百度网盘后再试。", error.message)
    }

    @Test
    fun `风控 -20 立即失败且文案不带视频语义`() {
        val error = playlistError(200, """{"errno":-20,"show_msg":"需要验证码"}""")
        assertFalse(error is TranscodingPending)
        assertTrue(error.message!!.contains("拒绝了播放取址请求"))
        assertFalse(error.message!!.contains("视频取址"))
        assertTrue(error.message!!.contains("需要验证码"))
    }

    @Test
    fun `31304 与 31023 立即失败`() {
        assertFalse(playlistError(200, """{"error_code":31304}""") is TranscodingPending)
        assertFalse(playlistError(200, """{"error_code":31023}""") is TranscodingPending)
    }

    @Test
    fun `401 403 是地址过期立即失败`() {
        assertFalse(playlistError(403, "") is TranscodingPending)
        assertFalse(playlistError(401, "") is TranscodingPending)
        assertTrue(playlistError(403, "").message!!.contains("地址约 8 小时有效"))
    }

    @Test
    fun `未知错误码立即失败且带错误码`() {
        val error = playlistError(200, """{"error_code":31999}""")
        assertFalse(error is TranscodingPending)
        assertTrue(error.message!!.contains("31999"))
    }

    // ---------- 播放清单判定 ----------

    @Test
    fun `带 ENDLIST 或 STREAM-INF 的 m3u8 才是可播放清单`() {
        assertTrue(isPlaybackPlaylist("#EXTM3U\n#EXT-X-VERSION:3\n#EXT-X-ENDLIST"))
        assertTrue(isPlaybackPlaylist("#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH=1000\na.m3u8"))
        assertFalse(isPlaybackPlaylist("#EXTM3U\n#EXT-X-VERSION:3"))
        assertFalse(isPlaybackPlaylist(""))
        assertFalse(isPlaybackPlaylist("""{"errno":0}"""))
    }
}
