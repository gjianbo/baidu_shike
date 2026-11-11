package com.hnwd.bdplayer

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 媒体取址层纯逻辑。脚本用 [JSONObject.quote] 转义服务端下发内容，
 * 是这里最需要钉死的安全边界；其余是 lane 出口、验证码弹窗与代次判定。
 */
class MediaLinkLogicTest {

    // ---------- 下载签名脚本 ----------

    @Test
    fun `签名脚本固定校验网盘域名并包装 btoa`() {
        val script = diskSignScript("s1", "s2", "s3")
        assertTrue(script.startsWith("(() => {"))
        assertTrue(script.endsWith("})()"))
        assertTrue(script.contains("location.origin !== 'https://pan.baidu.com'"))
        assertTrue(script.contains("return btoa(sign("))
        assertTrue(script.contains("catch (_)"))
    }

    @Test
    fun `签名脚本三个字段的位置与顺序`() {
        val q1 = JSONObject.quote("A")
        val q2 = JSONObject.quote("B")
        val q3 = JSONObject.quote("C")
        val script = diskSignScript("A", "B", "C")
        assertTrue(script.contains("new Function('return ' + $q2)()"))
        assertTrue(script.contains("btoa(sign($q3, $q1))"))
        assertFalse(script.contains("btoa(sign($q1, $q3))"))
    }

    @Test
    fun `服务端字段里的引号与脚本片段必须被转义`() {
        val evil = "\"); alert(1); //"
        val quoted = JSONObject.quote(evil)
        val script = diskSignScript(evil, "f", "g")
        // quote 把内层引号转义成 \"，且转义后的字面量原样嵌入脚本一次
        assertTrue(quoted.contains("\\\""))
        assertTrue(script.contains(quoted))
        assertEquals(1, script.split(quoted).size - 1)
    }

    @Test
    fun `脚本没有 trimIndent 残留缩进`() {
        val lines = diskSignScript("a", "b", "c").lines()
        assertEquals("(() => {", lines.first())
        lines.drop(1).dropLast(1).forEach { assertTrue(it.startsWith("    ")) }
    }

    // ---------- WebView 回传签名 ----------

    @Test
    fun `合法 JSON 字符串解析出签名`() {
        assertEquals("abc123", parseSignResult("\"abc123\""))
    }

    @Test
    fun `带转义的 JSON 字符串按 JSON 规则解码`() {
        assertEquals("a\"b\\c", parseSignResult("\"a\\\"b\\\\c\""))
    }

    @Test
    fun `空值 空串 空 JSON 字符串都判为失败`() {
        assertNull(parseSignResult(null))
        assertNull(parseSignResult(""))
        assertNull(parseSignResult("\"\""))
    }

    @Test
    fun `非字符串 JSON 值判为失败`() {
        assertNull(parseSignResult("123"))
        assertNull(parseSignResult("null"))
        assertNull(parseSignResult("{\"a\":1}"))
    }

    @Test
    fun `截断的 JSON 判为失败而不是抛异常`() {
        assertNull(parseSignResult("{\"a\":"))
    }

    // ---------- 验证码提示 ----------

    @Test
    fun `验证码首次与重试文案不同`() {
        assertEquals("请输入图片中的验证码", captchaPrompt(false))
        assertEquals("验证码错误或已失效，请重新输入", captchaPrompt(true))
    }

    // ---------- lane 错误出口 ----------

    @Test
    fun `只有 AUDIO 走音频页提示`() {
        assertEquals(LinkErrorTarget.AUDIO, linkErrorTarget(RequestLane.AUDIO))
    }

    @Test
    fun `其余六个 lane 都走状态栏`() {
        val others = RequestLane.entries - RequestLane.AUDIO
        assertEquals(6, others.size)
        others.forEach { assertEquals(LinkErrorTarget.STATUS, linkErrorTarget(it)) }
    }

    // ---------- 验证码弹窗守卫 ----------

    @Test
    fun `页面不可见时不弹且短路不读弹窗状态`() {
        var reads = 0
        assertTrue(captchaBlocked({ false }, { reads++; true }))
        assertEquals(0, reads)
    }

    @Test
    fun `页面可见且无弹窗才允许弹`() {
        assertFalse(captchaBlocked({ true }, { false }))
    }

    @Test
    fun `同一时刻只允许一个验证码弹窗`() {
        var reads = 0
        assertTrue(captchaBlocked({ true }, { reads++; true }))
        assertEquals(1, reads)
    }

    // ---------- 答案校验 ----------

    @Test
    fun `空白答案被拦下`() {
        assertEquals("请输入验证码", captchaAnswerError(""))
        assertEquals("请输入验证码", captchaAnswerError("   "))
        assertEquals("请输入验证码", captchaAnswerError("\t\n"))
    }

    @Test
    fun `非空白答案放行`() {
        assertNull(captchaAnswerError("ab12"))
        assertNull(captchaAnswerError("  ab12  "))
    }

    // ---------- 按 lane 关闭弹窗 ----------

    @Test
    fun `只关当前 lane 的弹窗`() {
        assertTrue(shouldDismissCaptcha(RequestLane.AUDIO, RequestLane.AUDIO))
        assertFalse(shouldDismissCaptcha(RequestLane.LIST, RequestLane.AUDIO))
        assertFalse(shouldDismissCaptcha(RequestLane.AUDIO, RequestLane.LIST))
    }

    @Test
    fun `没有弹窗时不误关`() {
        assertFalse(shouldDismissCaptcha(null, RequestLane.LIST))
    }
}
