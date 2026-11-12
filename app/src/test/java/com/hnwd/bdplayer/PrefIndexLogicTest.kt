package com.hnwd.bdplayer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.fail
import org.junit.Test

/** [SnapshotIndex] 的缓存 / 失效语义：不依赖 Android SharedPreferences，纯 JVM 可测。 */
class PrefIndexLogicTest {

    @Test
    fun `首次访问才加载 之后命中缓存`() {
        var loads = 0
        val index = SnapshotIndex {
            loads++
            mapOf("acct_a__dir:x:1" to "{\"syncedAt\":123}", "acct_a__dir:y:1" to 42L)
        }
        assertEquals(0, loads)
        assertEquals(2, index.keys().size)
        assertEquals("{\"syncedAt\":123}", index.value("acct_a__dir:x:1"))
        assertEquals(1, loads)
        assertEquals(setOf("acct_a__dir:x:1", "acct_a__dir:y:1"), index.keys())
        assertEquals(1, loads)
    }

    @Test
    fun `失效后重新加载并看到新数据`() {
        var data: Map<String, Any?> = mapOf("a" to 1)
        var loads = 0
        val index = SnapshotIndex { loads++; data }
        assertEquals(setOf("a"), index.keys())
        data = mapOf("b" to 2)
        assertEquals(setOf("a"), index.keys()) // 未失效前仍读旧快照
        index.invalidate()
        assertEquals(setOf("b"), index.keys())
        assertEquals(2, loads)
        assertNull(index.value("a"))
    }

    @Test
    fun `失效只换引用 旧视图仍可安全遍历`() {
        val index = SnapshotIndex { mapOf("a" to 1, "b" to 2, "c" to 3) }
        val oldKeys = index.keys()
        val oldSnapshot = index.snapshot()
        index.invalidate()
        assertEquals(setOf("a", "b", "c"), oldKeys)
        assertEquals(3, oldSnapshot.size)
        assertEquals(1, oldSnapshot["a"])
    }

    @Test
    fun `返回的 Map 与 Set 不可修改`() {
        val index = SnapshotIndex { mapOf("a" to 1) }
        @Suppress("UNCHECKED_CAST")
        val map = index.snapshot() as MutableMap<String, Any?>
        try {
            map["x"] = 9
            fail("snapshot 应不可修改")
        } catch (_: UnsupportedOperationException) {
            // 预期
        }
        @Suppress("UNCHECKED_CAST")
        val keys = index.keys() as MutableSet<String>
        try {
            keys.add("x")
            fail("keys 应不可修改")
        } catch (_: UnsupportedOperationException) {
            // 预期
        }
    }

    @Test
    fun `value 不额外触发加载`() {
        var loads = 0
        val index = SnapshotIndex { loads++; mapOf("k" to "v") }
        index.value("missing")
        assertEquals("v", index.value("k"))
        index.value("missing")
        assertEquals(1, loads)
    }
}
