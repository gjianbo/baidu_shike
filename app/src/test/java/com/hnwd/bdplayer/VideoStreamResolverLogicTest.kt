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
    fun `31304 与 31023 属于可换档错误`() {
        // 取址层据此逐档降级：这两类换个 stream_type 就有救，不是文件本身不可播。
        assertTrue(playlistError(200, """{"error_code":31304}""") is StreamTierUnsupported)
        assertTrue(playlistError(200, """{"error_code":31023}""") is StreamTierUnsupported)
        assertTrue(playlistError(400, """{"errno":31023}""") is StreamTierUnsupported)
    }

    @Test
    fun `133 与 31341 不是可换档错误`() {
        // 状态问题：换档只会把同一个错误重复几遍，必须原样上抛。
        assertFalse(playlistError(200, """{"errno":31341}""") is StreamTierUnsupported)
        assertFalse(playlistError(200, """{"errno":133,"adToken":"x"}""") is StreamTierUnsupported)
        assertFalse(playlistError(200, """{"errno":133}""") is StreamTierUnsupported)
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

    @Test
    fun `未知错误码附带网盘 show_msg 便于定位`() {
        val error = playlistError(200, """{"error_code":31999,"show_msg":"文件涉嫌违规"}""")
        assertTrue(error.message!!.contains("文件涉嫌违规"))
    }

    // ---------- 广告门（133 + adToken）：用 token 重试，不是永久受限 ----------

    @Test
    fun `133 带 adToken 判定为广告门并解析 adTime`() {
        val error = playlistError(200, """{"errno":133,"adTime":8,"adToken":"abc","ltime":5}""")
        assertTrue(error is AdGate)
        val gate = error as AdGate
        assertEquals("abc", gate.adToken)
        assertEquals(8, gate.adTimeSeconds)
    }

    @Test
    fun `133 不带 adToken 才是永久受限`() {
        assertTrue(playlistError(200, """{"errno":133}""") is StreamingRestricted)
        assertTrue(playlistError(200, """{"error_code":133}""") is StreamingRestricted)
        assertFalse(playlistError(200, """{"errno":133}""") is TranscodingPending)
        assertFalse(playlistError(200, """{"errno":133}""") is AdGate)
    }

    @Test
    fun `133 无 token 文案说明受限原因并附带网盘提示`() {
        val error = playlistError(200, """{"errno":133,"show_msg":"开通会员后可播放"}""")
        assertTrue(error.message!!.contains("133"))
        assertTrue(error.message!!.contains("开通会员后可播放"))
    }

    @Test
    fun `withAdToken 替换空 adToken 且对 base64 做 URL 编码`() {
        val url = "https://pan.baidu.com/api/streaming?path=%2Fa&type=M3U8_AUTO_480&adToken=&src_id=1"
        val out = withAdToken(url, "ab+/cd=")
        assertTrue(out.contains("adToken=ab%2B%2Fcd%3D"))
        assertFalse(out.contains("adToken=&"))
        assertTrue(out.contains("type=M3U8_AUTO_480"))
        assertTrue(out.contains("path=%2Fa"))
        // 已有真实 token 时再替换，不重复追加
        val out2 = withAdToken(out, "xy")
        assertTrue(out2.contains("adToken=xy"))
        assertFalse(out2.contains("ab%2B%2Fcd%3D"))
    }

    @Test
    fun `群组音频清单允许广告 token 且校验原始文件地址`() {
        val url = "https://pan.baidu.com/mbox/msg/streaming?fs_id=123&stream_type=M3U8_MP3_128"
        val withToken = withAdToken(url, "ab+/cd=")
        assertTrue(matchesGroupAudioPlaylistUrl(url, url))
        assertTrue(matchesGroupAudioPlaylistUrl(withToken, url))
        assertFalse(matchesGroupAudioPlaylistUrl("$url&adToken=", url))
        assertFalse(matchesGroupAudioPlaylistUrl("$withToken&fs_id=456", url))
        assertFalse(matchesGroupAudioPlaylistUrl("$withToken#fragment", url))
        assertFalse(matchesGroupAudioPlaylistUrl(withToken.replace("fs_id=123", "fs_id=456"), url))
        assertFalse(matchesGroupAudioPlaylistUrl(withToken.replace("pan.baidu.com", "example.com"), url))
    }

    @Test
    fun `deriveProbeUrl 改为 nom3u8 预检并去掉播放门参数`() {
        val url = "https://pan.baidu.com/api/streaming?path=%2Fa&type=M3U8_AUTO_480&isplayer=1&check_blue=1&adToken=&src_id=1"
        val probe = deriveProbeUrl(url)
        assertTrue(probe.contains("type=M3U8_FLV_264_480"))
        assertTrue(probe.contains("nom3u8=1"))
        assertFalse(probe.contains("isplayer="))
        assertFalse(probe.contains("check_blue="))
        assertFalse(probe.contains("adToken="))
        assertTrue(probe.contains("path=%2Fa"))
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
