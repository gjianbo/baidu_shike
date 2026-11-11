package com.hnwd.bdplayer

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream

/**
 * W6：导入备份时的内存防线。[BackupStore.read] 依赖 ContentResolver，跑不了 JVM 单测；
 * 但长度闸门与流式上限都是纯 JDK 逻辑，在这里钉死边界。
 */
class BackupStoreSizeTest {

    @Test
    fun `长度闸门放行未知长度和小文件`() {
        // SAF 的 UNKNOWN_LENGTH，拿不到长度时交给 readBounded 的流式上限兜底
        assertTrue(BackupStore.restoreSizeAllowed(-1L))
        assertTrue(BackupStore.restoreSizeAllowed(0L))
        assertTrue(BackupStore.restoreSizeAllowed(1L))
        assertTrue(BackupStore.restoreSizeAllowed(BackupStore.MAX_RESTORE_BYTES - 1))
        assertTrue(BackupStore.restoreSizeAllowed(BackupStore.MAX_RESTORE_BYTES))
    }

    @Test
    fun `长度闸门拒绝超上限的文件`() {
        assertFalse(BackupStore.restoreSizeAllowed(BackupStore.MAX_RESTORE_BYTES + 1))
        assertFalse(BackupStore.restoreSizeAllowed(100L * 1024 * 1024))
        assertFalse(BackupStore.restoreSizeAllowed(Long.MAX_VALUE))
    }

    @Test
    fun `硬上限是 5 MB`() {
        assertEquals(5, BackupStore.RESTORE_LIMIT_MB)
        assertEquals(5L * 1024 * 1024, BackupStore.MAX_RESTORE_BYTES)
    }

    @Test
    fun `小文件按原字节返回且 UTF-8 无损`() {
        val text = """{"app":"拾课备份"}"""
        val bytes = text.toByteArray(Charsets.UTF_8)
        val read = BackupStore.readBounded(ByteArrayInputStream(bytes))
        assertArrayEquals(bytes, read)
        assertEquals(text, String(read, Charsets.UTF_8))
    }

    @Test
    fun `空输入返回空数组`() {
        assertArrayEquals(
            ByteArray(0),
            BackupStore.readBounded(ByteArrayInputStream(ByteArray(0))),
        )
    }

    @Test
    fun `跨多次 read 的文件仍完整`() {
        val bytes = ByteArray(32 * 1024 + 7) { (it % 251).toByte() }
        assertArrayEquals(bytes, BackupStore.readBounded(ByteArrayInputStream(bytes)))
    }

    @Test
    fun `刚好等于上限可读取`() {
        val bytes = ByteArray(64) { 'a'.code.toByte() }
        assertArrayEquals(bytes, BackupStore.readBounded(ByteArrayInputStream(bytes), limit = 64))
    }

    @Test
    fun `超过上限立刻抛出专属异常`() {
        val bytes = ByteArray(65) { 'a'.code.toByte() }
        val error = assertThrows(BackupStore.BackupTooLargeException::class.java) {
            BackupStore.readBounded(ByteArrayInputStream(bytes), limit = 64)
        }
        assertTrue(error.message!!.contains("MB"))
    }

    @Test
    fun `默认上限下 5MB 加 1 字节被拦`() {
        val bytes = ByteArray(BackupStore.MAX_RESTORE_BYTES.toInt() + 1)
        assertThrows(BackupStore.BackupTooLargeException::class.java) {
            BackupStore.readBounded(ByteArrayInputStream(bytes))
        }
    }
}
