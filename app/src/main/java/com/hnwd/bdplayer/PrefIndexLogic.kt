package com.hnwd.bdplayer

import java.util.Collections

/**
 * SharedPreferences 全表快照的「失效式」缓存（纯逻辑，便于 JVM 单测）。
 *
 * [load] 只在首次访问与 [invalidate] 之后执行；浏览目录时 keys() 被反复调用，
 * 命中缓存后不再触发整表拷贝。失效只整体替换引用、不原地清空，因此调用方持有的
 * 旧只读视图在下一次写入后仍可安全遍历（不会 ConcurrentModificationException）。
 */
internal class SnapshotIndex(private val load: () -> Map<String, Any?>) {
    private var cached: Map<String, Any?>? = null
    private var cachedKeys: Set<String>? = null

    fun snapshot(): Map<String, Any?> = cached ?: run {
        val loaded = Collections.unmodifiableMap(HashMap(load()))
        cached = loaded
        loaded
    }

    fun keys(): Set<String> = cachedKeys ?: run {
        val loaded = Collections.unmodifiableSet(HashSet(snapshot().keys))
        cachedKeys = loaded
        loaded
    }

    fun value(key: String): Any? = snapshot()[key]

    fun invalidate() {
        cached = null
        cachedKeys = null
    }
}
