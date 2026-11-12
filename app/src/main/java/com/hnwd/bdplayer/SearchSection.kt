package com.hnwd.bdplayer

import android.app.Activity
import android.app.Dialog
import android.content.SharedPreferences
import android.graphics.Color
import androidx.core.graphics.drawable.toDrawable
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.BaseAdapter
import android.widget.EditText
import android.widget.ImageView
import android.widget.ListView
import android.widget.TextView
import org.json.JSONObject
import androidx.core.content.edit

/**
 * 全局搜索需要的最小宿主能力：来源列表、一次 JSON 请求、弹窗尺寸与页面跳转。
 * 与 DiskMutationHost 同一套思路：只暴露搜索真正用到的东西。
 */
internal interface SearchHost {
    val hostActivity: Activity

    /** 网盘已连接且账号已校验，才允许发起搜索。 */
    fun canSearch(): Boolean

    /** 当前已校验的账号 id，用于识别搜索过程中账号被切换。 */
    fun account(): String
    fun accountVerified(): Boolean
    fun shareSources(): List<ShareSource>
    fun settings(): SharedPreferences

    fun fetchJson(
        url: String,
        post: Boolean = false,
        form: String? = null,
        lane: RequestLane,
        acceptAnyErrno: Boolean = false,
        onCancelled: () -> Unit = {},
        onFailure: () -> Unit = {},
        onResult: (JSONObject) -> Unit,
    )

    fun withBdstoken(onReady: (String) -> Unit, onFailure: () -> Unit)
    fun say(message: String)
    fun sizeSheet(dialog: Dialog, height: (Int) -> Int)
    fun delay(millis: Long, action: () -> Unit)
    fun activateGroup(group: String)
    fun activeSourceId(): String
    fun openEntry(entry: PanEntry, entries: List<PanEntry>)
    fun openSourceDirectory(entry: PanEntry)
    fun openFolderTrail(group: String, trail: List<PanEntry>)
    fun cancelSearchRequests()
}

/**
 * 搜索入口与结果面板：输入弹窗 → 串行遍历各来源 → 命中列表 → 定位到目录。
 *
 * 两条不变式：
 * 1. 弹窗身份 + 账号 id 一起校验（[show]/[runGlobalSearch] 里的 isCurrent），
 *    弹窗关闭或账号切换后，已在路上的请求结果一律丢弃。
 * 2. 各来源串行搜索（150ms 间隔），因为 [RequestLane.SEARCH] 是按通道单请求集成的，
 *    并发会互相作废。
 */
internal class SearchSection(private val host: SearchHost) {
    private val activity: Activity get() = host.hostActivity
    private var activeDialog: Dialog? = null
    private var searchScope = SearchScope.DISK

    /** 页面销毁 / 账号失效时收起搜索面板。 */
    fun dismiss() {
        activeDialog?.dismiss()
    }

    fun show() {
        if (!host.canSearch()) {
            host.say("网盘正在连接，请稍后再试")
            return
        }
        val account = host.account()
        val groups = searchableGroups()
        val scopes = availableScopes(groups.isNotEmpty())
        searchScope = restoreScope(
            host.settings().getString("search_scope", SearchScope.DISK.name),
            scopes,
        )
        val view = activity.layoutInflater.inflate(R.layout.dialog_search_input, null, false)
        val input = view.findViewById<EditText>(R.id.searchInput)
        val chipDisk = view.findViewById<TextView>(R.id.scopeDisk)
        val chipGroup = view.findViewById<TextView>(R.id.scopeGroup)
        val chipAll = view.findViewById<TextView>(R.id.scopeAll)
        val scopeHint = view.findViewById<TextView>(R.id.scopeHint)
        val scopeRow = view.findViewById<View>(R.id.searchScopeRow)
        val subtitle = view.findViewById<TextView>(R.id.searchInputSubtitle)
        val titleView = view.findViewById<TextView>(R.id.searchInputTitle)
        chipGroup.visibility = if (groups.isNotEmpty()) View.VISIBLE else View.GONE
        chipAll.visibility = if (groups.isNotEmpty()) View.VISIBLE else View.GONE
        scopeRow.visibility = if (scopes.size > 1) View.VISIBLE else View.GONE
        fun paintScope() {
            fun paint(chip: TextView, on: Boolean) {
                chip.setBackgroundResource(if (on) R.drawable.bg_chip_on else R.drawable.bg_chip)
                chip.setTextColor(activity.color(if (on) R.color.accentInk else R.color.ink2))
                chip.isSelected = on
            }
            paint(chipDisk, searchScope == SearchScope.DISK)
            paint(chipGroup, searchScope == SearchScope.GROUP)
            paint(chipAll, searchScope == SearchScope.ALL)
            titleView.text = scopeTitle(searchScope)
            subtitle.text = scopeSubtitle(searchScope)
            scopeHint.visibility = if (searchScope == SearchScope.ALL) View.VISIBLE else View.GONE
            scopeHint.text = scopeHintText(groups.size)
        }
        fun choose(scope: SearchScope) {
            if (scope !in scopes) return
            searchScope = scope
            host.settings().edit {
                putString("search_scope", scope.name)
            }
            paintScope()
        }
        chipDisk.setOnClickListener { choose(SearchScope.DISK) }
        chipGroup.setOnClickListener { choose(SearchScope.GROUP) }
        chipAll.setOnClickListener { choose(SearchScope.ALL) }
        paintScope()
        val dialog = Dialog(activity)
        dialog.setContentView(view)
        host.sizeSheet(dialog) { WindowManager.LayoutParams.WRAP_CONTENT }
        dialog.window?.apply {
            setBackgroundDrawable(Color.TRANSPARENT.toDrawable())
            setDimAmount(0.45f)
            addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            setSoftInputMode(
                WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE or
                    WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE,
            )
            setWindowAnimations(R.style.SearchSheetAnimation)
        }
        dialog.setCanceledOnTouchOutside(true)
        fun submit() {
            val keyword = input.text.toString().trim()
            if (keyword.isEmpty()) {
                host.say("请输入关键词")
                input.requestFocus()
                return
            }
            activity.getSystemService(InputMethodManager::class.java)?.hideSoftInputFromWindow(input.windowToken, 0)
            dialog.dismiss()
            if (host.canSearch() && host.account() == account) {
                runGlobalSearch(keyword, searchTargets(searchScope, searchableGroups()), searchScope)
            } else {
                host.say("网盘连接已变化，请重新搜索")
            }
        }
        view.findViewById<TextView>(R.id.searchInputCancel).setOnClickListener { dialog.dismiss() }
        view.findViewById<TextView>(R.id.searchInputSubmit).setOnClickListener { submit() }
        input.setOnEditorActionListener { _, action, _ ->
            if (action != EditorInfo.IME_ACTION_SEARCH) return@setOnEditorActionListener false
            submit()
            true
        }
        dialog.show()
        input.requestFocus()
        dialog.window?.setSoftInputMode(
            WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE or
                WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE,
        )
    }

    /** 群组范围只认已关联的百度群组；联系人与夸克来源都不参与全局搜索。 */
    private fun searchableGroups(): List<SearchTarget> = searchGroupTargets(host.shareSources())

    /** V02-1 全局搜索：串行遍历各来源（避免 SEARCH 单请求 id 并发互斥），聚合并按来源去重。 */
    private fun runGlobalSearch(keyword: String, targets: List<SearchTarget>, scope: SearchScope) {
        val account = host.account()
        val hits = mutableListOf<PanSearchHit>()
        val seen = mutableSetOf<String>()
        val sourceNames = mutableMapOf<String, String>()
        val view = activity.layoutInflater.inflate(R.layout.dialog_search_results, null, false)
        val more = view.findViewById<View>(R.id.searchMore)
        val eyebrow = view.findViewById<TextView>(R.id.searchEyebrow)
        val keywordTitle = view.findViewById<TextView>(R.id.searchTitle)
        val summary = view.findViewById<TextView>(R.id.searchSummary)
        val loading = view.findViewById<View>(R.id.searchLoading)
        val list = view.findViewById<ListView>(R.id.searchResults)
        val empty = view.findViewById<View>(R.id.searchEmpty)
        val emptyTitle = view.findViewById<TextView>(R.id.searchEmptyTitle)
        val emptyHint = view.findViewById<TextView>(R.id.searchEmptyHint)
        more.visibility = View.GONE
        eyebrow.text = "搜索 · ${scope.label}"
        keywordTitle.text = keyword
        summary.text = "正在搜索…"
        loading.visibility = View.VISIBLE
        val adapter = object : BaseAdapter() {
            override fun getCount() = hits.size
            override fun getItem(position: Int) = hits[position]
            override fun getItemId(position: Int) = position.toLong()
            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                val row = convertView ?: activity.layoutInflater.inflate(R.layout.item_search_hit, parent, false)
                val hit = hits[position]
                val entry = hit.entry
                val kind = PanApi.kindOf(entry)
                activity.paintMediaIcon(row.findViewById<ImageView>(R.id.searchIcon), kind, entry.directory)
                row.findViewById<TextView>(R.id.searchName).text = entry.name
                val location = if (entry.directory) entry.path else hit.parentPath
                val sourceLabel = if (entry.isDisk) "我的网盘" else sourceNames[entry.gid] ?: "群组"
                row.findViewById<TextView>(R.id.searchMeta).text =
                    listOf(sourceLabel, mediaKindLabel(kind, entry.directory, formatOf(entry)), location.ifEmpty { "/" })
                        .filter { it.isNotEmpty() }.joinToString(" · ")
                row.findViewById<View>(R.id.searchDivider).visibility =
                    if (position == hits.lastIndex) View.GONE else View.VISIBLE
                return row
            }
        }
        list.adapter = adapter
        val dialog = Dialog(activity)
        dialog.setContentView(view)
        host.sizeSheet(dialog) { available ->
            searchResultHeight(available, activity.dp(320), activity.dp(640), activity.dp(24))
        }
        dialog.window?.apply {
            setBackgroundDrawable(Color.TRANSPARENT.toDrawable())
            setDimAmount(0.45f)
            addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            setWindowAnimations(R.style.SearchSheetAnimation)
        }
        dialog.setCanceledOnTouchOutside(true)
        activeDialog = dialog
        dialog.setOnDismissListener {
            host.cancelSearchRequests()
            activeDialog = null
        }
        view.findViewById<ImageView>(R.id.searchClose).setOnClickListener { dialog.dismiss() }
        fun isCurrent() = activeDialog === dialog && host.account() == account
        var stopped = false
        list.setOnItemClickListener { _, _, position, _ ->
            val hit = hits.getOrNull(position) ?: return@setOnItemClickListener
            stopped = true
            val entry = hit.entry
            if (entry.isDisk) {
                dialog.dismiss()
                if (entry.directory) host.openSourceDirectory(entry)
                else host.openEntry(entry, hits.filter { it.entry.isDisk }.map { it.entry })
            } else {
                list.isEnabled = false
                list.alpha = 0.55f
                loading.visibility = View.VISIBLE
                summary.text = "正在定位目录…"
                val group = entry.gid
                if (group != host.activeSourceId()) host.activateGroup(group)
                locateSearchHit(group, hit) { ok, message ->
                    if (!isCurrent() || !dialog.isShowing) return@locateSearchHit
                    list.isEnabled = true
                    list.alpha = 1f
                    if (ok) {
                        dialog.dismiss()
                    } else {
                        loading.visibility = View.GONE
                        summary.text = message ?: "定位失败，请稍后重试"
                    }
                }
            }
        }
        dialog.show()
        var index = 0
        var failed = 0
        fun finish() {
            if (stopped || !isCurrent()) return
            loading.visibility = View.GONE
            if (hits.isEmpty()) {
                empty.visibility = View.VISIBLE
                list.visibility = View.GONE
                emptyTitle.text = "没有找到匹配的文件"
                emptyHint.text = "换个关键词，或缩小搜索范围"
                summary.text = searchSummaryText(0, failed)
            } else {
                empty.visibility = View.GONE
                list.visibility = View.VISIBLE
                list.alpha = 1f
                list.isEnabled = true
                summary.text = searchSummaryText(hits.size, failed)
            }
        }
        fun updateProgress() {
            if (isCurrent()) summary.text = searchProgressText(index, targets.size, hits.size)
        }
        fun clampHits() {
            while (hits.size > MAX_GLOBAL_SEARCH_HITS) hits.removeAt(hits.size - 1)
        }
        fun next() {
            if (stopped || !isCurrent() || !dialog.isShowing) return
            if (index >= targets.size) {
                finish()
                return
            }
            val target = targets[index]
            updateProgress()
            if (target.scope == SearchScope.DISK) {
                host.withBdstoken(
                    onReady = { token ->
                        if (!isCurrent() || !dialog.isShowing) return@withBdstoken
                        val request = BaiduPersonalDrive(token).search(keyword, 1, 100)
                        host.fetchJson(
                            request.url, lane = RequestLane.SEARCH,
                            onCancelled = { failed++; index++; next() },
                            onFailure = { failed++; index++; next() },
                        ) { json ->
                            if (!isCurrent() || !dialog.isShowing) return@fetchJson
                            request.decode(json).items.forEach { hit ->
                                if (seen.add(diskHitKey(hit.entry.fileId))) hits.add(hit)
                            }
                            clampHits()
                            adapter.notifyDataSetChanged()
                            index++
                            updateProgress()
                            if (hits.size >= MAX_GLOBAL_SEARCH_HITS) {
                                finish()
                                return@fetchJson
                            }
                            host.delay(150L) { next() }
                        }
                    },
                    onFailure = { failed++; index++; next() },
                )
            } else {
                sourceNames[target.sourceId] = target.name
                host.fetchJson(
                    PanApi.searchGroup(target.sourceId, keyword), post = true, lane = RequestLane.SEARCH,
                    onCancelled = { failed++; index++; next() },
                    onFailure = { failed++; index++; next() },
                ) { json ->
                    if (!isCurrent() || !dialog.isShowing) return@fetchJson
                    PanApi.parseSearch(json, target.sourceId).take(800).forEach { hit ->
                        if (seen.add(groupHitKey(hit.entry.gid, hit.entry.fileId))) hits.add(hit)
                    }
                    clampHits()
                    adapter.notifyDataSetChanged()
                    index++
                    updateProgress()
                    if (hits.size >= MAX_GLOBAL_SEARCH_HITS) {
                        finish()
                        return@fetchJson
                    }
                    host.delay(150L) { next() }
                }
            }
        }
        next()
    }
    private fun locateSearchHit(group: String, hit: PanSearchHit, done: (Boolean, String?) -> Unit) {
        if (!host.accountVerified() || group != host.activeSourceId()) {
            done(false, "群组连接已变化，请重新搜索")
            return
        }
        val shareFsId = hit.shareFsId.takeIf { it.isNotEmpty() }
        if (shareFsId == null || hit.entry.messageId.isEmpty() || hit.entry.owner.isEmpty()) {
            done(false, "搜索结果缺少目录信息，无法定位")
            return
        }
        val targetPath = normalizeTargetPath(hit.entry.path, hit.entry.directory, hit.parentPath)
        if (hit.entry.directory && hit.entry.fileId == shareFsId) {
            host.openFolderTrail(group, listOf(hit.entry))
            done(true, null)
            return
        }

        var settled = false
        fun finish(ok: Boolean, message: String? = null) {
            if (settled) return
            settled = true
            done(ok, message)
        }
        fun fail(message: String) = finish(false, message)
        fun dialogOpen() = activeDialog?.isShowing == true
        fun open(trail: List<PanEntry>) {
            if (!host.accountVerified() || group != host.activeSourceId()) {
                fail("群组连接已变化，请重新搜索")
                return
            }
            host.openFolderTrail(group, trail)
            finish(true)
        }
        fun scanDirectory(
            entry: PanEntry,
            page: Int,
            seen: MutableSet<String>,
            match: (PanEntry) -> PanEntry?,
            onFound: (PanEntry) -> Unit,
            onMissing: () -> Unit,
        ) {
            if (!dialogOpen()) return
            host.fetchJson(
                PanApi.children(entry, page),
                post = true,
                lane = RequestLane.SEARCH,
                onCancelled = { finish(false) },
                onFailure = { fail("读取目录失败，请稍后重试") },
            ) { json ->
                if (!dialogOpen()) return@fetchJson
                val entries = PanApi.parseChildren(json, entry)
                val fresh = entries.filter { seen.add(entryKey(it)) }
                for (child in fresh) {
                    val found = match(child)
                    if (found != null) {
                        onFound(found)
                        return@fetchJson
                    }
                }
                if (PanApi.hasMore(json) && fresh.isNotEmpty()) {
                    scanDirectory(entry, page + 1, seen, match, onFound, onMissing)
                } else {
                    onMissing()
                }
            }
        }

        val fileHit = !hit.entry.directory
        val root = PanEntry(
            gid = group,
            messageId = hit.entry.messageId,
            owner = hit.entry.owner,
            fileId = shareFsId,
            name = "文件库",
            path = "",
            size = "",
            directory = true,
        )
        scanDirectory(
            entry = root,
            page = 1,
            seen = mutableSetOf(),
            match = { child ->
                if (matchesTrailChild(
                        child.path, child.directory, child.fileId,
                        targetPath, fileHit, hit.entry.fileId,
                    )
                ) child else null
            },
            onFound = { found ->
                val foundPath = found.path.trimEnd('/')
                val (rootPath, rootName) = trailRoot(found.path)
                val actualRoot = root.copy(name = rootName, path = rootPath)
                when {
                    targetPath == rootPath -> open(listOf(actualRoot))
                    found.directory && foundPath == targetPath -> open(listOf(actualRoot, found))
                    else -> {
                        val parts = trailParts(targetPath, rootPath)
                        if (parts.isEmpty()) {
                            fail("目录结构已变化，无法定位")
                        } else {
                            val trail = mutableListOf(actualRoot, found)
                            fun walk(index: Int) {
                                if (!dialogOpen()) return
                                if (index >= parts.size) {
                                    open(trail)
                                    return
                                }
                                val current = trail.last()
                                val expected = trailExpectedPath(current.path, parts[index])
                                scanDirectory(
                                    entry = current,
                                    page = 1,
                                    seen = mutableSetOf(),
                                    match = { child ->
                                        if (child.directory && child.path.trimEnd('/') == expected) child else null
                                    },
                                    onFound = { child ->
                                        trail.add(child)
                                        if (child.path.trimEnd('/') == targetPath) open(trail) else walk(index + 1)
                                    },
                                    onMissing = { fail("目录结构已变化，无法定位") },
                                )
                            }
                            walk(1)
                        }
                    }
                }
            },
            onMissing = {
                fail(if (fileHit) "未找到文件所在目录，可能已被移动" else "未找到该目录，可能已被移动")
            },
        )
    }
}
