package com.hnwd.bdplayer

/**
 * 设置页里不依赖 Android View 的判定与文案。
 *
 * SettingsPage 本身仍在操作 View，JVM 单测无法直接实例化；把三态判定、
 * 行文案、退出登录的 Cookie 计划抽到这里，宿主只负责把状态喂进来并把结果画上去。
 * 这里的函数必须保持纯函数：不读 Context、不碰 SharedPreferences、不碰 WebView。
 */

/** 账号行三态：ACTIVE（在用）/ DORMANT（退出过，数据保留）/ NEVER（从没登录）。 */
internal enum class DriveState { NEVER, ACTIVE, DORMANT }

internal data class DriveStates(val baidu: DriveState, val quark: DriveState)

internal val BAIDU_LOGOUT_URLS = listOf(
    "https://pan.baidu.com", "https://wappass.baidu.com",
    "https://passport.baidu.com", "https://www.baidu.com", "https://baidu.com",
)

internal val QUARK_LOGOUT_URLS = listOf(
    "https://pan.quark.cn", "https://drive-pc.quark.cn",
    "https://uop.quark.cn", "https://quark.cn",
)

/**
 * 判定参数刻意用惰性 lambda：原 when 表达式有短路（账号已校验时不会再去读来源列表），
 * 抽函数后必须保留，否则会多读几次宿主状态，也让「短路」这条契约无法被测试。
 */
internal fun resolveBaiduState(
    accountVerified: () -> Boolean,
    account: () -> String,
    scopeUk: () -> String,
    hasBaiduSource: () -> Boolean,
): DriveState = when {
    accountVerified() || account().isNotEmpty() -> DriveState.ACTIVE
    // 退出登录刻意保留 viewer_uk（数据归属锚点），此时按 DORMANT 呈现。
    scopeUk().isNotEmpty() -> DriveState.DORMANT
    hasBaiduSource() -> DriveState.DORMANT
    else -> DriveState.NEVER
}

internal fun resolveQuarkState(
    quarkLoggedIn: () -> Boolean,
    everLogged: () -> Boolean,
    hasQuarkSource: () -> Boolean,
): DriveState = when {
    quarkLoggedIn() -> DriveState.ACTIVE
    everLogged() || hasQuarkSource() -> DriveState.DORMANT
    else -> DriveState.NEVER
}

/** 只渲染非 NEVER 的网盘，顺序固定为百度 → 夸克。 */
internal fun linkedDriveRows(states: DriveStates): List<Pair<SourceProvider, DriveState>> =
    buildList {
        if (states.baidu != DriveState.NEVER) add(SourceProvider.BAIDU to states.baidu)
        if (states.quark != DriveState.NEVER) add(SourceProvider.QUARK to states.quark)
    }

internal fun hasNeverDrive(states: DriveStates): Boolean =
    states.baidu == DriveState.NEVER || states.quark == DriveState.NEVER

internal fun addLoginHint(hasNever: Boolean): String =
    if (hasNever) "选择还没登录过的网盘类型" else "已没有可添加的网盘"

/** 「添加登录」只列出从没登录过的网盘；tag 交给宿主回调决定启动哪个登录入口。 */
internal fun addLoginChoices(baidu: DriveState, quark: DriveState): List<SheetChoice> =
    buildList {
        if (baidu == DriveState.NEVER) {
            add(
                SheetChoice(
                    "百度网盘",
                    meta = "共享文件库可能在群组或联系人下",
                    tag = SourceProvider.BAIDU,
                )
            )
        }
        if (quark == DriveState.NEVER) {
            add(
                SheetChoice(
                    "夸克网盘",
                    meta = "关注根目录下的文件夹",
                    tag = SourceProvider.QUARK,
                )
            )
        }
    }

/** 百度账号尾号脱敏；昵称非空时拼在前面。 */
internal fun baiduAccountStatus(account: String, nickname: String): String {
    val tail = if (account.length <= 4) "账号 $account" else "账号 …${account.takeLast(4)}"
    val name = nickname.trim()
    return if (name.isNotEmpty()) "已登录 · $name（$tail）" else "已登录 · $tail"
}

/** 账号与昵称用惰性 lambda：只有「ACTIVE 的百度行」才需要读宿主账号信息。 */
internal fun driveStatusText(
    provider: SourceProvider,
    state: DriveState,
    account: () -> String = { "" },
    nickname: () -> String = { "" },
): String = when (state) {
    DriveState.NEVER -> ""
    DriveState.DORMANT -> "暂未使用 · 数据已保留"
    DriveState.ACTIVE -> if (provider == SourceProvider.QUARK) {
        "已登录 · Cookie 有效"
    } else {
        baiduAccountStatus(account(), nickname())
    }
}

internal fun driveCountText(state: DriveState, sources: Int): String =
    if (state == DriveState.ACTIVE) "$sources 个来源" else "保留 $sources 个来源"

internal fun driveActionText(state: DriveState): String =
    if (state == DriveState.ACTIVE) "退出登录" else "登录"

internal fun drivePillText(state: DriveState): String =
    if (state == DriveState.ACTIVE) "在用" else "已退出"

internal fun logoutLabel(provider: SourceProvider): String =
    if (provider == SourceProvider.BAIDU) "百度网盘" else "夸克网盘"

/**
 * 退出登录只清对应网盘的域名，避免误杀另一个网盘会话（两者共用 CookieManager）。
 * 返回待清理的 URL 列表与过期 Cookie 的 Domain 后缀。
 */
internal fun logoutCookiePlan(provider: SourceProvider): Pair<List<String>, String> =
    if (provider == SourceProvider.BAIDU) {
        BAIDU_LOGOUT_URLS to "baidu.com"
    } else {
        QUARK_LOGOUT_URLS to "quark.cn"
    }

/**
 * 把 getCookie() 返回的分号串展开成逐条 Max-Age=0 的清除指令（url → 指令）。
 * 每个 Cookie 名要写两条：Path=/ 与带 Domain 的，才能覆盖不同写入方式。
 */
internal fun cookieExpiryCommands(raw: String, url: String, domain: String): List<Pair<String, String>> =
    raw.split(';').flatMap { part ->
        val name = part.substringBefore('=').trim()
        if (name.isEmpty()) {
            emptyList()
        } else {
            listOf(
                url to "$name=; Max-Age=0; Path=/",
                url to "$name=; Max-Age=0; Path=/; Domain=.$domain",
            )
        }
    }
