package com.hnwd.bdplayer

/**
 * 全局搜索里不依赖 Android View 的判定、目标组装、文案与目录定位路径计算。
 *
 * SearchSection 负责弹窗/列表/串行请求；这里只放能在 JVM 单测里跑的纯逻辑。
 * 特别是 [matchesTrailChild] / [trailRoot] / [trailParts]，是「搜索结果定位到目录」
 * 这条链路上最容易出边界错的部分（尾部斜杠、根路径、文件命中 vs 目录命中）。
 */

/** 全局搜索单次会话的结果上限，超过即停止继续遍历来源。 */
internal const val MAX_GLOBAL_SEARCH_HITS = 1000

/** 可搜索的百度群组：夸克来源和联系人来源都不参与全局搜索。 */
internal fun searchGroupTargets(sources: List<ShareSource>): List<SearchTarget> =
    sources
        .filter { it.provider == SourceProvider.BAIDU && !isContactSource(it.id) }
        .map { SearchTarget(SearchScope.GROUP, it.id, it.name) }

/** 群组 = 全部已关联百度群组；全部 = 群组 + 我的网盘；我的网盘 = 只有盘。 */
internal fun searchTargets(scope: SearchScope, groups: List<SearchTarget>): List<SearchTarget> =
    buildList {
        if (scope != SearchScope.DISK) addAll(groups)
        if (scope != SearchScope.GROUP) {
            add(SearchTarget(SearchScope.DISK, DISK_SOURCE_ID, "我的网盘"))
        }
    }

/** 没有可搜索群组时，只提供「我的网盘」一个范围。 */
internal fun availableScopes(hasGroups: Boolean): List<SearchScope> =
    if (hasGroups) {
        listOf(SearchScope.DISK, SearchScope.GROUP, SearchScope.ALL)
    } else {
        listOf(SearchScope.DISK)
    }

/** 恢复上次选的范围；存的值不在当前可选集合里（例如群组被清空）时回落到我的网盘。 */
internal fun restoreScope(stored: String?, scopes: List<SearchScope>): SearchScope =
    SearchScope.entries.firstOrNull { it.name == stored && it in scopes } ?: SearchScope.DISK

internal fun scopeTitle(scope: SearchScope): String = when (scope) {
    SearchScope.DISK -> "搜索我的网盘"
    SearchScope.GROUP -> "搜索群组"
    SearchScope.ALL -> "搜索全部"
}

internal fun scopeSubtitle(scope: SearchScope): String = when (scope) {
    SearchScope.DISK -> "在我的网盘中搜索"
    SearchScope.GROUP -> "在已关联的群组文件库中搜索"
    SearchScope.ALL -> "在群组和我的网盘中搜索"
}

internal fun scopeHintText(groupCount: Int): String =
    "将搜索 $groupCount 个群 + 我的网盘，结果可能较多"

/** 结果面板高度：可用高度的 70%，夹在 [minHeight, maxHeight]，再让出左右边距。 */
internal fun searchResultHeight(available: Int, minHeight: Int, maxHeight: Int, sideMargin: Int): Int =
    minOf((available * 0.70f).toInt().coerceIn(minHeight, maxHeight), available - sideMargin)
        .coerceAtLeast(1)

internal fun searchProgressText(index: Int, total: Int, hits: Int): String =
    "已搜 $index/$total · 已找到 $hits 项"

internal fun searchSummaryText(hits: Int, failed: Int): String = when {
    hits == 0 -> if (failed > 0) "0 个结果 · $failed 个来源失败" else "0 个结果"
    else -> "已找到 $hits 项" + if (failed > 0) " · $failed 个来源失败" else " · 点选打开"
}

/** 盘与群组的 fileId 可能撞号，去重 key 必须带来源前缀。 */
internal fun diskHitKey(fileId: String): String = "D:$fileId"

internal fun groupHitKey(gid: String, fileId: String): String = "G:$gid:$fileId"

/** 文件命中看父目录，目录命中看自身路径；统一去掉尾部斜杠，空路径记为根。 */
internal fun normalizeTargetPath(path: String, directory: Boolean, parentPath: String): String =
    (if (directory) path else parentPath).trimEnd('/').ifEmpty { "/" }

/**
 * 扫描群组目录时判断某个 child 是否命中定位目标：
 * 1. 搜到的是文件 → 用 fileId 精确匹配（可跨目录）；
 * 2. 搜到的是目录 → 路径相等，或 child 是目标路径的祖先目录。
 */
internal fun matchesTrailChild(
    childPathRaw: String,
    childDirectory: Boolean,
    childFileId: String,
    targetPath: String,
    fileHit: Boolean,
    targetFileId: String,
): Boolean {
    val childPath = childPathRaw.trimEnd('/')
    return when {
        fileHit && !childDirectory && childFileId == targetFileId -> true
        childDirectory && childPath == targetPath -> true
        childDirectory && childPath.isNotEmpty() && targetPath.startsWith("$childPath/") -> true
        else -> false
    }
}

/** 由命中项的父路径推导面包屑根：返回 rootPath → rootName。 */
internal fun trailRoot(foundPathRaw: String): Pair<String, String> {
    val foundPath = foundPathRaw.trimEnd('/')
    val rootPath = foundPath.substringBeforeLast('/', "").ifEmpty { "/" }
    val rootName = if (rootPath == "/") "文件库" else rootPath.substringAfterLast('/').ifEmpty { "文件库" }
    return rootPath to rootName
}

/** 目标路径去掉根前缀后剩下的层级名。 */
internal fun trailParts(targetPath: String, rootPath: String): List<String> =
    targetPath.removePrefix(rootPath).trim('/').split('/').filter { it.isNotEmpty() }

/** 面包屑逐层下钻时，下一层目录应有的完整路径。 */
internal fun trailExpectedPath(currentPathRaw: String, part: String): String =
    currentPathRaw.trimEnd('/') + "/" + part
