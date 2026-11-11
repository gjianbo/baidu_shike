package com.hnwd.bdplayer

import android.os.Bundle
import android.view.View
import android.webkit.CookieManager
import org.json.JSONObject
import org.json.JSONTokener

/**
 * 浏览主流程：刷新、网盘/夸克分页加载与账号校验。
 *
 * 从 MainActivity.kt 按域原样搬出的扩展函数：函数体与语义未改，
 * 仅把 private 提升为 internal，并用 this@? 保真原 this@MainActivity 引用。
 */

internal fun MainActivity.refresh(force: Boolean = false, cachedFirst: Boolean = false) {
    if ((screen != Screen.HOME || folders.isNotEmpty()) && homeCheckInFlight) {
        homeCheckToken++
        homeCheckInFlight = false
    }
    if (screen == Screen.FAVORITES) {
        refreshFavoritesPage()
        return
    }
    if (screen == Screen.DISK) {
        refreshDisk()
        return
    }
    requestId++
    clearHomeUpdatePrompt()
    homeDisplayedEntries = emptyList()
    redrawFilteredRows = null
    showingHomeDirectory = false
    filterCount.text = ""
    content.removeAllViews()
    sortButton.visibility = View.GONE
    showAvailableMediaFilters(emptySet())
    styleContent()
    val earlyParent = folders.lastOrNull()
    val homeSourceKey = if (isContactSource(gid)) "roots:$gid" else "recent:$gid"
    val earlyKey = if (screen == Screen.HOME && earlyParent == null && gid.isNotEmpty()) homeSourceKey else ""
    val earlyCached = if (!hasActiveSession() && (checkingAccount || !ready) && earlyKey.isNotEmpty()) {
        courseCache.readAll(earlyKey)
    } else {
        emptyList()
    }
    if (earlyCached.isNotEmpty()) {
        updateNowPlaying()
        updateNav()
        filterBar.visibility = View.GONE
        sectionRow.visibility = View.VISIBLE
        renderHomeSections(allowUnverified = true)
        renderCourseSwitch()
        title.text = "首页"
        appSub.text = ""
        cacheNote = "缓存内容 · 上次同步 ${courseCache.syncLabel(earlyKey)}"
        display(earlyCached)
        status.text = cacheNote
        return
    }
    if (!hasActiveSession()) {
        val quark = currentPageProvider() == SourceProvider.QUARK
        val providerName = if (quark) "夸克网盘" else "百度网盘"
        historyPanel.removeAllViews()
        filterBar.visibility = View.GONE
        title.text = when (screen) {
            Screen.SETTINGS -> "设置"
            Screen.DISK -> "我的网盘"
            Screen.GROUPS -> groupTabLabel()
            else -> "首页"
        }
        appSub.text = "连接${providerName}后查看内容"
        courseEntries.clear()
        renderCourseSwitch()
        updateNowPlaying()
        updateNav()
        status.text = when {
            quark -> "请先登录夸克网盘"
            checkingAccount || !ready -> "正在连接网盘并确认账号…"
            else -> "无法确认网盘账号，请登录后刷新。"
        }
        if (screen == Screen.SETTINGS) {
            settingsPage.render()
            return
        }
        content.addView(headAction("登录$providerName") {
            if (quark) quarkLoginLauncher.launch(QuarkDriveActivity.loginIntent(this))
            else showLoginDialog()
        })
        return
    }
    updateNowPlaying()
    val parent = folders.lastOrNull()
    val cacheKey = when {
        parent != null -> "dir:${parent.gid}:${parent.messageId}:${parent.fileId}"
        screen == Screen.FAVORITES || screen == Screen.SETTINGS -> ""
        screen == Screen.HOME -> homeSourceKey
        else -> "roots:$gid"
    }
    val key = parent?.let { "${it.gid}:${it.messageId}:${it.fileId}" }.orEmpty()
    if (key != courseParentKey) {
        courseParentKey = key
        courseEntries.clear()
    }
    updateNav()
    updateFilterChips()
    // 由 addFilteredCourseRows 按实际内容决定是否显示；空列表/空目录不露出孤立筛选条。
    filterBar.visibility = View.GONE
    title.text = when (screen) {
        Screen.FAVORITES -> "收藏"
        Screen.SETTINGS -> "设置"
        Screen.DISK -> parent?.name ?: "我的网盘"
        Screen.GROUPS -> parent?.name ?: groupTabLabel()
        else -> parent?.name ?: "首页"
    }
    appSub.text = when {
        parent != null -> "第 ${folders.size} 级目录"
        screen == Screen.FAVORITES -> "仅保存在本机"
        screen == Screen.SETTINGS -> "外观、备份与本机数据"
        screen == Screen.DISK -> "个人文件 · 已转存内容可直接播放"
        screen == Screen.GROUPS -> when {
            isContactSource(gid) -> "联系人文件库"
            currentPageProvider() == SourceProvider.QUARK -> "夸克网盘 · 监控目录"
            else -> "群组文件库"
        }
        screen == Screen.HOME -> verifiedAccountName.trim().takeIf { it.isNotEmpty() }?.let { "你好，$it" } ?: ""
        else -> ""
    }
    renderCrumbs()
    renderHomeSections()
    renderCourseSwitch()
    if ((screen == Screen.HOME || screen == Screen.GROUPS) && parent == null) {
        renderSourcePicker()
    }
    val cached = if (cacheKey.isNotEmpty()) courseCache.readAll(cacheKey) else emptyList()
    val contactHome = screen == Screen.HOME && parent == null && isContactSource(gid)
    val homeRootKey = if (screen == Screen.HOME && !contactHome && parent == null && gid.isNotEmpty()) "roots:$gid" else null
    showingHomeDirectory = contactHome
    if (contactHome) sectionTitle.text = "群组"
    val recentPage = homeRootKey?.let { courseCache.readPage(cacheKey, 1) }
    val recentIsEmpty = recentPage?.let { it.latestEmpty || it.entries.isEmpty() } == true
    cacheNote = if (cached.isNotEmpty()) {
        "缓存内容 · 上次同步 ${courseCache.syncLabel(cacheKey)}"
    } else ""
    if (homeRootKey != null && recentIsEmpty) {
        renderHomeDirectory(homeRootKey)
    } else if (cached.isNotEmpty() || (cachedFirst && courseCache.readPage(cacheKey, 1) != null)) {
        if (parent != null) {
            courseEntries.clear()
            courseEntries.addAll(cached)
            renderCourse()
        } else {
            display(cached)
        }
    } else if (parent != null) {
        content.addView(headAction("← 返回上一级") {
            returnToFolder(folders.lastIndex)
        })
    }
    when {
        screen == Screen.FAVORITES && parent == null -> renderFavorites()
        screen == Screen.SETTINGS -> settingsPage.render()
        parent == null && gid.isEmpty() -> {
            status.text = "请从网盘勾选共享来源"
        }
        cachedFirst && courseCache.readPage(cacheKey, 1) != null -> {
            when {
                parent != null -> renderChildMore(parent, cacheKey)
                showingHomeDirectory && homeRootKey != null -> {
                    loadHomeDirectory(homeRootKey, force = false)
                }
                screen == Screen.GROUPS || contactHome -> renderRootMore(cacheKey)
            }
        }
        !hasActiveSession() -> {
            status.text = if (cached.isNotEmpty()) "$cacheNote · 正在连接网盘"
            else "正在连接网盘。如果列表无法加载，请先登录。"
        }
        cacheKey.isNotEmpty() && parent == null && !force && screen == Screen.GROUPS &&
            courseCache.isFresh(cacheKey, CACHE_TTL_MS) &&
            courseCache.readPage(cacheKey, 1)?.nextCursor != null -> {
            status.text = "$cacheNote · 30 分钟内已检查"
            renderRootMore(cacheKey)
        }
        parent != null -> if (providerOfSourceId(parent.gid) == SourceProvider.QUARK) {
            loadQuarkChildren(parent, 1, cacheKey = cacheKey)
        } else {
            loadChildren(parent, 1, cacheKey = cacheKey)
        }
        homeRootKey != null -> {
            if (!force && homeCheckInFlight) {
                status.text = if (cacheNote.isNotEmpty()) "$cacheNote · 正在检查更新" else "正在检查最近更新…"
            } else if (!force && System.currentTimeMillis() - lastHomeCheckAt < HOME_RECHECK_COOLDOWN_MS) {
                if (recentIsEmpty) loadHomeDirectory(homeRootKey, force = false)
                else status.text = if (cacheNote.isNotEmpty()) "$cacheNote · 刚刚已检查" else ""
            } else {
                startHomeCheck(force, cacheKey, homeRootKey, cached, recentIsEmpty)
            }
        }
        else -> if (providerOfSourceId(gid) == SourceProvider.QUARK) {
            loadQuarkRoots(cacheKey)
        } else {
            loadRoots(cacheKey)
        }
    }
}


internal fun MainActivity.refreshFavoritesPage() {
    requestId++
    clearHomeUpdatePrompt()
    updateNowPlaying()
    updateNav()
    title.text = "收藏"
    title.setTextSize(30f)
    appSub.visibility = View.GONE
    historyPanel.removeAllViews()
    sectionRow.visibility = View.GONE
    filterBar.visibility = View.GONE
    sortButton.visibility = View.GONE
    crumbsScroll.visibility = View.GONE
    redrawFilteredRows = null
    content.removeAllViews()
    status.text = ""
    if (!hasVerifiedAccount()) {
        content.addView(headAction("登录百度网盘") { showLoginDialog() })
        return
    }
    renderFavorites()
}


/** 网盘页只切网盘；首页/群组切换后自动选中该网盘的来源目录并列出内容。 */
internal fun MainActivity.chooseProvider() {
    val isDisk = screen == Screen.DISK
    val current = if (isDisk) diskProvider else providerOfSourceId(gid)
    val baiduReady = hasVerifiedAccount()
    val quarkReady = QuarkPersonalDrive.hasLoginCookie(
        CookieManager.getInstance().getCookie(QuarkPersonalDrive.HOME),
    )
    showChoiceSheet(
        title = "切换网盘",
        subtitle = if (isDisk) "只切换网盘，不影响首页与群组的来源" else "切换后自动选中该网盘的来源目录",
        choices = listOf(
            SheetChoice(
                "百度网盘",
                meta = if (baiduReady) "已登录" else "未登录",
                checked = current == SourceProvider.BAIDU,
                tag = SourceProvider.BAIDU,
            ),
            SheetChoice(
                "夸克网盘",
                meta = if (quarkReady) "已登录" else "未登录",
                checked = current == SourceProvider.QUARK,
                tag = SourceProvider.QUARK,
            ),
        ),
        onSelect = { choice ->
            val target = choice.tag as? SourceProvider
            if (target != null && target != current) {
                if (isDisk) applyDiskProvider(target) else applySourceProvider(target)
            }
        },
    )
}


/** 首页/群组：切到指定网盘，自动选该网盘已配置的来源（优先上次用的），清空目录栈并刷新。 */
internal fun MainActivity.applySourceProvider(target: SourceProvider) {
    val sources = shareSources().filter { it.provider == target }
    if (sources.isEmpty()) {
        toast(if (target == SourceProvider.QUARK) "还没有夸克监控目录，请先添加" else "还没有百度来源，请先添加")
        if (target == SourceProvider.QUARK) manageQuarkSources() else manageBaiduSources()
        return
    }
    val prefs = getSharedPreferences("account_scope", android.content.Context.MODE_PRIVATE)
    val memoryKey = AccountScope.key("source_last_${target.name.lowercase()}")
    val remembered = prefs.getString(memoryKey, "").orEmpty()
    val pick = sources.firstOrNull { it.id == sourceId(target, remembered) } ?: sources.first()
    selectSource(pick)
}


internal fun MainActivity.applyDiskProvider(target: SourceProvider) {
    diskProvider = target
    getSharedPreferences("account_scope", android.content.Context.MODE_PRIVATE).edit()
        .putString(AccountScope.key("disk_provider"), target.name)
        .apply()
    folders.clear()
    courseEntries.clear()
    courseParentKey = ""
    tabFolders[Screen.DISK] = emptyList()
    refreshDisk()
}


internal fun MainActivity.refreshDisk() {
    requestId++
    clearHomeUpdatePrompt()
    homeDisplayedEntries = emptyList()
    redrawFilteredRows = null
    showingHomeDirectory = false
    filterCount.text = ""
    content.removeAllViews()
    historyPanel.removeAllViews()
    status.text = ""
    sortButton.visibility = View.GONE
    showAvailableMediaFilters(emptySet())
    styleContent()
    updateNowPlaying()
    updateNav()
    if (!hasActiveSession()) {
        filterBar.visibility = View.GONE
        sectionRow.visibility = View.GONE
        title.text = "我的网盘"
        if (diskProvider == SourceProvider.QUARK) {
            appSub.text = "连接夸克网盘后浏览我的网盘"
            content.addView(headAction("登录夸克网盘") {
                quarkLoginLauncher.launch(QuarkDriveActivity.loginIntent(this))
            })
        } else {
            appSub.text = "连接百度网盘后浏览我的网盘"
            content.addView(headAction("登录百度网盘") { showLoginDialog() })
        }
        return
    }
    val parent = folders.lastOrNull()
    val cacheKey = when {
        diskProvider == SourceProvider.QUARK ->
            if (parent == null) "disk:quark:roots" else "disk:quark:dir:${parent.fileId}"
        parent == null -> "disk:roots"
        else -> "disk:dir:${parent.fileId}"
    }
    val key = parent?.let { "disk:${it.fileId}" }.orEmpty()
    if (key != courseParentKey) {
        courseParentKey = key
        courseEntries.clear()
    }
    updateFilterChips()
    filterBar.visibility = View.GONE
    title.text = parent?.name ?: "我的网盘"
    appSub.text = if (parent == null) "个人文件 · 已转存内容可直接播放" else "第 ${folders.size} 级目录"
    renderCrumbs()
    renderCourseSwitch()
    val cached = courseCache.readAll(cacheKey)
    cacheNote = if (cached.isNotEmpty()) {
        "缓存内容 · 上次同步 ${courseCache.syncLabel(cacheKey)}"
    } else ""
    if (cached.isNotEmpty()) {
        courseEntries.clear()
        courseEntries.addAll(cached)
        renderCourse()
        status.text = cacheNote
    } else if (parent != null) {
        content.addView(headAction("← 返回上一级") {
            returnToFolder(folders.lastIndex)
        })
    }
    if (diskProvider == SourceProvider.QUARK) {
        loadQuarkDiskPage(parent?.fileId ?: QuarkPersonalDrive.ROOT, parent?.path ?: "/", cacheKey)
    } else {
        loadDiskPage(parent?.path ?: "/", 1, cacheKey)
    }
}


/**
 * 夸克网盘列表：首版一次取 200 条、不分页、不写缓存。
 * 目录下钻沿用 folders 栈，父目录 fid 直接作为 pdir_fid。
 */
internal fun MainActivity.loadQuarkDiskPage(dirFid: String, parentPath: String, cacheKey: String) {
    status.text = "正在读取夸克网盘…"
    val request = ++requestId
    Thread {
        val result = runCatching {
            val session = QuarkPersonalDrive.openSession() ?: throw QuarkLoginRequired()
            val page = session.execute(session.list(dirFid, 1, 200))
            page.items
                .sortedWith(compareByDescending<QuarkFile> { it.directory }.thenBy { it.name })
                .map { quarkEntry(it, DISK_SOURCE_ID, EntryOrigin.DISK, parentPath) }
        }
        runOnUiThread {
            if (request != requestId || isFinishing || isDestroyed) return@runOnUiThread
            result.onFailure { error ->
                status.text = "无法读取夸克网盘：${error.message ?: "未知错误"}"
                if (courseEntries.isEmpty()) {
                    content.addView(headAction("重新登录夸克网盘") {
                        quarkLoginLauncher.launch(QuarkDriveActivity.loginIntent(this))
                    })
                }
            }.onSuccess { entries ->
                courseEntries.clear()
                courseEntries.addAll(entries)
                renderCourse()
                status.text = if (entries.isEmpty()) "此目录暂无内容" else ""
            }
        }
    }.start()
}


internal fun MainActivity.ensureBdstoken(onReady: (String) -> Unit, onFailure: () -> Unit) {
    if (bdstoken.isNotEmpty()) {
        onReady(bdstoken)
        return
    }
    val account = verifiedAccount
    load(
        PanApi.groupListSession(), lane = RequestLane.LIST, statusHint = "正在连接我的网盘…",
        onCancelled = onFailure, onFailure = onFailure,
    ) { json ->
        val session = json.getJSONObject("result")
        if (session.optString("uk") != account) {
            verifyAndRefresh()
            onFailure()
            return@load
        }
        val token = session.optString("bdstoken")
        if (token.isBlank() || session.isNull("bdstoken")) {
            status.text = "网盘会话参数缺失，请重新登录。"
            onFailure()
        } else {
            bdstoken = token
            onReady(token)
        }
    }
}


internal fun MainActivity.loadDiskPage(dir: String, page: Int, cacheKey: String, previousButton: View? = null) {
    val expectedParentKey = courseParentKey
    ensureBdstoken(
        onReady = { token ->
            val request = BaiduPersonalDrive(token).list(dir, page, 100)
            load(
                request.url, lane = RequestLane.COURSE,
                statusHint = if (page == 1 && courseCache.readAll(cacheKey).isEmpty()) {
                    "正在读取我的网盘…"
                } else null,
                onCancelled = { previousButton?.isEnabled = true },
                onFailure = {
                    previousButton?.isEnabled = true
                    if (page == 1 && courseCache.readAll(cacheKey).isEmpty()) {
                        status.text = "读取我的网盘失败，请刷新重试。"
                    }
                },
            ) { json ->
                if (screen != Screen.DISK || courseParentKey != expectedParentKey) return@load
                val (entries, hasMore) = request.decode(json)
                val changed = courseCache.writeIfChanged(
                    cacheKey, page, entries, if (hasMore) "more" else "",
                )
                if (changed || !hasMore) courseCache.clearAfter(cacheKey, page)
                diskIndexCache = null
                val all = courseCache.readAll(cacheKey)
                courseEntries.clear()
                courseEntries.addAll(all)
                cacheNote = "已同步 ${courseCache.syncLabel(cacheKey)}"
                renderCourse()
                if (all.isEmpty()) content.addView(label("此目录暂无内容", 12f))
                filterBar.visibility = if (hasFilterOptions(all)) View.VISIBLE else View.GONE
                renderDiskMore(dir, cacheKey)
                status.text = if (!changed && page == 1) "$cacheNote · 无更新" else cacheNote
            }
        },
        onFailure = {
            previousButton?.isEnabled = true
            if (courseCache.readAll(cacheKey).isEmpty()) status.text = "无法读取我的网盘，请登录后重试。"
        },
    )
}


internal fun MainActivity.renderDiskMore(dir: String, cacheKey: String) {
    val (page, cached) = courseCache.lastPage(cacheKey) ?: return
    if (cached.nextCursor != "more") return
    val more = headAction("加载更多") {}
    more.setBackgroundResource(R.drawable.bg_load_more)
    more.setTextColor(color(R.color.ink3))
    more.setOnClickListener {
        more.isEnabled = false
        loadDiskPage(dir, page + 1, cacheKey, more)
    }
    content.addView(more)
}


internal fun MainActivity.loadRoots(key: String, page: Int = 1, cursor: String = "", previousButton: View? = null) {
    val group = gid
    val homeDirectory = showingHomeDirectory
    load(
        PanApi.roots(group, cursor),
        post = isContactSource(group),
        statusHint = if (homeDirectory) "正在读取文件库…"
        else if (page == 1 && cacheNote.isNotEmpty()) "$cacheNote · 正在检查更新" else null,
        onCancelled = { previousButton?.isEnabled = true },
        onFailure = {
            previousButton?.isEnabled = true
            if (page == 1) {
                cacheFailure(key)
                renderRootMore(key)
            }
        },
    ) { json ->
        val entries = PanApi.parseRoots(json, group)
        val next = nextRootCursor(json, group, cursor)
        courseCache.writeIfChanged(key, page, entries, next)
        courseCache.clearAfter(key, page)
        cacheNote = "已同步 ${courseCache.syncLabel(key)}"
        if (homeDirectory) {
            renderHomeDirectory(key)
        } else {
            content.removeAllViews()
            renderSourcePicker()
            display(courseCache.readAll(key))
        }
        renderRootMore(key)
        val pagination = if (isContactSource(group)) json.getJSONObject("records") else json
        if (next.isEmpty() && PanApi.hasMore(pagination)) {
            status.text = "网盘未返回可用的分页游标，请刷新后重试。"
        }
    }
}


internal fun MainActivity.nextRootCursor(json: JSONObject, sourceId: String, cursor: String): String {
    if (isContactSource(sourceId)) {
        val records = json.getJSONObject("records")
        return records.optString("cursor", "").takeIf {
            PanApi.hasMore(records) && !records.isNull("cursor") && it != cursor
        }.orEmpty()
    }
    return json.optString("last_msg_time").takeIf {
        val time = it.toLongOrNull()
        PanApi.hasMore(json) && time != null && time > 0 &&
            time < (cursor.toLongOrNull() ?: Long.MAX_VALUE)
    }.orEmpty()
}


/**
 * 夸克来源的目录列表（群组/首页的「监控目录」）。走 QuarkPersonalDrive 原生 HTTP，
 * 结果转 PanEntry 后复用百度侧的缓存/渲染（CourseCache/display/renderCourse）。
 */
internal fun MainActivity.loadQuarkRoots(key: String, page: Int = 1, previousButton: View? = null) {
    val sourceId = gid
    if (providerOfSourceId(sourceId) != SourceProvider.QUARK) {
        loadRoots(key)
        return
    }
    status.text = if (page == 1 && cacheNote.isNotEmpty()) "$cacheNote · 正在检查更新" else "正在读取夸克目录…"
    val token = ++requestId
    Thread {
        val result = runCatching {
            val session = QuarkPersonalDrive.openSession() ?: throw QuarkLoginRequired()
            session.execute(session.list(rawSourceId(sourceId), page, 100))
        }
        runOnUiThread {
            previousButton?.isEnabled = true
            if (token != requestId) return@runOnUiThread
            result.onFailure { error ->
                if (page == 1) cacheFailure(key)
                status.text = when (error) {
                    is QuarkLoginRequired -> "请先登录夸克网盘"
                    is IllegalStateException -> error.message ?: "读取夸克目录失败"
                    else -> "读取夸克目录失败，请稍后重试"
                }
                if (page == 1) renderRootMore(key)
            }.onSuccess { pageData ->
                val entries = pageData.items.map { quarkEntry(it, sourceId) }
                courseCache.writeIfChanged(key, page, entries, if (pageData.hasMore) "more" else "")
                if (!pageData.hasMore) courseCache.clearAfter(key, page)
                cacheNote = "已同步 ${courseCache.syncLabel(key)}"
                if (showingHomeDirectory) renderHomeDirectory(key)
                else {
                    content.removeAllViews()
                    renderSourcePicker()
                    display(courseCache.readAll(key))
                    status.text = cacheNote
                }
                renderRootMore(key)
            }
        }
    }.start()
}


/** 夸克子目录列表（从监控目录下钻）。 */
internal fun MainActivity.loadQuarkChildren(
    parent: PanEntry,
    page: Int,
    previousButton: View? = null,
    cacheKey: String = "",
) {
    val key = cacheKey.ifEmpty { "dir:${parent.gid}:${parent.messageId}:${parent.fileId}" }
    val sourceId = parent.gid
    status.text = if (page == 1 && cacheNote.isNotEmpty()) "$cacheNote · 正在检查更新" else "正在读取…"
    val token = ++requestId
    Thread {
        val result = runCatching {
            val session = QuarkPersonalDrive.openSession() ?: throw QuarkLoginRequired()
            session.execute(session.list(parent.fileId, page, 100))
        }
        runOnUiThread {
            previousButton?.isEnabled = true
            if (token != requestId) return@runOnUiThread
            result.onFailure { error ->
                if (page == 1) cacheFailure(key)
                status.text = when (error) {
                    is QuarkLoginRequired -> "请先登录夸克网盘"
                    else -> "读取夸克目录失败，请稍后重试"
                }
            }.onSuccess { pageData ->
                val entries = pageData.items.map {
                    quarkEntry(it, sourceId, EntryOrigin.GROUP, parent.path)
                }
                val hasMore = pageData.hasMore && entries.isNotEmpty()
                val changed = courseCache.writeIfChanged(key, page, entries, if (hasMore) "more" else "")
                if (changed || !hasMore) courseCache.clearAfter(key, page)
                courseEntries.clear()
                courseEntries.addAll(courseCache.readAll(key))
                cacheNote = "已同步 ${courseCache.syncLabel(key)}"
                renderCourse()
                if (!changed) status.text = "$cacheNote · 无更新"
                renderChildMore(parent, key)
            }
        }
    }.start()
}


internal fun MainActivity.renderRootMore(key: String) {
    val (page, cached) = courseCache.lastPage(key) ?: return
    val cursor = cached.nextCursor?.takeIf { it.isNotEmpty() } ?: return
    val more = headAction("加载更多") {}
    more.setBackgroundResource(R.drawable.bg_load_more)
    more.setTextColor(color(R.color.ink3))
    more.setOnClickListener {
        more.isEnabled = false
        if (providerOfSourceId(gid) == SourceProvider.QUARK) loadQuarkRoots(key, page + 1, more)
        else loadRoots(key, page + 1, cursor, more)
    }
    content.addView(more)
}


internal fun MainActivity.invalidateAccount() {
    pdfPreviewRef?.close()
    imagePreviewRef?.close()
    accountProbeId++
    courseRequestId++
    automaticCourseLoad = null
    historyRefreshPending = false
    searchSectionRef?.dismiss()
    renewalRequestId++
    homeCheckToken++
    homeCheckInFlight = false
    lastHomeCheckAt = 0L
    renewingPlaybackId = null
    cancelPendingAudioRequest()
    requestId++
    checkingAccount = false
    verifiedAccount = ""
    verifiedAccountName = ""
    verifiedAccountAvatar = ""
    bdstoken = ""
    diskIndexCache = null
    diskSearchFingerprints.clear()
    writeRequestId++
    deleteCheckRequestId++
    resetSectionGuards()
    mediaLinks.dismissAnyCaptcha()
    videoResolver.cancel()
    renewalResolver.cancel()
    videoLoading?.dismiss()
    videoLoading = null
}


internal fun MainActivity.verifyAndRefresh(force: Boolean = false) {
    invalidateAccount()
    checkingAccount = true
    if (native.visibility == View.VISIBLE) refresh()
    probeAccount { changed, confirmed ->
        checkingAccount = false
        if (loginPending && confirmed) {
            loginPending = false
            if (pickingSource) openContacts() else showApp()
        } else if (native.visibility == View.VISIBLE) {
            refresh(force = force || changed)
        }
        // 无论本次探测是否成功都要清标记，否则残留到后续任意一次 verifyAndRefresh
        // 都会在 confirmed 时突然弹出「管理百度来源」，属于误触发。
        if (pendingAddProvider == SourceProvider.BAIDU) {
            val shouldOpenSources = confirmed
            pendingAddProvider = null
            if (shouldOpenSources) {
                web.post { if (!isFinishing && !isDestroyed) manageBaiduSources() }
            }
        }
    }
}


internal fun MainActivity.probeAccount(onDone: (Boolean, Boolean) -> Unit) {
    val probe = accountProbeId
    val key = "__groupPlayerUk$probe"
        val script = """
            (() => {
              if (location.origin !== 'https://pan.baidu.com') return false;
              const r = { ready: false };
              window.$key = r;
              fetch(${JSONObject.quote(PanApi.viewer())}, { credentials: 'same-origin', cache: 'no-store', headers: {'X-Requested-With':'XMLHttpRequest'} })
                .then(async resp => { r.body = await resp.text(); })
                .catch(() => { r.body = ''; })
                .finally(() => { r.ready = true; });
              return true;
            })()
        """.trimIndent()
    web.evaluateJavascript(script) { started ->
        if (probe != accountProbeId || isDestroyed) return@evaluateJavascript
        if (started != "true") {
            onDone(false, false)
            return@evaluateJavascript
        }
        var tries = 0
        var finished = false
        fun finish(changed: Boolean, confirmed: Boolean = false) {
            if (finished || isDestroyed) return
            finished = true
            onDone(changed, confirmed)
        }
        fun poll() {
            if (isDestroyed || probe != accountProbeId) return
            if (tries++ > 50) {
                web.evaluateJavascript("delete window.$key", null)
                finish(false)
                return
            }
            web.evaluateJavascript(
                "(() => { const r = window.$key; if (!r || !r.ready) return null; delete window.$key; return r.body || ''; })()",
            ) { raw ->
                if (probe != accountProbeId || isDestroyed) return@evaluateJavascript
                if (raw == null || raw == "null") {
                    web.postDelayed({ poll() }, 200)
                    return@evaluateJavascript
                }
                val body = runCatching { JSONTokener(raw).nextValue()?.toString() }.getOrNull().orEmpty()
                val result = runCatching {
                    val json = JSONObject(body)
                    if (json.optInt("errno", -1) == 0) json.optJSONObject("result") else null
                }.getOrNull()
                val uk = result?.optString("uk").orEmpty()
                val confirmed = uk.matches(Regex("[0-9]{1,24}"))
                verifiedAccount = uk.takeIf { confirmed }.orEmpty()
                verifiedAccountName = if (confirmed) {
                    result?.optString("netdisk_name").orEmpty().ifBlank {
                        result?.optString("username").orEmpty()
                    }
                } else ""
                verifiedAccountAvatar = if (confirmed) result?.optString("avatar_url").orEmpty() else ""
                val changed = confirmed && AccountScope.set(this@probeAccount, uk)
                if (confirmed) controller?.let {
                    it.sendCustomCommand(AudioPlaybackService.SYNC_ACCOUNT, Bundle.EMPTY)
                    renewBackgroundAudio(it)
                }
                if (changed) {
                    videoPage?.close()
                    closeAudioPage()
                    folders.clear()
                    folderPositions.clear()
                    tabFolders.clear()
                    tabScroll.clear()
                    favoritesOrigin = Screen.HOME
                    screen = Screen.HOME
                    recent = true
                    bdstoken = ""
                    diskIndexCache = null
                    diskSearchFingerprints.clear()
                    courseEntries.clear()
                    courseParentKey = ""
                    pendingQueue = pendingQueue?.takeIf { videoToRestore?.account == verifiedAccount }
                    PlaybackQueueState.anchor = null
                    PlaybackQueueState.entries = emptyList()
                    PlaybackQueueState.listKey = null
                    toast("已切换账号，数据按账号隔离")
                    setActiveGroup(readActiveSource())
                }
                finish(changed, confirmed)
                if (confirmed) {
                    restoreVideoIfReady()
                    if (!changed) controller?.let {
                        syncAudioAnchor(it)
                        advanceAudioIfEnded(it)
                    }
                }
            }
        }
        poll()
    }
}


internal fun MainActivity.cacheFailure(key: String) {
    if (key.isEmpty()) return
    val label = courseCache.syncLabel(key)
    if (label.isEmpty()) return
    cacheNote = "显示缓存内容 · 上次同步 $label"
    status.text = "${status.text}\n$cacheNote"
}


internal fun MainActivity.loadChildren(
    parent: PanEntry,
    page: Int,
    previousButton: View? = null,
    cacheKey: String = "",
) {
    val key = cacheKey.ifEmpty { "dir:${parent.gid}:${parent.messageId}:${parent.fileId}" }
    load(
        PanApi.children(parent, page),
        post = true,
        statusHint = if (page == 1 && courseCache.syncLabel(key).isNotEmpty()) {
            "$cacheNote · 正在检查更新"
        } else null,
        onCancelled = { previousButton?.isEnabled = true },
        onFailure = {
            previousButton?.isEnabled = true
            if (page == 1) cacheFailure(key)
        },
    ) { json ->
        val entries = PanApi.parseChildren(json, parent)
        val hasMore = PanApi.hasMore(json) && entries.isNotEmpty()
        val changed = courseCache.writeIfChanged(key, page, entries, if (hasMore) "more" else "")
        if (changed || !hasMore) courseCache.clearAfter(key, page)
        courseEntries.clear()
        courseEntries.addAll(courseCache.readAll(key))
        cacheNote = "已同步 ${courseCache.syncLabel(key)}"
        renderCourse()
        if (!changed) status.text = "$cacheNote · 无更新"
        renderChildMore(parent, key)
    }
}


internal fun MainActivity.renderChildMore(parent: PanEntry, key: String) {
    val (page, cached) = courseCache.lastPage(key) ?: return
    if (cached.nextCursor.isNullOrEmpty()) return
    val more = headAction("加载更多") {}
    more.setBackgroundResource(R.drawable.bg_load_more)
    more.setTextColor(color(R.color.ink3))
    more.setOnClickListener {
        more.isEnabled = false
        if (providerOfSourceId(parent.gid) == SourceProvider.QUARK) loadQuarkChildren(parent, page + 1, more, key)
        else loadChildren(parent, page + 1, more, key)
    }
    content.addView(more)
}


internal fun MainActivity.renderSourcePicker() {
    val name = shareSources().firstOrNull { it.id == gid }?.name
    sourceName.text = name ?: "选择文件库"
    sourceButton.contentDescription = name?.let { "切换文件库，当前为 $it" } ?: "选择文件库"
    homeGroupChip?.text = name ?: "选择文件库"
    homeGroupChip?.contentDescription = name?.let { "切换文件库，当前为 $it" } ?: "选择文件库"
}


internal fun MainActivity.display(entries: List<PanEntry>) {
    if (screen == Screen.HOME && folders.isEmpty()) homeDisplayedEntries = entries
    status.text = if (entries.isEmpty()) "文件库暂无内容" else cacheNote
    sectionTitle.text = when {
        screen == Screen.HOME -> "群组"
        else -> "文件"
    }
    addFilteredCourseRows(entries, sortable = listSortable())
}
