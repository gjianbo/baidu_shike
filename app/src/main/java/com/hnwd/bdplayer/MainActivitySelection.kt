package com.hnwd.bdplayer

import android.os.Bundle
import android.widget.ImageView
import androidx.appcompat.app.AlertDialog
import androidx.core.view.doOnPreDraw

/**
 * 批量选择、条目详情与操作菜单。
 *
 * 从 MainActivity.kt 按域原样搬出的扩展函数：函数体与语义未改，
 * 仅把 private 提升为 internal，并用 this@? 保真原 this@MainActivity 引用。
 */

internal fun MainActivity.openFolderTrail(group: String, trail: List<PanEntry>) {
    if (!hasVerifiedAccount() || group != gid) return
    clearSelectionIfActive()
    if (screen == Screen.GROUPS && trail.take(folders.size).map(::entryKey) == folders.map(::entryKey) && trail.size > folders.size) {
        rememberFolderPosition()
    }
    if (screen != Screen.GROUPS) folderPositions.clear()
    screen = Screen.GROUPS
    recent = false
    folders.clear()
    folders.addAll(trail)
    mediaFilter = MediaFilter.ALL
    transferredOnly = false
    refresh()
}


internal fun MainActivity.rememberFolderPosition() {
    folderPositions[folders.map(::entryKey)] =
        FolderPosition(contentScroll.scrollY, mediaFilter, transferredOnly)
}


internal fun MainActivity.returnToFolder(depth: Int) {
    clearSelectionIfActive()
    folders.subList(depth, folders.size).clear()
    val path = folders.map(::entryKey)
    val position = folderPositions.remove(path)
    folderPositions.keys.removeAll { it.size > depth }
    mediaFilter = position?.filter ?: MediaFilter.ALL
    transferredOnly = position?.transferredOnly ?: false
    refresh(cachedFirst = true)
    scrollAfterLayout(position?.scrollY ?: 0)
}


internal fun MainActivity.scrollAfterLayout(y: Int) {
    val renderedRequest = requestId
    contentScroll.doOnPreDraw {
        if (requestId == renderedRequest) contentScroll.scrollTo(0, y)
    }
}


// ---- V02-2 批量操作 ----

internal fun MainActivity.visibleSelectionEntries(): List<PanEntry> =
    lastRenderedEntries.filter { entryKey(it) in selectedKeys }


internal fun MainActivity.enterSelection(initial: PanEntry? = null) {
    if (selectionMode || lastRenderedEntries.isEmpty()) return
    selectionMode = true
    selectionScreen = screen
    selectedKeys.clear()
    initial?.let { selectedKeys.add(entryKey(it)) }
    redrawFilteredRows?.invoke() ?: renderBatchBar()
}


internal fun MainActivity.exitSelection() {
    if (!selectionMode) return
    selectionMode = false
    selectionScreen = null
    selectedKeys.clear()
    renderBatchBar()
    redrawFilteredRows?.invoke()
}


/** 离开屏幕/目录时强制退出选择模式（不重绘，交由后续 refresh 完成）。 */
internal fun MainActivity.clearSelectionIfActive() {
    if (!selectionMode) return
    selectionMode = false
    selectionScreen = null
    selectedKeys.clear()
    renderBatchBar()
}


internal fun MainActivity.toggleSelection(entry: PanEntry) {
    val key = entryKey(entry)
    if (!selectedKeys.add(key)) selectedKeys.remove(key)
    redrawFilteredRows?.invoke() ?: renderBatchBar()
}


internal fun MainActivity.selectAllVisible() {
    val visible = lastRenderedEntries.map(::entryKey)
    if (visible.isEmpty()) return
    if (selectedKeys.containsAll(visible)) selectedKeys.removeAll(visible.toSet())
    else selectedKeys.addAll(visible)
    redrawFilteredRows?.invoke() ?: renderBatchBar()
}


internal fun MainActivity.batchFavoriteSelected() {
    val entries = visibleSelectionEntries()
    if (entries.isEmpty()) {
        toast("请先选择条目")
        return
    }
    val allStarred = entries.all { favorites.has(it) }
    if (allStarred) {
        favorites.removeAll(entries)
        toast("已取消收藏 ${entries.size} 项")
    } else {
        favorites.addAll(entries)
        toast("已收藏 ${entries.size} 项")
    }
    updateFavoritesBadge()
    exitSelection()
}


// ===================== P2 网盘文件管理 =====================

internal fun MainActivity.currentDiskDir(): String = folders.lastOrNull()?.path ?: "/"

/** 夸克上传的 pdir_fid 要目录 fid（不是路径）；根目录用 QuarkPersonalDrive.ROOT。 */
internal fun MainActivity.currentDiskFolderId(): String =
    folders.lastOrNull()?.fileId?.takeIf { it.isNotBlank() } ?: QuarkPersonalDrive.ROOT


internal fun MainActivity.currentDiskCacheKey(): String =
    folders.lastOrNull()?.let { "disk:dir:${it.fileId}" } ?: "disk:roots"



internal fun MainActivity.showEntryDetail(entry: PanEntry) {
    val message = buildString {
        append("名称：").append(entry.name).append('\n')
        append("位置：").append(PanApi.readablePath(entry.path)).append('\n')
        append("类型：").append(if (entry.directory) "文件夹" else formatOf(entry)).append('\n')
        if (!entry.directory) {
            append("大小：").append(entry.size.toLongOrNull()?.let { formatSize(it) } ?: "-").append('\n')
        }
        if (entry.md5.isNotEmpty()) append("MD5：").append(entry.md5).append('\n')
        append("来源：").append(entrySourceName(entry))
    }
    AlertDialog.Builder(this).setTitle("详情").setMessage(message).setPositiveButton("关闭", null).show()
}

internal fun MainActivity.diskSelectionActions(entries: List<PanEntry>): List<ActionGridItem> = buildList {
    if (entries.isEmpty()) return@buildList
    val single = entries.singleOrNull()
    val baidu = diskProvider == SourceProvider.BAIDU && entries.all { it.provider == SourceProvider.BAIDU }
    // 夸克走独立的 QuarkDiskWrites：fid 模型 + 纯 Cookie，没有复制端点（官方未实现），
    // 只补删除 / 移动 / 分享三项；混合 provider 时两个分支都不进，保持只读操作。
    val quark = diskProvider == SourceProvider.QUARK && entries.all { it.provider == SourceProvider.QUARK }
    if (single != null) {
        val kind = PanApi.kindOf(single)
        val playback = kind == MediaKind.AUDIO || kind == MediaKind.VIDEO
        add(ActionGridItem(if (playback) "播放" else "打开", when {
            playback -> R.drawable.ic_play
            single.directory -> R.drawable.ic_folder
            else -> R.drawable.ic_file
        }) {
            exitSelection()
            openEntry(single, lastRenderedEntries, currentPlaybackListKey())
        })
    }
    if (entries.any(DownloadLogic::canDownload)) {
        add(ActionGridItem("下载", R.drawable.ic_download) {
            exitSelection()
            downloads.start(entries)
        })
    }
    if (baidu) {
        add(ActionGridItem("分享", R.drawable.ic_share) { diskWrites.promptShare(entries) })
        add(ActionGridItem("移动", R.drawable.ic_folder) {
            if (single == null) diskWrites.batchMoveSelected() else diskWrites.promptMoveCopy(single, copy = false)
        })
        add(ActionGridItem("复制", R.drawable.ic_layers) {
            if (single == null) diskWrites.batchCopySelected() else diskWrites.promptMoveCopy(single, copy = true)
        })
        if (single != null) {
            add(ActionGridItem("重命名", R.drawable.ic_rename) { diskWrites.promptRename(single) })
        }
    }
    if (quark) {
        add(ActionGridItem("分享", R.drawable.ic_share) { quarkWrites.promptShare(entries) })
        add(ActionGridItem("移动", R.drawable.ic_folder) { quarkWrites.promptMove(entries) })
    }
    val allStarred = entries.all { favorites.has(it) }
    add(ActionGridItem(if (allStarred) "取消收藏" else "收藏", if (allStarred) R.drawable.ic_star_filled else R.drawable.ic_star) {
        batchFavoriteSelected()
    })
    if (single != null) add(ActionGridItem("详情", R.drawable.ic_info) { showEntryDetail(single) })
    if (baidu) {
        add(ActionGridItem("删除", R.drawable.ic_trash, destructive = true) { diskWrites.confirmDelete(entries) })
    } else if (quark) {
        add(ActionGridItem("删除", R.drawable.ic_trash, destructive = true) { quarkWrites.confirmDelete(entries) })
    }
}

/**
 * 群组页多选的操作格子：形态与网盘页 [diskSelectionActions] 一致（图标 + 文字）。
 *
 * 群文件是「引用」而非自己的网盘文件，所以不给 分享/移动/复制/重命名/删除；
 * 单选时补「打开/播放」「详情」，让长按菜单取消后单条操作仍然有入口。
 * 转存沿用 [groupTransferEligible] 判据：夸克监控目录 / 联系人 / 关注目录都不显示。
 */
internal fun MainActivity.groupSelectionActions(entries: List<PanEntry>): List<ActionGridItem> = buildList {
    if (entries.isEmpty()) return@buildList
    val single = entries.singleOrNull()
    if (single != null) {
        val kind = PanApi.kindOf(single)
        val playback = kind == MediaKind.AUDIO || kind == MediaKind.VIDEO
        add(ActionGridItem(if (playback) "播放" else "打开", when {
            playback -> R.drawable.ic_play
            single.directory -> R.drawable.ic_folder
            else -> R.drawable.ic_file
        }) {
            exitSelection()
            openEntry(single, lastRenderedEntries, currentPlaybackListKey())
        })
    }
    if (entries.any(DownloadLogic::canDownload)) {
        add(ActionGridItem("下载", R.drawable.ic_download) {
            exitSelection()
            downloads.start(entries)
        })
    }
    if (groupTransferBatches(entries).isNotEmpty()) {
        add(ActionGridItem("转存", R.drawable.ic_cloud) { diskWrites.promptTransfer(entries) })
    }
    val allStarred = entries.all { favorites.has(it) }
    add(ActionGridItem(
        if (allStarred) "取消收藏" else "收藏",
        if (allStarred) R.drawable.ic_star_filled else R.drawable.ic_star,
    ) { batchFavoriteSelected() })
    if (single != null) add(ActionGridItem("详情", R.drawable.ic_info) { showEntryDetail(single) })
}


/** 长按条目的操作分发：写操作交给 diskWrites，播放/收藏仍是本页职责。 */
internal fun MainActivity.showEntryActions(entry: PanEntry, entries: List<PanEntry>) {
    val kind = PanApi.kindOf(entry)
    val choices = mutableListOf<SheetChoice>()
    choices += if (kind == MediaKind.AUDIO || kind == MediaKind.VIDEO) {
        SheetChoice("播放", meta = if (kind == MediaKind.AUDIO) "仅播放此音频" else "播放此视频", tag = "play")
    } else {
        SheetChoice("打开", tag = "open")
    }
    // 显示判据与提交判据共用 groupTransferEligible（唯一真相源），避免两套条件漂移：
    // 上一轮「文件夹长按没有转存入口」就是 UI 手写 !entry.directory 造成的。
    // 目录会由 promptTransfer 二次确认提示，联系人 / 关注目录 / 夸克条目统一在此排除。
    if (groupTransferEligible(entry)) {
        choices += SheetChoice(
            "转存到我的网盘",
            meta = if (entry.directory) "连同文件夹内容一起保存" else "保存为网盘副本",
            tag = "transfer",
        )
    }
    if (!entry.directory) {
        choices += SheetChoice("下载", meta = "保存到本机「下载 / 拾课」", tag = "download")
    }
    choices += SheetChoice(if (favorites.has(entry)) "取消收藏" else "收藏", tag = "favorite")
    choices += SheetChoice("详情", tag = "detail")

    showChoiceSheet(
        title = entry.name,
        subtitle = "文件操作",
        choices = choices,
        onSelect = { choice ->
            when (choice.tag) {
                "play", "open" -> openEntry(entry, entries, currentPlaybackListKey())
                "transfer" -> diskWrites.promptTransfer(listOf(entry))
                "download" -> downloads.start(entry)
                "favorite" -> toggleFavorite(entry)
                "detail" -> showEntryDetail(entry)
            }
        },
    )
}


/** 音频睡眠定时由服务执行。 */
internal fun MainActivity.sendSleepTimer(minutes: Int) {
    controller?.sendCustomCommand(
        AudioPlaybackService.SLEEP_TIMER,
        Bundle().apply { putInt("minutes", minutes) },
    )
}


/** 按扩展名选择音视频播放、文档预览或交给系统应用。 */
internal fun MainActivity.openEntry(entry: PanEntry, entries: List<PanEntry>, listKey: String? = null) {
    if (screen == Screen.FAVORITES && entry.directory) {
        openSourceDirectory(entry)
        return
    }
    val kind = PanApi.kindOf(entry)
    if (kind == MediaKind.AUDIO || kind == MediaKind.VIDEO) selectPlayback(entry, entries, listKey)
    else if (kind != MediaKind.PDF && kind != MediaKind.IMAGE && kind != MediaKind.TEXT &&
        !entry.directory && entry.provider == SourceProvider.QUARK && !isOfficeDocument(entry.name)
    ) {
        startActivity(QuarkDriveActivity.openFile(this, entry))
        return
    }
    when (kind) {
        MediaKind.DIRECTORY -> {
            rememberFolderPosition()
            folders.add(entry)
            transferredOnly = false
            contentScroll.scrollTo(0, 0)
            refresh()
        }
        MediaKind.VIDEO -> startVideo(entry)
        MediaKind.AUDIO -> resolveAudio(entry)
        MediaKind.PDF -> pdfPreview.open(entry)
        MediaKind.IMAGE -> imagePreview.open(entry, entries)
        MediaKind.TEXT, MediaKind.DOCUMENT -> documentPreview.open(entry)
        MediaKind.OTHER -> AlertDialog.Builder(this)
            .setTitle(entry.name)
            .setMessage("此版本暂不支持该类型，只提供目录信息。")
            .setPositiveButton("关闭", null)
            .show()
    }
}


internal fun MainActivity.progressMark(entry: PanEntry): Pair<String, Boolean>? {
    val kind = PanApi.kindOf(entry)
    val item = when (kind) {
        MediaKind.AUDIO -> audioHistory.find(entry)
        MediaKind.VIDEO -> videoHistory.find(entry)
        else -> null
    } ?: return null
    val verb = if (kind == MediaKind.AUDIO) "听完" else "看完"
    if (item.completed) return "已$verb" to true
    if (item.seconds <= 0) return null
    return "${if (kind == MediaKind.AUDIO) "听到" else "看到"} ${formatAudioTime(item.seconds)}" to false
}


internal fun MainActivity.toggleFavorite(entry: PanEntry): Boolean {
    if (!hasVerifiedAccount()) return false
    val starred = favorites.toggle(entry)
    updateFavoritesBadge()
    if (screen == Screen.FAVORITES) {
        content.removeAllViews()
        renderFavorites()
    } else if (screen == Screen.HOME && folders.isEmpty()) {
        renderHomeSections()
    } else {
        content.findViewWithTag<ImageView>(favoriteTag(entry))?.let { paintFavorite(it, starred) }
    }
    toast(if (starred) "已加入收藏" else "已取消收藏")
    return starred
}


internal fun MainActivity.showLoginDialog(provider: SourceProvider? = null) {
    val dialog = LoginDialog(this, web.settings.userAgentString) {
        if (provider == SourceProvider.BAIDU) pendingAddProvider = SourceProvider.BAIDU
        showApp(recheck = true)
    }
    sizeSheet(dialog) { available -> (available * 0.94f).toInt() }
    dialog.show()
}
