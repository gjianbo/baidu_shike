package com.hnwd.bdplayer

import android.app.Dialog
import android.graphics.Color
import android.graphics.Typeface
import androidx.core.graphics.drawable.toDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.BaseAdapter
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import androidx.core.view.isNotEmpty

/**
 * 页面外壳：导航、筛选、面包屑、通用弹窗与窗口布局。
 *
 * 从 MainActivity.kt 按域原样搬出的扩展函数：函数体与语义未改，
 * 仅把 private 提升为 internal，并用 this@? 保真原 this@MainActivity 引用。
 */

internal fun MainActivity.restoreNavigationState(state: Bundle?) {
    if (state == null) return
    val restored = runCatching { Screen.valueOf(state.getString(MainActivity.STATE_SCREEN).orEmpty()) }.getOrNull()
        ?: return
    screen = restored
    recent = restored == Screen.HOME
    mediaFilter = runCatching { MediaFilter.valueOf(state.getString(MainActivity.STATE_FILTER).orEmpty()) }
        .getOrDefault(MediaFilter.ALL)
    transferredOnly = state.getBoolean(MainActivity.STATE_TRANSFERRED_ONLY, false)
    favoritesOrigin = runCatching {
        Screen.valueOf(state.getString(MainActivity.STATE_FAVORITES_ORIGIN).orEmpty())
    }.getOrDefault(Screen.HOME)
    folders.clear()
    if (restored == Screen.DISK || restored == Screen.GROUPS || restored == Screen.HOME) {
        val trail = decodeEntries(state.getString(MainActivity.STATE_FOLDERS))
        if (restored == Screen.DISK || sourceFolderTrailMatches(gid, trail)) folders.addAll(trail)
    }
}


internal fun MainActivity.showScreen(target: Screen) {
    clearSelectionIfActive()
    if (screen == target) {
        contentScroll.scrollTo(0, 0)
        refresh()
        return
    }
    if (screen != Screen.FAVORITES) {
        tabFolders[screen] = folders.toList()
        tabScroll[screen] = contentScroll.scrollY
    }
    screen = target
    recent = target == Screen.HOME
    transferredOnly = false
    folders.clear()
    if (target == Screen.DISK || target == Screen.GROUPS || target == Screen.HOME) {
        tabFolders[target]?.let { folders.addAll(it) }
    }
    courseEntries.clear()
    courseParentKey = ""
    contentScroll.scrollTo(0, 0)
    refresh()
    scrollAfterLayout(tabScroll[target] ?: 0)
}


internal fun MainActivity.openFavorites() {
    if (!hasVerifiedAccount()) {
        toast("请先登录百度网盘")
        return
    }
    if (screen == Screen.FAVORITES) return
    favoritesOrigin = when (screen) {
        Screen.DISK, Screen.GROUPS, Screen.HOME -> screen
        else -> Screen.HOME
    }
    showScreen(Screen.FAVORITES)
}


internal fun MainActivity.closeFavorites() {
    if (screen == Screen.FAVORITES) showScreen(favoritesOrigin)
}


internal fun MainActivity.updateFilterChips() {
    MediaFilter.entries.forEach { filter ->
        val chip = mediaChips[filter.ordinal]
        chip.isSelected = filter == mediaFilter
        setTabActive(chip, chip.isSelected)
    }
    transferredChip.isSelected = transferredOnly
    setTabActive(transferredChip, transferredOnly)
}


internal fun MainActivity.showAvailableMediaFilters(kinds: Set<MediaKind>, includeSelected: Boolean = false) {
    MediaFilter.entries.forEach { filter ->
        val available = filter == MediaFilter.ALL || (includeSelected && filter == mediaFilter) ||
            kinds.any(filter::includes)
        mediaChips[filter.ordinal].visibility = if (available) View.VISIBLE else View.GONE
    }
}


internal fun MainActivity.updateNav() {
    val screens = listOf(Screen.HOME, Screen.DISK, Screen.GROUPS, Screen.SETTINGS)
    val active = if (screen == Screen.FAVORITES) screens.indexOf(favoritesOrigin) else screens.indexOf(screen)
    val titles = listOf("首页", "网盘", groupTabLabel(), "设置")
    val icons = listOf(R.drawable.ic_home, R.drawable.ic_cloud, R.drawable.ic_users, R.drawable.ic_settings)
    navRoots.forEachIndexed { index, root ->
        val on = index == active
        root.findViewById<View>(R.id.navIndicator).setBackgroundResource(
            if (on) R.drawable.bg_nav_pill_on else R.color.transparent,
        )
        root.findViewById<ImageView>(R.id.navIcon).apply {
            setImageResource(icons[index])
            setColorFilter(color(if (on) R.color.accentInk else R.color.ink3))
        }
        val label = root.findViewById<TextView>(R.id.navLabel)
        label.text = titles[index]
        label.setTextColor(color(if (on) R.color.ink else R.color.ink3))
        label.typeface = if (on) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
        root.setOnClickListener { showScreen(screens[index]) }
    }
    shell.findViewById<TextView>(R.id.btnQuarkDrive).apply {
        val browsing = screen == Screen.GROUPS
        visibility = if (screen == Screen.DISK || browsing) View.VISIBLE else View.GONE
        val currentProvider = if (screen == Screen.DISK) diskProvider else providerOfSourceId(gid)
        text = when (currentProvider) {
            SourceProvider.QUARK -> "夸克网盘"
            SourceProvider.BAIDU -> "百度网盘"
        }
        contentDescription = "切换网盘，当前为 $text"
        setOnClickListener { chooseProvider() }
    }
    renderSourcePicker()
    updateFavoritesBadge()
    val browsing = screen == Screen.HOME || screen == Screen.DISK || screen == Screen.GROUPS
    sourceButton.visibility = if (screen == Screen.GROUPS) View.VISIBLE else View.GONE
    homeGroupChip?.visibility = if (screen == Screen.HOME) View.VISIBLE else View.GONE
    btnFavorites.visibility = if (screen == Screen.DISK || screen == Screen.GROUPS) View.VISIBLE else View.GONE
    btnBack.visibility = if (screen == Screen.FAVORITES) View.VISIBLE else View.GONE
    appSub.visibility = when {
        screen == Screen.HOME && hasVerifiedAccount() && verifiedAccountName.isNotBlank() -> View.VISIBLE
        screen == Screen.DISK -> View.VISIBLE
        browsing && (folders.isNotEmpty() || !hasActiveSession()) -> View.VISIBLE
        else -> View.GONE
    }
    refreshButton.visibility = if (browsing) View.VISIBLE else View.GONE
    searchButton.visibility = if (hasActiveSession() &&
        (screen == Screen.DISK || (screen == Screen.GROUPS && gid.isNotEmpty() &&
            currentPageProvider() == SourceProvider.BAIDU && sourceKindOf(gid) == SourceKind.GROUP))
    ) View.VISIBLE else View.GONE
    if (fabReady()) {
        val diskManage = screen == Screen.DISK && hasActiveSession()
        fabAdd.visibility = if (diskManage) View.VISIBLE else View.GONE
        updateFabPosition()
    }
    title.setTextSize(
        if (screen == Screen.HOME ||
            ((screen == Screen.DISK || screen == Screen.GROUPS) && folders.isEmpty())
        ) 30f else 26f,
    )
    crumbsScroll.visibility =
        if (browsing && folders.isNotEmpty()) View.VISIBLE else View.GONE
    sectionRow.visibility = if (browsing) View.VISIBLE else View.GONE
    sectionTitle.text = when (screen) {
        Screen.HOME -> "群组"
        Screen.DISK, Screen.GROUPS -> "文件"
        else -> ""
    }
}


internal fun MainActivity.updateFavoritesBadge() {
    val count = favorites.all().size
    favBadge.text = count.toString()
    favBadge.visibility = if (count > 0) View.VISIBLE else View.GONE
}


internal fun MainActivity.renderCrumbs() {
    crumbsRow.removeAllViews()
    fun addCrumb(label: String, current: Boolean, action: (() -> Unit)?) {
        crumbsRow.addView(TextView(this).apply {
            text = label
            textSize = 11.5f
            typeface = if (current) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
            setTextColor(color(if (current) R.color.ink else R.color.ink2))
            gravity = Gravity.CENTER
            setPadding(dp(9), dp(6), dp(9), dp(6))
            if (current) setBackgroundResource(R.drawable.bg_source_chip)
            isClickable = action != null
            setOnClickListener { action?.invoke() }
        })
    }
    fun addSeparator() {
        crumbsRow.addView(TextView(this).apply {
            text = "›"
            textSize = 13f
            setTextColor(color(R.color.ink3))
            setPadding(dp(2), 0, dp(2), 0)
        })
    }
    val rootLabel = when (screen) {
        Screen.DISK -> "我的网盘"
        Screen.HOME -> "群组"
        Screen.GROUPS -> shareSources().firstOrNull { it.id == gid }?.name ?: "共享文件库"
        else -> "根目录"
    }
    addCrumb(rootLabel, folders.isEmpty()) {
        if (folders.isNotEmpty()) returnToFolder(0)
    }
    folders.forEachIndexed { index, entry ->
        addSeparator()
        addCrumb(entry.name, index == folders.lastIndex) {
            if (index < folders.lastIndex) {
                returnToFolder(index + 1)
            }
        }
    }
    crumbsScroll.visibility =
        if (folders.isNotEmpty()) View.VISIBLE else View.GONE
}


internal fun MainActivity.showChoiceSheet(
    title: String,
    subtitle: String? = null,
    choices: List<SheetChoice>,
    multi: Boolean = false,
    confirmLabel: String? = null,
    onSelect: ((SheetChoice) -> Unit)? = null,
    onConfirm: ((List<SheetChoice>) -> Unit)? = null,
    footerActions: List<Pair<String, () -> Unit>> = emptyList(),
    maxSelections: Int? = null,
) {
    if (choices.isEmpty()) return
    val checked = BooleanArray(choices.size) { choices[it].checked }
    val view = layoutInflater.inflate(R.layout.dialog_choice_sheet, null, false)
    view.findViewById<TextView>(R.id.choiceTitle).text = title
    val subtitleView = view.findViewById<TextView>(R.id.choiceSubtitle)
    if (subtitle.isNullOrEmpty()) {
        subtitleView.visibility = View.GONE
    } else {
        subtitleView.text = subtitle
        subtitleView.visibility = View.VISIBLE
    }
    val list = view.findViewById<ListView>(R.id.choiceList)
    val adapter = object : BaseAdapter() {
        override fun getCount() = choices.size
        override fun getItem(position: Int) = choices[position]
        override fun getItemId(position: Int) = position.toLong()
        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val row = convertView ?: layoutInflater.inflate(R.layout.item_choice, parent, false)
            val choice = choices[position]
            val label = row.findViewById<TextView>(R.id.choiceLabel)
            label.text = choice.title
            label.setTextColor(color(if (checked[position]) R.color.accentInk else R.color.ink))
            val meta = row.findViewById<TextView>(R.id.choiceMeta)
            if (choice.meta.isEmpty()) {
                meta.visibility = View.GONE
            } else {
                meta.text = choice.meta
                meta.visibility = View.VISIBLE
            }
            row.findViewById<View>(R.id.choiceCheck).visibility =
                if (checked[position]) View.VISIBLE else View.GONE
            row.findViewById<View>(R.id.choiceDivider).visibility =
                if (position == choices.lastIndex) View.GONE else View.VISIBLE
            return row
        }
    }
    list.adapter = adapter
    val dialog = Dialog(this)
    dialog.setContentView(view)
    val footer = view.findViewById<LinearLayout>(R.id.choiceFooter)
    val actions = view.findViewById<LinearLayout>(R.id.choiceActions)
    var footerHeight = 0
    fun addAction(label: String, primary: Boolean, enabledToConfirm: () -> Boolean = { true }, action: () -> Unit) {
        val button = TextView(this@showChoiceSheet).apply {
            text = label
            textSize = 13f
            setTypeface(null, Typeface.BOLD)
            gravity = Gravity.CENTER
            minHeight = dp(44)
            setPadding(dp(18), 0, dp(18), 0)
            if (primary) {
                setBackgroundResource(R.drawable.bg_search_action)
                setTextColor(color(R.color.onAccent))
            } else {
                setBackgroundResource(R.drawable.bg_chip)
                setTextColor(color(R.color.ink2))
            }
            isClickable = true
            isFocusable = true
            setOnClickListener {
                if (!enabledToConfirm()) return@setOnClickListener
                dialog.dismiss()
                action()
            }
        }
        val params = LinearLayout.LayoutParams(-2, dp(44))
        if (actions.isNotEmpty()) params.marginStart = dp(8)
        actions.addView(button, params)
    }
    if (multi && confirmLabel != null) {
        addAction("取消", false) { }
        addAction(confirmLabel, true, enabledToConfirm = {
            val withinLimit = maxSelections == null || checked.count { it } <= maxSelections
            if (!withinLimit) toast("最多选择 $maxSelections 项")
            withinLimit
        }) {
            onConfirm?.invoke(choices.filterIndexed { index, _ -> checked[index] })
        }
        footer.visibility = View.VISIBLE
        footerHeight = dp(66)
    } else if (footerActions.isNotEmpty()) {
        footerActions.forEachIndexed { index, pair ->
            addAction(pair.first, index == footerActions.lastIndex) { pair.second() }
        }
        footer.visibility = View.VISIBLE
        footerHeight = dp(66)
    }
    list.setOnItemClickListener { _, _, position, _ ->
        val choice = choices[position]
        if (multi) {
            if (!checked[position] && maxSelections != null && checked.count { it } >= maxSelections) {
                toast("最多选择 $maxSelections 项")
                return@setOnItemClickListener
            }
            checked[position] = !checked[position]
            adapter.notifyDataSetChanged()
        } else {
            dialog.dismiss()
            onSelect?.invoke(choice)
        }
    }
    sizeSheet(dialog) { available ->
        val maxHeight = sheetMaxHeight(available)
        (dp(132) + minOf(choices.size, 8) * dp(56) + footerHeight)
            .coerceIn(minOf(dp(260), maxHeight), maxHeight)
    }
    dialog.window?.apply {
        setBackgroundDrawable(Color.TRANSPARENT.toDrawable())
        setDimAmount(0.45f)
        addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
        setWindowAnimations(R.style.SearchSheetAnimation)
    }
    dialog.setCanceledOnTouchOutside(true)
    view.findViewById<ImageView>(R.id.choiceClose).setOnClickListener { dialog.dismiss() }
    dialog.show()
}


internal fun MainActivity.showThemeDialog() {
    val modes = listOf(ThemeStore.SYSTEM, ThemeStore.LIGHT, ThemeStore.DARK)
    val current = modes.indexOf(ThemeStore.mode(this)).coerceAtLeast(0)
    showChoiceSheet(
        title = "外观",
        subtitle = "选择界面配色",
        choices = modes.mapIndexed { index, mode ->
            SheetChoice(ThemeStore.label(mode), checked = index == current, tag = mode)
        },
        onSelect = { choice ->
            val mode = choice.tag as String
            ThemeStore.set(this, mode, screen.name)
            findViewById<TextView>(R.id.setThemeValue)?.text = ThemeStore.label(mode)
        },
    )
}


internal fun MainActivity.hasFilterOptions(entries: List<PanEntry>): Boolean =
    entries.map(PanApi::kindOf).distinct().size >= 2


internal fun MainActivity.transferredIndex(): Set<String> =
    diskIndexCache ?: (courseCache.diskIndex() + diskSearchFingerprints).also { diskIndexCache = it }


internal fun MainActivity.matchesTransferred(entry: PanEntry, index: Set<String>): Boolean =
    !entry.directory && entry.md5.isNotEmpty() &&
        index.contains("${PanApi.md5Key(entry.md5)}:${entry.size}")


internal fun MainActivity.updateWindowLayout() {
    val pagePad = resources.getDimensionPixelSize(R.dimen.page_pad)
    shell.findViewById<View>(R.id.brandRow).apply {
        setPaddingRelative(pagePad, paddingTop, paddingEnd, paddingBottom)
    }
    listOf(shell.findViewById<View>(R.id.titleRow), appSub, historyPanel, sectionRow, filterBar, status, content)
        .forEach { it.setPaddingRelative(pagePad, it.paddingTop, pagePad, it.paddingBottom) }
    listOf(courseSwitch, shell.findViewById<View>(R.id.homeRefreshNotice)).forEach { view ->
        view.layoutParams = (view.layoutParams as LinearLayout.LayoutParams).apply {
            marginStart = pagePad
            marginEnd = pagePad
        }
    }
    nowPlaying.layoutParams = nowPlaying.layoutParams.apply {
        width = resources.getDimensionPixelSize(R.dimen.mini_player_width)
    }
    shell.requestLayout()
    if (railVisible == hasWideWindow) return

    railVisible = hasWideWindow
    val tabBar = (if (railVisible) shell else navigationRail).findViewById<LinearLayout>(R.id.tabBar)
    (tabBar.parent as ViewGroup).removeView(tabBar)
    val tabItems = tabBar.findViewById<LinearLayout>(R.id.tabItems)
    tabItems.orientation = if (railVisible) LinearLayout.VERTICAL else LinearLayout.HORIZONTAL
    tabBar.findViewById<View>(R.id.tabDivider).visibility = if (railVisible) View.GONE else View.VISIBLE
    navRoots.forEach { tab ->
        tab.layoutParams = if (railVisible) LinearLayout.LayoutParams(-1, dp(64))
        else LinearLayout.LayoutParams(0, -2, 1f)
    }
    if (railVisible) {
        navigationRail.addView(tabBar, FrameLayout.LayoutParams(-1, -2))
        navigationRail.visibility = View.VISIBLE
    } else {
        navigationRail.visibility = View.GONE
        shell.addView(tabBar, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
    }
    redrawFilteredRows?.invoke()
}


internal fun MainActivity.sizeSheet(dialog: Dialog, height: (Int) -> Int) {
    sheetHeights[dialog] = height
    dialog.window?.sizeBottomSheet(this, height)
}


internal fun MainActivity.sheetMaxHeight(available: Int): Int =
    minOf((available * 0.70f).toInt(), dp(640), available - dp(24)).coerceAtLeast(1)


internal fun MainActivity.renderBatchBar() {
    if (!batchBarReady()) return
    if (batchMoveReady()) {
        val diskSel = selectionMode && selectionScreen == Screen.DISK
        batchMove.visibility = if (diskSel) View.VISIBLE else View.GONE
        batchCopy.visibility = if (diskSel) View.VISIBLE else View.GONE
        batchDelete.visibility = if (diskSel) View.VISIBLE else View.GONE
        batchFav.visibility = if (diskSel) View.GONE else View.VISIBLE
        batchDownload.visibility = if (selectionMode && visibleSelectionEntries().any(DownloadLogic::canDownload)) {
            View.VISIBLE
        } else {
            View.GONE
        }
    }
    batchBar.visibility = if (selectionMode) View.VISIBLE else View.GONE
    if (selectionMode) {
        batchCount.text = "已选 ${selectedKeys.size} 项"
        sectionTitle.text = "已选 ${selectedKeys.size} 项"
        filterCount.visibility = View.GONE
        sortButton.visibility = View.GONE
        val entries = visibleSelectionEntries()
        val allStarred = entries.isNotEmpty() && entries.all { favorites.has(it) }
        batchFav.text = if (allStarred) "取消收藏" else "收藏"
        batchAll.text = if (lastRenderedEntries.isNotEmpty() &&
            selectedKeys.containsAll(lastRenderedEntries.map(::entryKey))
        ) "取消全选" else "全选"
    } else {
        filterCount.visibility = View.VISIBLE
        sectionTitle.text = when (screen) {
            Screen.HOME -> "群组"
            Screen.DISK, Screen.GROUPS -> "文件"
            else -> ""
        }
    }
}


internal fun MainActivity.setTabActive(tab: TextView, active: Boolean) {
    tab.setBackgroundResource(if (active) R.drawable.bg_chip_on else R.drawable.bg_chip)
    tab.setTextColor(color(if (active) R.color.accentInk else R.color.ink2))
}
