package com.hnwd.bdplayer

import android.app.Activity
import android.content.SharedPreferences
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import android.webkit.CookieManager

/**
 * 设置页需要的宿主能力：页面容器、账号展示信息、以及各行的跳转动作。
 *
 * 设置页刻意不持有任何跨页状态：它每次 [render] 都按当前账号重新算一遍，
 * 所以宿主只需要提供「读」和「跳转」，不需要回写。
 */
internal interface SettingsHost {
    val hostActivity: Activity

    fun clearStatus()
    fun inflatePage(): View
    fun addPage(page: View)
    fun inflateDriveRow(): View
    fun renderCourseSwitch()
    fun historyCount(): Int
    fun themeLabel(): String
    fun showThemeDialog()
    fun launchBackup()
    fun appVersionLine(): String

    /** 夸克会话是否有效（Cookie 判定归宿主，设置页只读结果）。 */
    fun quarkLoggedIn(): Boolean
    fun markQuarkEverLogged()

    fun account(): String
    fun accountName(): String
    fun accountVerified(): Boolean
    fun shareSources(): List<ShareSource>
    fun renderAccountAvatar(icon: ImageView)

    fun manageSources(provider: SourceProvider)
    fun startLogin(provider: SourceProvider)
    fun confirmClearHistory()
    fun invalidateAccount()
    fun setCheckingAccount(checking: Boolean)
    fun clearPendingAddProvider()
    fun pickFromSheet(
        title: String,
        subtitle: String,
        choices: List<SheetChoice>,
        onSelect: (SheetChoice) -> Unit,
    )

    fun settings(): SharedPreferences
    fun say(message: String)
    fun refresh()
}

/**
 * 设置页：账号行（百度 / 夸克的状态、来源数、登录/退出）+ 主题、清记录、备份、关于。
 *
 * 账号行有三态：ACTIVE（在用）/ DORMANT（退出过，数据保留）/ NEVER（从没登录）。
 * 退出登录只清该域名的 Cookie，不删来源与播放记录——这条是产品口径，改之前先确认。
 */
internal class SettingsPage(private val host: SettingsHost) {
    private val activity: Activity get() = host.hostActivity

    fun render() {
        host.clearStatus()
        val page = host.inflatePage()
        renderLinkedDrives(page)
        page.findViewById<View>(R.id.setAddLoginRow).setOnClickListener { openAddLoginSheet() }
        page.findViewById<TextView>(R.id.setClearValue).text =
            host.historyCount().let { "$it 条" }
        page.findViewById<View>(R.id.setClearRow).setOnClickListener { host.confirmClearHistory() }
        page.findViewById<TextView>(R.id.setThemeValue).text = host.themeLabel()
        page.findViewById<View>(R.id.setThemeRow).setOnClickListener { host.showThemeDialog() }
        page.findViewById<View>(R.id.setBackupRow).setOnClickListener { host.launchBackup() }
        page.findViewById<TextView>(R.id.setAbout).text = host.appVersionLine()
        host.addPage(page)
        host.renderCourseSwitch()
    }

    private fun quarkEverLogged(): Boolean =
        host.settings().getBoolean("quark_ever_logged", false)

    private fun baiduDriveState(): DriveState = resolveBaiduState(
        accountVerified = { host.accountVerified() },
        account = { host.account() },
        scopeUk = { AccountScope.current },
        hasBaiduSource = { host.shareSources().any { it.provider == SourceProvider.BAIDU } },
    )

    private fun quarkDriveState(): DriveState = resolveQuarkState(
        quarkLoggedIn = { host.quarkLoggedIn() },
        everLogged = { quarkEverLogged() },
        hasQuarkSource = { host.shareSources().any { it.provider == SourceProvider.QUARK } },
    )

    private fun renderLinkedDrives(page: View) {
        val container = page.findViewById<LinearLayout>(R.id.setLinkedContainer)
        container.removeAllViews()
        if (host.quarkLoggedIn()) host.markQuarkEverLogged()
        val states = DriveStates(baiduDriveState(), quarkDriveState())
        val linked = linkedDriveRows(states)
        if (linked.isEmpty()) {
            container.addView(emptyDriveRow())
        } else {
            linked.forEachIndexed { index, (provider, state) ->
                if (index > 0) container.addView(driveDivider())
                container.addView(driveRow(provider, state))
            }
        }
        val hasNever = hasNeverDrive(states)
        page.findViewById<View>(R.id.setAddLoginRow).visibility =
            if (hasNever) View.VISIBLE else View.GONE
        page.findViewById<TextView>(R.id.setAddLoginHint).text = addLoginHint(hasNever)
    }

    private fun driveDivider(): View = View(activity).apply {
        setBackgroundColor(activity.color(R.color.line))
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            activity.dp(1),
        ).apply { marginStart = activity.dp(69) }
    }

    private fun emptyDriveRow(): View {
        val row = host.inflateDriveRow()
        row.isClickable = false
        row.findViewById<TextView>(R.id.drivePill).visibility = View.GONE
        row.findViewById<TextView>(R.id.driveName).text = "还没有关联网盘"
        row.findViewById<TextView>(R.id.driveStatus).text = "点下方「添加登录」开始"
        row.findViewById<TextView>(R.id.driveCount).visibility = View.GONE
        row.findViewById<TextView>(R.id.driveAction).visibility = View.GONE
        return row
    }

    private fun driveRow(provider: SourceProvider, state: DriveState): View {
        val row = host.inflateDriveRow()
        val baidu = provider == SourceProvider.BAIDU
        val icon = row.findViewById<ImageView>(R.id.driveIcon)
        val pill = row.findViewById<TextView>(R.id.drivePill)
        val name = row.findViewById<TextView>(R.id.driveName)
        val status = row.findViewById<TextView>(R.id.driveStatus)
        val count = row.findViewById<TextView>(R.id.driveCount)
        val action = row.findViewById<TextView>(R.id.driveAction)
        name.text = if (baidu) "百度网盘" else "夸克网盘"
        val sources = host.shareSources().count { it.provider == provider }
        status.text = driveStatusText(
            provider, state,
            account = { host.account() },
            nickname = { host.accountName() },
        )
        pill.text = drivePillText(state)
        count.text = driveCountText(state, sources)
        action.text = driveActionText(state)
        if (state == DriveState.ACTIVE) {
            if (baidu) host.renderAccountAvatar(icon) else icon.setImageResource(R.drawable.ic_layers)
            pill.setBackgroundResource(R.drawable.bg_chip_on)
            pill.setTextColor(activity.color(R.color.accentInk))
            action.setTextColor(activity.color(R.color.danger))
            action.setOnClickListener { confirmLogout(provider) }
            row.setOnClickListener { openSourcesFor(provider) }
        } else {
            icon.setImageResource(if (baidu) R.drawable.ic_cloud else R.drawable.ic_layers)
            pill.setBackgroundResource(R.drawable.bg_chip)
            pill.setTextColor(activity.color(R.color.ink3))
            action.setTextColor(activity.color(R.color.accentInk))
            action.setOnClickListener { startLoginFor(provider) }
            row.setOnClickListener { startLoginFor(provider) }
        }
        return row
    }

    private fun openSourcesFor(provider: SourceProvider) = host.manageSources(provider)

    private fun startLoginFor(provider: SourceProvider) = host.startLogin(provider)

    private fun openAddLoginSheet() {
        val choices = addLoginChoices(baiduDriveState(), quarkDriveState())
        if (choices.isEmpty()) return
        host.pickFromSheet(
            title = "添加登录",
            subtitle = "选择还没登录过的网盘类型",
            choices = choices,
            onSelect = { choice -> (choice.tag as? SourceProvider)?.let(::startLoginFor) },
        )
    }

    private fun confirmLogout(provider: SourceProvider) {
        val label = logoutLabel(provider)
        val (urls, domain) = logoutCookiePlan(provider)
        AlertDialog.Builder(activity)
            .setTitle("退出$label")
            .setMessage("退出只终止会话，已关联的来源和播放记录都会保留，可随时重新登录。")
            .setNegativeButton("取消", null)
            .setPositiveButton("退出登录") { _, _ ->
                clearCookies(urls, domain)
                if (provider == SourceProvider.BAIDU) {
                    host.invalidateAccount()
                    host.setCheckingAccount(false)
                } else {
                    host.markQuarkEverLogged()
                }
                host.clearPendingAddProvider()
                host.say("已退出$label，数据已保留")
                host.refresh()
            }
            .show()
    }

    /** 只清指定域名的 Cookie，避免误杀另一个网盘会话（百度/夸克共用 CookieManager）。 */
    private fun clearCookies(urls: List<String>, domain: String) {
        val manager = CookieManager.getInstance()
        urls.forEach { url ->
            val raw = manager.getCookie(url).orEmpty()
            cookieExpiryCommands(raw, url, domain).forEach { (target, command) ->
                manager.setCookie(target, command)
            }
        }
        manager.flush()
    }
}
