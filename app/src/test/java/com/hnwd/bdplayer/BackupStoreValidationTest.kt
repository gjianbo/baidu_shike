package com.hnwd.bdplayer

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 备份文件的结构校验：[BackupStore.valid] / [BackupStore.backupAccount] / [BackupStore.summary]。
 *
 * valid 是恢复流程的唯一闸门 —— 它放行什么，就会直接写进当前账号的 prefs。
 * 所以这里主要钉住"该拒绝的都拒绝"：换过 schema、别的 App 的文件、账号串台、
 * 重复或非法的来源 id、收藏数组里混进脏数据。
 */
class BackupStoreValidationTest {

    private fun favorite(
        gid: String = "1001",
        message: String = "m1",
        owner: String = "2002",
        file: String = "f1",
        name: String = "a.mp3",
    ) = JSONObject()
        .put("gid", gid).put("message", message).put("owner", owner)
        .put("file", file).put("name", name)

    private fun payload(
        schemaVersion: Int = 2,
        app: String = "com.hnwd.bdplayer",
        sources: JSONArray = JSONArray().put(JSONObject().put("id", "12345").put("name", "群 A")),
        groupId: Any = "12345",
        favorites: Any = JSONArray().put(favorite()).toString(),
        account: String = "10086",
    ) = JSONObject()
        .put("schemaVersion", schemaVersion)
        .put("app", app)
        .put(
            "data",
            JSONObject()
                .put("account", account)
                .put("sources", sources)
                .put("groupId", groupId)
                .put("favorites", favorites),
        )

    // ===================== 放行 =====================

    @Test
    fun `正常备份可以通过校验`() {
        assertTrue(BackupStore.valid(payload()))
    }

    @Test
    fun `老版本 schema 仍然兼容`() {
        assertTrue(BackupStore.valid(payload(schemaVersion = 1)))
    }

    @Test
    fun `app 字段缺失或为空时不作为拒绝理由`() {
        // 早期导出的文件没有 app 字段，恢复时要兼容
        assertTrue(BackupStore.valid(payload(app = "")))
    }

    // ===================== 拒绝 =====================

    @Test
    fun `null 或版本越界直接拒绝`() {
        assertFalse(BackupStore.valid(null))
        assertFalse(BackupStore.valid(payload(schemaVersion = 0)))
        assertFalse(BackupStore.valid(payload(schemaVersion = 3)))
        assertFalse(BackupStore.valid(JSONObject().put("data", JSONObject())))
    }

    @Test
    fun `别家 App 的备份文件被拒绝`() {
        assertFalse(BackupStore.valid(payload(app = "com.example.other")))
    }

    @Test
    fun `data 或 sources 缺失时拒绝`() {
        assertFalse(BackupStore.valid(JSONObject().put("schemaVersion", 2)))
        assertFalse(
            BackupStore.valid(
                JSONObject().put("schemaVersion", 2)
                    .put("data", JSONObject().put("groupId", "").put("favorites", "[]")),
            ),
        )
    }

    @Test
    fun `groupId 与 favorites 类型不对时拒绝`() {
        assertFalse(BackupStore.valid(payload(groupId = 12345)))
        assertFalse(BackupStore.valid(payload(favorites = JSONArray().put(favorite()))))
    }

    @Test
    fun `收藏不是合法 JSON 数组时拒绝`() {
        assertFalse(BackupStore.valid(payload(favorites = "not json")))
        assertFalse(BackupStore.valid(payload(favorites = """{"gid":"1"}""")))
    }

    @Test
    fun `收藏条目缺字段时拒绝`() {
        val missingName = JSONObject()
            .put("gid", "1").put("message", "m").put("owner", "o").put("file", "f")
        assertFalse(BackupStore.valid(payload(favorites = JSONArray().put(missingName).toString())))
    }

    @Test
    fun `来源 id 非法或重复时拒绝`() {
        assertFalse(BackupStore.valid(payload(sources = JSONArray().put(JSONObject().put("id", "abc").put("name", "x")))))
        assertFalse(
            BackupStore.valid(
                payload(
                    sources = JSONArray()
                        .put(JSONObject().put("id", "12345").put("name", "A"))
                        .put(JSONObject().put("id", "12345").put("name", "A 重复")),
                ),
            ),
        )
    }

    @Test
    fun `来源缺名字时拒绝`() {
        assertFalse(BackupStore.valid(payload(sources = JSONArray().put(JSONObject().put("id", "12345")))))
    }

    @Test
    fun `当前 groupId 非法时拒绝`() {
        assertFalse(BackupStore.valid(payload(groupId = "not-a-gid")))
        // 空 groupId 允许：老导出里可能是"还没进过群组"的状态
        assertTrue(BackupStore.valid(payload(groupId = "")))
    }

    @Test
    fun `夸克的十六进制来源 id 也能通过`() {
        assertTrue(
            BackupStore.valid(
                payload(sources = JSONArray().put(JSONObject().put("id", "quark:abc12345").put("name", "夸克"))),
            ),
        )
    }

    // ===================== 账号 =====================

    @Test
    fun `备份账号只认纯数字`() {
        assertEquals("10086", BackupStore.backupAccount(payload()))
        assertNull(BackupStore.backupAccount(payload(account = "abc")))
        // 超过 24 位不是合法 uk
        assertNull(BackupStore.backupAccount(payload(account = "1".repeat(25))))
    }

    @Test
    fun `备份没记账号且当前也没登录时不认领`() {
        // 测试环境没有登录态（AccountScope.current 为空），此时应拒绝恢复而不是写进 guest
        assertNull(BackupStore.restoreAccount(payload(account = "")))
    }

    @Test
    fun `当前未登录时直接采用备份里的账号`() {
        assertEquals("10086", BackupStore.restoreAccount(payload()))
        // 反例（备份账号 != 当前账号时拒绝）需要先设置登录态，而 AccountScope.set 要 Context，
        // 见 BackupStoreValidationTest 末尾说明。
    }

    // ===================== 摘要 =====================

    @Test
    fun `摘要统计群组收藏进度与主题`() {
        val data = JSONObject()
            .put("sources", JSONArray().put(JSONObject().put("id", "1")).put(JSONObject().put("id", "2")))
            .put("favorites", JSONArray().put(favorite()).put(favorite(file = "f2")).toString())
            .put("audioHistory", JSONObject().put("k1", JSONArray().put(favorite()).toString()))
            .put("videoHistory", JSONObject().put("k2", "[]"))
            .put("prefs", JSONObject().put("appearance", ThemeStore.DARK))
        val text = BackupStore.summary(JSONObject().put("data", data))

        assertEquals("2 个群组 · 2 个收藏 · 1 条进度 · 深色", text)
    }

    @Test
    fun `摘要对空数据不崩`() {
        assertEquals("0 个群组 · 0 个收藏 · 0 条进度 · 浅色", BackupStore.summary(JSONObject()))
    }
}
