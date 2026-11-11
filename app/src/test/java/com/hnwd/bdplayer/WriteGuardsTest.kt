package com.hnwd.bdplayer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 写操作互斥门 [WriteGuards]。
 *
 * 这条不变式是网盘写操作里唯一「错一次就删错文件」的地方：
 * 两笔删除同时跑，第二笔会拿着过期列表去比对 path -> fs_id，
 * 于是「核对通过」也可能指向已经变了的文件。
 *
 * 除了互斥本身，这里重点钉住 W1 的回归场景：账号切换后上一笔删除核对的
 * 回调仍会抵达，它必须能释放自己那把锁，又绝不能把新会话刚抢到的锁清掉。
 */
class WriteGuardsTest {

    @Test
    fun `初始状态空闲`() {
        val guards = WriteGuards()

        assertFalse(guards.busy)
        assertFalse(guards.writeInFlight)
        assertFalse(guards.deleteInFlight)
        assertEquals(0L, guards.writeToken)
        assertEquals(0L, guards.deleteToken)
    }

    @Test
    fun `开始写操作后其余入口都被挡住`() {
        val guards = WriteGuards()

        val lock = guards.beginWrite()
        assertNotNull(lock)
        assertTrue(guards.busy)
        assertTrue(guards.writeInFlight)
        assertNull("写操作进行中不能开始第二笔写", guards.beginWrite())
        assertNull("写操作进行中不能开始删除核对", guards.beginDelete())
        // 被拒绝的调用不能污染状态，也不能改动当前持锁 token
        assertTrue(guards.writeInFlight)
        assertFalse(guards.deleteInFlight)
        assertEquals(lock, guards.writeToken)
        assertEquals(0L, guards.deleteToken)
    }

    @Test
    fun `开始删除核对后写操作被挡住`() {
        val guards = WriteGuards()

        assertNotNull(guards.beginDelete())
        assertTrue(guards.deleteInFlight)
        assertNull("删除核对进行中不能提交写操作", guards.beginWrite())
        assertNull("删除核对进行中不能开始第二次核对", guards.beginDelete())
    }

    @Test
    fun `每次抢锁都发新 token 且 reset 后不复用`() {
        val guards = WriteGuards()

        val a = guards.beginWrite()!!
        assertTrue(guards.endWrite(a))
        val b = guards.beginDelete()!!
        assertNotEquals(a, b)
        assertTrue(guards.endDelete(b))
        guards.reset()
        val c = guards.beginWrite()!!
        assertNotEquals("reset 之后旧 token 不能被复用", b, c)
        assertTrue(guards.endWrite(c))
    }

    @Test
    fun `结束一个通道后可以重新开始`() {
        val guards = WriteGuards()

        val write = guards.beginWrite()!!
        assertTrue(guards.endWrite(write))
        assertFalse(guards.busy)

        val check = guards.beginDelete()!!
        assertTrue(guards.endDelete(check))
        assertFalse(guards.busy)

        assertNotNull(guards.beginWrite())
    }

    @Test
    fun `旧 token 不能释放后来者抢到的锁`() {
        val guards = WriteGuards()

        val old = guards.beginDelete()!!
        assertTrue(guards.endDelete(old))
        val fresh = guards.beginDelete()!!
        assertNotEquals(old, fresh)

        assertFalse("上一笔回调不能清掉新会话的锁", guards.endDelete(old))
        assertTrue(guards.deleteInFlight)
        assertEquals(fresh, guards.deleteToken)
        assertTrue(guards.endDelete(fresh))
    }

    @Test
    fun `账号切换后旧回调抵达不会放行第二笔删除`() {
        val guards = WriteGuards()

        val old = guards.beginDelete()!!
        guards.reset()                      // invalidateAccount() 的兜底
        val fresh = guards.beginDelete()!!  // 新会话开始核对

        assertFalse(guards.endDelete(old))  // 旧回调迟到
        assertNull("旧回调不能清掉新会话的锁，否则两笔核对会并行", guards.beginDelete())
        assertTrue(guards.endDelete(fresh))
    }

    @Test
    fun `失效回调不依赖 reset 也能释放自己那把锁`() {
        val guards = WriteGuards()

        val lock = guards.beginDelete()!!
        assertTrue(guards.endDelete(lock))
        assertFalse(guards.busy)
        assertEquals(0L, guards.deleteToken)
        assertFalse("重复释放必须被拒绝", guards.endDelete(lock))

        val fresh = guards.beginDelete()!!
        assertFalse(guards.endDelete(lock))
        assertTrue(guards.deleteInFlight)
        assertTrue(guards.endDelete(fresh))
    }

    @Test
    fun `错误 token 与错通道的释放都不会带走当前锁`() {
        val guards = WriteGuards()

        val lock = guards.beginWrite()!!
        assertFalse(guards.endWrite(0L))
        assertFalse(guards.endWrite(lock + 1_000))
        assertFalse("endDelete 不应清掉写锁", guards.endDelete(lock))
        assertTrue(guards.writeInFlight)
        assertEquals(lock, guards.writeToken)
        assertTrue(guards.endWrite(lock))
        assertFalse(guards.busy)
    }

    @Test
    fun `账号失效时锁与 token 一起清掉`() {
        val guards = WriteGuards()

        val first = guards.beginWrite()!!
        guards.reset()
        assertFalse(guards.busy)
        assertEquals(0L, guards.writeToken)
        assertEquals(0L, guards.deleteToken)
        assertFalse(guards.endWrite(first))

        // 清锁之后必须能重新开始，否则后续写操作会被永久挡住
        val fresh = guards.beginDelete()
        assertNotNull(fresh)
        assertNotEquals(first, fresh)
        assertTrue(guards.endDelete(fresh!!))
    }

    @Test
    fun `删除链路可以连续占用写与删除两个通道`() {
        // 实际顺序：核对删除（delete）-> 提交删除（write）-> 回读确认（delete）
        val guards = WriteGuards()

        val check = guards.beginDelete()!!
        assertTrue(guards.endDelete(check))
        val submit = guards.beginWrite()!!
        assertTrue(guards.endWrite(submit))
        val confirm = guards.beginDelete()!!
        assertTrue(guards.endDelete(confirm))

        assertFalse(guards.busy)
    }
}
