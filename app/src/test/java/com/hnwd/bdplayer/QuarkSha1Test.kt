package com.hnwd.bdplayer

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.MessageDigest
import java.util.Base64

class QuarkSha1Test {

    private fun reference(data: ByteArray): String {
        val digits = "0123456789abcdef"
        return buildString(40) {
            MessageDigest.getInstance("SHA-1").digest(data).forEach { byte ->
                val value = byte.toInt() and 0xff
                append(digits[value ushr 4])
                append(digits[value and 0x0f])
            }
        }
    }

    @Test
    fun digestMatchesMessageDigestAcrossBlockBoundaries() {
        val sizes = listOf(0, 1, 55, 56, 63, 64, 65, 119, 120, 128, 1024, 4096)
        sizes.forEach { size ->
            val data = ByteArray(size) { (it * 31 + 7).toByte() }
            assertEquals("size=$size", reference(data), QuarkSha1().apply { update(data) }.hexDigest())
        }
    }

    @Test
    fun incrementalUpdateMatchesOneShot() {
        val data = ByteArray(200_000) { (it * 17 + 3).toByte() }
        val oneShot = QuarkSha1().apply { update(data) }.hexDigest()
        val incremental = QuarkSha1()
        var offset = 0
        listOf(1, 63, 64, 65, 4096, 100_000).forEach { step ->
            if (offset + step <= data.size) {
                incremental.update(data, offset, step)
                offset += step
            }
        }
        incremental.update(data, offset, data.size - offset)
        assertEquals(reference(data), oneShot)
        assertEquals(oneShot, incremental.hexDigest())
    }

    @Test
    fun knownVectors() {
        assertEquals(
            "da39a3ee5e6b4b0d3255bfef95601890afd80709",
            QuarkSha1().hexDigest(),
        )
        assertEquals(
            "a9993e364706816aba3e25717850c26c9cd0d89d",
            QuarkSha1().apply { update("abc".toByteArray()) }.hexDigest(),
        )
    }

    @Test
    fun hashCtxCarriesSha1StateAndBitLength() {
        val sha1 = QuarkSha1()
        val first = ByteArray(64) { 'a'.code.toByte() }
        sha1.update(first)
        val decoded = String(Base64.getDecoder().decode(sha1.hashCtxBase64()))
        val json = JSONObject(decoded)
        assertEquals("sha1", json.getString("hash_type"))
        // 64 字节 → 512 bit；Nl 是累计比特数的低 32 位，Nh 为高位。
        assertEquals("512", json.getString("Nl"))
        assertEquals("0", json.getString("Nh"))
        assertEquals("", json.getString("data"))
        assertEquals("0", json.getString("num"))
        listOf("h0", "h1", "h2", "h3", "h4").forEach { key ->
            val value = json.getString(key).toLong()
            assertTrue("$key out of uint32: $value", value in 0..0xFFFFFFFFL)
        }
        // 再喂一片后累计比特数应为 (64+64)*8。
        sha1.update(ByteArray(64) { 'b'.code.toByte() })
        val json2 = JSONObject(String(Base64.getDecoder().decode(sha1.hashCtxBase64())))
        assertEquals("1024", json2.getString("Nl"))
    }

    @Test
    fun digestDoesNotCorruptIncrementalState() {
        val sha1 = QuarkSha1()
        sha1.update("abc".toByteArray())
        val before = sha1.hashCtxBase64()
        assertEquals("a9993e364706816aba3e25717850c26c9cd0d89d", sha1.hexDigest())
        // 对拍用摘要不能改变增量状态（否则后续 X-Oss-Hash-Ctx 会算错）。
        assertEquals(before, sha1.hashCtxBase64())
        sha1.update("def".toByteArray())
        assertEquals(reference("abcdef".toByteArray()), sha1.hexDigest())
    }

    @Test
    fun hashCtxChangesAfterEachChunk() {
        val sha1 = QuarkSha1()
        val a = sha1.hashCtxBase64()
        sha1.update(ByteArray(4 * 1024 * 1024) { 1 })
        val b = sha1.hashCtxBase64()
        sha1.update(ByteArray(4 * 1024 * 1024) { 2 })
        val c = sha1.hashCtxBase64()
        assertTrue(a != b && b != c)
    }
}
