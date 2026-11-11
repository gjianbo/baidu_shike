package com.hnwd.bdplayer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 设置页三态判定 / 行文案 / 退出登录 Cookie 计划的 JVM 单测。
 * 重点钉死两条不变式：判定短路（不白读宿主状态）、退出登录只清本网盘域名。
 */
class SettingsLogicTest {

    // ---------- 百度三态 ----------

    @Test
    fun `百度账号已校验即为在用且后续状态不再读取`() {
        var calls = 0
        val state = resolveBaiduState(
            accountVerified = { true },
            account = { calls++; "" },
            scopeUk = { calls++; "" },
            hasBaiduSource = { calls++; false },
        )
        assertEquals(DriveState.ACTIVE, state)
        assertEquals(0, calls)
    }

    @Test
    fun `百度账号非空但尚未校验也算在用`() {
        assertEquals(
            DriveState.ACTIVE,
            resolveBaiduState({ false }, { "123456" }, { "" }, { false }),
        )
    }

    @Test
    fun `百度退出过保留 viewer_uk 则为已退出`() {
        var sourceRead = false
        val state = resolveBaiduState(
            accountVerified = { false },
            account = { "" },
            scopeUk = { "uk_123" },
            hasBaiduSource = { sourceRead = true; false },
        )
        assertEquals(DriveState.DORMANT, state)
        assertFalse("scopeUk 命中后不应再读来源列表", sourceRead)
    }

    @Test
    fun `百度无账号但有已存来源也算已退出`() {
        assertEquals(
            DriveState.DORMANT,
            resolveBaiduState({ false }, { "" }, { "" }, { true }),
        )
    }

    @Test
    fun `百度什么都没有才是从没登录`() {
        assertEquals(
            DriveState.NEVER,
            resolveBaiduState({ false }, { "" }, { "" }, { false }),
        )
    }

    // ---------- 夸克三态 ----------

    @Test
    fun `夸克 Cookie 有效即为在用且不再读历史标记`() {
        var calls = 0
        val state = resolveQuarkState(
            quarkLoggedIn = { true },
            everLogged = { calls++; true },
            hasQuarkSource = { calls++; true },
        )
        assertEquals(DriveState.ACTIVE, state)
        assertEquals(0, calls)
    }

    @Test
    fun `夸克登录过但 Cookie 失效为已退出且来源不再读取`() {
        var sourceRead = false
        val state = resolveQuarkState(
            quarkLoggedIn = { false },
            everLogged = { true },
            hasQuarkSource = { sourceRead = true; false },
        )
        assertEquals(DriveState.DORMANT, state)
        assertFalse("everLogged 为真时 || 应短路", sourceRead)
    }

    @Test
    fun `夸克没登录过但有监控目录为已退出`() {
        assertEquals(
            DriveState.DORMANT,
            resolveQuarkState({ false }, { false }, { true }),
        )
    }

    @Test
    fun `夸克三项全无才是从没登录`() {
        assertEquals(
            DriveState.NEVER,
            resolveQuarkState({ false }, { false }, { false }),
        )
    }

    // ---------- 行渲染与「添加登录」 ----------

    @Test
    fun `两个网盘都没登录过时不渲染任何行`() {
        val states = DriveStates(DriveState.NEVER, DriveState.NEVER)
        assertTrue(linkedDriveRows(states).isEmpty())
        assertTrue(hasNeverDrive(states))
    }

    @Test
    fun `只登录百度时只渲染百度行但仍可添加夸克`() {
        val states = DriveStates(DriveState.ACTIVE, DriveState.NEVER)
        assertEquals(
            listOf(SourceProvider.BAIDU to DriveState.ACTIVE),
            linkedDriveRows(states),
        )
        assertTrue(hasNeverDrive(states))
    }

    @Test
    fun `夸克退出态仍要渲染行且顺序在百度之后`() {
        val states = DriveStates(DriveState.ACTIVE, DriveState.DORMANT)
        assertEquals(
            listOf(
                SourceProvider.BAIDU to DriveState.ACTIVE,
                SourceProvider.QUARK to DriveState.DORMANT,
            ),
            linkedDriveRows(states),
        )
        assertFalse(hasNeverDrive(states))
    }

    @Test
    fun `夸克单独在用时不渲染百度行`() {
        val states = DriveStates(DriveState.NEVER, DriveState.ACTIVE)
        assertEquals(
            listOf(SourceProvider.QUARK to DriveState.ACTIVE),
            linkedDriveRows(states),
        )
    }

    @Test
    fun `添加登录提示随可添加状态切换`() {
        assertEquals("选择还没登录过的网盘类型", addLoginHint(true))
        assertEquals("已没有可添加的网盘", addLoginHint(false))
    }

    @Test
    fun `添加登录列出两个没登录过的网盘并带上 provider tag`() {
        val choices = addLoginChoices(DriveState.NEVER, DriveState.NEVER)
        assertEquals(listOf("百度网盘", "夸克网盘"), choices.map { it.title })
        assertEquals(
            listOf(SourceProvider.BAIDU, SourceProvider.QUARK),
            choices.map { it.tag },
        )
        assertEquals("共享文件库可能在群组或联系人下", choices[0].meta)
        assertEquals("关注根目录下的文件夹", choices[1].meta)
    }

    @Test
    fun `已退出态不算可添加登录`() {
        assertTrue(addLoginChoices(DriveState.ACTIVE, DriveState.DORMANT).isEmpty())
        assertEquals(
            listOf("夸克网盘"),
            addLoginChoices(DriveState.DORMANT, DriveState.NEVER).map { it.title },
        )
    }

    // ---------- 账号文案 ----------

    @Test
    fun `百度长账号只显示后四位且昵称拼在前面`() {
        assertEquals("已登录 · 账号 …5678", baiduAccountStatus("12345678", ""))
        assertEquals("已登录 · 张三（账号 …5678）", baiduAccountStatus("12345678", " 张三 "))
    }

    @Test
    fun `百度四位及更短账号不脱敏`() {
        assertEquals("已登录 · 账号 abcd", baiduAccountStatus("abcd", ""))
        assertEquals("已登录 · 账号 abc", baiduAccountStatus("abc", ""))
    }

    @Test
    fun `百度昵称只有空白时不拼接`() {
        assertEquals("已登录 · 账号 …5678", baiduAccountStatus("12345678", "   "))
    }

    @Test
    fun `百度五位账号开始脱敏`() {
        assertEquals("已登录 · 账号 …2345", baiduAccountStatus("12345", ""))
    }

    @Test
    fun `行状态文案按 provider 与状态分派`() {
        assertEquals("", driveStatusText(SourceProvider.BAIDU, DriveState.NEVER))
        assertEquals(
            "暂未使用 · 数据已保留",
            driveStatusText(SourceProvider.QUARK, DriveState.DORMANT),
        )
        assertEquals(
            "已登录 · Cookie 有效",
            driveStatusText(SourceProvider.QUARK, DriveState.ACTIVE),
        )
        assertEquals(
            "已登录 · 账号 …5678",
            driveStatusText(SourceProvider.BAIDU, DriveState.ACTIVE, { "12345678" }, { "" }),
        )
    }

    @Test
    fun `非 ACTIVE 百度行不读取账号信息`() {
        var calls = 0
        driveStatusText(SourceProvider.BAIDU, DriveState.DORMANT, { calls++; "x" }, { calls++; "y" })
        driveStatusText(SourceProvider.QUARK, DriveState.ACTIVE, { calls++; "x" }, { calls++; "y" })
        assertEquals(0, calls)
    }

    @Test
    fun `行来源数与动作文案随状态切换`() {
        assertEquals("3 个来源", driveCountText(DriveState.ACTIVE, 3))
        assertEquals("保留 3 个来源", driveCountText(DriveState.DORMANT, 3))
        assertEquals("退出登录", driveActionText(DriveState.ACTIVE))
        assertEquals("登录", driveActionText(DriveState.DORMANT))
        assertEquals("在用", drivePillText(DriveState.ACTIVE))
        assertEquals("已退出", drivePillText(DriveState.DORMANT))
    }

    @Test
    fun `退出登录文案带上网盘名`() {
        assertEquals("百度网盘", logoutLabel(SourceProvider.BAIDU))
        assertEquals("夸克网盘", logoutLabel(SourceProvider.QUARK))
    }

    // ---------- 退出登录 Cookie 计划 ----------

    @Test
    fun `退出百度只清百度域名`() {
        val (urls, domain) = logoutCookiePlan(SourceProvider.BAIDU)
        assertEquals("baidu.com", domain)
        assertTrue(urls.isNotEmpty())
        assertTrue(urls.all { it.contains("baidu") })
        assertTrue(urls.none { it.contains("quark") })
    }

    @Test
    fun `退出夸克只清夸克域名`() {
        val (urls, domain) = logoutCookiePlan(SourceProvider.QUARK)
        assertEquals("quark.cn", domain)
        assertTrue(urls.isNotEmpty())
        assertTrue(urls.all { it.contains("quark") })
        assertTrue(urls.none { it.contains("baidu") })
    }

    @Test
    fun `两个网盘的待清 URL 集合不相交`() {
        val (baidu, _) = logoutCookiePlan(SourceProvider.BAIDU)
        val (quark, _) = logoutCookiePlan(SourceProvider.QUARK)
        assertTrue(baidu.intersect(quark.toSet()).isEmpty())
    }

    @Test
    fun `空 Cookie 串不产生清除指令`() {
        assertTrue(cookieExpiryCommands("", "https://pan.baidu.com", "baidu.com").isEmpty())
    }

    @Test
    fun `每个 Cookie 名产生两条清除指令且都指向原 URL`() {
        val cmds = cookieExpiryCommands(
            "BDUSS=abc; STOKEN=x",
            "https://pan.baidu.com",
            "baidu.com",
        )
        assertEquals(4, cmds.size)
        assertEquals(
            listOf("BDUSS", "BDUSS", "STOKEN", "STOKEN"),
            cmds.map { it.second.substringBefore('=') },
        )
        assertTrue(cmds.all { it.first == "https://pan.baidu.com" })
        assertEquals("BDUSS=; Max-Age=0; Path=/", cmds[0].second)
        assertEquals("BDUSS=; Max-Age=0; Path=/; Domain=.baidu.com", cmds[1].second)
    }

    @Test
    fun `Cookie 名两侧空白被裁剪且空名片段被忽略`() {
        val cmds = cookieExpiryCommands(" A=1 ; =dropped ;B=2", "https://quark.cn", "quark.cn")
        assertEquals(4, cmds.size)
        assertEquals(listOf("A", "A", "B", "B"), cmds.map { it.second.substringBefore('=') })
        assertEquals("A=; Max-Age=0; Path=/; Domain=.quark.cn", cmds[1].second)
    }
}
