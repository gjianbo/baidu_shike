package com.hnwd.bdplayer

import java.net.HttpURLConnection
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.util.Locale

/**
 * 文本 / 通用文档链路里不依赖 Android View 的纯判定。
 *
 * [DocumentPreviewSection] 与 [TextReaderActivity] 都依赖 Activity / AlertDialog，JVM 单测实例化不了；
 * 这里抽出最容易出错的边界：扩展名到「应用内阅读 / 交系统」的分流、文本编码探测与解码、
 * 按类型区分的体积上限、系统分享用的 MIME。宿主状态不在这里读取。
 */

/** 文档链路最终把本地文件交给谁。 */
internal enum class DocumentTarget { TEXT_READER, SYSTEM }

/** UTF-8 / UTF-16 BOM，命中后按对应编码解码；无 BOM 走 [decodeDocumentText] 的探测。 */
private data class Bom(val bytes: ByteArray, val charset: Charset)

private val BOMS = listOf(
    Bom(byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()), StandardCharsets.UTF_8),
    Bom(byteArrayOf(0xFF.toByte(), 0xFE.toByte()), StandardCharsets.UTF_16LE),
    Bom(byteArrayOf(0xFE.toByte(), 0xFF.toByte()), StandardCharsets.UTF_16BE),
)

/** GB18030 兼容 GBK 且覆盖更广，Android 与 JVM 均内置；作为中文文本的兜底编码。 */
private val GB18030: Charset = Charset.forName("GB18030")

/**
 * 按 BOM 优先、严格 UTF-8 次之、GB18030 兜底解码。
 *
 * 用 REPORT 模式探测 UTF-8 合法性，避免用 REPLACE 时把 GBK 字节悄悄变成乱码却判成 UTF-8。
 * 解码失败（非法字节序列）不抛异常，交给调用方展示原文或报错。
 */
internal fun decodeDocumentText(bytes: ByteArray): String {
    if (bytes.isEmpty()) return ""
    val bom = BOMS.firstOrNull { bytes.size >= it.bytes.size && it.bytes.indices.all { i -> bytes[i] == it.bytes[i] } }
    if (bom != null) return String(bytes, bom.bytes.size, bytes.size - bom.bytes.size, bom.charset)
    val strictUtf8 = StandardCharsets.UTF_8.newDecoder()
        .onMalformedInput(CodingErrorAction.REPORT)
        .onUnmappableCharacter(CodingErrorAction.REPORT)
    return runCatching { strictUtf8.decode(java.nio.ByteBuffer.wrap(bytes)).toString() }
        .getOrElse { String(bytes, GB18030) }
}

private const val MAX_TEXT_CHUNK_CHARS = 4096
private const val MAX_TEXT_CHUNK_LINES = 128

/** 保留原文的分段下标，让列表只排版可见的文本。 */
internal fun documentTextChunks(text: String): List<IntRange> = buildList {
    var start = 0
    while (start < text.length) {
        val limit = minOf(start + MAX_TEXT_CHUNK_CHARS, text.length)
        var end = start
        var lineEnd = start
        var lines = 0
        while (end < limit && lines < MAX_TEXT_CHUNK_LINES) {
            if (text[end++] == '\n') {
                lineEnd = end
                lines++
            }
        }
        if (end < text.length) {
            end = when {
                lineEnd > start -> lineEnd
                text[end - 1] == '\r' && text[end] == '\n' -> end + 1
                text[end - 1].isHighSurrogate() && text[end].isLowSurrogate() -> end - 1
                else -> end
            }
        }
        var visibleEnd = end
        // 下一段从新行开始，移除段尾分隔符，保留文档末尾的换行和原有空行。
        if (end < text.length && text[end - 1] == '\n') {
            visibleEnd--
            if (visibleEnd > start && text[visibleEnd - 1] == '\r') visibleEnd--
        }
        add(start until visibleEnd)
        start = end
    }
}

/** 文档链路的体积上限：文本 20MB（拍板默认），Office / 压缩包 200MB（与 PDF 对齐）。 */
internal const val MAX_TEXT_BYTES = 20L * 1024 * 1024
internal const val MAX_DOCUMENT_BYTES = 200L * 1024 * 1024

internal fun documentTooLarge(target: DocumentTarget, bytes: Long): Boolean =
    bytes > when (target) {
        DocumentTarget.TEXT_READER -> MAX_TEXT_BYTES
        DocumentTarget.SYSTEM -> MAX_DOCUMENT_BYTES
    }

internal fun documentTooLargeMessage(target: DocumentTarget): String = when (target) {
    DocumentTarget.TEXT_READER -> "文本文件超过 20MB，暂不支持在 App 内阅读。"
    DocumentTarget.SYSTEM -> "文档超过 200MB，暂不支持下载查看。"
}

internal class InvalidDocumentLink(message: String) : IllegalStateException(message)

internal fun checkDocumentResponse(code: Int, contentType: String?) {
    if (code in setOf(400, 401, 403, 404, 410)) {
        throw InvalidDocumentLink("文档下载失败（HTTP $code），请重新打开。")
    }
    check(code == HttpURLConnection.HTTP_OK) { "文档下载失败（HTTP $code），请重新打开。" }
    when (contentType?.substringBefore(';')?.trim()?.lowercase(Locale.ROOT)) {
        "text/html", "application/xhtml+xml", "application/json", "application/problem+json" ->
            throw InvalidDocumentLink("网盘返回了错误页面，请重新打开文档。")
    }
}

internal fun checkDocumentLength(target: DocumentTarget, declared: Long, received: Long) {
    if (declared >= 0 && received != declared) {
        throw InvalidDocumentLink("文档下载不完整，请重新打开。")
    }
    if (target == DocumentTarget.SYSTEM && received == 0L) {
        throw InvalidDocumentLink("文档内容为空，请重新打开。")
    }
}

/** 下载到本地后交给系统应用时使用的 MIME，与下载历史共用同一份映射。 */
internal fun documentMime(name: String): String = DownloadLogic.mimeType(name)

/** 文档下载进度文案（与 PDF 共用体积格式化口径）。 */
internal fun documentProgressText(done: Long, total: Long): String {
    if (done <= 0L) return "正在加载文档…"
    val percent = pdfProgressPercent(done, total)
    val loaded = String.format(Locale.US, "%.1f MB", done.coerceAtLeast(0L) / 1024.0 / 1024.0)
    return if (percent < 0) "已加载 $loaded" else "已加载 $loaded / ${String.format(Locale.US, "%.1f MB", total / 1024.0 / 1024.0)} · $percent%"
}

internal fun documentFailureMessage(error: Throwable): String = error.message ?: "文档打开失败，请重试。"

// ===================== 下载失败降级 =====================

internal enum class DocumentRetry { RETRY_LINK, SHOW_ERROR }

internal data class DocumentFailurePlan(val clearCachedLink: Boolean, val action: DocumentRetry)

/**
 * 链接失效时的降级。取址只有「原文件 dlink」一条路径（不像 PDF 有 docview -> fallback 两级），
 * 所以最多重取一次：[retried] 为真时不再重试，避免坏链接导致「取址 -> 下载失败 -> 再取址」无限循环。
 * [clearCachedLink] 与动作分开：即使宿主未就绪或已放弃重试，缓存里的坏地址也必须清掉。
 * 夸克走的不是 URL 取址，不参与降级。
 */
internal fun documentFailurePlan(
    quark: Boolean,
    invalidLink: Boolean,
    hostReady: () -> Boolean,
    retried: Boolean,
): DocumentFailurePlan {
    if (quark || !invalidLink) return DocumentFailurePlan(false, DocumentRetry.SHOW_ERROR)
    if (!hostReady() || retried) return DocumentFailurePlan(true, DocumentRetry.SHOW_ERROR)
    return DocumentFailurePlan(true, DocumentRetry.RETRY_LINK)
}
