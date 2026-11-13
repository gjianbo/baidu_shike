package com.hnwd.bdplayer

import android.webkit.CookieManager
import java.io.IOException
import java.security.MessageDigest

/** Credentials belong to one execution and are never written to the task record. */
internal class TransferSession private constructor(
    val provider: SourceProvider,
    val cookie: String,
) {
    fun quarkAccount(): String {
        check(provider == SourceProvider.QUARK)
        return quarkSessionId(cookie) ?: throw Failure("请登录夸克网盘后重试")
    }

    class Failure(message: String) : IOException(message)

    companion object {
        fun capture(provider: SourceProvider): TransferSession {
            val url = when (provider) {
                SourceProvider.BAIDU -> "https://pan.baidu.com"
                SourceProvider.QUARK -> "${QuarkUploadLogic.API}/file/sort"
            }
            val cookie = CookieManager.getInstance().getCookie(url).orEmpty()
            if (cookie.isBlank() || provider == SourceProvider.QUARK && !QuarkPersonalDrive.hasLoginCookie(cookie)) {
                throw Failure("请登录${providerName(provider)}网盘后重试")
            }
            return TransferSession(provider, cookie)
        }

        fun providerName(provider: SourceProvider): String = when (provider) {
            SourceProvider.BAIDU -> "百度"
            SourceProvider.QUARK -> "夸克"
        }

        fun requireAccount(provider: SourceProvider, expected: String, actual: String) {
            if (expected.isBlank() || actual.isBlank() || expected != actual) {
                val message = when (provider) {
                    SourceProvider.BAIDU -> "请切回添加此任务时的百度网盘账号后重试"
                    SourceProvider.QUARK -> "夸克网盘登录会话已变更，请确认账号后重新添加任务"
                }
                throw Failure(message)
            }
        }

        /** Ignore browser metadata and bind both credentials when the site supplies both. */
        fun quarkSessionId(cookie: String): String? {
            val credentials = cookie.split(';').map(String::trim).filter {
                it.substringBefore('=') in setOf("__pus", "__puus") && it.substringAfter('=', "").isNotBlank()
            }.sorted()
            if (credentials.isEmpty()) return null
            val digest = MessageDigest.getInstance("SHA-256")
                .digest(credentials.joinToString(";").toByteArray(Charsets.UTF_8))
            return digest.joinToString("") { "%02x".format(it) }
        }
    }
}
