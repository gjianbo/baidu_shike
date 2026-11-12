package com.hnwd.bdplayer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UploadLogicTest {

    private val mb = 1024L * 1024
    private val gb = 1024L * mb

    @Test
    fun chunkCount_matchesWebUploaderFourMegabyteRule() {
        assertEquals(1, UploadLogic.chunkCount(0))
        assertEquals(1, UploadLogic.chunkCount(1))
        assertEquals(1, UploadLogic.chunkCount(4 * mb))
        assertEquals(2, UploadLogic.chunkCount(4 * mb + 1))
        assertEquals(2, UploadLogic.chunkCount(8 * mb))
        assertEquals(3, UploadLogic.chunkCount(10 * mb))
        assertEquals(1024, UploadLogic.chunkCount(4 * gb))
    }

    @Test
    fun chunkAt_givesOffsetAndRealLengthOfLastPart() {
        val size = 4 * mb + 100
        val first = UploadLogic.chunkAt(size, 0)
        val last = UploadLogic.chunkAt(size, 1)
        assertEquals(0, first.index)
        assertEquals(0L, first.offset)
        assertEquals((4 * mb).toInt(), first.length)
        assertEquals(1, last.index)
        assertEquals(4 * mb, last.offset)
        assertEquals(100, last.length)
    }

    @Test
    fun rapidEligible_onlyAbove256Kb() {
        assertFalse(UploadLogic.rapidEligible(256 * 1024))
        assertTrue(UploadLogic.rapidEligible(256 * 1024 + 1))
        assertFalse(UploadLogic.rapidEligible(0))
    }

    @Test
    fun vipLevelAndMaxBytes() {
        assertEquals(0, UploadLogic.vipLevel(""))
        assertEquals(0, UploadLogic.vipLevel("0"))
        assertEquals(1, UploadLogic.vipLevel("1"))
        assertEquals(2, UploadLogic.vipLevel("2"))
        assertEquals(2, UploadLogic.vipLevel("9"))
        assertEquals(4 * gb, UploadLogic.maxBytes("0"))
        assertEquals(10 * gb, UploadLogic.maxBytes("1"))
        assertEquals(20 * gb, UploadLogic.maxBytes("2"))
    }

    @Test
    fun plan_allowsSingleChunkAndRejectsOver1024Parts() {
        val small = UploadLogic.plan(4 * mb, "0")
        assertTrue(small.allowed)
        assertEquals(1, small.chunks)
        assertTrue(small.rapid)

        val edge = UploadLogic.plan(4 * gb, "0")
        assertTrue(edge.allowed)
        assertEquals(1024, edge.chunks)

        val tooMany = UploadLogic.plan(4 * gb + 1, "0")
        assertFalse(tooMany.allowed)
        assertTrue(tooMany.reason.contains("1024"))

        val vipBig = UploadLogic.plan(10 * gb, "1")
        assertFalse(vipBig.allowed)
        assertTrue(vipBig.reason.contains("1024"))
    }

    @Test
    fun createBlockList_usesReturnedMd5NotLocalOnes() {
        assertEquals("[\"a\",\"b\"]", UploadLogic.createBlockList(listOf("a", "b")))
        assertEquals("[]", UploadLogic.createBlockList(emptyList()))
    }

    @Test
    fun rtype_defaultsToRename() {
        assertEquals(UploadLogic.RTYPE_RENAME, UploadLogic.rtype(false))
        assertEquals(UploadLogic.RTYPE_OVERWRITE, UploadLogic.rtype(true))
    }

    @Test
    fun parsePrecreate_readsUploadIdAndNeededParts() {
        val pre = UploadLogic.parsePrecreate(
            org.json.JSONObject("{\"errno\":0,\"uploadid\":\"UID\",\"return_type\":1,\"block_list\":[0,2]}"),
        )
        assertEquals("UID", pre.uploadId)
        assertEquals(1, pre.returnType)
        assertEquals(setOf(0, 2), pre.neededParts)
    }

    @Test
    fun parseHelpers_rejectMissingFields() {
        assertEquals("abc", UploadLogic.parsePartMd5(org.json.JSONObject("{\"md5\":\"abc\"}")))
        assertEquals("c.pcs.baidu.com", UploadLogic.parseUploadHost(org.json.JSONObject("{\"host\":\"c.pcs.baidu.com\"}")))
        assertEquals(Pair("TOKEN", 0), UploadLogic.parseSession(
            org.json.JSONObject("{\"result\":{\"bdstoken\":\"TOKEN\",\"is_vip\":\"0\"}}"),
        ))
        assertTrue(runCatching { UploadLogic.parsePartMd5(org.json.JSONObject("{}")) }.isFailure)
        assertTrue(runCatching { UploadLogic.parseUploadHost(org.json.JSONObject("{}")) }.isFailure)
    }

    @Test
    fun partsToUpload_handlesRapidHitAndPartiallyMissingBlocks() {
        assertEquals(setOf(0, 1, 2), UploadLogic.partsToUpload(3, 1, emptySet()))
        assertEquals(setOf(1), UploadLogic.partsToUpload(3, 1, setOf(1, 99)))
        assertEquals(emptySet<Int>(), UploadLogic.partsToUpload(3, 2, setOf(0, 1)))
        assertEquals(emptySet<Int>(), UploadLogic.partsToUpload(3, 2, emptySet()))
    }

    @Test
    fun skippedBytes_countsServerSideBlocksAsProgressBaseline() {
        // 8MB 两片，服务端已有第 0 片：进度起点应是 4MB（50%），不是 0%。
        assertEquals(4 * mb, UploadLogic.skippedBytes(8 * mb, 2, setOf(1)))
        assertEquals(50, UploadLogic.progress(UploadLogic.skippedBytes(8 * mb, 2, setOf(1)), 8 * mb))
        // 秒传命中：全部跳过，起点即 100%。
        assertEquals(8 * mb, UploadLogic.skippedBytes(8 * mb, 2, emptySet()))
        assertEquals(0L, UploadLogic.skippedBytes(0, 1, emptySet()))
    }

    @Test
    fun cacheFileName_stripsPathSeparatorsAndStaysInCacheDir() {
        assertEquals("a_b.txt", UploadLogic.cacheFileName("a/b.txt"))
        assertEquals("_hidden", UploadLogic.cacheFileName("../hidden"))
        assertEquals("上传文件", UploadLogic.cacheFileName("  "))
        assertFalse(UploadLogic.cacheFileName("a/b/c").contains("/"))
        assertTrue(UploadLogic.cacheFileName("x".repeat(200)).length <= 80)
    }

    @Test
    fun progressAndErrorTexts() {
        assertEquals(-1, UploadLogic.progress(0, -1))
        assertEquals(50, UploadLogic.progress(50, 100))
        assertEquals(100, UploadLogic.progress(120, 100))
        assertTrue(UploadLogic.uploadError(2, "预上传").contains("block_list"))
        assertTrue(UploadLogic.uploadError(9019, "预上传").contains("9019"))
        assertEquals("4.0 GB", UploadLogic.formatBytes(4 * gb))
    }
}
