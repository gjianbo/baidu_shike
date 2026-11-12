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


internal fun MainActivity.enterSelection() {
    if (selectionMode || lastRenderedEntries.isEmpty()) return
    selectionMode = true
    selectionScreen = screen
    selectedKeys.clear()
    renderBatchBar()
    redrawFilteredRows?.invoke()
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
    renderBatchBar()
    redrawFilteredRows?.invoke()
}


internal fun MainActivity.selectAllVisible() {
    val visible = lastRenderedEntries.map(::entryKey)
    if (visible.isEmpty()) return
    if (selectedKeys.containsAll(visible)) selectedKeys.removeAll(visible.toSet())
    else selectedKeys.addAll(visible)
    renderBatchBar()
    redrawFilteredRows?.invoke()
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


/** 长按条目的操作分发：写操作交给 diskWrites，播放/收藏仍是本页职责。 */
internal fun MainActivity.showEntryActions(entry: PanEntry, entries: List<PanEntry>) {
    val kind = PanApi.kindOf(entry)
    val quark = entry.provider == SourceProvider.QUARK
    val choices = mutableListOf<SheetChoice>()
    choices += if (kind == MediaKind.AUDIO || kind == MediaKind.VIDEO) {
        SheetChoice("播放", meta = if (kind == MediaKind.AUDIO) "仅播放此音频" else "播放此视频", tag = "play")
    } else {
        SheetChoice("打开", tag = "open")
    }
    if (entry.isDisk && screen == Screen.DISK && !quark) {
        choices += SheetChoice("重命名", tag = "rename")
        choices += SheetChoice("移动", tag = "move")
        choices += SheetChoice("复制", tag = "copy")
        choices += SheetChoice("分享", meta = "生成分享链接", tag = "share")
        choices += SheetChoice("删除", meta = "移入回收站", tag = "delete")
    } else if (!entry.isDisk && !entry.directory && !quark) {
        choices += SheetChoice("转存到我的网盘", meta = "保存为网盘副本", tag = "transfer")
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
                "rename" -> diskWrites.promptRename(entry)
                "move" -> diskWrites.promptMoveCopy(entry, copy = false)
                "copy" -> diskWrites.promptMoveCopy(entry, copy = true)
                "share" -> diskWrites.promptShare(listOf(entry))
                "delete" -> diskWrites.confirmDelete(listOf(entry))
                "transfer" -> diskWrites.promptTransfer(entry)
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


/** 按扩展名选择音视频播放或 PDF 下载。 */
internal fun MainActivity.openEntry(entry: PanEntry, entries: List<PanEntry>, listKey: String? = null) {
    if (screen == Screen.FAVORITES && entry.directory) {
        openSourceDirectory(entry)
        return
    }
    val kind = PanApi.kindOf(entry)
    if (kind == MediaKind.AUDIO || kind == MediaKind.VIDEO) selectPlayback(entry, entries, listKey)
    else if (kind != MediaKind.PDF && kind != MediaKind.IMAGE && !entry.directory && entry.provider == SourceProvider.QUARK) {
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
        MediaKind.DOCUMENT -> AlertDialog.Builder(this)
            .setTitle(entry.name)
        .setMessage("App 内目前支持阅读 PDF。请在百度网盘中打开或下载此文件。")
            .setPositiveButton("关闭", null)
            .show()
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
