package com.hnwd.bdplayer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 技术债：migrateLegacy 原先只复制、不删旧键，preferences.all 会随历史键无限增长
 * （prune / diskIndex 都要整表遍历）。这里钉死迁移计划：新键照旧生成，
 * 旧键也必须出现在计划里，调用方才能把它们删掉。
 */
class AccountScopeMigrationTest {

    private val prefix = "acct_guest__"

    @Test
    fun `空集合返回空计划`() {
        assertTrue(legacyKeyMoves(emptyList(), prefix).isEmpty())
    }

    @Test
    fun `旧键映射到当前账号前缀`() {
        assertEquals(
            listOf("gid:1:2:3" to "acct_guest__gid:1:2:3"),
            legacyKeyMoves(listOf("gid:1:2:3"), prefix),
        )
    }

    @Test
    fun `已是账号前缀的键不再迁移`() {
        assertTrue(legacyKeyMoves(listOf("acct_gjianbo__k", "acct_guest__k"), prefix).isEmpty())
    }

    @Test
    fun `混合键只保留旧键`() {
        assertEquals(
            listOf("old" to "acct_guest__old", "old2" to "acct_guest__old2"),
            legacyKeyMoves(listOf("old", "acct_a__new", "old2"), prefix),
        )
    }

    @Test
    fun `含冒号与中文的旧键原样映射`() {
        assertEquals(
            listOf("课程:第 1 页:2" to "acct_guest__课程:第 1 页:2"),
            legacyKeyMoves(listOf("课程:第 1 页:2"), prefix),
        )
    }

    @Test
    fun `计划里的旧键数量与待迁移键一致`() {
        val moves = legacyKeyMoves(listOf("a", "b", "acct_x__c"), prefix)
        assertEquals(2, moves.size)
        assertTrue(moves.none { it.first.startsWith(ACCOUNT_KEY_PREFIX) })
    }

    @Test
    fun `目标键一定带账号前缀`() {
        val moves = legacyKeyMoves(listOf("k1", "k2"), "acct_gjianbo__")
        assertTrue(moves.all { it.second.startsWith("acct_gjianbo__") })
        assertEquals(listOf("k1", "k2"), moves.map { it.first })
    }
}
