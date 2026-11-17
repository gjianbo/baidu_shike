package com.hnwd.bdplayer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.charset.Charset
import java.nio.charset.StandardCharsets

/** 文档链路纯逻辑：编码探测、体积上限、MIME、打开目标分流。 */
class DocumentLogicTest {

    // ===================== 文本编码探测 =====================

    @Test
    fun decodePlainUtf8() {
        val raw = "拾课 文档 阅读\n第二行".toByteArray(StandardCharsets.UTF_8)
        assertEquals("拾课 文档 阅读\n第二行", decodeDocumentText(raw))
    }

    @Test
    fun decodeUtf8WithBom() {
        val body = "带 BOM 的中文".toByteArray(StandardCharsets.UTF_8)
        val raw = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) + body
        assertEquals("带 BOM 的中文", decodeDocumentText(raw))
    }

    @Test
    fun decodeUtf16Bom() {
        val body = "UTF16 文本".toByteArray(StandardCharsets.UTF_16LE)
        val raw = byteArrayOf(0xFF.toByte(), 0xFE.toByte()) + body
        assertEquals("UTF16 文本", decodeDocumentText(raw))
    }

    @Test
    fun decodeGbkFallback() {
        // 用 GBK 编码中文，严格 UTF-8 解码会失败，必须回退到 GB18030。
        val raw = "中文 GBK 文本".toByteArray(Charset.forName("GBK"))
        assertEquals("中文 GBK 文本", decodeDocumentText(raw))
    }

    @Test
    fun decodeEmpty() {
        assertEquals("", decodeDocumentText(ByteArray(0)))
    }

    @Test
    fun textChunksKeepBlankLinesAndFinalNewline() {
        val first = List(128) { "字" }.joinToString("\r\n")
        val last = "\r\n尾行\r\n"
        val text = "$first\r\n$last"

        assertEquals(listOf(first, last), documentTextChunks(text).map(text::substring))
        assertTrue(documentTextChunks("").isEmpty())
    }

    @Test
    fun textChunksSplitLongLinesWithoutBreakingUnicodeOrCrLf() {
        val prefix = "a".repeat(4095)
        val unicode = "$prefix😀b"
        val crlf = "$prefix\r\n尾"

        assertEquals(listOf(prefix, "😀b"), documentTextChunks(unicode).map(unicode::substring))
        assertEquals(listOf(prefix, "尾"), documentTextChunks(crlf).map(crlf::substring))
    }

    // ===================== 体积上限 =====================

    @Test
    fun textLimitIs20Mb() {
        assertFalse(documentTooLarge(DocumentTarget.TEXT_READER, MAX_TEXT_BYTES))
        assertTrue(documentTooLarge(DocumentTarget.TEXT_READER, MAX_TEXT_BYTES + 1))
    }

    @Test
    fun officeLimitIs200Mb() {
        assertFalse(documentTooLarge(DocumentTarget.SYSTEM, MAX_DOCUMENT_BYTES))
        assertTrue(documentTooLarge(DocumentTarget.SYSTEM, MAX_DOCUMENT_BYTES + 1))
    }

    @Test
    fun textLimitMessageMentions20Mb() {
        assertTrue(documentTooLargeMessage(DocumentTarget.TEXT_READER).contains("20MB"))
        assertTrue(documentTooLargeMessage(DocumentTarget.SYSTEM).contains("200MB"))
    }

    @Test
    fun acceptsEmptyAndShortTextDownloads() {
        for (length in listOf(0L, 1L, 3L, 5L)) {
            checkDocumentLength(DocumentTarget.TEXT_READER, length, length)
            checkDocumentLength(DocumentTarget.TEXT_READER, -1L, length)
        }
    }

    @Test
    fun rejectsTruncatedTextAndEmptyOfficeDownloads() {
        assertThrows(InvalidDocumentLink::class.java) {
            checkDocumentLength(DocumentTarget.TEXT_READER, 5L, 3L)
        }
        assertThrows(InvalidDocumentLink::class.java) {
            checkDocumentLength(DocumentTarget.SYSTEM, 0L, 0L)
        }
    }

    @Test
    fun rejectsHttp200ErrorPagesAsInvalidLinks() {
        for (contentType in listOf("Text/HTML; charset=utf-8", "application/xhtml+xml", "application/json")) {
            assertThrows(InvalidDocumentLink::class.java) { checkDocumentResponse(200, contentType) }
        }
    }

    @Test
    fun acceptsDocumentResponsesWithGenericOrMissingContentType() {
        checkDocumentResponse(200, "application/octet-stream")
        checkDocumentResponse(200, "text/plain; charset=utf-8")
        checkDocumentResponse(200, null)
    }

    // ===================== MIME =====================

    @Test
    fun officeMimeTypes() {
        assertEquals("application/msword", documentMime("README.DOC"))
        assertEquals(
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
            documentMime("a.docx"),
        )
        assertEquals("application/vnd.ms-excel", documentMime("sheet.xls"))
        assertEquals(
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
            documentMime("sheet.xlsx"),
        )
        assertEquals("application/vnd.ms-powerpoint", documentMime("deck.ppt"))
        assertEquals(
            "application/vnd.openxmlformats-officedocument.presentationml.presentation",
            documentMime("deck.pptx"),
        )
    }

    @Test
    fun distinguishesOfficeDocumentsFromArchivesForQuarkRouting() {
        for (name in listOf("a.doc", "a.DOCX", "a.xls", "a.xlsx", "a.ppt", "a.pptx")) {
            assertEquals(MediaKind.DOCUMENT, mediaKind(name, false))
            assertTrue(isOfficeDocument(name))
        }
        assertEquals(MediaKind.DOCUMENT, mediaKind("a.zip", false))
        assertFalse(isOfficeDocument("a.zip"))
    }

    @Test
    fun textAndUnknownMime() {
        assertEquals("text/plain", documentMime("notes.md"))
        assertEquals("application/octet-stream", documentMime("noext"))
    }

    // ===================== 打开目标分流 =====================

    @Test
    fun targetFollowsMediaKind() {
        val text = PanEntry("", "", "", "f1", "a.md", "/", "10", false, EntryOrigin.DISK)
        val office = PanEntry("", "", "", "f2", "a.docx", "/", "10", false, EntryOrigin.DISK)
        assertEquals(DocumentTarget.TEXT_READER, documentTarget(text))
        assertEquals(DocumentTarget.SYSTEM, documentTarget(office))
    }

    // ===================== 进度文案 =====================

    @Test
    fun progressText() {
        assertTrue(documentProgressText(0, 100).contains("正在加载"))
        assertEquals("已加载 1.0 MB / 2.0 MB · 50%", documentProgressText(1024L * 1024, 2L * 1024 * 1024))
        assertTrue(documentProgressText(1024L * 1024, -1L).startsWith("已加载"))
    }

    // ===================== 失败降级（只重取一次，杜绝无限循环） =====================

    @Test
    fun invalidLinkRetriesOnce() {
        val plan = documentFailurePlan(
            quark = false, invalidLink = true, hostReady = { true }, retried = false,
        )
        assertTrue(plan.clearCachedLink)
        assertEquals(DocumentRetry.RETRY_LINK, plan.action)
    }

    @Test
    fun invalidLinkGivesUpAfterRetry() {
        // 这是修掉无限循环的关键：第二次失败即使地址仍失效，也必须停下。
        val plan = documentFailurePlan(
            quark = false, invalidLink = true, hostReady = { true }, retried = true,
        )
        assertTrue(plan.clearCachedLink)
        assertEquals(DocumentRetry.SHOW_ERROR, plan.action)
    }

    @Test
    fun notReadyClearsLinkButGivesUp() {
        val plan = documentFailurePlan(
            quark = false, invalidLink = true, hostReady = { false }, retried = false,
        )
        assertTrue(plan.clearCachedLink)
        assertEquals(DocumentRetry.SHOW_ERROR, plan.action)
    }

    @Test
    fun nonInvalidErrorDoesNotClearLinkOrRetry() {
        val plan = documentFailurePlan(
            quark = false, invalidLink = false, hostReady = { true }, retried = false,
        )
        assertFalse(plan.clearCachedLink)
        assertEquals(DocumentRetry.SHOW_ERROR, plan.action)
    }

    @Test
    fun quarkNeverRetriesOrClears() {
        // 夸克走 fileId 下载，不是 URL 缓存，链接失效不参与清地址/重取。
        val plan = documentFailurePlan(
            quark = true, invalidLink = true, hostReady = { true }, retried = false,
        )
        assertFalse(plan.clearCachedLink)
        assertEquals(DocumentRetry.SHOW_ERROR, plan.action)
    }
}
