package com.hnwd.bdplayer

import android.net.Uri
import android.webkit.CookieManager
import android.widget.EditText
import androidx.appcompat.app.AlertDialog
import org.json.JSONArray
import org.json.JSONObject
import androidx.core.net.toUri
import androidx.core.content.edit

/**
 * 来源管理：共享来源仓储、群组/联系人发现与切换。
 *
 * 从 MainActivity.kt 按域原样搬出的扩展函数：函数体与语义未改，
 * 仅把 private 提升为 internal，并用 this@? 保真原 this@MainActivity 引用。
 */

/** 夸克会话是否有效：登录成功后才会种下 __pus / __puus。 */
internal fun MainActivity.quarkLoggedIn(): Boolean = QuarkPersonalDrive.hasLoginCookie(
    CookieManager.getInstance().getCookie(QuarkPersonalDrive.HOME),
)


internal fun MainActivity.markQuarkEverLogged() {
    getSharedPreferences("account_scope", android.content.Context.MODE_PRIVATE).edit {
        putBoolean("quark_ever_logged", true)
    }
}


internal fun MainActivity.hasVerifiedAccount() = verifiedAccount.isNotEmpty() && verifiedAccount == AccountScope.current


internal fun MainActivity.playbackAccount(entry: PanEntry): String =
    if (entry.provider == SourceProvider.QUARK) AccountScope.current else verifiedAccount


/** 当前页面按哪个网盘算会话：网盘页看 diskProvider，首页/群组看活跃来源前缀。 */
internal fun MainActivity.currentPageProvider(): SourceProvider =
    if (screen == Screen.DISK) DriveSourceRouter.diskProvider()
    else DriveSourceRouter.providerOf(DriveSourceRouter.activeSourceId())


/** 统一会话门禁。百度侧仍是 ready + 已校验账号；夸克侧只看登录 Cookie。 */
internal fun MainActivity.hasActiveSession(p: SourceProvider = currentPageProvider()): Boolean =
    when (p) {
        SourceProvider.BAIDU -> ready && hasVerifiedAccount()
        SourceProvider.QUARK -> QuarkPersonalDrive.openSession() != null
    }


internal fun MainActivity.groupTabLabel() =
    if (providerOfSourceId(gid) == SourceProvider.QUARK) "监控目录" else "群组"


// 百度沿用原群号，保持接口参数、缓存和历史记录一致；夸克使用独立的前缀。
internal fun MainActivity.sourceId(provider: SourceProvider, id: String): String =
    if (provider == SourceProvider.QUARK) qualifySourceId(provider, rawSourceId(id)) else rawSourceId(id)


internal fun MainActivity.storedShareSources(): List<ShareSource> {
    val prefs = getSharedPreferences("account_scope", android.content.Context.MODE_PRIVATE)
    val stored = JSONArray(prefs.getString(AccountScope.key("share_sources"), "[]") ?: "[]")
    return List(stored.length()) { index ->
        val item = stored.getJSONObject(index)
        val rawId = item.getString("id")
        val provider = if (rawId.startsWith(PREFIX_BAIDU) || rawId.startsWith(PREFIX_QUARK)) {
            providerOfSourceId(rawId)
        } else {
            SourceProvider.entries.firstOrNull { it.name == item.optString("provider") } ?: SourceProvider.BAIDU
        }
        ShareSource(sourceId(provider, rawId), item.getString("name"), item.optString("session"), provider)
    }
}


internal fun MainActivity.readActiveSource(): String {
    val prefs = getSharedPreferences("account_scope", android.content.Context.MODE_PRIVATE)
    val saved = prefs.getString(AccountScope.key("group_id"), "").orEmpty()
    val stored = storedShareSources()
    val sources = stored.filterNot {
        it.provider == SourceProvider.BAIDU && it.name == "原群文件库" && it.sessionUrl.isEmpty()
    }.distinctBy { it.id }
    val normalized = sourceId(providerOfSourceId(saved), saved)
    val active = sources.firstOrNull { it.id == normalized }?.id
        ?: sources.firstOrNull { it.provider == SourceProvider.QUARK && rawSourceId(it.id) == saved }?.id
        ?: sources.firstOrNull()?.id.orEmpty()
    if (sources.size != stored.size || active != saved) {
        saveShareSources(sources, active)
    }
    return active
}


internal fun MainActivity.shareSources(): List<ShareSource> = storedShareSources().distinctBy { it.id }


internal fun MainActivity.chooseSource() {
    val sources = shareSources()
    if (sources.isEmpty()) {
        manageSources()
        return
    }
    // 扁平列表 + 按网盘分组：百度在前（群组/联系人），夸克在后（监控目录）。
    val ordered = sources.sortedBy { it.provider.ordinal }
    showChoiceSheet(
        title = "切换文件库",
        subtitle = "选择要浏览的来源（按网盘分组）",
        choices = ordered.map {
            val meta = when (it.provider) {
                SourceProvider.QUARK -> "夸克 · 监控目录"
                SourceProvider.BAIDU -> if (isContactSource(it.id)) "百度 · 联系人" else "百度 · 群组"
            }
            SheetChoice(it.name, meta = meta, checked = it.id == gid, tag = it)
        },
        onSelect = { choice ->
            (choice.tag as? ShareSource)?.let { selectSource(it) }
        },
        footerActions = listOf("添加 / 管理来源" to { manageSources() }),
    )
}


internal fun MainActivity.selectSource(source: ShareSource) {
    getSharedPreferences("account_scope", android.content.Context.MODE_PRIVATE).edit {
        putString(AccountScope.key("source_last_${source.provider.name.lowercase()}"), source.id)
    }
    activateGroup(source.id)
}


internal fun MainActivity.manageSources() {
    showChoiceSheet(
        title = "添加 / 管理来源",
        subtitle = "选择来源所属网盘",
        choices = listOf(
            SheetChoice("百度网盘", meta = "群组 / 联系人", tag = SourceProvider.BAIDU),
            SheetChoice("夸克网盘", meta = "从根目录勾选监控目录", tag = SourceProvider.QUARK),
        ),
        onSelect = { choice ->
            when (choice.tag as? SourceProvider) {
                SourceProvider.QUARK -> manageQuarkSources()
                else -> manageBaiduSources()
            }
        },
    )
}


internal fun MainActivity.manageBaiduSources() {
    if (!ready || !hasVerifiedAccount()) {
        toast("请先确认百度网盘登录")
        return
    }
    showChoiceSheet(
        title = "管理百度来源",
        subtitle = "勾选要在 App 中浏览的文件库",
        choices = listOf(SheetChoice("群组"), SheetChoice("联系人")),
        onSelect = { discoverSources(contacts = it.title == "联系人") },
    )
}


/** 从夸克网盘根目录勾选最多 5 个目录，存为 provider = QUARK 的来源。 */
internal fun MainActivity.manageQuarkSources() {
    val session = QuarkPersonalDrive.openSession()
    if (session == null) {
        toast("请先登录夸克网盘")
        return
    }
    toast("正在读取夸克网盘目录…")
    AppIo.execute {
        val result = runCatching {
            session.execute(session.list(QuarkPersonalDrive.ROOT, 1, 200))
        }
        runOnUiThread {
            result.onFailure { error ->
                toast("读取夸克目录失败：${error.message ?: "未知错误"}")
            }.onSuccess { page ->
                val dirs = page.items.filter { it.directory }
                if (dirs.isEmpty()) {
                    toast("夸克网盘根目录没有子文件夹")
                    return@onSuccess
                }
                val checkedIds = shareSources()
                    .filter { it.provider == SourceProvider.QUARK }
                    .map { rawSourceId(it.id) }
                    .toSet()
                showChoiceSheet(
                    title = "夸克监控目录",
                    subtitle = "勾选要监控的目录（最多 5 个，仅根目录一层）",
                    choices = dirs.map {
                        SheetChoice(
                            it.name,
                            meta = "夸克 · 目录",
                            checked = it.id in checkedIds,
                            tag = it,
                        )
                    },
                    multi = true,
                    confirmLabel = "保存",
                    onConfirm = { selected ->
                        val picked = selected.mapNotNull { it.tag as? QuarkFile }.take(5)
                        val others = shareSources().filter { it.provider != SourceProvider.QUARK }
                        val quark = picked.map {
                            ShareSource(qualifySourceId(SourceProvider.QUARK, it.id), it.name,
                                provider = SourceProvider.QUARK)
                        }
                        val merged = others + quark
                        val next = gid.takeIf { current -> merged.any { it.id == current } }
                            ?: quark.firstOrNull()?.id
                            ?: others.firstOrNull()?.id.orEmpty()
                        saveShareSources(merged, next)
                        activateGroup(next)
                        toast("已保存 ${quark.size} 个夸克监控目录")
                    },
                )
            }
        }
    }
}


internal fun MainActivity.discoverSources(contacts: Boolean) {
    if (!ready || !hasVerifiedAccount()) return
    val label = if (contacts) "联系人" else "群组"
    val account = verifiedAccount
    val candidates = linkedMapOf<String, ShareSource>()
    val seenPages = mutableSetOf<List<String>>()
    val idField = if (contacts) "uk" else "gid"
    val pageSize = 20

    fun failed() {
        if (!hasVerifiedAccount() || verifiedAccount != account) return
        AlertDialog.Builder(this)
            .setTitle("无法读取网盘${label}")
            .setMessage(status.text)
            .setPositiveButton("重试") { _, _ -> discoverSources(contacts) }
            .setNeutralButton("打开网页通讯录") { _, _ -> openContacts() }
            .setNegativeButton("取消", null)
            .show()
    }

    fun nextPage(token: String, start: Int) {
        val url = if (contacts) PanApi.contacts(token, start, pageSize) else PanApi.groups(token, start, pageSize)
        load(url, statusHint = "正在读取网盘${label}…", onFailure = ::failed) { json ->
            val records = json.getJSONArray("records")
            val items = List(records.length(), records::getJSONObject)
            val freshPage = seenPages.add(items.map { it.optString(idField) })
            for (item in items) {
                val id = item.optString(idField)
                if (!id.matches(Regex("[0-9]{1,24}"))) continue
                if (contacts && item.optInt("black_flag") != 0) continue
                val sourceId = if (contacts) CONTACT_SOURCE_PREFIX + id else id
                val name = if (contacts) sequenceOf("remark", "display_name", "nick_name", "uname")
                    .filterNot(item::isNull).map { item.optString(it, "") }.firstOrNull { it.isNotBlank() } else item.optString("name")
                candidates[sourceId] = ShareSource(sourceId, name?.takeIf { it.isNotBlank() } ?: "$label $id")
            }
            val nextStart = start + records.length()
            val hasMore = if (!json.isNull("has_more")) PanApi.hasMore(json)
                else json.optInt("count", nextStart) > nextStart || records.length() == pageSize
            if (hasMore && items.isNotEmpty() && freshPage) {
                nextPage(token, nextStart)
            } else {
                showSourcePicker(account, contacts, candidates.values.toList())
                if (hasMore) toast("网盘仅返回了部分${label}，请稍后重试")
            }
        }
    }

    load(PanApi.groupListSession(), statusHint = "正在读取网盘${label}…", onFailure = ::failed) { json ->
        val session = json.getJSONObject("result")
        if (session.optString("uk") != account) {
            verifyAndRefresh()
        } else {
            val token = session.optString("bdstoken")
            check(!session.isNull("bdstoken") && token.isNotBlank()) {
                "网盘未返回通讯录所需的会话参数，请重新登录。"
            }
            nextPage(token, 0)
        }
    }
}


internal fun MainActivity.showSourcePicker(account: String, contacts: Boolean, discovered: List<ShareSource>) {
    status.text = ""
    val label = if (contacts) "联系人" else "群组"
    val existing = shareSources().associateBy { it.id }
    val candidates = (discovered + existing.values.filter {
        it.provider == SourceProvider.BAIDU && isContactSource(it.id) == contacts
    })
        .distinctBy { it.id }
    if (candidates.isEmpty()) {
        AlertDialog.Builder(this)
            .setTitle("网盘${label}")
            .setMessage("这个账号的${label}列表为空。")
            .setNeutralButton("打开网页通讯录") { _, _ -> openContacts() }
            .setPositiveButton("关闭", null)
            .show()
        return
    }
    showChoiceSheet(
        title = "管理${label}文件库",
        subtitle = "可多选，取消勾选可从 App 移除",
        choices = candidates.map { source ->
            SheetChoice(source.name, checked = source.id in existing, tag = source)
        },
        multi = true,
        confirmLabel = "保存",
        onConfirm = { selected ->
            if (hasVerifiedAccount() && verifiedAccount == account) {
                val candidateIds = candidates.map { it.id }.toSet()
                val selectedSources = selected.mapNotNull { it.tag as? ShareSource }
                    .map { it.copy(sessionUrl = existing[it.id]?.sessionUrl.orEmpty()) }
                val retained = shareSources().filterNot { it.id in candidateIds }
                val sources = retained + selectedSources
                val nextGroup = gid.takeIf { current -> sources.any { it.id == current } }
                    ?: selectedSources.firstOrNull()?.id
                    ?: retained.firstOrNull()?.id.orEmpty()
                saveShareSources(sources, nextGroup)
                activateGroup(nextGroup)
            }
        },
    )
}


internal fun MainActivity.saveShareSources(sources: List<ShareSource>, activeGroup: String) {
    val entries = JSONArray()
    sources.forEach {
        entries.put(
            JSONObject()
                .put("id", it.id)
                .put("name", it.name)
                .put("session", it.sessionUrl)
                .put("provider", it.provider.name),
        )
    }
    getSharedPreferences("account_scope", android.content.Context.MODE_PRIVATE).edit {
        putString(AccountScope.key("share_sources"), entries.toString())
        putString(AccountScope.key("group_id"), activeGroup)
    }
}


internal fun MainActivity.openContacts() {
    pickingSource = true
    observedGroup = null
    showWeb(PanApi.CONTACTS)
    toast("群文件库可在这里关联，联系人文件库可在 App 设置中勾选")
}


internal fun MainActivity.associateCurrentSession() {
    val account = verifiedAccount
    if (!ready || account.isEmpty() || account != AccountScope.current) {
        toast("请先登录百度网盘")
        return
    }
    val address = web.url.orEmpty().toUri()
    val route = address.fragment.orEmpty()
    val isSession = address.scheme == "https" && address.host == PanApi.HOST &&
        address.path == "/disk/main" && route.startsWith("/im/session?")
    val group = observedGroup?.takeIf { isSession && it.first == route }?.second.orEmpty()
    if (group.isEmpty()) {
        toast("还未识别到群文件库。请进入会话的文件列表，或返回 App 从网盘勾选群组。")
        return
    }
    val sources = shareSources()
    val existing = sources.firstOrNull { it.id == group }
    val defaultName = existing?.name ?: "群文件库 ${sources.size + 1}"
    val name = EditText(this).apply {
        setSingleLine()
        setText(defaultName)
        hint = "显示名称"
    }
    AlertDialog.Builder(this)
        .setTitle("关联群文件库")
        .setView(name)
        .setNegativeButton("取消", null)
        .setPositiveButton("保存") { _, _ ->
            if (account != verifiedAccount || account != AccountScope.current) return@setPositiveButton
            val source = ShareSource(group, name.text.toString().trim().ifEmpty { defaultName })
            saveShareSources(shareSources().filterNot { it.id == group } + source, group)
            activateGroup(group, returnFromWeb = true)
        }.show()
}


internal fun MainActivity.activateGroup(group: String, returnFromWeb: Boolean = false) {
    clearSelectionIfActive()
    val changed = group != gid
    setActiveGroup(group)
    if (!returnFromWeb && screen == Screen.GROUPS && isContactSource(group)) {
        tabFolders.remove(Screen.HOME)
        tabScroll.remove(Screen.HOME)
        showScreen(Screen.HOME)
        tabFolders.remove(Screen.GROUPS)
        tabScroll.remove(Screen.GROUPS)
        return
    }
    if (returnFromWeb) {
        tabFolders.remove(Screen.GROUPS)
        tabScroll.remove(Screen.GROUPS)
        screen = Screen.GROUPS
        recent = false
        folders.clear()
        folderPositions.clear()
        courseEntries.clear()
        courseParentKey = ""
        showApp()
    } else {
        if (changed && (screen == Screen.HOME || screen == Screen.GROUPS)) {
            folders.clear()
            folderPositions.clear()
            courseEntries.clear()
            courseParentKey = ""
            mediaFilter = MediaFilter.ALL
            transferredOnly = false
        }
        refresh(force = changed)
        contentScroll.scrollTo(0, 0)
    }
}


internal fun MainActivity.setActiveGroup(group: String) {
    if (group != gid) {
        tabFolders.remove(Screen.HOME)
        tabFolders.remove(Screen.GROUPS)
        tabScroll.remove(Screen.HOME)
        tabScroll.remove(Screen.GROUPS)
    }
    gid = group
    homeCheckToken++
    homeCheckInFlight = false
    lastHomeCheckAt = 0L
    getSharedPreferences("account_scope", android.content.Context.MODE_PRIVATE).edit {
        putString(AccountScope.key("group_id"), gid)
    }
}
