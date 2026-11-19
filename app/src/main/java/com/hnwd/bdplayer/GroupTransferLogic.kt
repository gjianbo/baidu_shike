package com.hnwd.bdplayer

import java.net.URLEncoder
import org.json.JSONArray

/**
 * 百度群组文件「转存到我的网盘」的纯逻辑（不依赖 Android View，可 JVM 单测）。
 *
 * 契约来自 BaiduPCS-Py issue #73 的 Chrome 抓包：`POST /mbox/msg/transfer`，
 * 与公开分享链接的 `/share/transfer`、写文件管理的 `/api/filemanager` 都是不同端点。
 *
 * 关键约束：`msg_id` / `from_uk` / `gid` 是**消息级**，同一条分享消息下的多个 fs_id
 * 才能合并成一次请求；跨消息勾选必须拆成多笔，否则服务端无法定位引用。
 */
internal data class GroupTransferBatch(
    val gid: String,
    val msgId: String,
    val fromUk: String,
    val fsIds: List<String>,
)

/** 抓包记录：errno=4（QPS 上限）时文件实际可能已转存成功，不能当失败处理。 */
internal const val GROUP_TRANSFER_LENIENT_ERRNO = 4

/**
 * 只收「群组」分享文件。
 * 联系人（好友）分享虽然也走 /mbox/msg/transfer，但契约不同：type=1 + to_uk、没有 gid，
 * 本期不做（见方案对齐表），必须先排除，否则会用 type=2 + gid 提交出一个必然失败的请求。
 * 群组页本身可能切到联系人来源（PanApi.roots() 里 sourceKindOf(gid)==CONTACT 走 sessioninfo），
 * 也可能切到「我的网盘 · 关注目录」（dir- 前缀，条目来自 /api/list）。后者当前已被 !isDisk
 * 挡住，但显式排除一层，避免将来解析改版后把网盘目录 id 当成 gid 提交。
 * 所以不能只靠 UI 判断，这里做最后一道防线，也是 UI 显示转存入口的唯一判据。
 */
internal fun groupTransferEligible(entry: PanEntry): Boolean =
    entry.provider == SourceProvider.BAIDU && !entry.isDisk && !entry.isContact &&
        !isDirectorySource(entry.gid) &&
        entry.fileId.isNotBlank() && entry.messageId.isNotBlank() && entry.owner.isNotBlank()

/** 按 (gid, msg_id, from_uk) 稳定分组；同一消息的 fs_id 去重并保持勾选顺序。 */
internal fun groupTransferBatches(entries: List<PanEntry>): List<GroupTransferBatch> {
    val grouped = linkedMapOf<Triple<String, String, String>, MutableList<String>>()
    entries.filter(::groupTransferEligible).forEach { entry ->
        val key = Triple(rawSourceId(entry.gid), entry.messageId, entry.owner)
        val ids = grouped.getOrPut(key) { mutableListOf() }
        if (entry.fileId !in ids) ids.add(entry.fileId)
    }
    return grouped.map { (key, ids) -> GroupTransferBatch(key.first, key.second, key.third, ids) }
}

/** fs_ids 按抓包格式提交数字数组字符串；fs_id 非数字时退回字符串。 */
internal fun groupTransferForm(batch: GroupTransferBatch, destDir: String): String {
    val fsIds = JSONArray().apply {
        batch.fsIds.forEach { id -> put(id.toLongOrNull() ?: id) }
    }.toString()
    return listOf(
        "from_uk" to batch.fromUk,
        "msg_id" to batch.msgId,
        "path" to destDir,
        "ondup" to "newcopy",
        "async" to "1",
        "type" to "2",
        "gid" to batch.gid,
        "fs_ids" to fsIds,
    ).joinToString("&") { (key, value) -> "$key=${URLEncoder.encode(value, "UTF-8")}" }
}

internal fun groupTransferContainsDirectory(entries: List<PanEntry>): Boolean =
    entries.any { groupTransferEligible(it) && it.directory }

/**
 * 成功反馈文案：明确写「转存成功」并带上目标目录，不再只说「已提交、请稍后刷新」
 * —— 异步落盘的说明放到结果弹窗里，这里保持简短，状态栏 / Toast 都用它。
 */
internal fun groupTransferResultText(items: Int, batches: Int, dest: String): String =
    "转存成功：已提交 $items 项（$batches 批）到 $dest"

internal const val GROUP_TRANSFER_DIRECTORY_NOTICE =
    "所选条目包含文件夹，转存时会连同文件夹内的全部内容一起保存。"
