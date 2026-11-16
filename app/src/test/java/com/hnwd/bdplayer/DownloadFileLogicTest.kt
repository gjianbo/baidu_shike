package com.hnwd.bdplayer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DownloadFileLogicTest {

    @Test
    fun actions_successOffersAllFileOperations() {
        assertEquals(
            listOf(
                MoreAction.SHARE, MoreAction.RENAME, MoreAction.INFO,
                MoreAction.REMOVE_RECORD, MoreAction.DELETE_FILE,
            ),
            DownloadFileLogic.actions(DownloadState.SUCCESS),
        )
    }

    @Test
    fun actions_failedOnlyAllowsRemovingRecord() {
        assertEquals(listOf(MoreAction.REMOVE_RECORD), DownloadFileLogic.actions(DownloadState.FAILED))
    }

    @Test
    fun actions_activeStatesHaveNoLongPressMenu() {
        assertTrue(DownloadFileLogic.actions(DownloadState.RUNNING).isEmpty())
        assertTrue(DownloadFileLogic.actions(DownloadState.QUEUED).isEmpty())
    }

    @Test
    fun labels_areStable() {
        assertEquals("分享", DownloadFileLogic.label(MoreAction.SHARE))
        assertEquals("重命名", DownloadFileLogic.label(MoreAction.RENAME))
        assertEquals("文件信息", DownloadFileLogic.label(MoreAction.INFO))
        assertEquals("移除记录", DownloadFileLogic.label(MoreAction.REMOVE_RECORD))
        assertEquals("删除文件", DownloadFileLogic.label(MoreAction.DELETE_FILE))
    }

    @Test
    fun nameError_rejectsEmptyAndReservedInput() {
        assertEquals("请输入文件名", DownloadFileLogic.nameError("", "课程.mp4"))
        assertEquals("请输入文件名", DownloadFileLogic.nameError("   ", "课程.mp4"))
        assertEquals("文件名不能是 . 或 ..", DownloadFileLogic.nameError(".", "课程.mp4"))
        assertEquals("文件名不能是 . 或 ..", DownloadFileLogic.nameError("..", "课程.mp4"))
        assertEquals("文件名包含不支持的字符", DownloadFileLogic.nameError("a/b.mp4", "课程.mp4"))
        assertEquals("文件名包含不支持的字符", DownloadFileLogic.nameError("a:b.mp4", "课程.mp4"))
        assertEquals("文件名太长", DownloadFileLogic.nameError("a".repeat(121) + ".mp4", "课程.mp4"))
    }

    @Test
    fun nameError_keepsExtensionOfOriginal() {
        assertNull(DownloadFileLogic.nameError("第 3 课.mp4", "第 3 课.mp4"))
        assertNull(DownloadFileLogic.nameError("第 3 课.MP4", "第 3 课.mp4"))
        assertEquals("请保留扩展名 .mp4", DownloadFileLogic.nameError("第 3 课", "第 3 课.mp4"))
        assertEquals("请保留扩展名 .mp4", DownloadFileLogic.nameError("第 3 课.mkv", "第 3 课.mp4"))
        // 原名本来就没有扩展名时不限制后缀。
        assertNull(DownloadFileLogic.nameError("任意名字.txt", "README"))
    }

    @Test
    fun extensionOf_isLowercased() {
        assertEquals("mp4", DownloadFileLogic.extensionOf("第 3 课.MP4"))
        assertEquals("pdf", DownloadFileLogic.extensionOf("手册.Pdf"))
        assertEquals("", DownloadFileLogic.extensionOf("没有扩展名"))
    }

    @Test
    fun infoText_listsEveryFieldOnItsOwnLine() {
        val text = DownloadFileLogic.infoText(
            name = "第 3 课.mp4",
            size = "12.3 MB",
            location = "Download/拾课/第 3 课.mp4",
            finished = "11-16 09:30",
            source = "我的网盘",
            mime = "video/mp4",
        )
        assertEquals(
            "名称：第 3 课.mp4\n大小：12.3 MB\n位置：Download/拾课/第 3 课.mp4\n" +
                "完成：11-16 09:30\n来源：我的网盘\n类型：video/mp4",
            text,
        )
    }

    @Test
    fun confirms_explainWhatIsAffected() {
        assertTrue(DownloadFileLogic.REMOVE_RECORD_CONFIRM.contains("文件会保留"))
        assertTrue(DownloadFileLogic.deleteFileConfirm("第 3 课.mp4").contains("第 3 课.mp4"))
        assertTrue(DownloadFileLogic.deleteFileConfirm("第 3 课.mp4").contains("无法恢复"))
    }
}
