package com.hnwd.bdplayer

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 夸克批量写操作纯逻辑的 JVM 单测：fid 去重、分享响应容错、目录阻断与错误文案。 */
class QuarkDiskWriteLogicTest {

    private fun entry(
        name: String = "a.txt",
        path: String = "/dir/$name",
        fileId: String = "f1",
        directory: Boolean = false,
    ) = PanEntry(
        gid = "quark:0", messageId = "", owner = "",
        fileId = fileId, name = name, path = path, size = "1",
        directory = directory, origin = EntryOrigin.DISK,
    )

    // ---------- fid ----------

    @Test
    fun `批量 fid 去重并过滤空值`() {
        val ids = quarkMutationIds(
            listOf(entry(fileId = "a"), entry(fileId = "b"), entry(fileId = "a"), entry(fileId = "")),
        )
        assertEquals(listOf("a", "b"), ids)
    }

    @Test
    fun `空白 fid 同样不能提交`() {
        assertTrue(quarkMutationIds(emptyList()).isEmpty())
        assertTrue(quarkMutationIds(listOf(entry(fileId = "  "))).isEmpty())
    }

    // ---------- 分享有效期 ----------

    @Test
    fun `分享有效期三档带 expired_type`() {
        val choices = quarkSharePeriodChoices()
        assertEquals(listOf("永久有效", "1 天有效", "7 天有效"), choices.map { it.title })
        // 真机实测：0 是推测值，传 0 会得到 HTTP 404 / code 41000；合法枚举从 1 开始。
        assertEquals(listOf(1, 2, 3), choices.map { it.tag })
    }

    @Test
    fun `分享任务两步解析 task_id 与 share_id`() {
        val created = JSONObject("""{"code":0,"data":{"task_id":"e61ecdef","task_sync":false}}""")
        assertEquals("e61ecdef", quarkShareTaskId(created))
        assertTrue(quarkShareId(created).isEmpty())
        val task = JSONObject("""{"code":0,"data":{"share_id":"9f8e7d","status":2}}""")
        assertEquals("9f8e7d", quarkShareId(task))
        assertTrue(quarkShareTaskId(task).isEmpty())
        assertTrue(quarkShareTaskId(JSONObject("""{"code":0,"data":{}}""")).isEmpty())
    }

    // ---------- 分享响应解析 ----------

    @Test
    fun `顶层直接返回分享链接`() {
        val json = JSONObject("""{"code":0,"url":"https://pan.quark.cn/s/abcd","passcode":"x7k2"}""")
        val info = parseQuarkShare(json, "fall")
        assertEquals("https://pan.quark.cn/s/abcd", info?.link)
        assertEquals("x7k2", info?.pwd)
    }

    @Test
    fun `链接藏在 data 里也能取到`() {
        val json = JSONObject("""{"code":0,"data":{"share_url":"https://pan.quark.cn/s/xyz"}}""")
        assertEquals("https://pan.quark.cn/s/xyz", parseQuarkShare(json, "fall")?.link)
    }

    @Test
    fun `没有 URL 时用 pwd_id 拼公开分享链接`() {
        val json = JSONObject("""{"code":0,"data":{"pwd_id":"9f8e7d"}}""")
        val info = parseQuarkShare(json, "fall")
        assertEquals("https://pan.quark.cn/s/9f8e7d", info?.link)
        assertEquals("fall", info?.pwd)
    }

    @Test
    fun `响应没带提取码时回落到本地生成的`() {
        val json = JSONObject("""{"data":{"pwd_id":"abc"},"passcode":""}""")
        assertEquals("local", parseQuarkShare(json, "local")?.pwd)
    }

    @Test
    fun `既无 URL 又无分享 ID 时返回 null 不假装成功`() {
        assertNull(parseQuarkShare(JSONObject("""{"code":0,"data":{}}"""), "fall"))
    }

    // ---------- 目录阻断 / 文案 ----------

    @Test
    fun `移动阻断集合只含被选中的目录`() {
        val blocked = quarkMoveBlockedPaths(
            listOf(entry(path = "/dir/A", directory = true), entry(path = "/dir/b.txt")),
        )
        assertEquals(setOf("/dir/A"), blocked)
        assertTrue(isBlockedFolder("/dir/A/sub", blocked))
        assertTrue(!isBlockedFolder("/dir", blocked))
    }

    @Test
    fun `分享标题与数量文案`() {
        assertEquals("a.txt", quarkShareTitle(listOf(entry(name = "a.txt"))))
        assertEquals("a.txt 等 3 项", quarkShareTitle(List(3) { entry(name = "a.txt") }))
        assertEquals("拾课分享", quarkShareTitle(emptyList()))
    }

    @Test
    fun `删除确认与成功文案`() {
        assertTrue(quarkDeleteConfirmMessage("「a.txt」").contains("a.txt"))
        assertEquals("已删除 3 项", quarkDeleteSuccess(3))
        assertEquals("已移动 2 项到「根目录」", quarkMoveSuccess(2, "根目录"))
    }

    @Test
    fun `错误文案按异常类型分流`() {
        assertEquals("夸克网盘登录状态已失效，请重新登录", quarkWriteErrorMessage(QuarkLoginRequired()))
        assertEquals("网盘仍在处理，请稍后刷新确认结果", quarkWriteErrorMessage(QuarkMutationPending()))
        assertEquals("夸克请求失败（HTTP 403）", quarkWriteErrorMessage(IllegalStateException("夸克请求失败（HTTP 403）")))
        assertEquals("连接或响应异常，请重试", quarkWriteErrorMessage(RuntimeException("boom")))
    }
}
