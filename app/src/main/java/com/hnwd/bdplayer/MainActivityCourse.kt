package com.hnwd.bdplayer

import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.TextView
import androidx.media3.common.Player

/**
 * 课程列表：排序、分页、行渲染与选集切换。
 *
 * 从 MainActivity.kt 按域原样搬出的扩展函数：函数体与语义未改，
 * 仅把 private 提升为 internal，并用 this@? 保真原 this@MainActivity 引用。
 */

internal fun MainActivity.renderCourse() {
    status.text = if (courseEntries.isEmpty()) "没有条目" else cacheNote
    sectionTitle.text = "文件"
    content.removeAllViews()
    addFilteredCourseRows(courseEntries.toList(), sortable = listSortable())
}


/** 目录列表是否支持排序：网盘/群组的目录可以，首页最近列表、收藏、设置不参与。 */
internal fun MainActivity.listSortable(): Boolean = screen == Screen.DISK || screen == Screen.GROUPS


/**
 * 批量选择入口是否露出。
 *
 * 网盘页的「选择」与批量操作条布局待重做，先隐藏入口；
 * 排序不受影响，网盘页只留排序一个按钮。
 */
internal fun MainActivity.listSelectable(): Boolean = listSortable() && screen != Screen.DISK


internal fun MainActivity.addFilteredCourseRows(
    entries: List<PanEntry>,
    sortable: Boolean,
    preserveMediaFilter: Boolean = false,
) {
    val home = screen == Screen.HOME
    val transferred = if (entries.any { !it.isDisk }) transferredIndex() else emptySet()
    val transferredCount = entries.count { matchesTransferred(it, transferred) }
    if (home || transferredCount == 0) transferredOnly = false
    transferredChip.visibility = if (!home && transferredCount > 0) View.VISIBLE else View.GONE
    transferredChip.text = "已转存 $transferredCount"
    transferredChip.contentDescription = "只看已转存，共 $transferredCount 项"
    val kinds = entries.map(PanApi::kindOf).toSet()
    val showFilters = if (home) {
        (preserveMediaFilter && mediaFilter != MediaFilter.ALL) ||
            MediaFilter.entries.any { it != MediaFilter.ALL && kinds.any(it::includes) }
    } else {
        kinds.size >= 2 || transferredCount > 0
    }
    filterBar.visibility = if (showFilters) View.VISIBLE else View.GONE
    showAvailableMediaFilters(kinds, includeSelected = preserveMediaFilter)
    if (!preserveMediaFilter && mediaFilter != MediaFilter.ALL && kinds.none(mediaFilter::includes)) {
        mediaFilter = MediaFilter.ALL
    }
    val rows = column()
    fun renderRows() {
        val matching = entries.filter {
            mediaFilter.includes(PanApi.kindOf(it)) &&
                (!transferredOnly || matchesTransferred(it, transferred))
        }
        updateFilterChips()
        val order = if (sortable) listOrder() else courseOrder
        val ordered = if (sortable) orderCourses(matching, order) else matching
        filterCount.text = "共 ${ordered.size} 项"
        lastRenderedEntries = ordered
        sortButton.text = order.label
        sortButton.contentDescription = "排序，当前 ${order.label}"
        sortButton.visibility = if (sortable && entries.isNotEmpty() && !selectionMode) View.VISIBLE else View.GONE
        btnSelect.visibility = if (!selectionMode && listSelectable() && ordered.isNotEmpty()) View.VISIBLE else View.GONE
        rows.removeAllViews()
        extendPlaybackQueue(ordered)
        if (ordered.isEmpty() && entries.isNotEmpty()) rows.addView(label("当前已加载的内容中没有匹配项", 12f))
        addCourseRows(rows, ordered, transferred)
        renderCourseSwitch()
    }
    content.addView(rows)
    redrawFilteredRows = ::renderRows
    renderRows()
}


/** 当前目录的身份键，与课程缓存的目录键同构；收藏/设置/首页根目录不参与排序，返回 null。 */
internal fun MainActivity.currentDirectoryKey(): String? {
    val parent = folders.lastOrNull()
    return when {
        parent != null && parent.isDisk -> "disk:dir:${parent.fileId}"
        parent != null -> "dir:${parent.gid}:${parent.messageId}:${parent.fileId}"
        screen == Screen.DISK -> "disk:roots"
        screen == Screen.GROUPS -> "roots:$gid"
        else -> null
    }
}


internal fun MainActivity.directorySortKey(): String? =
    currentDirectoryKey()?.let { AccountScope.key(it) }


/** 目录列表的排序：按目录记忆，没记过就跟随全局默认。 */
internal fun MainActivity.listOrder(): CourseOrder =
    directorySortKey()?.let { SortStore.list(this, it) } ?: courseOrder


internal fun MainActivity.showCourseSort() {
    val key = directorySortKey()
    val current = listOrder()
    showChoiceSheet(
        title = "排序",
        subtitle = if (key == null) null else "只对当前目录生效",
        choices = CourseOrder.entries.map { SheetChoice(it.label, checked = it == current, tag = it) },
        onSelect = { choice ->
            val order = choice.tag as CourseOrder
            SortStore.setList(this, key, order)
            if (key != null) {
                // 顺手更新全局默认：没单独设过的目录跟随最近一次选择，而不是退回旧值。
                courseOrder = order
                ThemeStore.setCourseOrder(this, order.name)
            }
            sortButton.text = order.label
            sortButton.contentDescription = "排序，当前 ${order.label}"
            redrawFilteredRows?.invoke()
        },
    )
}


internal fun MainActivity.playbackOrderKey(entry: PanEntry, listKey: String?): String? {
    // 磁盘条目也优先用目录键，才能和列表页的排序对上；拿不到目录再退回路径。
    val directory = directoryCacheKey(entry, listKey) ?: courseCache.directoryContaining(entry)
    return directory?.let { AccountScope.key(it) } ?: playbackPathOrderKey(entry)
}


internal fun MainActivity.playbackPathOrderKey(entry: PanEntry): String? {
    val path = PanApi.readablePath(entry.path)
    if (!path.startsWith('/')) return null
    return AccountScope.key("path:${entry.origin}:${entry.sourceId}:${path.substringBeforeLast('/')}")
}


internal fun MainActivity.playbackOrder(entry: PanEntry, listKey: String?): CourseOrder {
    val key = playbackOrderKey(entry, listKey)
    val pathKey = playbackPathOrderKey(entry)
    // 播放页自己的排序优先；没单独设过就继承这个目录的列表排序；
    // 再退到老版本按路径存的偏好；都没有才用全局默认。
    return key?.let { SortStore.playback(this, it) }
        ?: key?.let { SortStore.list(this, it) }
        ?: pathKey?.takeIf { it != key }?.let { SortStore.playback(this, it) }
        ?: courseOrder
}


internal fun MainActivity.playbackSortLabel(entry: PanEntry, listKey: String?): String =
    when (playbackOrder(entry, listKey)) {
        CourseOrder.ASCENDING -> "升序 ▾"
        CourseOrder.DESCENDING -> "降序 ▾"
        CourseOrder.NETWORK -> "原序 ▾"
    }


internal fun MainActivity.showPlaybackSort(anchor: View, entry: PanEntry, listKey: String?, onSorted: (List<PanEntry>) -> Unit) {
    val key = playbackOrderKey(entry, listKey) ?: return
    val account = verifiedAccount
    val options = CourseOrder.entries
    PopupMenu(anchor.context, anchor).apply {
        val selected = playbackOrder(entry, listKey)
        options.forEachIndexed { index, order ->
            menu.add(0, index, index, order.label)
        }
        menu.setGroupCheckable(0, true, true)
        menu.findItem(options.indexOf(selected)).isChecked = true
        setOnMenuItemClickListener { item ->
            val order = options[item.itemId]
            // 先落盘：即使此刻播放队列已经变了，用户的选择也不该被吞掉。
            SortStore.setPlayback(this@showPlaybackSort, key, order)
            val stillCurrent = account == verifiedAccount && hasVerifiedAccount() &&
                PlaybackQueueState.listKey == listKey &&
                PlaybackQueueState.anchor?.let { sameEntry(it, entry) } == true
            if (!stillCurrent) {
                toast("排序已保存，下次进入该目录生效")
            } else {
                val courses = orderCourses(PlaybackQueueState.entries, order)
                PlaybackQueueState.entries = courses
                pendingQueue = pendingQueue?.let { pending ->
                    if (playbackOrderKey(pending.entry, pending.listKey) == key) {
                        pending.copy(entries = orderCourses(pending.entries, order))
                    } else pending
                }
                renderCourseSwitch()
                onSorted(courses)
            }
            true
        }
        show()
    }
}


internal fun MainActivity.playbackDirectory(entry: PanEntry, listKey: String?): Pair<String, List<PanEntry>>? {
    val preferredKey = directoryCacheKey(entry, listKey)
    val key = preferredKey?.takeIf { courseCache.readAll(it).any { file -> sameEntry(file, entry) } }
        ?: courseCache.directoryContaining(entry)
        ?: return null
    val media = sameMediaKind(entry, courseCache.readAll(key))
    val queueKey = when {
        key == "disk:roots" -> "D:root"
        key.startsWith("disk:dir:") -> "D:${key.removePrefix("disk:dir:")}"
        key.startsWith("roots:") -> "G:${entry.gid}"
        key.startsWith("dir:") -> "G:${key.removePrefix("dir:")}"
        else -> if (entry.isDisk) "D:root" else "G:${entry.gid}"
    }
    return queueKey to orderCourses(media, playbackOrder(entry, queueKey))
}


internal fun MainActivity.nextDirectoryPage(entry: PanEntry, listKey: String?): String? {
    val key = directoryCacheKey(entry, listKey) ?: return null
    if (entry.isDisk || key.startsWith("disk:")) return null
    val (page, cached) = courseCache.lastPage(key) ?: return null
    if (key.startsWith("roots:")) {
        val cursor = cached.nextCursor?.takeIf { it.isNotEmpty() } ?: return null
        return PanApi.roots(entry.gid, cursor)
    }
    if (cached.nextCursor != "more" && (cached.nextCursor != null || cached.entries.size < 100)) return null
    val directoryId = key.substringAfterLast(':')
    return PanApi.children(entry, page + 1, directoryId)
}


internal fun MainActivity.loadMoreCourses(
    entry: PanEntry,
    listKey: String?,
    isCurrentCourse: () -> Boolean = { PlaybackQueueState.anchor?.let { sameEntry(it, entry) } == true },
    done: (List<PanEntry>?) -> Unit,
) {
    if (entry.provider == SourceProvider.QUARK) return done(null)
    if (entry.isDisk) {
        loadMoreDiskCourses(entry, listKey, isCurrentCourse, done)
        return
    }
    val key = directoryCacheKey(entry, listKey) ?: return done(null)
    val (page, cached) = courseCache.lastPage(key) ?: return done(null)
    val url = nextDirectoryPage(entry, listKey) ?: return done(null)
    val nested = key.startsWith("dir:")
    var finished = false
    fun complete(entries: List<PanEntry>?) {
        if (finished) return
        finished = true
        done(entries)
    }
    load(
        url, post = nested || entry.isContact, lane = RequestLane.COURSE,
        onCancelled = { complete(null) }, onFailure = { complete(null) },
    ) { json ->
        val files = if (nested) PanApi.parseChildren(json, entry) else PanApi.parseRoots(json, entry.gid)
        val next = if (nested) {
            if (PanApi.hasMore(json) && files.isNotEmpty() && files != cached.entries) "more" else ""
        } else {
            nextRootCursor(json, entry.gid, cached.nextCursor.orEmpty())
        }
        val last = courseCache.lastPage(key)
        if (last?.first == page && last.second == cached) {
            courseCache.writeIfChanged(key, page + 1, files, next)
        } else if (last == null || last.first <= page) {
            complete(null)
            return@load
        }
        if (PlaybackQueueState.listKey != listKey || !isCurrentCourse()) {
            complete(null)
            return@load
        }
        val courses = orderCourses(sameMediaKind(entry, courseCache.readAll(key)), playbackOrder(entry, listKey))
        PlaybackQueueState.entries = courses
        renderCourseSwitch()
        complete(courses)
    }
}


internal fun MainActivity.loadMoreDiskCourses(
    entry: PanEntry,
    listKey: String?,
    isCurrentCourse: () -> Boolean,
    done: (List<PanEntry>?) -> Unit,
) {
    val key = directoryCacheKey(entry, listKey) ?: return done(null)
    if (!key.startsWith("disk:")) return done(null)
    val (page, cached) = courseCache.lastPage(key) ?: return done(null)
    if (cached.nextCursor != "more") return done(null)
    val dir = if (key == "disk:roots") {
        "/"
    } else {
        PanApi.readablePath(entry.path).substringBeforeLast('/').ifEmpty { "/" }
    }
    ensureBdstoken(
        onReady = { token ->
            val request = BaiduPersonalDrive(token).list(dir, page + 1, 100)
            load(
                request.url, lane = RequestLane.COURSE,
                onCancelled = { done(null) }, onFailure = { done(null) },
            ) { json ->
                if (PlaybackQueueState.listKey != listKey || !isCurrentCourse()) {
                    done(null)
                    return@load
                }
                val (files, hasMore) = request.decode(json)
                val last = courseCache.lastPage(key)
                if (last?.first != page || last.second != cached) {
                    done(null)
                    return@load
                }
                courseCache.writeIfChanged(key, page + 1, files, if (hasMore) "more" else "")
                diskIndexCache = null
                val courses = orderCourses(
                    sameMediaKind(entry, courseCache.readAll(key)),
                    playbackOrder(entry, listKey),
                )
                PlaybackQueueState.entries = courses
                renderCourseSwitch()
                done(courses)
            }
        },
        onFailure = { done(null) },
    )
}


internal fun MainActivity.hasMoreCourses(entry: PanEntry, listKey: String?): Boolean {
    if (entry.provider == SourceProvider.QUARK) return false
    val key = directoryCacheKey(entry, listKey) ?: return false
    val (_, cached) = courseCache.lastPage(key) ?: return false
    return if (entry.isDisk) cached.nextCursor == "more" else nextDirectoryPage(entry, listKey) != null
}


internal fun MainActivity.directoryCacheKey(entry: PanEntry, listKey: String?): String? = when {
    listKey == "D:root" && entry.isDisk -> "disk:roots"
    listKey?.startsWith("D:") == true && entry.isDisk -> "disk:dir:${listKey.removePrefix("D:")}"
    listKey == "G:${entry.gid}" -> "roots:${entry.gid}"
    listKey?.startsWith("G:${entry.gid}:") == true -> "dir:${listKey.removePrefix("G:")}"
    else -> null
}


internal fun MainActivity.styleContent() {
    content.setBackgroundColor(color(R.color.transparent))
}


internal fun MainActivity.addCourseRows(
    container: LinearLayout,
    entries: List<PanEntry>,
    transferred: Set<String> = emptySet(),
) {
    val favoriteKeys = favorites.all().map(::entryKey).toSet()
    val sourceNames = if (screen == Screen.FAVORITES) {
        shareSources().associate { it.id to it.name }
    } else {
        emptyMap()
    }
    if (!hasWideWindow) {
        entries.forEach { container.addView(courseRow(it, entries, transferred, favoriteKeys, sourceNames)) }
        return
    }
    entries.chunked(2).forEach { pair ->
        val line = row()
        pair.forEachIndexed { index, entry ->
            val params = LinearLayout.LayoutParams(0, -2, 1f)
            if (index == 0 && pair.size == 2) params.rightMargin = dp(8)
            line.addView(courseRow(entry, entries, transferred, favoriteKeys, sourceNames), params)
        }
        if (pair.size == 1) line.addView(View(this), LinearLayout.LayoutParams(0, -2, 1f))
        container.addView(line)
    }
}


internal fun MainActivity.courseRow(
    entry: PanEntry,
    entries: List<PanEntry>,
    transferred: Set<String> = emptySet(),
    favoriteKeys: Set<String> = emptySet(),
    sourceNames: Map<String, String> = emptyMap(),
): View {
    val format = formatOf(entry)
    val kind = PanApi.kindOf(entry)
    val row = layoutInflater.inflate(R.layout.item_course, content, false)
    row.tag = entryKey(entry)
    val fmt = row.findViewById<ImageView>(R.id.fmt)
    paintMediaIcon(fmt, kind, entry.directory)
    if (screen == Screen.HOME && folders.isEmpty() && !showingHomeDirectory &&
        entry.provider == SourceProvider.BAIDU && !entry.isDisk && !entry.isContact &&
        hasVerifiedAccount() && (kind == MediaKind.IMAGE || kind == MediaKind.VIDEO)
    ) {
        loadRecentThumbnail(entry, fmt)
    }
    row.findViewById<TextView>(R.id.itemTitle).text = entry.name
    // 兼容旧收藏/历史里已持久化的编码路径；无斜杠时不再把整串路径回显到 meta
    val folder = PanApi.readablePath(entry.path)
        .substringBeforeLast('/', "")
        .substringAfterLast('/')
    val size = entry.size.toLongOrNull()?.let { if (it >= 1024 * 1024) "${it / 1024 / 1024} MB" else "" }.orEmpty()
    val kindName = mediaKindLabel(kind, entry.directory, format)
    val source = when {
        screen != Screen.FAVORITES -> ""
        entry.isDisk -> "我的网盘"
        else -> sourceNames[entry.gid] ?: "共享文件库"
    }
    val transferredLabel = if (matchesTransferred(entry, transferred)) "已转存" else ""
    row.findViewById<TextView>(R.id.itemMeta).text =
        listOf(source, transferredLabel, kindName, folder, size).filter { it.isNotEmpty() }.joinToString(" · ")
    val side = row.findViewById<ImageView>(R.id.itemSide)
    side.tag = favoriteTag(entry)
    paintFavorite(side, entryKey(entry) in favoriteKeys)
    side.setOnClickListener { toggleFavorite(entry) }
    val mark = progressMark(entry)
    if (mark != null) {
        row.findViewById<View>(R.id.itemFoot).visibility = View.VISIBLE
        val time = row.findViewById<TextView>(R.id.footTime)
        time.text = mark.first
        time.setTextColor(color(if (mark.second) R.color.ok else R.color.ink3))
        row.findViewById<View>(R.id.footDot).setBackgroundResource(
            if (mark.second) R.drawable.bg_dot_ok else R.drawable.bg_dot_accent,
        )
    }
    val check = row.findViewById<ImageView>(R.id.itemCheck)
    if (selectionMode) {
        val key = entryKey(entry)
        check.visibility = View.VISIBLE
        check.isSelected = key in selectedKeys
        check.contentDescription = if (check.isSelected) "已选：${entry.name}" else "未选：${entry.name}"
        row.setOnClickListener { toggleSelection(entry) }
        row.setOnLongClickListener(null)
    } else {
        check.visibility = View.GONE
        row.setOnClickListener { openEntry(entry, entries, currentPlaybackListKey()) }
        row.setOnLongClickListener {
            showEntryActions(entry, entries)
            true
        }
    }
    return row
}


internal fun MainActivity.currentPlaybackListKey(): String {
    val parent = folders.lastOrNull()
    return when {
        parent != null && parent.isDisk -> "D:${parent.fileId}"
        parent != null -> "G:${parent.gid}:${parent.messageId}:${parent.fileId}"
        screen == Screen.DISK -> "D:root"
        screen == Screen.GROUPS || showingHomeDirectory -> "G:$gid"
        else -> "${screen}:$gid"
    }
}


internal fun MainActivity.selectPlayback(entry: PanEntry, entries: List<PanEntry>, listKey: String? = null) {
    val directory = playbackDirectory(entry, listKey)
    val queue = directory?.second ?: orderCourses(courseSiblings(entry, entries), playbackOrder(entry, null))
    pendingQueue = QueueSelection(entry, queue, directory?.first)
}


internal fun MainActivity.extendPlaybackQueue(entries: List<PanEntry>) {
    val anchor = PlaybackQueueState.anchor ?: return
    if (PlaybackQueueState.listKey != currentPlaybackListKey() || entries.none { sameEntry(it, anchor) }) return
    if (PanApi.kindOf(anchor) == MediaKind.VIDEO && videoPage != null) return
    PlaybackQueueState.entries = playbackDirectory(anchor, PlaybackQueueState.listKey)?.second ?: return
}


internal fun MainActivity.currentAudioEntry(player: Player): PanEntry? {
    if (!ownsAudio(player)) return null
    val item = player.currentMediaItem ?: return null
    val extras = item.mediaMetadata.extras ?: return null
    val origin = runCatching { EntryOrigin.valueOf(extras.getString("origin") ?: "GROUP") }
        .getOrDefault(EntryOrigin.GROUP)
    return PanEntry(
        extras.getString("gid").orEmpty(), extras.getString("message").orEmpty(),
        extras.getString("owner").orEmpty(), item.mediaId, item.mediaMetadata.title?.toString().orEmpty(),
        extras.getString("path").orEmpty(), extras.getString("size").orEmpty(), false, origin,
    )
}


internal fun MainActivity.siblingOf(entry: PanEntry, delta: Int): PanEntry? {
    val index = PlaybackQueueState.entries.indexOfFirst { sameEntry(it, entry) }
    return if (index >= 0) PlaybackQueueState.entries.getOrNull(index + delta) else null
}


internal fun MainActivity.renderCourseSwitch() {
    // 课程切换已收敛到播放页内（VideoPlaybackPage 的上一节/下一节、音频页动作栏）。
    // 主界面底部 include 的 courseSwitch 是早期遗留控件：关闭视频播放页时
    // PlaybackQueueState.anchor 不清空，目录页会残留一条「第 X / N 节」却没有任何
    // 播放内容的切换条。按产品决策：目录页彻底不显示它。
    // 保留本函数是因为播放确认、排序、加载更多、设置页等多处仍会调用它，统一在此收敛。
    courseSwitch.visibility = View.GONE
}


internal fun MainActivity.advanceAfterEnd(
    entry: PanEntry,
    isCurrent: () -> Boolean,
    onUnavailable: () -> Unit = {},
    playNext: () -> Unit,
) {
    val listKey = PlaybackQueueState.listKey
    fun canAdvance() = hasActiveSession(entry.provider) && !isFinishing && !isDestroyed &&
        PlaybackQueueState.listKey == listKey && isCurrent() &&
        PlaybackQueueState.entries.any { sameEntry(it, entry) }

    fun tryNext() {
        if (!canAdvance()) return onUnavailable()
        if (siblingOf(entry, 1) != null) {
            playNext()
            return
        }
        if (automaticCourseLoad?.let { sameEntry(it, entry) } == true) return
        if (!hasMoreCourses(entry, listKey)) return onUnavailable()
        automaticCourseLoad = entry
        loadMoreCourses(entry, listKey, ::canAdvance) { courses ->
            if (automaticCourseLoad === entry) {
                automaticCourseLoad = null
                if (courses != null) tryNext() else onUnavailable()
            } else {
                onUnavailable()
            }
        }
    }
    tryNext()
}


internal fun MainActivity.playSibling(
    entry: PanEntry,
    delta: Int,
    automatic: Boolean = false,
    route: PlaybackRoute = PlaybackRoute.AUDIO,
    videoState: VideoPlaybackPage.Snapshot? = null,
) {
    if (!hasActiveSession(entry.provider) || isFinishing || isDestroyed) return
    val target = siblingOf(entry, delta) ?: return
    when (PanApi.kindOf(target)) {
        MediaKind.AUDIO -> resolveAudio(target, route, openPlayer = !automatic)
        MediaKind.VIDEO -> {
            startVideo(
                target, audioOnly = videoState?.audioOnly == true,
                fullscreen = videoState?.fullscreen == true,
            )
        }
        else -> Unit
    }
}
