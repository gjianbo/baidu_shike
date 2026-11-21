package com.hnwd.bdplayer

import org.json.JSONObject

/**
 * 「转存到我的网盘再下载」的可行性判定与容量闸门（纯逻辑，可 JVM 单测）。
 * 实测结论见 `docs/百度网盘转存后下载可行性-实测结论.md`。
 *
 * 为什么需要它：群组视频的原文件直链在群组侧拿不到（`list` 是加密串），但**转存之后**
 * 走「我的网盘」的 `/api/download` 就能拿到 `d.pcs.baidu.com/file/...` 明文直链。
 * 这条路对**小文件**实测可下（302 到 CDN 拿到真实字节），对**大文件**则被服务端
 * 按 `31360 expire time out error` 限流（dlink 只给 1h 且 dstime 比签发时间早 2 小时，
 * 签发即过期）。所以它不是无条件可用：必须先看容量，再对大文件保留流式兜底。
 */
internal object GroupTransferLogic2 {

    /**
     * 转存前的空间闸门。
     *
     * 需要留的安全余量按「本次转存总大小」的 5% 与 64 MB 取大者：
     * 转存是异步落盘，服务端在提交后还会做一次容量校验，卡着 0 字节余量提交容易半途失败。
     */
    fun hasRoomFor(freeBytes: Long, neededBytes: Long): Boolean =
        freeBytes > 0 && neededBytes >= 0 && freeBytes - neededBytes >= reserveFor(neededBytes)

    fun reserveFor(neededBytes: Long): Long = maxOf(neededBytes / 20, 64L * 1024 * 1024)

    /** `/api/quota` 的解析：只取 free/total/used，任一缺失都当「未知」而不是 0。 */
    data class Quota(val freeBytes: Long, val totalBytes: Long, val usedBytes: Long)

    fun parseQuota(json: JSONObject): Quota? {
        if (json.optInt("errno", -1) != 0) return null
        val free = json.optLong("free", -1)
        val total = json.optLong("total", -1)
        if (free < 0 || total <= 0) return null
        return Quota(freeBytes = free, totalBytes = total, usedBytes = json.optLong("used", total - free))
    }

    /**
     * 「转存后直链是否可用」的判定不按大小猜，而是看 dlink 本身的两个可测特征。
     *
     * 实测两个样本（同账号、同一签名）：
     * · 11.9 KB JSON → `expires=8h`、skew=0   → 带 Cookie 302 到 CDN，**能下**
     * · 160.7 MB mp4 → `expires=1h`、skew=7200 → 31360 `expire time out error`，**不能下**
     *
     * skew = `timestamp - dstime`，固定 7200 秒（正好 2 小时），是百度对「不给直链」的
     * 文件加的时间戳偏移 —— 签发即过期。所以只要下到 dlink 后检查这两个值，
     * 就能在**不下载试错**的前提下判断能不能用；比按文件大小猜可靠得多。
     */
    const val DLINK_SKEW_LIMIT_SECONDS = 60L

    /**
     * 解析 dlink 的可用性特征。返回 null 表示 URL 形状不认识（不赌）。
     * [usable] = 没有被加偏移，且有效期不是被压到 1h。
     */
    data class DlinkShape(val expires: String, val skewSeconds: Long, val usable: Boolean)

    fun inspectDlink(dlink: String, signTimestamp: Long): DlinkShape? {
        val query = dlink.substringAfter('?', "").ifEmpty { return null }
        val params = query.split('&').mapNotNull { pair ->
            val key = pair.substringBefore('=')
            if (key.isEmpty()) null else key to pair.substringAfter('=', "")
        }.toMap()
        val expires = params["expires"] ?: return null
        val dstime = params["dstime"]?.toLongOrNull() ?: return null
        val skew = if (signTimestamp > 0) signTimestamp - dstime else 0L
        // 被加了偏移（skew 明显为正）或有效期被压到 1h，都说明服务端不给这条直链。
        val usable = skew <= DLINK_SKEW_LIMIT_SECONDS && expires != "1h"
        return DlinkShape(expires = expires, skewSeconds = skew, usable = usable)
    }

    /**
     * 转存前的粗筛：只要求「是群组文件 + 空间够」。
     *
     * 不再按文件大小预判能否拿到直链 —— 大小不是可靠判据（见 [DLINK_SKEW_LIMIT_SECONDS]）。
     * 真正的可用性在拿到 dlink 后用 [inspectDlink] 判定，不可用立即放弃转存路径，
     * 代价只是多一次转存请求，换来的是不误杀能下的文件。
     */
    fun canPrefetch(entry: PanEntry, freeBytes: Long): Boolean =
        groupTransferEligible(entry) && !entry.directory &&
            entry.size.toLongOrNull()?.let { size -> hasRoomFor(freeBytes, size) } ?: false

    /** 转存后的落盘目录：固定一个专用目录，避免污染网盘根目录、也便于清理。 */
    const val STAGING_DIR = "/拾课转存"

    /** 只有专用目录下的文件才有可能被自动清理；别处一律不碰。 */
    fun isStagingPath(entry: PanEntry): Boolean =
        entry.path.startsWith("$STAGING_DIR/") && entry.fileId.isNotBlank() && !entry.directory

    /**
     * 是否「本次转存刚产生」的副本：必须在专用目录下，且不在提交前的目录快照里。
     *
     * 没有这道过滤，[matchTransferred] 的模糊匹配会选中用户早就放在该目录的同名文件
     * （例如异步落盘还没完成时，目录里原本就有 `xxx(1).mp4`），后续清理就会误删它。
     */
    fun isOwnStagingCopy(entry: PanEntry, baselinePaths: Set<String>): Boolean =
        isStagingPath(entry) && entry.path !in baselinePaths

    /**
     * 只认快照之后新增的条目，再交给 [matchTransferred] 做名字 / 大小匹配。
     */
    fun matchNewlyTransferred(
        baselinePaths: Set<String>, candidates: List<PanEntry>, name: String, sizeBytes: Long,
    ): PanEntry? = matchTransferred(
        candidates.filter { isOwnStagingCopy(it, baselinePaths) }, name, sizeBytes,
    )

    /** 超时兜底清理时也要限定名字，避免把同一时刻别人放进去的文件当成本次副本。 */
    fun isOwnCopyNamed(entry: PanEntry, baselinePaths: Set<String>, sourceName: String): Boolean {
        if (!isOwnStagingCopy(entry, baselinePaths)) return false
        val base = sourceName.substringBeforeLast('.').trim()
        val ext = sourceName.substringAfterLast('.', "").lowercase()
        return entry.name.substringBeforeLast('.').trim().startsWith(base) &&
            entry.name.substringAfterLast('.', "").lowercase() == ext
    }

    /**
     * 转存后的文件名匹配。
     *
     * 转存接口用 `ondup=newcopy`，同名文件会被改成 `xxx(1).mp4`，所以不能拿原文件名做
     * 相等比较；按「去扩展名后的主体 + 同扩展名」匹配，取 size 最接近的那一条。
     * 返回 null 表示还没落盘（异步任务未完成），调用方应轮询而不是判失败。
     */
    fun matchTransferred(
        candidates: List<PanEntry>, name: String, sizeBytes: Long,
    ): PanEntry? {
        val base = name.substringBeforeLast('.').trim()
        val ext = name.substringAfterLast('.', "").lowercase()
        return candidates
            .filter { !it.directory }
            .filter { it.name.substringBeforeLast('.').trim().startsWith(base) }
            .filter { it.name.substringAfterLast('.', "").lowercase() == ext }
            .minByOrNull { candidate ->
                val size = candidate.size.toLongOrNull() ?: -1L
                if (size == sizeBytes) 0L else if (size < 0) Long.MAX_VALUE / 2 else kotlin.math.abs(size - sizeBytes)
            }
    }

    /** 转存结果 → 给用户的一句话（区分「已就绪」与「还在落盘」）。 */
    fun prefetchNotice(found: Boolean): String =
        if (found) "已转存，正在按原始文件下载" else "已提交转存，等待网盘落盘后继续"
}
