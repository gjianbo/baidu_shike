package com.hnwd.bdplayer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TransferCleanupTest {

    @Test
    fun fileId_parsesNumericPrefixOnly() {
        assertEquals(1234567890123L, TransferCleanup.fileId("1234567890123-shike-reg-a.txt"))
        assertEquals(7L, TransferCleanup.fileId("7-a-b-c.bin"))
        assertNull(TransferCleanup.fileId("no-dash-prefix.txt"))
        assertNull(TransferCleanup.fileId("-leading-dash.txt"))
        assertNull(TransferCleanup.fileId("abc-file.txt"))
        assertNull(TransferCleanup.fileId(""))
        // 负号开头不是合法的任务 id 前缀，直接判为脏文件名
        assertNull(TransferCleanup.fileId("-1-file.txt"))
    }

    @Test
    fun staleFailedFiles_onlyDeletesFailedAndIdleFiles() {
        val names = listOf(
            "100-a.txt",   // failed + idle -> 删
            "200-b.txt",   // failed + active -> 保
            "300-c.txt",   // 非 failed -> 保
            "orphan.txt",  // 格式不符 -> 保
            "-9-d.bin",    // 负号开头格式不符 -> 保
            "100-a(1).txt", // 同一失败任务的另一个残留 -> 删
        )
        val result = TransferCleanup.staleFailedFiles(
            names, failedIds = setOf(100L, 200L, -9L), activeIds = setOf(200L, 400L),
        )
        assertEquals(listOf("100-a.txt", "100-a(1).txt"), result)
    }

    @Test
    fun staleFailedFiles_emptyInputsAreNoOp() {
        assertEquals(emptyList<String>(), TransferCleanup.staleFailedFiles(emptyList(), setOf(1L), emptySet()))
        assertEquals(
            emptyList<String>(),
            TransferCleanup.staleFailedFiles(listOf("1-a.txt"), emptySet(), emptySet()),
        )
    }

    @Test
    fun labelsCarryCounts() {
        assertEquals("清空已完成（3）", TransferCleanup.menuSuccessLabel(3))
        assertEquals("清理失败的记录（0）", TransferCleanup.menuFailedLabel(0))
        assertEquals("已清空 3 条已完成记录", TransferCleanup.clearSuccessMessage(3))
        assertEquals("没有已完成的记录", TransferCleanup.clearSuccessMessage(0))
    }

    @Test
    fun clearFailedMessageReflectsRecordsAndFiles() {
        assertEquals("没有失败的记录", TransferCleanup.clearFailedMessage(0, 0))
        assertEquals("已清理 2 条失败记录", TransferCleanup.clearFailedMessage(2, 0))
        assertEquals("已清理 2 条失败记录，删除 3 个残留文件", TransferCleanup.clearFailedMessage(2, 3))
    }
}
