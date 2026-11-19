package com.hnwd.bdplayer

import org.json.JSONObject

/**
 * 夸克网盘批量写操作（删除 / 移动 / 分享）里不依赖 Android View 的纯逻辑。
 *
 * 与 [DiskMutationLogic] 刻意分开：夸克是 fid + pdir_fid 模型、纯 Cookie 鉴权，
 * 没有 bdstoken、path -> fs_id 核对、errno 132 安全验证那套百度语义；硬套会把两套
 * 状态机搅在一起。弹窗 / 请求 / 写锁的接线在 [QuarkDiskWrites]。
 */

internal const val QUARK_SHARE_LINK_PREFIX = "https://pan.quark.cn/s/"

/**
 * 夸克 `expired_type` 枚举（官方 quark-clouddrive SDK / QuarkPanTool 一致）：
 * 1=永久、2=1 天、3=7 天、4=30 天。项目文档 §5.1 里写的「0=永久」是源码推测值，
 * 真机实测传 0 会返回 HTTP 404 + code 41000，已按实测修正。三档集中在这一张表便于调整。
 */
internal val QUARK_SHARE_PERIODS = listOf(1 to "永久有效", 2 to "1 天有效", 3 to "7 天有效")

internal fun quarkSharePeriodChoices(): List<SheetChoice> =
    QUARK_SHARE_PERIODS.map { SheetChoice(it.second, tag = it.first) }

/** 去重后的 fid 列表；空 fid 不能提交（夸克会报 14001 Bad Parameter）。 */
internal fun quarkMutationIds(entries: List<PanEntry>): List<String> =
    entries.map { it.fileId }.filter { it.isNotBlank() }.distinct()

/** /share 返回异步任务；/task 返回任务完成后的 share_id。两者都做空值容错。 */
internal fun quarkShareTaskId(json: JSONObject): String =
    json.optJSONObject("data")?.optString("task_id").orEmpty()

internal fun quarkShareId(json: JSONObject): String =
    json.optJSONObject("data")?.optString("share_id").orEmpty()

/** 任务轮询上限与间隔：QuarkPanTool 实测需 500~1000ms 多次重试，这里取 10 次 × 500ms。 */
internal const val QUARK_SHARE_TASK_ATTEMPTS = 10
internal const val QUARK_SHARE_TASK_POLL_MS = 500L

internal fun quarkDeleteConfirmMessage(label: String): String =
    "确定从夸克网盘删除 $label 吗？删除目录时其内容会一并删除。"

/** 被移动的目录自身及其所有子目录都不能作为目标；夸克 path 同样是层级字符串，可复用百度判定。 */
internal fun quarkMoveBlockedPaths(entries: List<PanEntry>): Set<String> =
    entries.filter { it.directory }.map { it.path }.toSet()

internal fun quarkShareTitle(entries: List<PanEntry>): String {
    val first = entries.firstOrNull()?.name ?: return "拾课分享"
    return if (entries.size == 1) first else "$first 等 ${entries.size} 项"
}

internal fun quarkDeleteSuccess(count: Int): String = "已删除 $count 项"

internal fun quarkMoveSuccess(count: Int, target: String): String = "已移动 $count 项到「$target」"

internal data class QuarkShareInfo(val link: String, val pwd: String)

/**
 * 分享响应未端到端实测，做多形态容错：
 * ① 优先用服务端直接下发的 URL；② 其次用 pwd_id / share_id 拼夸克公开分享链接；
 * ③ 都取不到返回 null，由 UI 提示「已创建但未取到链接」，不假装成功。
 * 提取码以响应为准，缺失时回落到本地生成的那个（提交时已发给网盘）。
 */
internal fun parseQuarkShare(json: JSONObject, fallbackPwd: String): QuarkShareInfo? {
    val sources = listOfNotNull(json, json.optJSONObject("data"))
    val link = sources.asSequence()
        .flatMap { obj -> sequenceOf("share_url", "url", "short_url", "link", "share_link").map(obj::optString) }
        .firstOrNull { it.startsWith("http") }
        ?: sources.asSequence()
            .flatMap { obj -> sequenceOf("pwd_id", "share_id", "sid").map(obj::optString) }
            .firstOrNull { it.isNotBlank() }
            ?.let { "$QUARK_SHARE_LINK_PREFIX$it" }
        ?: return null
    val returnedPwd = sources.asSequence()
        .flatMap { obj -> sequenceOf("passcode", "pwd", "extract_code").map(obj::optString) }
        .firstOrNull { it.isNotEmpty() }.orEmpty()
    return QuarkShareInfo(link, if (returnedPwd.isEmpty()) fallbackPwd else returnedPwd)
}

internal fun quarkWriteErrorMessage(error: Throwable): String = when (error) {
    is QuarkLoginRequired -> "夸克网盘登录状态已失效，请重新登录"
    is QuarkMutationPending -> "网盘仍在处理，请稍后刷新确认结果"
    is IllegalStateException -> error.message ?: "操作失败，请重试"
    else -> "连接或响应异常，请重试"
}
