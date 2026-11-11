package com.hnwd.bdplayer

import org.json.JSONObject

/**
 * 目录缓存淘汰的纯逻辑。[CourseCache.prune] 依赖 SharedPreferences（Android 框架），
 * 跑不了 JVM 单测，所以把「该删谁」和「什么时候删」抽到这里钉死边界。
 *
 * 淘汰单位是「目录组」而不是单页：readAll 从 page=1 逐页读、遇缺口就停，
 * 若按页删掉中间页，后面的页会变成永远读不到的孤儿数据。
 */
internal const val MAX_CACHED_GROUPS = 200
internal const val CACHE_KEEP_MS = 7 * 24 * 60 * 60 * 1000L
internal const val PRUNE_EVERY_WRITES = 50
internal const val PRUNE_INTERVAL_MS = 30 * 60 * 1000L

/** [collectCacheGroups] 的结果：组 -> 最新同步时间，组 -> 该组全部存储键。 */
internal data class CacheGroups(
    val syncedAt: Map<String, Long>,
    val keys: Map<String, List<String>>,
)

/**
 * 从 SharedPreferences 的原始键值聚合目录组。[prefix] 之外的账号缓存一概不碰，
 * 只认 `<目录>:<数字页码>` 格式的键；组内取所有页里最新的 syncedAt。
 * 脏 JSON / 非 String 值的页记为 0（不主动删，超限时才作为最旧淘汰）。
 */
internal fun collectCacheGroups(all: Map<String, Any?>, prefix: String): CacheGroups {
    val syncedAt = LinkedHashMap<String, Long>()
    val keys = LinkedHashMap<String, MutableList<String>>()
    for ((stored, value) in all) {
        if (!stored.startsWith(prefix)) continue
        if (stored.substringAfterLast(':').toIntOrNull() == null) continue
        val group = stored.removePrefix(prefix).substringBeforeLast(':')
        val at = (value as? String)?.let { raw ->
            runCatching { JSONObject(raw).optLong("syncedAt") }.getOrDefault(0L)
        } ?: 0L
        syncedAt[group] = maxOf(syncedAt[group] ?: 0L, at)
        keys.getOrPut(group) { mutableListOf() }.add(stored)
    }
    return CacheGroups(syncedAt, keys)
}

/**
 * 返回要整组删除的目录 key（不含账号前缀、不含 :page）。
 *
 * - 7 天没同步过的组直接淘汰（缓存新鲜度只有 30 分钟，留 7 天已经很宽松）。
 * - 其余按最近同步时间降序，只保留前 [maxGroups] 组。
 * - syncedAt <= 0 的脏数据不主动删，只在超限时作为「最旧」优先淘汰。
 * - 未来时间戳（时钟回拨）不算过期；同一时间戳按组名排序保证结果确定。
 */
internal fun groupsToEvict(
    syncedAt: Map<String, Long>,
    now: Long,
    maxGroups: Int = MAX_CACHED_GROUPS,
    keepMs: Long = CACHE_KEEP_MS,
): Set<String> {
    if (syncedAt.isEmpty()) return emptySet()
    val ordered = syncedAt.entries.sortedWith(
        compareByDescending<Map.Entry<String, Long>> { it.value }.thenBy { it.key },
    )
    val evict = LinkedHashSet<String>()
    ordered.forEach { (group, at) ->
        if (at > 0 && now - at > keepMs) evict.add(group)
    }
    val keepCount = maxGroups.coerceAtLeast(0)
    val alive = ordered.filter { it.key !in evict }
    alive.drop(keepCount).forEach { evict.add(it.key) }
    return evict
}

/** write() 后的淘汰节流：避免每次写都扫一遍 preferences.all（那正是卡顿来源）。 */
internal class PruneThrottle {
    private var writes = 0
    private var lastPruneAt = 0L

    /** 每次写入调用一次，返回是否该执行 prune。构造后首次写入一定触发，用于清历史欠账。 */
    @Synchronized
    fun onWrite(now: Long): Boolean {
        writes++
        if (now - lastPruneAt < PRUNE_INTERVAL_MS && writes < PRUNE_EVERY_WRITES) return false
        writes = 0
        lastPruneAt = now
        return true
    }
}
