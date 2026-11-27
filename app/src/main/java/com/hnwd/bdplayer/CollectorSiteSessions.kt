package com.hnwd.bdplayer

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import java.io.File
import java.security.KeyStore
import java.util.Locale
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

internal enum class CollectorSite(
    val title: String,
    val loginUrl: String?,
    val domains: Set<String>,
    val cookieUrls: List<String>,
    val minExtensionVersion: Long = 1,
    val allowLegacyParser: Boolean = minExtensionVersion == 1L,
) {
    BILIBILI("哔哩哔哩 (B 站)", "https://passport.bilibili.com/login", setOf("bilibili.com"),
        listOf("https://www.bilibili.com/", "https://api.bilibili.com/")),
    DOUYIN("抖音", "https://www.douyin.com/", setOf("douyin.com", "iesdouyin.com"),
        listOf("https://www.douyin.com/", "https://www.iesdouyin.com/")),
    WEIBO("微博", "https://weibo.com/login.php", setOf("weibo.com", "weibo.cn"),
        listOf("https://weibo.com/", "https://m.weibo.cn/", "https://passport.weibo.com/"), 2),
    X("X", "https://x.com/i/flow/login", setOf("x.com", "twitter.com"),
        listOf("https://x.com/", "https://twitter.com/")),
    TIKTOK("TikTok", "https://www.tiktok.com/login", setOf("tiktok.com"),
        listOf("https://www.tiktok.com/"), 2),
    XIAOHONGSHU("小红书", "https://www.xiaohongshu.com/", setOf("xiaohongshu.com"),
        listOf("https://www.xiaohongshu.com/"), 3, allowLegacyParser = true),
    YOUTUBE("YouTube", null, setOf("youtube.com"), emptyList(), 3);

    fun allows(host: String): Boolean = domains.any { host == it || host.endsWith(".$it") }

    companion object {
        fun fromPlatform(platform: String): CollectorSite? = entries.firstOrNull { it.name == platform }
    }
}

internal object CollectorSiteCookies {
    private const val MAX_LENGTH = 32_768
    private const val HEADER = "# Netscape HTTP Cookie File\n"

    fun normalize(site: CollectorSite, text: String, now: Long = System.currentTimeMillis() / 1000): String? {
        require(text.length <= MAX_LENGTH) { "Cookie 文件太大" }
        val usable = mutableListOf<String>()
        for (line in text.lineSequence()) {
            val row = line.removePrefix("#HttpOnly_")
            if (line.isBlank() || (row == line && line.startsWith('#'))) continue
            val fields = row.split('\t')
            require(fields.size == 7 && fields.none { field -> field.any(Char::isISOControl) }) {
                "请粘贴 Netscape cookies.txt 格式的内容"
            }
            val domain = fields[0].removePrefix(".").lowercase(Locale.ROOT)
            require(site.allows(domain)) { "Cookie 中含有其他平台的域名" }
            val expiry = fields[4].ifEmpty { "0" }.toLongOrNull()
            require(fields[1] == if (fields[0].startsWith('.')) "TRUE" else "FALSE") { "Cookie 域名格式无效" }
            require(fields[2].startsWith('/') &&
                fields[3] in setOf("TRUE", "FALSE") && expiry != null && expiry >= 0 &&
                fields[5].isNotBlank()) { "Cookie 字段不完整或无效" }
            if (expiry == 0L || expiry > now) usable.add(line)
        }
        if (usable.isEmpty()) return null
        val normalized = usable.joinToString("\n", HEADER, "\n")
        require(normalized.length <= MAX_LENGTH) { "Cookie 文件太大" }
        return normalized
    }
}

internal object CollectorSiteSessions {
    enum class Origin { WEB, MANUAL }
    data class Session(val origin: Origin, val cookies: String?)

    private const val ALIAS = "collector_site_sessions"
    private const val TRANSFORMATION = "AES/GCM/NoPadding"
    private const val IV_LENGTH = 12

    @Synchronized
    fun read(context: Context, site: CollectorSite): Session? = runCatching {
        val data = file(context, site).readFully()
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, data, 0, IV_LENGTH))
        cipher.updateAAD(site.name.toByteArray())
        val saved = cipher.doFinal(data, IV_LENGTH, data.size - IV_LENGTH).toString(Charsets.UTF_8)
        Session(Origin.valueOf(saved.substringBefore('\n')),
            CollectorSiteCookies.normalize(site, saved.substringAfter('\n')))
    }.getOrNull()

    @Synchronized
    fun save(context: Context, site: CollectorSite, origin: Origin, cookies: String) {
        val payload = requireNotNull(CollectorSiteCookies.normalize(site, cookies)) { "没有可用的 Cookie" }
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key())
        cipher.updateAAD(site.name.toByteArray())
        val data = cipher.iv + cipher.doFinal("${origin.name}\n$payload".toByteArray())
        val target = file(context, site)
        val stream = target.startWrite()
        try {
            stream.write(data)
            target.finishWrite(stream)
        } catch (error: Exception) {
            target.failWrite(stream)
            throw error
        }
    }

    @Synchronized
    fun clear(context: Context, site: CollectorSite) {
        file(context, site).delete()
    }

    private fun file(context: Context, site: CollectorSite) =
        AtomicFile(File(context.noBackupFilesDir, "collector_sessions/${site.name}.bin"))

    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        val existing = store.getKey(ALIAS, null) as? SecretKey
        if (existing != null) return existing
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").run {
            init(KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build())
            generateKey()
        }
    }
}
