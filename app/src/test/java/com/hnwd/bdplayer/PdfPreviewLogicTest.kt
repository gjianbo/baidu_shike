package com.hnwd.bdplayer

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * PDF 预览（含两条链路共用的账号探测 / 打开代次）纯逻辑的 JVM 单测。
 * 重点是短路：迟到回调一旦判定代次失效，就不该再去读任何宿主状态。
 */
class PdfPreviewLogicTest {

    // ---------- 文档体积上限（夸克按原始大小、百度按 Content-Length） ----------

    @Test
    fun `夸克超过 50MB 预拦且提示说明平台限制`() {
        assertEquals(50L * 1024 * 1024, MAX_QUARK_PDF_BYTES)
        assertEquals(
            "夸克网盘非会员单个文件下载上限约 50MB，请开通会员或到夸克网页版查看。",
            QUARK_PDF_TOO_LARGE_MESSAGE,
        )
        assertFalse(quarkPdfTooLarge(null))
        assertFalse(quarkPdfTooLarge(""))
        assertFalse(quarkPdfTooLarge("abc"))
        assertFalse(quarkPdfTooLarge("-1"))
        assertFalse(quarkPdfTooLarge((50L * 1024 * 1024).toString()))
        assertTrue(quarkPdfTooLarge((50L * 1024 * 1024 + 1).toString()))
        assertTrue(quarkPdfTooLarge("70675789")) // 实测 67.4MB 触发 23018
    }

    @Test
    fun `200MB 以内放行 超过 200MB 拦截`() {
        assertEquals(200L * 1024 * 1024, MAX_PDF_BYTES)
        assertEquals("文档超过 200MB，暂不支持在 App 内在线阅读。", PDF_TOO_LARGE_MESSAGE)
        assertFalse(pdfTooLarge(-1L)) // 分块传输没有 Content-Length，交给累计字节数兜底
        assertFalse(pdfTooLarge(0L))
        assertFalse(pdfTooLarge(MAX_PDF_BYTES))
        assertTrue(pdfTooLarge(MAX_PDF_BYTES + 1))
        assertTrue(pdfTooLarge(240L * 1024 * 1024))
    }

    @Test
    fun `UA 修复后 大原件与转码副本都不该被体积误拦`() {
        // 96.4MB / 113.6MB 原件直出：旧实现先被 50MB 预拦、再被 80MB 下载上限拦
        assertFalse(pdfTooLarge(96_366_817L))
        assertFalse(pdfTooLarge(113_612_768L))
        // 240MB 原件经 docview 转码后只有 29.6MB，原始 size 不该作为判据
        assertFalse(pdfTooLarge(29_638_474L))
        assertFalse(pdfTooLarge(109L * 1024 * 1024))
    }

    // ---------- 下载进度（百分比与文案都是纯函数） ----------

    @Test
    fun `百分比在总长度未知或数据异常时返回负一`() {
        assertEquals(-1, pdfProgressPercent(0L, -1L))
        assertEquals(-1, pdfProgressPercent(0L, 0L))
        assertEquals(-1, pdfProgressPercent(-1L, 100L))
    }

    @Test
    fun `百分比按总长度计算并钳在 0 到 100`() {
        assertEquals(0, pdfProgressPercent(0L, 120L * 1024 * 1024))
        assertEquals(10, pdfProgressPercent(12L * 1024 * 1024, 120L * 1024 * 1024))
        assertEquals(100, pdfProgressPercent(120L * 1024 * 1024, 120L * 1024 * 1024))
        assertEquals(100, pdfProgressPercent(200L * 1024 * 1024, 120L * 1024 * 1024))
        // 100MB 级原件：先乘 100 也不能溢出 Long
        assertEquals(84, pdfProgressPercent(96_366_817L, 113_612_768L))
    }

    @Test
    fun `进度文案区分已知总长与分块传输`() {
        assertEquals("正在加载文档…", pdfProgressText(0L, 113_612_768L))
        assertEquals(
            "已加载 12.0 MB / 120.0 MB · 10%",
            pdfProgressText(12L * 1024 * 1024, 120L * 1024 * 1024),
        )
        assertEquals("已加载 12.0 MB", pdfProgressText(12L * 1024 * 1024, -1L))
        assertEquals("已加载 108.3 MB", pdfProgressText(113_612_768L, -1L))
    }

    // ---------- 账号探测 ----------

    @Test
    fun `uk 必须是 1 到 24 位纯数字`() {
        assertFalse(isNumericUk(null))
        assertFalse(isNumericUk(""))
        assertTrue(isNumericUk("1"))
        assertTrue(isNumericUk("123456789012345678901234"))
        assertFalse(isNumericUk("1234567890123456789012345"))
        assertFalse(isNumericUk("123a"))
        assertFalse(isNumericUk("-1"))
        assertFalse(isNumericUk(" 1"))
    }

    @Test
    fun `JS 回传的 JSON 字符串字面量能取出 uk`() {
        assertEquals("12345", parseViewerUk("\"12345\""))
        assertEquals("", parseViewerUk("\"\""))
        assertEquals("abc", parseViewerUk("\"abc\""))
    }

    @Test
    fun `JS 回传非字符串或坏 JSON 时回落到 viewer 接口`() {
        assertNull(parseViewerUk(null))
        assertNull(parseViewerUk("12345"))
        assertNull(parseViewerUk("null"))
        assertNull(parseViewerUk("{"))
    }

    @Test
    fun `viewer 接口缺字段时拿到空串`() {
        assertEquals("999", viewerUkFromJson(JSONObject("""{"result":{"uk":"999"}}""")))
        assertEquals("", viewerUkFromJson(JSONObject("{}")))
        assertEquals("", viewerUkFromJson(JSONObject("""{"result":{}}""")))
    }

    @Test
    fun `viewer 接口返回的非法 uk 抛出错并带提示`() {
        assertEquals("999", requireViewerUk("999"))
        val error = runCatching { requireViewerUk("abc") }.exceptionOrNull()
        assertTrue(error is IllegalStateException)
        assertEquals(VIEWER_UK_ERROR, error?.message)
    }

    // ---------- 打开代次 ----------

    @Test
    fun `代次不匹配时一次宿主状态都不读`() {
        var calls = 0
        val current = previewStillCurrent(
            1, 2,
            destroyed = { calls++; false },
            visible = { calls++; true },
            accountMatches = { calls++; true },
        )
        assertFalse(current)
        assertEquals(0, calls)
    }

    @Test
    fun `Activity 已销毁时不再读可见性与账号`() {
        var calls = 0
        val current = previewStillCurrent(
            1, 1,
            destroyed = { true },
            visible = { calls++; true },
            accountMatches = { calls++; true },
        )
        assertFalse(current)
        assertEquals(0, calls)
    }

    @Test
    fun `页面不可见时不再读账号归属`() {
        var calls = 0
        val current = previewStillCurrent(
            1, 1,
            destroyed = { false },
            visible = { false },
            accountMatches = { calls++; true },
        )
        assertFalse(current)
        assertEquals(0, calls)
    }

    @Test
    fun `代次页面账号三项都成立才算有效`() {
        assertTrue(previewStillCurrent(1, 1, { false }, { true }, { true }))
        var calls = 0
        assertFalse(previewStillCurrent(1, 1, { false }, { true }, { calls++; false }))
        assertEquals(1, calls)
    }

    // ---------- PDF 下载失败降级 ----------

    @Test
    fun `夸克下载失败不清理地址也不读宿主`() {
        var calls = 0
        val plan = pdfFailurePlan(true, true, { calls++; true }, false, false)
        assertFalse(plan.clearCachedLink)
        assertEquals(PdfRetry.SHOW_ERROR, plan.action)
        assertEquals(0, calls)
    }

    @Test
    fun `非链接失效的错误不动缓存`() {
        var calls = 0
        val plan = pdfFailurePlan(false, false, { calls++; true }, true, false)
        assertFalse(plan.clearCachedLink)
        assertEquals(PdfRetry.SHOW_ERROR, plan.action)
        assertEquals(0, calls)
    }

    @Test
    fun `宿主未就绪仍要清掉失效地址但只提示错误`() {
        val plan = pdfFailurePlan(false, true, { false }, true, false)
        assertTrue(plan.clearCachedLink)
        assertEquals(PdfRetry.SHOW_ERROR, plan.action)
    }

    @Test
    fun `缓存地址失效先退回重新取预览地址`() {
        val plan = pdfFailurePlan(false, true, { true }, fromCache = true, fromFallback = false)
        assertEquals(PdfRetry.RETRY_LINK, plan.action)
        assertTrue(plan.clearCachedLink)
    }

    @Test
    fun `非缓存地址失效退回下载取址`() {
        val plan = pdfFailurePlan(false, true, { true }, fromCache = false, fromFallback = false)
        assertEquals(PdfRetry.RETRY_FALLBACK, plan.action)
    }

    @Test
    fun `两级都退过就不再重试但仍清缓存`() {
        val plan = pdfFailurePlan(false, true, { true }, fromCache = false, fromFallback = true)
        assertEquals(PdfRetry.SHOW_ERROR, plan.action)
        assertTrue(plan.clearCachedLink)
    }

    @Test
    fun `失败文案优先用异常自带信息`() {
        assertEquals("自定义错误", pdfFailureMessage(IllegalStateException("自定义错误")))
        assertEquals("文档打开失败，请重试。", pdfFailureMessage(IllegalStateException()))
    }
}
