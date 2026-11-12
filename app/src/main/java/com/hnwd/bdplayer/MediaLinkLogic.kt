package com.hnwd.bdplayer

import org.json.JSONObject
import org.json.JSONTokener

/**
 * 媒体取址层的纯判定与纯构造。
 *
 * [MediaLinkSection] 里混着 WebView 脚本执行、CookieManager、Bitmap 与 AlertDialog，
 * 这些在 JVM 单测里都实例化不了，所以把「脚本长什么样」「结果该不该丢弃」
 * 「错误走哪个出口」「验证码弹窗能不能弹」抽到这里，Section 只保留接线。
 * 宿主状态一律用惰性 lambda，保留原 when 的短路顺序与调用次数。
 */

/**
 * 我的网盘下载签名脚本。sign1/sign2/sign3 是服务端原样下发的字符串，
 * 必须经 [JSONObject.quote] 转义后再嵌入 JS，否则服务端内容可以闭合字面量注入脚本。
 * 脚本自身再校验 location.origin，避免被挂到非网盘域名的隐藏 WebView 上执行。
 */
internal fun diskSignScript(sign1: String, sign2: String, sign3: String): String {
    val q1 = JSONObject.quote(sign1)
    val q2 = JSONObject.quote(sign2)
    val q3 = JSONObject.quote(sign3)
    return """
        (() => {
            if (location.origin !== 'https://pan.baidu.com') return '';
            try {
                const sign = new Function('return ' + $q2)();
                return btoa(sign($q3, $q1));
            } catch (_) {
                return '';
            }
        })()
    """.trimIndent()
}

/**
 * 隐藏 WebView 回传的签名值。返回 null 表示「签名没拿到，应当提示重登」，
 * 空串、非字符串、非法 JSON、null 原始值都归到这一类。
 */
internal fun parseSignResult(raw: String?): String? = runCatching {
    JSONTokener(raw.orEmpty()).nextValue() as? String
}.getOrNull()?.takeIf { it.isNotEmpty() }

/** 验证码弹窗的提示语：首次输入与重试文案不同。 */
internal fun captchaPrompt(retry: Boolean): String =
    if (retry) "验证码错误或已失效，请重新输入" else "请输入图片中的验证码"

/** 取址失败的错误出口：AUDIO 走音频页提示，其余 lane 走主界面状态栏。 */
internal enum class LinkErrorTarget { STATUS, AUDIO }

internal fun linkErrorTarget(lane: RequestLane): LinkErrorTarget = when (lane) {
    RequestLane.AUDIO -> LinkErrorTarget.AUDIO
    RequestLane.LIST, RequestLane.SEARCH, RequestLane.COURSE,
    RequestLane.RENEWAL, RequestLane.WRITE, RequestLane.DELETE_CHECK,
    RequestLane.DOWNLOAD -> LinkErrorTarget.STATUS
}

/**
 * 页面不可见，或已有一个验证码弹窗时，本次弹窗请求必须放弃。
 * 用惰性 lambda 保留原 `!isVisible() || dialog != null` 的短路：不可见时不去读弹窗状态。
 */
internal fun captchaBlocked(visible: () -> Boolean, dialogPresent: () -> Boolean): Boolean =
    !visible() || dialogPresent()

/** 返回非空表示答案不合法，直接写进 EditText.error，不要提交。 */
internal fun captchaAnswerError(value: String): String? =
    if (value.trim().isEmpty()) "请输入验证码" else null

/** 只允许关掉与当前 lane 匹配的弹窗，避免关掉别的链路正在用的验证码框。 */
internal fun shouldDismissCaptcha(current: RequestLane?, target: RequestLane): Boolean =
    current == target
