package com.hnwd.bdplayer

import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 网盘写操作纯逻辑的 JVM 单测。
 *
 * 两类不变式是重点：① 宿主 getter 的短路顺序与调用次数（切号/切页后不能读旧状态）；
 * ② 删除两步确认的翻页边界（漏翻一页就可能误判「已删除」）。
 */
class DiskMutationLogicTest {

    private fun entry(
        name: String = "a.txt",
        path: String = "/dir/$name",
        isDisk: Boolean = true,
        fileId: String = "f1",
    ) = PanEntry(
        gid = "1001", messageId = "m1", owner = "1001",
        fileId = fileId, name = name, path = path, size = "1",
        directory = false, origin = if (isDisk) EntryOrigin.DISK else EntryOrigin.GROUP,
    )

    // ---------- 删除守卫 ----------

    @Test
    fun `会话变更后不再读取任何宿主状态`() {
        var calls = 0
        val guard = deleteGuard(
            1,
            currentSession = { 2 },
            deleteInFlight = { calls++; false },
            onDiskScreen = { calls++; true },
            entries = listOf(entry()),
            diskDir = { calls++; "/dir" },
        )
        assertEquals(DeleteGuard.STALE_SESSION, guard)
        assertEquals(0, calls)
        assertEquals("网盘会话已变更，请重试", deleteGuardMessage(guard))
    }

    @Test
    fun `上一笔删除核对未结束时挡住新删除`() {
        var calls = 0
        val guard = deleteGuard(
            1,
            currentSession = { 1 },
            deleteInFlight = { true },
            onDiskScreen = { calls++; true },
            entries = listOf(entry()),
            diskDir = { calls++; "/dir" },
        )
        assertEquals(DeleteGuard.DELETE_IN_FLIGHT, guard)
        assertEquals(0, calls)
        assertEquals("正在确认上一笔删除，请稍候", deleteGuardMessage(guard))
    }

    @Test
    fun `不在网盘页时不会去读当前目录`() {
        var calls = 0
        val guard = deleteGuard(
            1,
            currentSession = { 1 },
            deleteInFlight = { false },
            onDiskScreen = { false },
            entries = listOf(entry()),
            diskDir = { calls++; "/dir" },
        )
        assertEquals(DeleteGuard.REFRESH_REQUIRED, guard)
        assertEquals(0, calls)
        assertEquals("请先刷新当前目录后重试", deleteGuardMessage(guard))
    }

    @Test
    fun `全部条目都是当前目录的网盘文件时允许删除`() {
        val guard = deleteGuard(
            1,
            currentSession = { 1 },
            deleteInFlight = { false },
            onDiskScreen = { true },
            entries = listOf(entry("a.txt"), entry("b.txt", fileId = "f2")),
            diskDir = { "/dir/" },
        )
        // parentPath("/dir/a.txt") == "/dir"，而当前目录带尾斜杠 -> 必须判为需刷新
        assertEquals(DeleteGuard.REFRESH_REQUIRED, guard)
        val ready = deleteGuard(
            1, { 1 }, { false }, { true },
            listOf(entry("a.txt"), entry("b.txt", fileId = "f2")),
        ) { "/dir" }
        assertEquals(DeleteGuard.READY, ready)
        assertEquals("", deleteGuardMessage(ready))
    }

    @Test
    fun `群组条目或父目录不匹配的条目一律拦下`() {
        val notDisk = deleteGuard(1, { 1 }, { false }, { true }, listOf(entry(isDisk = false))) { "/dir" }
        assertEquals(DeleteGuard.REFRESH_REQUIRED, notDisk)
        val otherDir = deleteGuard(1, { 1 }, { false }, { true }, listOf(entry(path = "/other/a.txt"))) { "/dir" }
        assertEquals(DeleteGuard.REFRESH_REQUIRED, otherDir)
        val mixed = deleteGuard(
            1, { 1 }, { false }, { true },
            listOf(entry("a.txt"), entry("b.txt", path = "/other/b.txt", fileId = "f2")),
        ) { "/dir" }
        assertEquals(DeleteGuard.REFRESH_REQUIRED, mixed)
    }

    @Test
    fun `遇到非盘条目立即停止遍历，后续不再读目录`() {
        var calls = 0
        deleteGuard(
            1, { 1 }, { false }, { true },
            // 首个条目匹配 -> 读一次目录；第二个非盘条目让 all 短路，后面不再遍历。
            listOf(entry("a.txt"), entry(isDisk = false, fileId = "f2"), entry("c.txt", fileId = "f3")),
        ) { calls++; "/dir" }
        assertEquals(1, calls)
    }

    @Test
    fun `删除标签与二次确认文案`() {
        assertEquals("「a.txt」", deleteLabel(listOf(entry())))
        assertEquals("3 项", deleteLabel(List(3) { entry() }))
        assertEquals(
            "确定删除 「a.txt」 吗？删除后可在网盘回收站找回。",
            deleteConfirmMessage(deleteLabel(listOf(entry()))),
        )
    }

    // ---------- 删除前核对 ----------

    @Test
    fun `剩余项为空时直接通过且不读 JSON 的 list`() {
        var reads = 0
        val step = deleteCheckStep(0, 3, 1) { reads++; true }
        assertEquals(DeleteCheckStep.VERIFIED, step)
        assertEquals(0, reads)
    }

    @Test
    fun `空页或超过页上限时判为核对失败`() {
        var reads = 0
        assertEquals(DeleteCheckStep.FAILED, deleteCheckStep(2, 0, 1) { reads++; true })
        assertEquals(DeleteCheckStep.FAILED, deleteCheckStep(2, 100, MAX_DELETE_SCAN_PAGES) { reads++; true })
        assertEquals(0, reads)
    }

    @Test
    fun `还有剩余且确实有下一页时继续翻页`() {
        assertEquals(DeleteCheckStep.NEXT_PAGE, deleteCheckStep(2, 100, 1) { true })
        assertEquals(DeleteCheckStep.FAILED, deleteCheckStep(2, 10, 1) { false })
    }

    // ---------- 删除后回读 ----------

    @Test
    fun `页面里仍出现目标文件则重试且不记录去重集合`() {
        var reads = 0
        val step = reconcileStep(listOf("f1", "f9"), setOf("f1"), false, { reads++; true }, 1)
        assertEquals(ReconcileStep.RETRY, step)
        assertEquals(0, reads)
    }

    @Test
    fun `同一页重复出现时判定失败而不是无限翻页`() {
        val step = reconcileStep(listOf("f9"), setOf("f1"), true, { true }, 1)
        assertEquals(ReconcileStep.FAILED, step)
    }

    @Test
    fun `读完最后一页且目标文件消失才算确认删除`() {
        assertEquals(
            ReconcileStep.CONFIRMED,
            reconcileStep(listOf("f9"), setOf("f1"), false, { false }, 1),
        )
    }

    @Test
    fun `回读时空页或超页上限判为未确认`() {
        assertEquals(
            ReconcileStep.FAILED,
            reconcileStep(emptyList(), setOf("f1"), false, { true }, 1),
        )
        assertEquals(
            ReconcileStep.FAILED,
            reconcileStep(listOf("f9"), setOf("f1"), false, { true }, MAX_DELETE_SCAN_PAGES),
        )
    }

    @Test
    fun `回读还有下一页时继续翻`() {
        assertEquals(
            ReconcileStep.NEXT_PAGE,
            reconcileStep(listOf("f9"), setOf("f1"), false, { true }, 1),
        )
    }

    @Test
    fun `回读重试上限与结果文案`() {
        assertTrue(shouldRetryReconcile(MAX_RECONCILE_ATTEMPTS - 1))
        assertFalse(shouldRetryReconcile(MAX_RECONCILE_ATTEMPTS))
        assertEquals("已删除 3 项", reconcileMessage(true, 3))
        assertEquals("删除尚未确认，请稍后刷新查看", reconcileMessage(false, 3))
    }

    // ---------- 写请求响应 ----------

    @Test
    fun `errno 132 引导安全验证且不读外层 errno`() {
        var reads = 0
        assertEquals(WriteResponse.SECURITY_VERIFY, writeResponse(132) { reads++; 0 })
        assertEquals(0, reads)
    }

    @Test
    fun `外层成功但单项失败算部分成功`() {
        assertEquals(WriteResponse.PARTIAL_SUCCESS, writeResponse(12) { 0 })
    }

    @Test
    fun `外层也失败算整笔失败`() {
        assertEquals(WriteResponse.FAILED, writeResponse(12) { 12 })
    }

    @Test
    fun `errno 为 0 才算成功且不读外层 errno`() {
        var reads = 0
        assertEquals(WriteResponse.SUCCESS, writeResponse(0) { reads++; -1 })
        assertEquals(0, reads)
    }

    @Test
    fun `写操作相关文案`() {
        assertEquals("删除结果未确认，请刷新查看", diskWritePendingText("删除"))
        assertEquals(
            "部分项目可能已处理，请查看刷新后的列表。错误",
            partialWriteMessage("错误"),
        )
        assertEquals("百度网盘要求安全验证，「删除」未执行", securityVerifyStatus("删除"))
    }

    // ---------- 名称校验 ----------

    @Test
    fun `空名称与点目录名被拒`() {
        assertEquals("请输入名称", diskNameError(""))
        assertEquals("名称包含网盘不支持的字符", diskNameError("."))
        assertEquals("名称包含网盘不支持的字符", diskNameError(".."))
    }

    @Test
    fun `网盘不支持的字符逐个被拒`() {
        for (ch in "/\\:*?\"<>|") {
            assertEquals("字符 $ch 应被拒", "名称包含网盘不支持的字符", diskNameError("a${ch}b"))
        }
        assertEquals("名称包含网盘不支持的字符", diskNameError("a\u0001b"))
    }

    @Test
    fun `中文空格与常见后缀是合法名称`() {
        assertEquals(null, diskNameError("新建 文件夹"))
        assertEquals(null, diskNameError("第2讲.mp4"))
        assertEquals(null, diskNameError(".隐藏"))
    }

    // ---------- 目录选择器 ----------

    @Test
    fun `被操作目录自身与所有子目录都不可选`() {
        val blocked = setOf("/课程")
        assertTrue(isBlockedFolder("/课程", blocked))
        assertTrue(isBlockedFolder("/课程/", blocked))
        assertTrue(isBlockedFolder("/课程/第1讲", blocked))
        assertTrue(isBlockedFolder("/课程/第1讲/子目录", blocked))
    }

    @Test
    fun `相似前缀与根目录不会被误屏蔽`() {
        val blocked = setOf("/课程")
        assertFalse(isBlockedFolder("/课程资料", blocked))
        assertFalse(isBlockedFolder("/其他", blocked))
        assertFalse(isBlockedFolder("/a", setOf("/")))
        assertFalse(isBlockedFolder("/a", emptySet()))
        assertTrue(isBlockedFolder("/a", setOf("/a/")))
    }

    @Test
    fun `加载中禁用全部按钮且保留加载更多入口`() {
        val ui = folderPickerUi(loading = true, current = "/a", page = 1, hasMore = true)
        assertFalse(ui.upEnabled)
        assertFalse(ui.pickEnabled)
        assertTrue(ui.moreVisible)
        assertEquals("加载更多目录", ui.moreText)
        assertFalse(ui.moreEnabled)
        assertFalse(ui.listEnabled)
    }

    @Test
    fun `根目录不能上一级且首次加载失败时显示重试`() {
        val ui = folderPickerUi(loading = false, current = "/", page = 0, hasMore = false)
        assertFalse(ui.upEnabled)
        assertFalse(ui.pickEnabled)
        assertTrue(ui.moreVisible)
        assertEquals("重试读取目录", ui.moreText)
        assertTrue(ui.moreEnabled)
        assertTrue(ui.listEnabled)
    }

    @Test
    fun `已加载且无更多时隐藏加载更多`() {
        val ui = folderPickerUi(loading = false, current = "/a", page = 1, hasMore = false)
        assertTrue(ui.upEnabled)
        assertTrue(ui.pickEnabled)
        assertFalse(ui.moreVisible)
    }

    @Test
    fun `空目录且没有更多时才显示占位项`() {
        assertEquals(listOf("（无可选子目录）"), folderPickerNames(emptyList(), false))
        assertEquals(emptyList<String>(), folderPickerNames(emptyList(), true))
        assertEquals(listOf("子目录"), folderPickerNames(listOf("子目录"), false))
    }

    // ---------- 分享 ----------

    @Test
    fun `随机提取码长度四位且不含易混淆字符`() {
        val pwd = randomSharePwd(Random(42))
        assertEquals(4, pwd.length)
        assertTrue(pwd.all { it in SHARE_PWD_ALPHABET })
        assertTrue(pwd.none { it in "lo01" })
        assertEquals(pwd, randomSharePwd(Random(42)))
    }

    @Test
    fun `分享有效期选项顺序与 tag`() {
        val choices = sharePeriodChoices()
        assertEquals(listOf("永久有效", "7 天有效", "1 天有效"), choices.map { it.title })
        assertEquals(listOf(0, 7, 1), choices.map { it.tag })
    }

    @Test
    fun `接口没返回提取码时用本地生成的兜底`() {
        assertEquals("ab2d", sharePwd("", "ab2d"))
        assertEquals("zzzz", sharePwd("zzzz", "ab2d"))
    }

    @Test
    fun `分享结果文案与剪贴板文案`() {
        assertEquals("链接：https://pan/a\n提取码：ab2d", shareResultText("https://pan/a", "ab2d"))
        assertEquals("链接：https://pan/a", shareResultText("https://pan/a", ""))
        assertEquals("https://pan/a 提取码 ab2d", shareClipText("https://pan/a", "ab2d"))
        assertEquals("https://pan/a", shareClipText("https://pan/a", ""))
    }
}
