package com.hnwd.bdplayer

import android.view.View

/**
 * 百度「网盘目录」来源的目录列表链路。
 *
 * 群组 / 联系人走 PanApi.recent/roots/children（群共享消息模型）；网盘目录走
 * BaiduPersonalDrive.list（/api/list?dir=）列**第一层、不递归**，与夸克监控目录同口径。
 * 结果转 PanEntry 后复用 CourseCache / display / renderCourse，播放下载仍靠 isDisk 链路。
 *
 * 百度个人网盘条目的 gid 为空。浏览时用活跃来源确定关注目录，用文件路径读取子目录。
 */

/** 切页、切目录或切账号后，作废浏览请求及其取会话参数的请求。 */
private fun MainActivity.baiduDirectoryRequestGuard(): () -> Boolean {
    val request = requestId
    val account = accountProbeId
    return { request == requestId && account == accountProbeId && !isDestroyed && !isFinishing }
}

private fun CourseCache.writeBaiduDirectoryPage(key: String, page: Int, data: DrivePage<PanEntry>): Boolean {
    val hasMore = data.hasMore && data.items.isNotEmpty()
    val changed = writeIfChanged(key, page, data.items, if (hasMore) "more" else "")
    if (changed || !hasMore) clearAfter(key, page)
    return changed
}

/** 首页「群最新更新」：目录第一层按修改时间倒序当快照，复用夸克的 directorySnapshot 分支。 */
internal fun MainActivity.startBaiduDirectoryHomeCheck(
    force: Boolean,
    cacheKey: String,
    homeRootKey: String,
    cached: List<PanEntry>,
    recentIsEmpty: Boolean,
    checkToken: Int,
    onRefresh: ((String?) -> Unit)?,
) {
    val dir = directorySourcePath(gid)
    val browseCurrent = baiduDirectoryRequestGuard()
    fun isCurrent() = checkToken == homeCheckToken && browseCurrent()
    fun cancelled() {
        if (checkToken == homeCheckToken) homeCheckInFlight = false
        onRefresh?.invoke(null)
    }
    fun failed() {
        if (!isCurrent()) {
            cancelled()
            return
        }
        homeCheckInFlight = false
        if (onRefresh != null) {
            cacheFailure(cacheKey)
            onRefresh(HOME_REFRESH_FAILED)
        } else if (recentIsEmpty) {
            loadHomeDirectory(homeRootKey, force = false)
        } else if (cached.isEmpty()) {
            status.text = "无法加载目录内容，请稍后重试"
            content.addView(headAction("打开文件库") { showScreen(Screen.GROUPS) })
        } else cacheFailure(cacheKey)
    }
    if (dir.isEmpty()) {
        failed()
        return
    }
    ensureBdstoken(
        onReady = { token ->
            val request = BaiduPersonalDrive(token).list(dir, 1, 100)
            load(
                request.url, lane = RequestLane.COURSE, statusHint = null,
                onCancelled = { cancelled() }, onFailure = { failed() },
                requestCurrent = ::isCurrent,
            ) { json ->
                val page = runCatching { request.decode(json) }.getOrNull()
                if (page == null) {
                    failed()
                    return@load
                }
                courseCache.writeBaiduDirectoryPage(homeRootKey, 1, page)
                val fresh = page.items.sortedByDescending { it.modifiedAt }
                applyHomeRecentEntries(
                    fresh, cacheKey, homeRootKey, force, checkToken, onRefresh,
                    directorySnapshot = true,
                )
            }
        },
        onFailure = { failed() },
        lane = RequestLane.COURSE,
        requestCurrent = ::isCurrent,
    )
}

/** 首页回落 / GROUPS 页根目录：列来源目录第一层，分页由「加载更多」触发。 */
internal fun MainActivity.loadBaiduDirectoryRoots(
    key: String,
    page: Int = 1,
    previousButton: View? = null,
    onRefresh: ((String?) -> Unit)? = null,
) {
    val dir = directorySourcePath(gid)
    val isCurrent = baiduDirectoryRequestGuard()
    fun done() {
        previousButton?.isEnabled = true
    }
    fun failed() {
        done()
        if (!isCurrent()) {
            onRefresh?.invoke(null)
            return
        }
        if (page == 1) {
            cacheFailure(key)
            if (onRefresh == null) renderRootMore(key)
        }
        status.text = "读取网盘目录失败，请重试"
        onRefresh?.invoke(HOME_REFRESH_FAILED)
    }
    if (dir.isEmpty()) {
        failed()
        return
    }
    ensureBdstoken(
        onReady = { token ->
            val request = BaiduPersonalDrive(token).list(dir, page, 100)
            load(
                request.url, lane = RequestLane.COURSE, statusHint = null,
                onCancelled = { done(); onRefresh?.invoke(null) },
                onFailure = { failed() },
                requestCurrent = isCurrent,
            ) { json ->
                done()
                val pageData = runCatching { request.decode(json) }.getOrNull()
                if (pageData == null) {
                    failed()
                    return@load
                }
                val entries = pageData.items
                val message = onRefresh?.let { homeRefreshMessage(key, entries) }
                courseCache.writeBaiduDirectoryPage(key, page, pageData)
                cacheNote = "已同步 ${courseCache.syncLabel(key)}"
                if (showingHomeDirectory || onRefresh != null) {
                    val scrollY = contentScroll.scrollY
                    renderHomeDirectory(key, preserveMediaFilter = onRefresh != null)
                    if (onRefresh != null) scrollAfterLayout(scrollY)
                } else {
                    content.removeAllViews()
                    renderSourcePicker()
                    display(courseCache.readAll(key))
                    status.text = cacheNote
                }
                renderRootMore(key)
                onRefresh?.invoke(message)
            }
        },
        onFailure = { failed() },
        lane = RequestLane.COURSE,
        requestCurrent = isCurrent,
    )
}

/** 从已关注的目录下钻一层：用父目录的绝对路径再列一次。 */
internal fun MainActivity.loadBaiduDirectoryChildren(
    parent: PanEntry,
    page: Int,
    previousButton: View? = null,
    cacheKey: String = sourceFolderCacheKey(gid, parent),
) {
    val dir = parent.path
    val isCurrent = baiduDirectoryRequestGuard()
    fun done() {
        previousButton?.isEnabled = true
    }
    fun failed() {
        done()
        if (!isCurrent()) return
        if (page == 1) cacheFailure(cacheKey)
        status.text = "读取网盘目录失败，请重试"
    }
    if (dir.isEmpty()) {
        failed()
        return
    }
    ensureBdstoken(
        onReady = { token ->
            val request = BaiduPersonalDrive(token).list(dir, page, 100)
            load(
                request.url, lane = RequestLane.COURSE, statusHint = null,
                onCancelled = { done() }, onFailure = { failed() },
                requestCurrent = isCurrent,
            ) { json ->
                done()
                val pageData = runCatching { request.decode(json) }.getOrNull()
                if (pageData == null) {
                    failed()
                    return@load
                }
                val changed = courseCache.writeBaiduDirectoryPage(cacheKey, page, pageData)
                courseEntries.clear()
                courseEntries.addAll(courseCache.readAll(cacheKey))
                cacheNote = "已同步 ${courseCache.syncLabel(cacheKey)}"
                renderCourse()
                if (!changed) status.text = "$cacheNote · 无更新"
                renderChildMore(parent, cacheKey)
            }
        },
        onFailure = { failed() },
        lane = RequestLane.COURSE,
        requestCurrent = isCurrent,
    )
}
