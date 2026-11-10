package com.hnwd.bdplayer

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 写操作互斥门 [WriteGuards]。
 *
 * 这条不变式是网盘写操作里唯一「错一次就删错文件」的地方：
 * 两笔删除同时跑，第二笔会拿着过期列表去比对 path -> fs_id，
 * 于是「核对通过」也可能指向已经变了的文件。
 */
class WriteGuardsTest {

    @Test
    fun `初始状态空闲`() {
        val guards = WriteGuards()

        assertFalse(guards.busy)
        assertFalse(guards.writeInFlight)
        assertFalse(guards.deleteInFlight)
    }

    @Test
    fun `开始写操作后其余入口都被挡住`() {
        val guards = WriteGuards()

        assertTrue(guards.beginWrite())
        assertTrue(guards.busy)
        assertTrue(guards.writeInFlight)
        assertFalse("写操作进行中不能开始第二笔写", guards.beginWrite())
        assertFalse("写操作进行中不能开始删除核对", guards.beginDelete())
        // 被拒绝的调用不能污染状态
        assertTrue(guards.writeInFlight)
        assertFalse(guards.deleteInFlight)
    }

    @Test
    fun `开始删除核对后写操作被挡住`() {
        val guards = WriteGuards()

        assertTrue(guards.beginDelete())
        assertTrue(guards.deleteInFlight)
        assertFalse("删除核对进行中不能提交写操作", guards.beginWrite())
        assertFalse("删除核对进行中不能开始第二次核对", guards.beginDelete())
    }

    @Test
    fun `结束一个通道后可以重新开始`() {
        val guards = WriteGuards()

        assertTrue(guards.beginWrite())
        guards.endWrite()
        assertFalse(guards.busy)

        assertTrue(guards.beginDelete())
        guards.endDelete()
        assertFalse(guards.busy)

        assertTrue(guards.beginWrite())
    }

    @Test
    fun `结束未占用的通道是幂等的`() {
        val guards = WriteGuards()

        guards.endWrite()
        guards.endDelete()

        assertFalse(guards.busy)
        // 不能因为误调 end 就把另一把锁带走
        assertTrue(guards.beginWrite())
        guards.endDelete()
        assertTrue("endDelete 不应清掉写锁", guards.writeInFlight)
    }

    @Test
    fun `账号失效时两把锁一起清掉`() {
        val guards = WriteGuards()
        assertTrue(guards.beginWrite())
        guards.reset()
        assertFalse(guards.busy)
        // 清锁之后必须能重新开始，否则后续写操作会被永久挡住
        assertTrue(guards.beginWrite())

        guards.reset()
        assertTrue(guards.beginDelete())
    }

    @Test
    fun `删除链路可以连续占用写与删除两个通道`() {
        // 实际顺序：核对删除（delete）-> 提交删除（write）-> 回读确认（delete）
        val guards = WriteGuards()

        assertTrue(guards.beginDelete())
        guards.endDelete()
        assertTrue(guards.beginWrite())
        guards.endWrite()
        assertTrue(guards.beginDelete())
        guards.endDelete()

        assertFalse(guards.busy)
    }
}
