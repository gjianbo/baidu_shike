package com.hnwd.bdplayer

import kotlin.random.Random

/**
 * 网盘写操作里不依赖 Android View 的判定、状态机与文案。
 *
 * DiskMutationSection 仍负责弹窗 / 请求 / 写锁，这里只放能在 JVM 单测里跑的纯逻辑。
 * 宿主状态一律用惰性 lambda 传入：原实现的 when 表达式有短路（例如删除守卫在会话
 * 已变更时不会去读目录），抽函数后必须保留调用顺序与次数，否则「切号后不写回旧 UI」
 * 这类不变式就没有测试可钉。
 */

// ===================== 删除前的守卫 =====================

internal enum class DeleteGuard { STALE_SESSION, DELETE_IN_FLIGHT, READY, REFRESH_REQUIRED }

internal fun deleteGuardMessage(guard: DeleteGuard): String = when (guard) {
    DeleteGuard.STALE_SESSION -> "网盘会话已变更，请重试"
    DeleteGuard.DELETE_IN_FLIGHT -> "正在确认上一笔删除，请稍候"
    DeleteGuard.REFRESH_REQUIRED -> "请先刷新当前目录后重试"
    DeleteGuard.READY -> ""
}

/**
 * 删除提交前的四态守卫。只有「当前正停在网盘页，且每个条目的父目录就是当前目录」
 * 才允许用缓存里的 path 去提交删除（删除接口按路径操作）。
 */
internal fun deleteGuard(
    session: Int,
    currentSession: () -> Int,
    deleteInFlight: () -> Boolean,
    onDiskScreen: () -> Boolean,
    entries: List<PanEntry>,
    diskDir: () -> String,
): DeleteGuard = when {
    session != currentSession() -> DeleteGuard.STALE_SESSION
    deleteInFlight() -> DeleteGuard.DELETE_IN_FLIGHT
    onDiskScreen() && entries.all { it.isDisk && PanApi.parentPath(it.path) == diskDir() } -> DeleteGuard.READY
    else -> DeleteGuard.REFRESH_REQUIRED
}

internal fun deleteLabel(entries: List<PanEntry>): String =
    if (entries.size == 1) "「${entries[0].name}」" else "${entries.size} 项"

internal fun deleteConfirmMessage(label: String): String =
    "确定删除 $label 吗？删除后可在网盘回收站找回。"

// ===================== 删除后的两步确认 =====================

internal enum class DeleteCheckStep { VERIFIED, FAILED, NEXT_PAGE }

/**
 * 删除前核对：把「path -> fs_id」逐页对完才算通过。
 * [diskHasMore] 必须惰性传入：remaining 已空 / 空页 / 超页上限时，原实现不会去读 JSON 的 list。
 */
internal fun deleteCheckStep(
    remaining: Int,
    fileCount: Int,
    page: Int,
    diskHasMore: () -> Boolean,
): DeleteCheckStep = when {
    remaining == 0 -> DeleteCheckStep.VERIFIED
    fileCount == 0 || page >= 100 || !diskHasMore() -> DeleteCheckStep.FAILED
    else -> DeleteCheckStep.NEXT_PAGE
}

internal enum class ReconcileStep { RETRY, CONFIRMED, FAILED, NEXT_PAGE }

internal const val MAX_RECONCILE_ATTEMPTS = 8

internal const val MAX_DELETE_SCAN_PAGES = 100

/** 删除提交后的回读：[alreadySeen] 挡住网盘一直返回同一页导致的死循环。 */
internal fun reconcileStep(
    pageIds: List<String>,
    ids: Set<String>,
    alreadySeen: Boolean,
    diskHasMore: () -> Boolean,
    page: Int,
): ReconcileStep = when {
    pageIds.any { it in ids } -> ReconcileStep.RETRY
    alreadySeen -> ReconcileStep.FAILED
    !diskHasMore() -> ReconcileStep.CONFIRMED
    pageIds.isEmpty() || page >= MAX_DELETE_SCAN_PAGES -> ReconcileStep.FAILED
    else -> ReconcileStep.NEXT_PAGE
}

internal fun shouldRetryReconcile(attempt: Int): Boolean = attempt < MAX_RECONCILE_ATTEMPTS

internal fun reconcileMessage(confirmed: Boolean, count: Int): String =
    if (confirmed) "已删除 $count 项" else "删除尚未确认，请稍后刷新查看"

// ===================== 写请求的响应分支 =====================

internal enum class WriteResponse { SECURITY_VERIFY, PARTIAL_SUCCESS, FAILED, SUCCESS }

/**
 * filemanager 外层 errno 为 0 时，info 数组里仍可能有单项失败（[PanApi.writeErrno] 会捞出来）。
 * 此时按「部分成功」处理：刷新列表并提示，而不是当成整笔失败。
 * [outerErrno] 惰性传入：原实现只在 errno != 0 分支里读 json.getInt("errno")。
 */
internal fun writeResponse(errno: Int, outerErrno: () -> Int): WriteResponse = when {
    errno == 132 -> WriteResponse.SECURITY_VERIFY
    errno != 0 && outerErrno() == 0 -> WriteResponse.PARTIAL_SUCCESS
    errno != 0 -> WriteResponse.FAILED
    else -> WriteResponse.SUCCESS
}

internal fun diskWritePendingText(op: String): String = "${op}结果未确认，请刷新查看"

internal fun partialWriteMessage(error: String): String =
    "部分项目可能已处理，请查看刷新后的列表。$error"

internal fun securityVerifyStatus(op: String): String = "百度网盘要求安全验证，「$op」未执行"

// ===================== 名称 / 目录选择器 =====================

/** 参数是已经 trim 过的名字；返回输入框的 error 文案，null 表示可以提交。 */
internal fun diskNameError(name: String): String? = when {
    name.isEmpty() -> "请输入名称"
    name == "." || name == ".." || name.any { it in "/\\:*?\"<>|" || it.isISOControl() } ->
        "名称包含网盘不支持的字符"
    else -> null
}

/** 被操作的目录自身及其所有子目录都不能作为移动/复制的目标。 */
internal fun isBlockedFolder(dir: String, blocked: Set<String>): Boolean = blocked.any { path ->
    val base = path.trimEnd('/')
    base.isNotEmpty() && (dir.trimEnd('/') == base || dir.startsWith("$base/"))
}

internal data class FolderPickerUi(
    val upEnabled: Boolean,
    val pickEnabled: Boolean,
    val moreVisible: Boolean,
    val moreText: String,
    val moreEnabled: Boolean,
    val listEnabled: Boolean,
)

internal fun folderPickerUi(loading: Boolean, current: String, page: Int, hasMore: Boolean): FolderPickerUi =
    FolderPickerUi(
        upEnabled = !loading && current != "/",
        pickEnabled = !loading && page > 0,
        moreVisible = hasMore || page == 0,
        moreText = if (page == 0) "重试读取目录" else "加载更多目录",
        moreEnabled = !loading,
        listEnabled = !loading,
    )

internal fun folderPickerNames(names: List<String>, hasMore: Boolean): List<String> =
    if (names.isEmpty() && !hasMore) listOf("（无可选子目录）") else names

// ===================== 分享 =====================

/** 去掉了容易看错的 l / o / 0 / 1。 */
internal const val SHARE_PWD_ALPHABET = "abcdefghijkmnpqrstuvwxyz23456789"

internal fun randomSharePwd(random: Random = Random.Default): String =
    (1..4).map { SHARE_PWD_ALPHABET.random(random) }.joinToString("")

internal fun sharePeriodChoices(): List<SheetChoice> =
    listOf(0 to "永久有效", 7 to "7 天有效", 1 to "1 天有效")
        .map { SheetChoice(it.second, tag = it.first) }

internal fun sharePwd(infoPwd: String, fallback: String): String = infoPwd.ifEmpty { fallback }

internal fun shareResultText(link: String, pwd: String): String = buildString {
    append("链接：").append(link)
    if (pwd.isNotEmpty()) append("\n提取码：").append(pwd)
}

internal fun shareClipText(link: String, pwd: String): String =
    if (pwd.isNotEmpty()) "$link 提取码 $pwd" else link
