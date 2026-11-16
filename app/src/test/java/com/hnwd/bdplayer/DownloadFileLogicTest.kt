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
                MoreAction.SHARE, MoreAction.RENAME, MoreAction.COPY_PATH,
                MoreAction.INFO, MoreAction.REVEAL, MoreAction.DELETE,
            ),
            DownloadFileLogic.actions(DownloadState.SUCCESS),
        )
    }

    @Test
    fun actions_successFitsInTwoRows() {
        val rows = actionGridRows(DownloadFileLogic.actions(DownloadState.SUCCESS))
        assertEquals(2, rows.size)
        assertEquals(4, rows[0].size)
        assertTrue(rows[1].size <= 4)
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
        assertEquals("复制路径", DownloadFileLogic.label(MoreAction.COPY_PATH, DownloadState.SUCCESS))
        assertEquals("文件详情", DownloadFileLogic.label(MoreAction.INFO, DownloadState.SUCCESS))
        assertEquals("打开位置", DownloadFileLogic.label(MoreAction.REVEAL, DownloadState.SUCCESS))
        assertEquals("删除", DownloadFileLogic.label(MoreAction.DELETE, DownloadState.SUCCESS))
        assertEquals("移除记录", DownloadFileLogic.label(MoreAction.DELETE, DownloadState.FAILED))
    }

    @Test
    fun labels_fitTheGridCell() {
        // 面板是 4 列，单格只放得下 5 个中文字，标签超长会被截断。
        MoreAction.entries.forEach { action ->
            listOf(DownloadState.SUCCESS, DownloadState.FAILED).forEach { state ->
                val label = DownloadFileLogic.label(action, state)
                assertTrue("$label 太长", label.length <= 5)
            }
        }
    }

    @Test
    fun gridRows_splitsIntoFourColumnsAndKeepsOrder() {
        val actions = DownloadFileLogic.actions(DownloadState.SUCCESS)
        val rows = actionGridRows(actions)
        assertEquals(2, rows.size)
        assertEquals(listOf(4, 2), rows.map { it.size })
        assertEquals(actions, rows.flatten())
    }

    @Test
    fun gridRows_singleActionStaysOnOneRow() {
        assertEquals(
            listOf(listOf(MoreAction.DELETE)),
            actionGridRows(DownloadFileLogic.actions(DownloadState.FAILED)),
        )
    }

    @Test
    fun gridRows_nineActionsUseFiveColumnsAndStayInTwoRows() {
        val actions = (1..9).toList()
        assertEquals(listOf(actions.take(5), actions.drop(5)), actionGridRows(actions))
    }

    @Test
    fun gridRows_fiveActionsFitOnOneRow() {
        val actions = (1..5).toList()
        assertEquals(listOf(actions), actionGridRows(actions))
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

    @Test
    fun deleteResultMessage_neverClaimsFileDeletedWhenItWasNot() {
        // 失败记录没有文件，不能说「文件仍在下载目录」。
        assertEquals(
            "已移除这条失败记录",
            DownloadFileLogic.deleteResultMessage(DownloadState.FAILED, withFile = false, fileDeleted = false),
        )
        // 勾了删文件、也确实删到了。
        assertEquals(
            "已删除文件和记录",
            DownloadFileLogic.deleteResultMessage(DownloadState.SUCCESS, withFile = true, fileDeleted = true),
        )
        // 勾了删文件但定位不到文件：不能谎报「已删除文件和记录」。
        assertEquals(
            "文件已不在，记录已移除",
            DownloadFileLogic.deleteResultMessage(DownloadState.SUCCESS, withFile = true, fileDeleted = false),
        )
        // 没勾删文件：文件确实还在。
        assertEquals(
            "已移除记录，文件仍在下载目录",
            DownloadFileLogic.deleteResultMessage(DownloadState.SUCCESS, withFile = false, fileDeleted = false),
        )
    }

    @Test
    fun batchDeleteResultMessage_reportsFailedAndMissingSeparately() {
        assertEquals(
            "有 2 个文件删除失败，这些记录已保留",
            DownloadFileLogic.batchDeleteResultMessage(5, withFile = true, failed = 2, missingFiles = 1),
        )
        assertEquals(
            "已移除 5 条记录，文件仍在下载目录",
            DownloadFileLogic.batchDeleteResultMessage(5, withFile = false, failed = 0, missingFiles = 0),
        )
        assertEquals(
            "已删除 3 个文件和记录，另有 2 个文件已不在",
            DownloadFileLogic.batchDeleteResultMessage(5, withFile = true, failed = 0, missingFiles = 2),
        )
        assertEquals(
            "已删除 5 个文件和记录",
            DownloadFileLogic.batchDeleteResultMessage(5, withFile = true, failed = 0, missingFiles = 0),
        )
    }

    @Test
    fun batchDeleteConfirmation_limitsFileDeletionToCompletedDownloads() {
        assertEquals("选中的 2 条失败记录会从列表移除。", DownloadFileLogic.batchDeleteMessage(2, failedRecords = 2))
        val mixed = DownloadFileLogic.batchDeleteMessage(3, failedRecords = 2)
        assertTrue(mixed.contains("其中 2 条传输失败"))
        assertTrue(mixed.contains("只会删除已完成下载对应的本地文件"))
    }

    @Test
    fun multiShareMessage_surfacesSkippedCount() {
        assertEquals("已分享 3 个文件", DownloadFileLogic.multiShareMessage(3, 0))
        assertEquals("已分享 2 个文件，1 个文件已不在", DownloadFileLogic.multiShareMessage(2, 1))
    }
}
