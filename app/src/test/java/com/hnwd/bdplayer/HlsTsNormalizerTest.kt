package com.hnwd.bdplayer

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class HlsTsNormalizerTest {

    /** 造一个标准 188 字节 TS 包；afc: 1/3 = 有 payload，2 = 仅 adaptation field，0 = reserved。 */
    private fun packet(pid: Int, afc: Int, cc: Int, pusi: Boolean = false): ByteArray {
        val b = ByteArray(188) { 0x55.toByte() }
        b[0] = 0x47.toByte()
        b[1] = (((if (pusi) 0x40 else 0) or ((pid shr 8) and 0x1F))).toByte()
        b[2] = (pid and 0xFF).toByte()
        b[3] = (((afc shl 4) or (cc and 0x0F))).toByte()
        if (afc == 2 || afc == 3) b[4] = 0 // adaptation_field_length = 0
        return b
    }

    private fun segment(vararg packets: ByteArray): ByteArray =
        packets.flatMap { it.toList() }.toByteArray()

    private fun cc(data: ByteArray, packetIndex: Int): Int =
        data[packetIndex * 188 + 3].toInt() and 0x0F

    @Test
    fun cc_isRebasedAcrossSegmentBoundaries() {
        // 每个 HLS 分片都是独立可播的 TS，CC 从 0 重启；拼接时必须接成一条连续流。
        // 归一化后的期望值是「全局重排」而不是「在原值上 +1」。
        val first = segment(packet(0x100, 1, 7), packet(0x100, 1, 8))
        val second = segment(packet(0x100, 1, 0), packet(0x100, 1, 1))

        val normalizer = HlsTsNormalizer()
        normalizer.normalize(first)
        normalizer.normalize(second)

        assertEquals(listOf(0, 1), listOf(cc(first, 0), cc(first, 1)))
        assertEquals(listOf(2, 3), listOf(cc(second, 0), cc(second, 1)))
    }

    @Test
    fun cc_wrapsAtFourBits() {
        val normalizer = HlsTsNormalizer()
        val seen = mutableListOf<Int>()
        repeat(18) { i ->
            val one = packet(0x100, 1, i and 0x0F)
            normalizer.normalize(one)
            seen += cc(one, 0)
        }
        assertEquals((0..15).toList() + listOf(0, 1), seen)
    }

    @Test
    fun adaptationOnlyPacket_doesNotAdvanceCc() {
        // PCR 常放在只有 adaptation field 的空包里，这类包不能让计数 +1。
        val data = segment(
            packet(0x100, 1, 5),
            packet(0x100, 2, 6),
            packet(0x100, 1, 7),
        )
        HlsTsNormalizer().normalize(data)

        assertEquals(listOf(0, 0, 1), listOf(cc(data, 0), cc(data, 1), cc(data, 2)))
        // 除第 4 字节的低 4 位外，adaptation field 内容原样保留
        assertEquals(0, data[188 + 4].toInt())
    }

    @Test
    fun nullPacket_isIgnored() {
        val data = segment(packet(0x1FFF, 1, 3), packet(0x100, 1, 9))
        HlsTsNormalizer().normalize(data)

        assertEquals(3, cc(data, 0)) // null packet 不参与计数
        assertEquals(0, cc(data, 1))
    }

    @Test
    fun counters_arePerPid() {
        val data = segment(
            packet(0x0, 1, 11),    // PAT
            packet(0x100, 1, 11),  // video
            packet(0x101, 1, 11),  // audio
            packet(0x0, 1, 12),
            packet(0x100, 1, 12),
            packet(0x101, 1, 12),
        )
        HlsTsNormalizer().normalize(data)

        assertEquals(listOf(0, 0, 0, 1, 1, 1), (0..5).map { cc(data, it) })
    }

    @Test
    fun onlyCcNibbleChanges_lengthAndPayloadPreserved() {
        val original = segment(packet(0x100, 1, 7), packet(0x101, 3, 2))
        val data = original.copyOf()
        HlsTsNormalizer().normalize(data)

        assertEquals(original.size, data.size)
        (original.indices).forEach { i ->
            if (i % 188 != 3) assertEquals("byte $i", original[i], data[i])
        }
        assertEquals(0x10, data[3].toInt() and 0xF0) // 高 4 位的 afc 不动
    }

    @Test
    fun tailBytesArePreservedWhenNotPacketAligned() {
        val tail = byteArrayOf(1, 2, 3, 4, 5)
        val head = packet(0x100, 1, 7)
        val data = head + tail
        HlsTsNormalizer().normalize(data)

        assertEquals(head.size + tail.size, data.size)
        assertEquals(0, cc(data, 0))
        assertArrayEquals(tail, data.copyOfRange(head.size, data.size))
    }

    @Test
    fun malformedInput_isLeftUntouchedAndDoesNotPoisonState() {
        val short = ByteArray(187) { 0x47.toByte() }
        val badSync = segment(packet(0x100, 1, 0), packet(0x100, 1, 1))
        badSync[188] = 0x00
        val fmp4 = "ftypisommoofmdat".toByteArray()
        listOf(short, badSync, fmp4).forEach { input ->
            val before = input.copyOf()
            val normalizer = HlsTsNormalizer()
            normalizer.normalize(input)
            assertArrayEquals(before, input)
            // 坏分片没被计入，下一片仍从头建立计数
            val good = packet(0x100, 1, 9)
            normalizer.normalize(good)
            assertEquals(0, cc(good, 0))
        }
    }

    @Test
    fun normalization_isDeterministic() {
        val make = { segment(packet(0x100, 1, 4), packet(0x100, 1, 5), packet(0x101, 1, 8)) }
        val a = HlsTsNormalizer().run { val s = make(); normalize(s); s }
        val b = HlsTsNormalizer().run { val s = make(); normalize(s); s }
        assertArrayEquals(a, b)
    }
}
