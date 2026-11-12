package com.hnwd.bdplayer

import android.content.SharedPreferences
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import org.json.JSONArray
import org.json.JSONObject
import androidx.core.content.edit

/** 缓存里的看图签名地址最多沿用 2 小时（短于夸克 3h / 百度 8h 的有效期）。 */
private const val PREVIEW_TTL_MS = 2 * 60 * 60 * 1000L

internal class CourseCache(private val preferences: SharedPreferences) {
    private val throttle = PruneThrottle()
    private val pruneInFlight = AtomicBoolean(false)

    fun readPage(key: String, page: Int): CachedPage? = runCatching {
        val raw = preferences.getString(pageKey(key, page), null) ?: return null
        val data = JSONObject(raw)
        val syncedAt = data.optLong("syncedAt")
        // 签名看图地址有时效（夸克 ≈3h / 百度 ≈8h），缓存过旧就丢弃，让打开时回落原文件链路
        val previewFresh = syncedAt > 0 && System.currentTimeMillis() - syncedAt < PREVIEW_TTL_MS
        val array = data.getJSONArray("entries")
        CachedPage(List(array.length()) { index ->
            val item = array.getJSONObject(index)
            PanEntry(
                item.getString("gid"), item.getString("message"), item.getString("owner"),
                item.getString("file"), item.getString("name"), item.optString("path"),
                item.optString("size"), item.optBoolean("directory"),
                runCatching { EntryOrigin.valueOf(item.optString("origin", "GROUP")) }
                    .getOrDefault(EntryOrigin.GROUP),
                item.optString("md5"),
                if (previewFresh) item.optString("preview") else "",
            )
        }, syncedAt, data.optString("nextCursor").takeIf { data.has("nextCursor") },
            data.optBoolean("latestEmpty"),
        )
    }.getOrNull()

    fun readAll(key: String): List<PanEntry> {
        val result = mutableListOf<PanEntry>()
        val seen = HashSet<String>()
        var page = 1
        while (true) {
            val cached = readPage(key, page++) ?: break
            cached.entries.forEach { entry ->
                val id = "${entry.gid}:${entry.messageId}:${entry.fileId}"
                if (seen.add(id)) result.add(entry)
            }
        }
        return result
    }

    fun directoryContaining(entry: PanEntry): String? {
        val prefix = AccountScope.prefix()
        val candidates = if (entry.isDisk) {
            PrefIndex.keys(preferences).asSequence()
                .filter { it.startsWith(prefix) }
                .map { it.removePrefix(prefix).substringBeforeLast(':') }
                .filter { it == "disk:roots" || it.startsWith("disk:dir:") }
                .distinct()
                .toList()
        } else {
            val directoryPrefix = "${prefix}dir:${entry.gid}:${entry.messageId}:"
            val nested = PrefIndex.keys(preferences).asSequence()
                .filter { it.startsWith(directoryPrefix) }
                .map { it.removePrefix(prefix).substringBeforeLast(':') }
                .distinct()
            (nested + sequenceOf("roots:${entry.gid}")).toList()
        }
        return candidates.firstOrNull { key ->
            readAll(key).any { idOf(it) == idOf(entry) }
        }
    }

    private fun write(
        key: String,
        page: Int,
        entries: List<PanEntry>,
        nextCursor: String?,
        latestEmpty: Boolean = false,
    ) {
        val data = JSONArray()
        entries.forEach {
            data.put(JSONObject()
                .put("gid", it.gid)
                .put("message", it.messageId)
                .put("owner", it.owner)
                .put("file", it.fileId)
                .put("name", it.name)
                .put("path", it.path)
                .put("size", it.size)
                .put("directory", it.directory)
                .put("origin", it.origin.name)
                .put("md5", it.md5)
                .put("preview", it.previewUrl))
        }
        preferences.edit {
            putString(
                pageKey(key, page),
                JSONObject().put("syncedAt", System.currentTimeMillis()).put("entries", data)
                    .put("nextCursor", nextCursor).put("latestEmpty", latestEmpty).toString(),
            )
        }
        maybePrune()
    }

    private fun maybePrune() {
        val now = System.currentTimeMillis()
        if (!throttle.onWrite(now)) return
        // 整表快照走 PrefIndex 只读索引，写入后自动失效、命中期间零拷贝；淘汰本身
        // 只是维护动作，返回值没有调用方消费，所以仍放后台，单飞标志防止连续写入
        // 堆出多个并发扫描。读缓存仍走同步路径，不受淘汰时序影响
        // （readPage 自带 TTL 判断，最坏只是多留一会儿过期页）。
        // prefix 必须在主线程快照：后台再读 AccountScope.current 可能已切账号。
        if (!pruneInFlight.compareAndSet(false, true)) return
        val prefix = AccountScope.prefix()
        AppIo.execute {
            try {
                prune(now, prefix)
            } finally {
                pruneInFlight.set(false)
            }
        }
    }

    /**
     * 按目录组淘汰：只清当前账号前缀，7 天未同步的组直接删，其余保留最近
     * [MAX_CACHED_GROUPS] 组。整组删除，避免留下 readAll 读不到的孤儿页。
     * 由 [maybePrune] 节流触发，也可由调用方显式触发；返回删掉的组数便于排查。
     * [prefix] 由调用方传入，便于后台执行时锁定触发那一刻的账号。
     */
    @Synchronized
    fun prune(now: Long = System.currentTimeMillis(), prefix: String = AccountScope.prefix()): Int {
        val plan = planCachePrune(PrefIndex.snapshot(preferences), prefix, now)
        if (plan.keys.isEmpty()) return 0
        preferences.edit {
            plan.keys.forEach { remove(it) }
        }
        return plan.groups
    }

    /** 签名看图地址每次请求都会重新签发，不能参与「目录内容是否变化」的判断。 */
    private fun List<PanEntry>.contentOnly() = map { it.copy(previewUrl = "") }

    fun writeIfChanged(key: String, page: Int, fresh: List<PanEntry>, nextCursor: String? = null): Boolean {
        val existing = readPage(key, page)
        write(key, page, fresh, nextCursor)
        if (existing == null) return true
        return existing.entries.contentOnly() != fresh.contentOnly() ||
            existing.nextCursor != nextCursor
    }

    /** 从指定目录缓存中移除若干条目（删除后立即从列表消失，避免异步任务落盘延迟）。 */
    fun removeEntries(key: String, entries: List<PanEntry>) {
        if (entries.isEmpty()) return
        val ids = entries.map(::idOf).toHashSet()
        var page = 1
        while (true) {
            val cached = readPage(key, page) ?: break
            val filtered = cached.entries.filterNot { idOf(it) in ids }
            if (filtered.size != cached.entries.size) {
                write(key, page, filtered, cached.nextCursor, cached.latestEmpty)
            }
            page++
        }
    }

    /** 已缓存网盘文件的内容指纹（md5:size），用于「已转存」匹配。 */
    fun diskIndex(): Set<String> {
        val prefix = AccountScope.prefix()
        val keys = PrefIndex.keys(preferences).asSequence()
            .filter { it.startsWith(prefix) }
            .map { it.removePrefix(prefix).substringBeforeLast(':') }
            .filter { it == "disk:roots" || it.startsWith("disk:dir:") }
            .distinct()
        val result = HashSet<String>()
        keys.forEach { key ->
            readAll(key).forEach { entry ->
                if (!entry.directory && entry.md5.isNotEmpty()) {
                    result.add("${PanApi.md5Key(entry.md5)}:${entry.size}")
                }
            }
        }
        return result
    }

    fun lastPage(key: String): Pair<Int, CachedPage>? {
        var last: Pair<Int, CachedPage>? = null
        var page = 1
        while (true) {
            val cached = readPage(key, page) ?: return last
            last = page to cached
            page++
        }
    }

    fun writeNewFirst(key: String, page: Int, fresh: List<PanEntry>, limit: Int = 200): Boolean {
        if (page != 1) return writeIfChanged(key, page, fresh)
        val old = readPage(key, page)
        val ids = fresh.map(::idOf).toHashSet()
        val entries = (fresh + old?.entries.orEmpty().filterNot { idOf(it) in ids }).take(limit)
        val latestEmpty = fresh.isEmpty()
        write(key, page, entries, nextCursor = null, latestEmpty = latestEmpty)
        if (old == null) return true
        return old.entries.contentOnly() != entries.contentOnly() || old.latestEmpty != latestEmpty
    }

    fun isFresh(key: String, ttlMillis: Long): Boolean {
        val first = readPage(key, 1) ?: return false
        if (first.syncedAt <= 0 || System.currentTimeMillis() - first.syncedAt >= ttlMillis) return false
        // 升级前的缓存页没有 preview 字段：视为不新鲜，强制重拉一次列表带回签名看图地址
        val raw = preferences.getString(pageKey(key, 1), null) ?: return false
        val entries = runCatching { JSONObject(raw).optJSONArray("entries") }.getOrNull() ?: return false
        return entries.length() == 0 || entries.optJSONObject(0)?.has("preview") == true
    }

    fun syncLabel(key: String): String {
        val at = readPage(key, 1)?.syncedAt ?: return ""
        if (at <= 0) return ""
        return SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(at))
    }

    /** 清除当前账号下所有「我的网盘」目录缓存（写操作成功后调用）。 */
    fun clearDisk() {
        val prefix = AccountScope.prefix()
        preferences.edit {
            PrefIndex.keys(preferences).filter { stored ->
                if (!stored.startsWith(prefix)) {
                    false
                } else {
                    val key = stored.removePrefix(prefix).substringBeforeLast(':')
                    key == "disk:roots" || key.startsWith("disk:dir:")
                }
            }.forEach { remove(it) }
        }
    }

    fun clear(key: String) = clearAfter(key, 0)

    fun clearAfter(key: String, page: Int) {
        val prefix = AccountScope.key("$key:")
        preferences.edit {
            PrefIndex.keys(preferences).filter { storedKey ->
                storedKey.startsWith(prefix) && (storedKey.removePrefix(prefix).toIntOrNull() ?: 0) > page
            }.forEach { remove(it) }
        }
    }

    private fun pageKey(key: String, page: Int) = AccountScope.key("$key:$page")

    private fun idOf(entry: PanEntry) =
        "${entry.origin}:${entry.sourceId}:${entry.gid}:${entry.messageId}:${entry.fileId}"
}

internal data class CachedPage(
    val entries: List<PanEntry>,
    val syncedAt: Long,
    val nextCursor: String?,
    val latestEmpty: Boolean,
)
