package com.hnwd.bdplayer

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * W5：目录缓存淘汰的边界。[CourseCache.prune] 本身依赖 SharedPreferences，
 * 这里钉死纯逻辑部分——该删哪些组、什么时候触发扫描。
 */
class CourseCachePruneTest {

    private val now = 1_700_000_000_000L

    /** i 越大越新：group-001 最旧，末尾那组 syncedAt == now。 */
    private fun groups(count: Int, step: Long = 1_000L): Map<String, Long> =
        (1..count).associate { i -> "dir:group-%03d".format(i) to now - (count - i) * step }

    @Test
    fun `阈值常量`() {
        assertEquals(200, MAX_CACHED_GROUPS)
        assertEquals(7 * 24 * 60 * 60 * 1000L, CACHE_KEEP_MS)
        assertEquals(50, PRUNE_EVERY_WRITES)
        assertEquals(30 * 60 * 1000L, PRUNE_INTERVAL_MS)
    }

    @Test
    fun `空缓存不淘汰`() {
        assertTrue(groupsToEvict(emptyMap(), now).isEmpty())
    }

    @Test
    fun `未超限且未过期时全部保留`() {
        assertTrue(groupsToEvict(groups(5), now).isEmpty())
    }

    @Test
    fun `恰好 200 组不淘汰`() {
        assertTrue(groupsToEvict(groups(MAX_CACHED_GROUPS), now).isEmpty())
    }

    @Test
    fun `201 组只淘汰最旧的一组`() {
        assertEquals(setOf("dir:group-001"), groupsToEvict(groups(MAX_CACHED_GROUPS + 1), now))
    }

    @Test
    fun `超限时只淘汰最旧的几组且最新组一定保留`() {
        val evict = groupsToEvict(groups(MAX_CACHED_GROUPS + 3), now)
        assertEquals(3, evict.size)
        assertTrue(evict.containsAll(listOf("dir:group-001", "dir:group-002", "dir:group-003")))
        assertFalse(evict.contains("dir:group-203"))
    }

    @Test
    fun `7 天没同步的组即使总数不超限也淘汰`() {
        val data = mapOf("fresh" to now - 1_000L, "stale" to now - CACHE_KEEP_MS - 1)
        assertEquals(setOf("stale"), groupsToEvict(data, now))
    }

    @Test
    fun `刚好满 7 天的组仍保留`() {
        assertTrue(groupsToEvict(mapOf("edge" to now - CACHE_KEEP_MS), now).isEmpty())
    }

    @Test
    fun `无同步时间的脏组不主动删但超限时优先淘汰`() {
        val data = linkedMapOf("dirty" to 0L, "fresh" to now)
        assertTrue(groupsToEvict(data, now).isEmpty())
        assertEquals(setOf("dirty"), groupsToEvict(data, now, maxGroups = 1))
    }

    @Test
    fun `未来时间戳不算过期`() {
        val data = mapOf("future" to now + 10 * CACHE_KEEP_MS, "stale" to now - CACHE_KEEP_MS - 1)
        assertEquals(setOf("stale"), groupsToEvict(data, now))
    }

    @Test
    fun `相同时间戳按组名稳定排序`() {
        val data = (1..3).associate { "g$it" to now }
        assertEquals(setOf("g3"), groupsToEvict(data, now, maxGroups = 2))
    }

    @Test
    fun `过期组也计入淘汰集合但不影响最新组`() {
        val data = groups(MAX_CACHED_GROUPS + 2).toMutableMap()
        data["ancient"] = now - CACHE_KEEP_MS - 1
        val evict = groupsToEvict(data, now)
        assertTrue(evict.contains("ancient"))
        assertFalse(evict.contains("dir:group-202"))
    }

    // ===================== 触发节流 =====================

    @Test
    fun `首次写入触发一次清理历史欠账`() {
        assertTrue(PruneThrottle().onWrite(now))
    }

    @Test
    fun `未到阈值不重复清理`() {
        val throttle = PruneThrottle()
        assertTrue(throttle.onWrite(now))
        for (i in 2 until PRUNE_EVERY_WRITES) {
            assertFalse(throttle.onWrite(now + i.toLong()))
        }
    }

    @Test
    fun `写满 50 次触发清理`() {
        val throttle = PruneThrottle()
        assertTrue(throttle.onWrite(now))
        for (i in 1 until PRUNE_EVERY_WRITES) {
            assertFalse(throttle.onWrite(now + i.toLong()))
        }
        assertTrue(throttle.onWrite(now + PRUNE_EVERY_WRITES.toLong()))
    }

    @Test
    fun `满 30 分钟即使只写一次也触发`() {
        val throttle = PruneThrottle()
        assertTrue(throttle.onWrite(now))
        assertFalse(throttle.onWrite(now + 1))
        assertTrue(throttle.onWrite(now + PRUNE_INTERVAL_MS))
    }

    @Test
    fun `触发后计数重置`() {
        val throttle = PruneThrottle()
        assertTrue(throttle.onWrite(now))
        for (i in 1 until PRUNE_EVERY_WRITES) throttle.onWrite(now + i.toLong())
        assertTrue(throttle.onWrite(now + PRUNE_EVERY_WRITES.toLong()))
        assertFalse(throttle.onWrite(now + PRUNE_EVERY_WRITES.toLong() + 1))
    }

    // ===================== 键聚合接线 =====================

    private val prefix = "acct_10086__"

    private fun page(group: String, n: Int, at: Long): Pair<String, Any?> =
        "${prefix}$group:$n" to JSONObject().put("syncedAt", at).toString()

    @Test
    fun `聚合只认当前账号前缀且只认数字页码`() {
        val all: Map<String, Any?> = mapOf(
            page("dir:a", 1, now - 1_000),
            "${prefix}dir:a:abc" to "{}",                       // 页码不是数字
            "${prefix}noPageSuffix" to "{}",                    // 根本没有页码
            "acct_20002__dir:b:1" to """{"syncedAt":$now}""",  // 别的账号
        )
        val groups = collectCacheGroups(all, prefix)
        assertEquals(mapOf("dir:a" to now - 1_000), groups.syncedAt)
        assertEquals(listOf("${prefix}dir:a:1"), groups.keys["dir:a"])
    }

    @Test
    fun `同组多页取最新同步时间且整组键都列出`() {
        val all = mapOf(
            page("dir:a", 1, now - 5_000),
            page("dir:a", 2, now - 2_000),
            page("dir:a", 3, now),
            page("roots:gid", 1, now - 9_000),
        )
        val groups = collectCacheGroups(all, prefix)
        assertEquals(now, groups.syncedAt["dir:a"])
        assertEquals(3, groups.keys["dir:a"]?.size)
        assertEquals(setOf("dir:a", "roots:gid"), groups.syncedAt.keys)
    }

    @Test
    fun `脏 JSON 或非字符串值记为 0 但组仍保留`() {
        val all: Map<String, Any?> = mapOf(
            "${prefix}dir:a:1" to "not json",
            "${prefix}dir:b:1" to 123L,
            page("dir:c", 1, now),
        )
        val groups = collectCacheGroups(all, prefix)
        assertEquals(0L, groups.syncedAt["dir:a"])
        assertEquals(0L, groups.syncedAt["dir:b"])
        assertEquals(now, groups.syncedAt["dir:c"])
        assertEquals(3, groups.keys.size)
    }

    @Test
    fun `删整组时该组所有页都在清单里`() {
        val all = (1..3).associate { page("dir:old", it, now - CACHE_KEEP_MS - 1) } +
            mapOf(page("dir:fresh", 1, now))
        val groups = collectCacheGroups(all, prefix)
        assertEquals(setOf("dir:old"), groupsToEvict(groups.syncedAt, now))
        assertEquals(3, groups.keys["dir:old"]?.size)
        assertTrue(groups.keys["dir:fresh"] != null)
    }
}
