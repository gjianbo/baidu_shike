package com.hnwd.bdplayer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DownloadFileLogicTest {

    @Test
    fun actions_successHasSingleDeleteEntry() {
        assertEquals(
            listOf(
                MoreAction.SHARE, MoreAction.RENAME, MoreAction.COPY_NAME, MoreAction.COPY_PATH,
                MoreAction.INFO, MoreAction.REVEAL, MoreAction.DELETE,
            ),
            DownloadFileLogic.actions(DownloadState.SUCCESS),
        )
    }

    @Test
    fun actions_failedOnlyAllowsRemovingRecord() {
        assertEquals(listOf(MoreAction.DELETE), DownloadFileLogic.actions(DownloadState.FAILED))
    }

    @Test
    fun actions_activeStatesHaveNoLongPressMenu() {
        assertTrue(DownloadFileLogic.actions(DownloadState.RUNNING).isEmpty())
        assertTrue(DownloadFileLogic.actions(DownloadState.QUEUED).isEmpty())
    }

    @Test
    fun labels_areStable() {
        assertEquals("分享", DownloadFileLogic.label(MoreAction.SHARE, DownloadState.SUCCESS))
        assertEquals("重命名", DownloadFileLogic.label(MoreAction.RENAME, DownloadState.SUCCESS))
        assertEquals("复制文件名", DownloadFileLogic.label(MoreAction.COPY_NAME, DownloadState.SUCCESS))
        assertEquals("复制路径", DownloadFileLogic.label(MoreAction.COPY_PATH, DownloadState.SUCCESS))
        assertEquals("文件信息", DownloadFileLogic.label(MoreAction.INFO, DownloadState.SUCCESS))
        assertEquals("打开所在文件夹", DownloadFileLogic.label(MoreAction.REVEAL, DownloadState.SUCCESS))
        assertEquals("删除", DownloadFileLogic.label(MoreAction.DELETE, DownloadState.SUCCESS))
        assertEquals("移除记录", DownloadFileLogic.label(MoreAction.DELETE, DownloadState.FAILED))
    }

    @Test
    fun locationOf_matchesInfoPanelPath() {
        assertEquals(
            "Download/${DownloadLogic.FOLDER}/第 3 课.mp4",
            DownloadFileLogic.locationOf("第 3 课.mp4"),
        )
        assertTrue(DownloadFileLogic.copiedPathMessage(DownloadFileLogic.locationOf("a.pdf")).contains("a.pdf"))
    }

    @Test
    fun selectionHint_countsSelection() {
        assertEquals(DownloadFileLogic.SELECTION_EMPTY_HINT, DownloadFileLogic.selectionHint(0))
        assertEquals("已选 2 项", DownloadFileLogic.selectionHint(2))
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
    fun deleteTexts_explainWhatIsAffected() {
        assertEquals("删除记录", DownloadFileLogic.deleteTitle(DownloadState.SUCCESS))
        assertEquals("移除记录", DownloadFileLogic.deleteTitle(DownloadState.FAILED))
        val success = DownloadFileLogic.deleteMessage(DownloadState.SUCCESS, "第 3 课.mp4")
        assertTrue(success.contains("第 3 课.mp4"))
        assertTrue(success.contains("文件仍留在"))
        assertTrue(success.contains("勾选"))
        // 失败记录没有文件，文案不能提「删除文件」。
        val failed = DownloadFileLogic.deleteMessage(DownloadState.FAILED, "第 3 课.mp4")
        assertTrue(failed.contains("不涉及本地文件"))
        assertTrue(DownloadFileLogic.DELETE_FILE_OPTION.contains("无法恢复"))
    }

    @Test
    fun batchTexts_carryCount() {
        assertEquals("删除 5 项记录", DownloadFileLogic.batchDeleteTitle(5))
        assertTrue(DownloadFileLogic.batchDeleteMessage(5).contains("5 条"))
        assertTrue(DownloadFileLogic.batchDeleteMessage(5).contains("勾选"))
    }
}
