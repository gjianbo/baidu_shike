package com.hnwd.bdplayer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HlsPlaylistLogicTest {

    private val base = "https://pan.baidu.com/mbox/msg/streaming?from_uk=1&to=2"

    /** 百度实测形态：media 列表、分片是自带签名的绝对地址、无加密、有 ENDLIST。 */
    private val baiduMedia = """
        #EXTM3U
        #EXT-X-VERSION:3
        #EXT-X-TARGETDURATION:10
        #EXT-X-MEDIA-SEQUENCE:0
        #EXTINF:9.960,
        https://bdcm06.baidupcs.com/netdisk-videotran-baoding/a_1_ts?sign=abc&expires=8h
        #EXTINF:9.960,
        https://bdcm06.baidupcs.com/netdisk-videotran-baoding/a_2_ts?sign=abc&expires=8h
        #EXTINF:5.120,
        https://bdcm06.baidupcs.com/netdisk-videotran-baoding/a_3_ts?sign=abc&expires=8h
        #EXT-X-ENDLIST
    """.trimIndent()

    @Test
    fun mediaPlaylist_becomesSegmentList() {
        val plan = requireNotNull(parseHlsPlan(baiduMedia, base))
        assertEquals(3, plan.count)
        assertEquals(3, plan.segments.size)
        assertFalse(plan.encrypted)
        assertNull(plan.initSegment)
        assertEquals(25.04, plan.durationSeconds, 0.001)
        assertTrue(plan.segments.first().startsWith("https://bdcm06.baidupcs.com/"))
    }

    @Test
    fun emptyOrPlaceholderPlaylist_isRejectedForRetry() {
        // 冷文件首次取址会拿到还没生成完的清单：没有分片就必须返回 null，让上层重试。
        assertNull(parseHlsPlan("#EXTM3U\n#EXT-X-VERSION:3\n", base))
        assertNull(parseHlsPlan("", base))
        assertNull(parseHlsPlan("{\"errno\":31341}", base))
    }

    @Test
    fun masterPlaylist_picksHighestBandwidthThenStaysUsable() {
        val master = """
            #EXTM3U
            #EXT-X-STREAM-INF:BANDWIDTH=800000
            /mbox/msg/streaming?to=2&stream_type=M3U8_AUTO_480
            #EXT-X-STREAM-INF:BANDWIDTH=2400000
            /mbox/msg/streaming?to=2&stream_type=M3U8_AUTO_1080
            #EXT-X-STREAM-INF:BANDWIDTH=1200000
            /mbox/msg/streaming?to=2&stream_type=M3U8_AUTO_720
        """.trimIndent()
        assertTrue(isMasterPlaylist(master))
        assertEquals(
            "https://pan.baidu.com/mbox/msg/streaming?to=2&stream_type=M3U8_AUTO_1080",
            pickHlsVariant(master, base),
        )
        assertFalse(isMasterPlaylist(baiduMedia))
        assertNull(pickHlsVariant(baiduMedia, base))
    }

    @Test
    fun relativeSegments_areResolvedAgainstThePlaylistUrl() {
        val body = """
            #EXTM3U
            #EXTINF:4.0,
            seg/00001.ts
            #EXTINF:4.0,
            /nest/ts/00002.ts
        """.trimIndent()
        val plan = requireNotNull(parseHlsPlan(body, "https://pan.baidu.com/a/b/list.m3u8?x=1"))
        assertEquals("https://pan.baidu.com/a/b/seg/00001.ts", plan.segments[0])
        assertEquals("https://pan.baidu.com/nest/ts/00002.ts", plan.segments[1])
    }

    @Test
    fun encryptionAndInitSegment_areReportedNotSilentlyIgnored() {
        val body = """
            #EXTM3U
            #EXT-X-KEY:METHOD=AES-128,URI="https://pan.baidu.com/key.bin"
            #EXT-X-MAP:URI="init.mp4"
            #EXTINF:4.0,
            https://cdn.example.com/1.ts
        """.trimIndent()
        val plan = requireNotNull(parseHlsPlan(body, base))
        assertTrue(plan.encrypted)
        assertEquals("AES-128", plan.keyMethod)
        assertEquals("https://pan.baidu.com/mbox/msg/init.mp4", plan.initSegment)
    }

    @Test
    fun methodNone_isNotTreatedAsEncrypted() {
        val body = """
            #EXTM3U
            #EXT-X-KEY:METHOD=NONE
            #EXTINF:4.0,
            https://cdn.example.com/1.ts
        """.trimIndent()
        val plan = requireNotNull(parseHlsPlan(body, base))
        assertFalse(plan.encrypted)
    }

    @Test
    fun absoluteUrl_passesThroughAndKeepsQuery() {
        val url = "https://bdcm06.baidupcs.com/x?a=1&b=2"
        assertEquals(url, absoluteHlsUrl(base, url))
        assertNull(absoluteHlsUrl(base, ""))
    }
}
