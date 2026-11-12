package com.hnwd.bdplayer

import android.content.SharedPreferences

/**
 * SharedPreferences 全表快照的进程内索引。
 *
 * `SharedPreferences.all` 每次调用都会把内部 mMap 完整拷贝一份（含大 JSON 值引用）。
 * directoryContaining / diskIndex / progressIndex / clearDisk 等在主线程反复调用它，
 * 历史欠账大时一次遍历就能卡掉一帧。这里按 prefs 实例缓存一份只读快照，靠
 * OnSharedPreferenceChangeListener 在写入后失效，命中期间零拷贝。
 *
 * 同步返回值与全部调用链保持不变；写入只经过 SharedPreferences API，单进程
 * MODE_PRIVATE 下 listener 覆盖所有变更。listener 由 Entry 强引用持有 ——
 * SharedPreferencesImpl 内部用弱引用存 listener，必须自己保活。
 */
internal object PrefIndex {
    private class Entry(prefs: SharedPreferences) {
        val index = SnapshotIndex { HashMap<String, Any?>(prefs.all) }
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ -> index.invalidate() }
    }

    // prefs 实例由 ContextImpl 静态缓存、生命周期同 App，这里强引用不会额外泄漏。
    private val entries = HashMap<SharedPreferences, Entry>()

    @Synchronized
    private fun entry(prefs: SharedPreferences): Entry = entries.getOrPut(prefs) {
        Entry(prefs).also { prefs.registerOnSharedPreferenceChangeListener(it.listener) }
    }

    /** 键集合只读视图（不拷贝）。 */
    @Synchronized
    fun keys(prefs: SharedPreferences): Set<String> = entry(prefs).index.keys()

    /** 全表快照只读视图（不拷贝）；供后台 prune、备份导出、迁移取值使用。 */
    @Synchronized
    fun snapshot(prefs: SharedPreferences): Map<String, Any?> = entry(prefs).index.snapshot()

    /** 单个值定向读取：迁移循环里不再每键触发一次整表拷贝（O(n²) → O(n)）。 */
    @Synchronized
    fun value(prefs: SharedPreferences, key: String): Any? = entry(prefs).index.value(key)

    /** 显式失效（写入不经过 SharedPreferences API 时的兜底，也便于排查）。 */
    @Synchronized
    fun invalidate(prefs: SharedPreferences) {
        entries[prefs]?.index?.invalidate()
    }
}
