package com.hnwd.bdplayer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * PanApi 里的纯函数：来源 id 前缀、路径拼接、缩略图参数、md5 混淆位。
 *
 * 这些是多网盘混装（百度 / 夸克同列表）最容易出错的地方 —— 前缀一旦丢或加错，
 * 请求会打到错误的网盘，而且错误信息通常很含糊。
 */
class PanApiHelpersTest {

    // ===================== 来源 id 前缀 =====================

    @Test
    fun `百度来源带前缀后仍可还原`() {
        val qualified = qualifySourceId(SourceProvider.BAIDU, "123456")
        assertEquals("baidu:123456", qualified)
        assertEquals("123456", rawSourceId(qualified))
        assertEquals(SourceProvider.BAIDU, providerOfSourceId(qualified))
    }

    @Test
    fun `夸克来源带前缀后仍可还原`() {
        val fid = "a1b2c3d4e5f60718"
        val qualified = qualifySourceId(SourceProvider.QUARK, fid)
        assertEquals("quark:$fid", qualified)
        assertEquals(fid, rawSourceId(qualified))
        assertEquals(SourceProvider.QUARK, providerOfSourceId(qualified))
    }

    @Test
    fun `已带前缀的 id 不会被二次加前缀`() {
        assertEquals("quark:abcdef12", qualifySourceId(SourceProvider.QUARK, "quark:abcdef12"))
        assertEquals("baidu:123", qualifySourceId(SourceProvider.BAIDU, "baidu:123"))
    }

    @Test
    fun `老数据无前缀时按百度处理`() {
        assertEquals(SourceProvider.BAIDU, providerOfSourceId("987654"))
        assertEquals("987654", rawSourceId("987654"))
    }

    @Test
    fun `联系人来源识别`() {
        assertTrue(isContactSource("contact-123"))
        assertTrue(isContactSource("baidu:contact-123"))
        assertFalse(isContactSource("123"))
        assertEquals("123", PanEntry(
            gid = "contact-123", messageId = "", owner = "1001", fileId = "f1", name = "a",
            path = "/a", size = "1", directory = false,
        ).contactRecipient(viewer = "1001"))
    }

    @Test
    fun `来源 id 合法性校验`() {
        assertTrue(isShareSourceId("123456"))
        assertTrue(isShareSourceId("contact-123456"))
        assertFalse(isShareSourceId("abc"))            // 百度侧只认数字
        assertFalse(isShareSourceId("1234567890123456789012345")) // 超过 24 位
        assertTrue(isShareSourceId("quark:a1b2c3d4e5f60718"))
        assertFalse(isShareSourceId("quark:xyz"))      // 夸克侧只认十六进制
    }

    // ===================== 路径 =====================

    @Test
    fun `拼接盘内路径`() {
        assertEquals("/课程A", PanApi.diskPath("/", "课程A"))
        assertEquals("/课程A/第1章", PanApi.diskPath("/课程A", "第1章"))
        assertEquals("/课程A/第1章", PanApi.diskPath("/课程A/", "第1章"))
    }

    @Test
    fun `取父目录`() {
        assertEquals("/课程A", PanApi.parentPath("/课程A/第1讲.mp3"))
        assertEquals("/", PanApi.parentPath("/课程A"))
        assertEquals("/", PanApi.parentPath("/"))
    }

    // ===================== 缩略图与 md5 =====================

    @Test
    fun `缩略图只替换 size 参数并保留其余参数顺序`() {
        val raw = "https://x/thumb?f=1&size=c100&t=2"
        assertEquals("https://x/thumb?f=1&size=c2560_u2560&t=2", PanApi.thumbResize(raw, "c2560_u2560"))
    }

    @Test
    fun `没有 size 参数时原样返回`() {
        val raw = "https://x/thumb?f=1&t=2"
        assertEquals(raw, PanApi.thumbResize(raw, "c2560_u2560"))
    }

    @Test
    fun `md5 第 10 位被混淆时去掉该位后可比对`() {
        val clean = "0123456789abcdef0123456789abcdef"
        assertEquals(clean, PanApi.md5Key(clean))          // 第 10 位是数字，保留

        // 只把第 10 位（下标 9）换成非十六进制字母，其余保持原样
        val obfuscated = clean.substring(0, 9) + "z" + clean.substring(10)
        assertEquals(32, obfuscated.length)
        assertEquals(31, PanApi.md5Key(obfuscated).length)
        assertEquals(clean.take(9) + clean.drop(10), PanApi.md5Key(obfuscated))
    }

    @Test
    fun `非 32 位 md5 原样返回`() {
        assertEquals("abc", PanApi.md5Key("abc"))
        assertEquals("", PanApi.md5Key(""))
    }

    // ===================== 媒体类型分流 =====================

    @Test
    fun `扩展名分流不受大小写影响`() {
        assertEquals(MediaKind.VIDEO, PanApi.kindOf(entryOf("课程.MP4")))
        assertEquals(MediaKind.AUDIO, PanApi.kindOf(entryOf("课程.Mp3")))
        assertEquals(MediaKind.IMAGE, PanApi.kindOf(entryOf("封面.JPEG")))
        assertEquals(MediaKind.PDF, PanApi.kindOf(entryOf("讲义.pdf")))
        assertEquals(MediaKind.DOCUMENT, PanApi.kindOf(entryOf("资料.zip")))
        assertEquals(MediaKind.OTHER, PanApi.kindOf(entryOf("无扩展名")))
    }

    @Test
    fun `目录优先于扩展名`() {
        assertEquals(MediaKind.DIRECTORY, PanApi.kindOf(entryOf("看起来像.mp4", directory = true)))
    }

    private fun entryOf(name: String, directory: Boolean = false) = PanEntry(
        gid = "1", messageId = "m", owner = "1", fileId = "f", name = name,
        path = "/$name", size = "1", directory = directory,
    )
}
