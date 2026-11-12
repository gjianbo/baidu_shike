package com.hnwd.bdplayer

import org.json.JSONObject
import java.util.Base64

/**
 * 可导出「内部中间状态」的增量 SHA1，用于夸克 OSS parallel multipart 的 `X-Oss-Hash-Ctx`。
 *
 * Java 的 `MessageDigest` 只给最终摘要（经过 padding/finalization），而 OSS 要的是
 * **未经 finalization 的 h0–h4 与累计比特数**（等价于 Go `sha1.New().MarshalBinary()` 的
 * `magic(4B) + h[0..4](20B 大端) + x(64B) + len(8B)` 中的 h 与 len）。
 *
 * 本类自己实现 SHA1 压缩函数，因此可以在任意分片边界把状态导出；纯 Kotlin、无 Android 依赖，
 * 可在 JVM 单测里与 `MessageDigest` 对拍验证。
 */
internal class QuarkSha1 {
    private var h0 = 0x67452301u.toInt()
    private var h1 = 0xEFCDAB89u.toInt()
    private var h2 = 0x98BADCFEu.toInt()
    private var h3 = 0x10325476
    private var h4 = 0xC3D2E1F0u.toInt()

    private val w = IntArray(80)
    private val buffer = ByteArray(64)
    private var buffered = 0
    private var total = 0L

    fun update(data: ByteArray, offset: Int = 0, length: Int = data.size) {
        require(offset >= 0 && length >= 0 && offset + length <= data.size)
        total += length.toLong()
        var pos = offset
        var left = length
        if (buffered > 0) {
            val take = minOf(64 - buffered, left)
            System.arraycopy(data, pos, buffer, buffered, take)
            buffered += take
            pos += take
            left -= take
            if (buffered == 64) {
                compress(buffer, 0)
                buffered = 0
            }
        }
        while (left >= 64) {
            compress(data, pos)
            pos += 64
            left -= 64
        }
        if (left > 0) {
            System.arraycopy(data, pos, buffer, 0, left)
            buffered = left
        }
    }

    private fun compress(block: ByteArray, off: Int) {
        for (i in 0..15) {
            val p = off + i * 4
            w[i] = (block[p].toInt() and 0xff shl 24) or
                (block[p + 1].toInt() and 0xff shl 16) or
                (block[p + 2].toInt() and 0xff shl 8) or
                (block[p + 3].toInt() and 0xff)
        }
        for (i in 16..79) {
            w[i] = rotl(w[i - 3] xor w[i - 8] xor w[i - 14] xor w[i - 16], 1)
        }
        var a = h0
        var b = h1
        var c = h2
        var d = h3
        var e = h4
        for (i in 0..79) {
            val (f, k) = when {
                i < 20 -> ((b and c) or (b.inv() and d)) to 0x5A827999
                i < 40 -> (b xor c xor d) to 0x6ED9EBA1
                i < 60 -> ((b and c) or (b and d) or (c and d)) to 0x8F1BBCDCu.toInt()
                else -> (b xor c xor d) to 0xCA62C1D6u.toInt()
            }
            val temp = rotl(a, 5) + f + e + k + w[i]
            e = d
            d = c
            c = rotl(b, 30)
            b = a
            a = temp
        }
        h0 += a
        h1 += b
        h2 += c
        h3 += d
        h4 += e
    }

    private fun rotl(value: Int, bits: Int): Int = (value shl bits) or (value ushr (32 - bits))

    private fun unsigned(value: Int): String = (value.toLong() and 0xFFFFFFFFL).toString()

    /**
     * `X-Oss-Hash-Ctx` 的值：base64(紧凑 JSON)。
     * 字段大小写与真实浏览器请求一致（hash_type/h0..h4 小写，Nl/Nh 大写开头）。
     * Nl/Nh 是「累计已处理字节数 × 8」的比特数低/高 32 位，不是字节数。
     */
    fun hashCtxBase64(): String {
        val bits = total * 8
        val json = JSONObject()
            .put("hash_type", "sha1")
            .put("h0", unsigned(h0))
            .put("h1", unsigned(h1))
            .put("h2", unsigned(h2))
            .put("h3", unsigned(h3))
            .put("h4", unsigned(h4))
            .put("Nl", unsigned((bits and 0xFFFFFFFFL).toInt()))
            .put("Nh", unsigned((bits ushr 32).toInt()))
            .put("data", "")
            .put("num", "0")
        return Base64.getEncoder().encodeToString(json.toString().toByteArray(Charsets.UTF_8))
    }

    /** 供 JVM 单测与 MessageDigest 对拍；在副本上做 finalization，不污染本对象的增量状态。 */
    fun digest(): ByteArray = snapshot().finalized()

    private fun snapshot(): QuarkSha1 = QuarkSha1().apply {
        h0 = this@QuarkSha1.h0
        h1 = this@QuarkSha1.h1
        h2 = this@QuarkSha1.h2
        h3 = this@QuarkSha1.h3
        h4 = this@QuarkSha1.h4
        System.arraycopy(this@QuarkSha1.buffer, 0, buffer, 0, 64)
        buffered = this@QuarkSha1.buffered
        total = this@QuarkSha1.total
    }

    /** padding + finalization 只能在副本上调用：它会 update() 改变 total / h0–h4。 */
    private fun finalized(): ByteArray {
        val bitLen = total * 8
        val tail = ByteArray(72)
        tail[0] = 0x80.toByte()
        var padLen = 1
        while ((buffered + padLen) % 64 != 56) padLen++
        for (i in 0..7) {
            tail[padLen + i] = ((bitLen ushr (56 - i * 8)) and 0xffL).toByte()
        }
        update(tail, 0, padLen + 8)
        val out = ByteArray(20)
        listOf(h0, h1, h2, h3, h4).forEachIndexed { index, value ->
            out[index * 4] = (value ushr 24).toByte()
            out[index * 4 + 1] = (value ushr 16).toByte()
            out[index * 4 + 2] = (value ushr 8).toByte()
            out[index * 4 + 3] = value.toByte()
        }
        return out
    }

    fun hexDigest(): String {
        val digits = "0123456789abcdef"
        return buildString(40) {
            digest().forEach { byte ->
                val value = byte.toInt() and 0xff
                append(digits[value ushr 4])
                append(digits[value and 0x0f])
            }
        }
    }
}
