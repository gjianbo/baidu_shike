package com.hnwd.bdplayer

import android.content.Context

/**
 * 打开 App 时读取剪贴板的节流状态。
 *
 * 规则：
 * - 同一条链接只提示一次，用户忽略后不再反复打扰；
 * - 只保留上一条链接的摘要；剪贴板换成不同链接就重新提示。
 */
internal object SharedLinkPrompt {
    private const val PREFS = "shared_link_prompt"
    private const val KEY_LAST_URL = "last_url"
    private const val KEY_LAST_DIGEST = "last_url_sha256"

    private fun digest(url: String): String = java.security.MessageDigest.getInstance("SHA-256")
        .digest(url.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }

    fun shouldPrompt(context: Context, url: String): Boolean {
        if (url.isBlank()) return false
        val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        var previous = prefs.getString(KEY_LAST_DIGEST, null)
        if (prefs.contains(KEY_LAST_URL)) {
            previous = previous ?: prefs.getString(KEY_LAST_URL, null)?.takeIf { it.isNotBlank() }?.let(::digest)
            prefs.edit().remove(KEY_LAST_URL).putString(KEY_LAST_DIGEST, previous).apply()
        }
        return previous != digest(url)
    }

    fun markPrompted(context: Context, url: String) {
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().remove(KEY_LAST_URL).putString(KEY_LAST_DIGEST, digest(url)).apply()
    }

    fun clear(context: Context) {
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().clear().apply()
    }
}
