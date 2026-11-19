package com.hnwd.bdplayer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TransferFolderLogicTest {

    @Test
    fun `读目录期间所有可写操作都禁用`() {
        val ui = transferFolderUi(loading = true, current = "/工具", page = 1, hasMore = true, empty = false)
        assertFalse(ui.upEnabled)
        assertFalse(ui.saveEnabled)
        assertFalse(ui.createEnabled)
        assertFalse(ui.moreEnabled)
        assertFalse(ui.listEnabled)
        assertTrue(ui.moreVisible)
        assertEquals("正在读取目录…", ui.moreText)
    }

    @Test
    fun `根目录不能上一级 首次加载失败只给重试入口`() {
        val ui = transferFolderUi(loading = false, current = "/", page = 0, hasMore = false, empty = false)
        assertFalse(ui.upEnabled)
        assertFalse(ui.saveEnabled)
        assertFalse(ui.createEnabled)
        assertTrue(ui.moreVisible)
        assertEquals("重试读取目录", ui.moreText)
        assertTrue(ui.moreEnabled)
    }

    @Test
    fun `加载成功后可保存可新建 有更多时给加载更多`() {
        val ui = transferFolderUi(loading = false, current = "/工具", page = 1, hasMore = true, empty = false)
        assertTrue(ui.upEnabled)
        assertTrue(ui.saveEnabled)
        assertTrue(ui.createEnabled)
        assertTrue(ui.moreVisible)
        assertEquals("加载更多目录", ui.moreText)
        assertTrue(ui.moreEnabled)
    }

    @Test
    fun `空目录把更多行换成提示且不可点`() {
        val ui = transferFolderUi(loading = false, current = "/工具", page = 1, hasMore = false, empty = true)
        assertTrue(ui.moreVisible)
        assertEquals("（暂无子目录，可直接保存到此或新建文件夹）", ui.moreText)
        assertFalse(ui.moreEnabled)
        assertTrue(ui.saveEnabled)
    }

    @Test
    fun `已加载且无更多时隐藏更多行`() {
        val ui = transferFolderUi(loading = false, current = "/工具", page = 1, hasMore = false, empty = false)
        assertFalse(ui.moreVisible)
    }

    @Test
    fun `新建文件夹校验复用网盘名称规则`() {
        assertEquals("正在读取目录，请稍候", transferFolderCreateError("课程资料", true))
        assertEquals("请输入名称", transferFolderCreateError("   ", false))
        assertEquals("名称包含网盘不支持的字符", transferFolderCreateError("a/b", false))
        assertEquals("名称包含网盘不支持的字符", transferFolderCreateError("a:b", false))
        assertNull(transferFolderCreateError("  课程资料  ", false))
    }

    @Test
    fun `修改时间格式化为 年-月-日 时-分 缺失时为空`() {
        assertEquals("", folderPickerTime(0))
        assertEquals("", folderPickerTime(-1))
        assertTrue(Regex("\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}").matches(folderPickerTime(1_700_000_000L)))
    }
}
