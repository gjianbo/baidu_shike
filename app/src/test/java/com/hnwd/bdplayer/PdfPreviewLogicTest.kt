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
