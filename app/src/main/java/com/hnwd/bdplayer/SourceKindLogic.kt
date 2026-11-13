package com.hnwd.bdplayer

/**
 * 来源类型（群组 / 联系人 / 网盘目录）的纯决策。
 *
 * 收口前散落各处的 `isContactSource(id)` 是「联系人 vs 其他」的二分，非联系人一律被
 * 当成群组；加入网盘目录后会拿目录路径去调群共享接口。这里把判据统一成 [SourceKind]
 * 三态，Activity 只负责接线。所有函数对 GROUP / CONTACT 的返回值与收口前的二分逐点
 * 一致，由 SourceKindLogicTest 钉住。
 *
 * 判据一律只看 id（[sourceKindOf]），不依赖 [ShareSource.kind] 这个冗余展示字段 ——
 * 老数据、单测里手动构造的来源都可能没给 kind。
 */

/** 首页缓存 key：联系人走 roots（列目录）；群组与网盘目录走 recent（首页快照）。 */
internal fun homeSourceKeyOf(sourceId: String, kind: SourceKind): String =
    if (kind == SourceKind.CONTACT) "roots:$sourceId" else "recent:$sourceId"

/** 联系人根目录接口需要 POST；群组与网盘目录是 GET。 */
internal fun rootNeedsPost(kind: SourceKind): Boolean = kind == SourceKind.CONTACT

/** 联系人分页信息在 records 里；群组与网盘目录在响应顶层。 */
internal fun rootPaginationIsRecords(kind: SourceKind): Boolean = kind == SourceKind.CONTACT

/** 联系人游标取 records.cursor；群组取顶层 last_msg_time。 */
internal fun rootCursorIsRecords(kind: SourceKind): Boolean = kind == SourceKind.CONTACT

/** 首页后台检查只针对群组来源；联系人与网盘目录都跳过。 */
internal fun shouldCheckHomeInBackground(kind: SourceKind): Boolean = kind == SourceKind.GROUP

/** 来源下拉副标题：三类百度来源 + 夸克。 */
internal fun sourceMetaOf(source: ShareSource): String = when {
    source.provider == SourceProvider.QUARK -> "夸克 · 监控目录"
    sourceKindOf(source.id) == SourceKind.CONTACT -> "百度 · 联系人"
    sourceKindOf(source.id) == SourceKind.DIRECTORY -> "百度 · 网盘目录"
    else -> "百度 · 群组"
}

/** 全局搜索只纳入百度群组；联系人与网盘目录都不参与。 */
internal fun isGroupSearchSource(source: ShareSource): Boolean =
    source.provider == SourceProvider.BAIDU && sourceKindOf(source.id) == SourceKind.GROUP

/**
 * 「管理群组 / 联系人文件库」弹窗的候选：只列同一类型的来源。
 * 收口前用 `isContactSource(id) == contacts` 过滤，网盘目录会被误当成群组混进来。
 * discovered 是本次发现的同类型候选，不做二次过滤，与原行为一致。
 */
internal fun sourcePickerCandidates(
    discovered: List<ShareSource>,
    existing: List<ShareSource>,
    contacts: Boolean,
): List<ShareSource> {
    val wanted = if (contacts) SourceKind.CONTACT else SourceKind.GROUP
    return (discovered + existing.filter {
        it.provider == SourceProvider.BAIDU && sourceKindOf(it.id) == wanted
    }).distinctBy { it.id }
}

/** 百度网盘目录来源数量上限，与夸克监控目录一致。 */
internal const val MAX_BAIDU_DIR_SOURCES = 5

/** 目录路径 → 显示名：取最后一段，根目录显示 "/" 。 */
internal fun directorySourceName(path: String): String =
    path.trimEnd('/').substringAfterLast('/').ifEmpty { "/" }

/**
 * 用本次勾选的目录覆盖已有的百度目录来源，其余来源（群组 / 联系人 / 夸克）原样保留。
 * selected 为「来源 id → 可读路径」，由目录浏览器逐层累积。
 */
internal fun mergeDirectorySources(
    existing: List<ShareSource>,
    selected: Map<String, String>,
): List<ShareSource> {
    val others = existing.filterNot {
        it.provider == SourceProvider.BAIDU && sourceKindOf(it.id) == SourceKind.DIRECTORY
    }
    val dirs = selected.map { (id, rawPath) ->
        // 老数据 / 预填时 path 可能为空，目录 id 自包含路径，从 id 解码兜底。
        val path = rawPath.ifEmpty { directorySourcePath(id) }
        ShareSource(id, directorySourceName(path), kind = SourceKind.DIRECTORY, path = path)
    }
    return others + dirs
}

/**
 * 根目录一级目录的勾选结果 → 来源 id→路径 映射。超过上限按列表顺序截断，
 * 与夸克监控目录的 take(5) 口径一致。保留插入顺序以便保存后顺序稳定。
 */
internal fun directorySelectionOf(paths: List<String>): Map<String, String> {
    val selected = linkedMapOf<String, String>()
    paths.take(MAX_BAIDU_DIR_SOURCES).forEach { selected[directorySourceId(it)] = it }
    return selected
}
