package com.hnwd.bdplayer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 群文件转存纯逻辑：消息分组、参数契约、过滤与文案。 */
class GroupTransferLogicTest {

    private fun entry(
        gid: String = "baidu:461057192968828484",
        msgId: String = "6217907761116094706",
        owner: String = "29515193",
        fileId: String = "59479004746095",
        directory: Boolean = false,
        isDisk: Boolean = false,
    ) = PanEntry(
        gid = gid,
        messageId = msgId,
        owner = owner,
        fileId = fileId,
        name = "课程.mp4",
        path = "/课程.mp4",
        size = "1024",
        directory = directory,
        origin = if (isDisk) EntryOrigin.DISK else EntryOrigin.GROUP,
    )

    // ---------- 分组 ----------

    @Test
    fun `同一条消息的多个文件合并成一批`() {
        val batches = groupTransferBatches(
            listOf(entry(fileId = "1"), entry(fileId = "2"), entry(fileId = "3")),
        )
        assertEquals(1, batches.size)
        assertEquals(listOf("1", "2", "3"), batches[0].fsIds)
        assertEquals("461057192968828484", batches[0].gid) // 前缀已剥
        assertEquals("6217907761116094706", batches[0].msgId)
        assertEquals("29515193", batches[0].fromUk)
    }

    @Test
    fun `跨消息与跨群组必须拆批`() {
        val batches = groupTransferBatches(
            listOf(
                entry(msgId = "m1", fileId = "1"),
                entry(msgId = "m2", fileId = "2"),
                entry(gid = "baidu:other", msgId = "m1", fileId = "3"),
                entry(owner = "other-uk", fileId = "4"),
            ),
        )
        assertEquals(4, batches.size)
        assertTrue(batches.all { it.fsIds.size == 1 })
    }

    @Test
    fun `同一 fs_id 不重复提交`() {
        val batches = groupTransferBatches(listOf(entry(fileId = "1"), entry(fileId = "1")))
        assertEquals(listOf("1"), batches.single().fsIds)
    }

    @Test
    fun `过滤掉网盘文件 夸克条目 联系人条目与缺字段的条目`() {
        val batches = groupTransferBatches(
            listOf(
                entry(fileId = "ok"),
                entry(fileId = "disk", isDisk = true),
                entry(gid = "quark:abc", fileId = "quark"),
                entry(gid = "baidu:contact-123", fileId = "friend"),
                entry(fileId = ""),
                entry(msgId = ""),
                entry(owner = ""),
            ),
        )
        assertEquals(1, batches.size)
        assertEquals(listOf("ok"), batches.single().fsIds)
    }

    @Test
    fun `文件夹条目同样可转存`() {
        assertTrue(groupTransferEligible(entry(directory = true)))
        val batches = groupTransferBatches(listOf(entry(directory = true, fileId = "dir-1")))
        assertEquals(listOf("dir-1"), batches.single().fsIds)
        assertTrue(groupTransferContainsDirectory(listOf(entry(directory = true))))
    }

    @Test
    fun `我的网盘关注目录来源不参与群转存`() {
        val folder = entry(gid = "dir-%2F%E6%88%91%E7%9A%84%E8%B5%84%E6%BA%90")
        assertFalse(groupTransferEligible(folder))
        assertTrue(groupTransferBatches(listOf(folder)).isEmpty())
    }

    @Test
    fun `联系人来源整批不可转存 不会误用 type=2`() {
        assertTrue(groupTransferBatches(listOf(entry(gid = "baidu:contact-123"))).isEmpty())
        assertFalse(groupTransferEligible(entry(gid = "baidu:contact-123")))
        assertTrue(groupTransferEligible(entry()))
    }

    @Test
    fun `容量不足 errno -10 有可读文案`() {
        val message = PanApi.writeError("转存", -10, org.json.JSONObject("""{"errno":-10}"""))
        assertTrue(message.contains("网盘空间不足或转存额度已用尽"))
        assertTrue(message.contains("错误码 -10"))
    }

    // ---------- 请求体契约 ----------

    @Test
    fun `请求体字段与抓包契约一致`() {
        val form = groupTransferForm(
            GroupTransferBatch("461057192968828484", "6217907761116094706", "29515193", listOf("59479004746095", "29253795039390")),
            "/我的课程",
        )
        val params = form.split("&").associate {
            val (k, v) = it.split("=", limit = 2)
            k to java.net.URLDecoder.decode(v, "UTF-8")
        }
        assertEquals("29515193", params["from_uk"])
        assertEquals("6217907761116094706", params["msg_id"])
        assertEquals("/我的课程", params["path"])
        assertEquals("newcopy", params["ondup"])
        assertEquals("1", params["async"])
        assertEquals("2", params["type"])
        assertEquals("461057192968828484", params["gid"])
        assertEquals("[59479004746095,29253795039390]", params["fs_ids"])
    }

    @Test
    fun `非数字 fs_id 退回字符串`() {
        val form = groupTransferForm(
            GroupTransferBatch("g", "m", "u", listOf("abc")), "/",
        )
        assertTrue(form.contains("fs_ids=%5B%22abc%22%5D"))
    }

    // ---------- 提示文案 ----------

    @Test
    fun `目录检测与结果文案`() {
        assertFalse(groupTransferContainsDirectory(listOf(entry())))
        assertTrue(groupTransferContainsDirectory(listOf(entry(), entry(directory = true))))
        assertEquals("转存成功：已提交 6 项（2 批）到 /工具", groupTransferResultText(6, 2, "/工具"))
        assertEquals(4, GROUP_TRANSFER_LENIENT_ERRNO)
    }
}
