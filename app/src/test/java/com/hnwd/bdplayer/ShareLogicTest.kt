package com.hnwd.bdplayer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ShareLogicTest {

    private val known = mapOf("mp4" to "video/mp4", "pdf" to "application/pdf")

    private fun typeOf(mediaType: String, name: String) =
        ShareLogic.typeOf(mediaType, name) { known[it] }

    @Test
    fun canShare_requiresSuccessAndResolvableFile() {
        assertTrue(ShareLogic.canShare(DownloadState.SUCCESS, "content://media/external/downloads/7", -1))
        assertTrue(ShareLogic.canShare(DownloadState.SUCCESS, "", 42))
        // 成功记录也可能两头都空（暂存被清过、downloadId 已归零），此时没有可分享的文件。
        assertFalse(ShareLogic.canShare(DownloadState.SUCCESS, " ", -1))
        assertFalse(ShareLogic.canShare(DownloadState.RUNNING, "content://media/external/downloads/7", 42))
        assertFalse(ShareLogic.canShare(DownloadState.QUEUED, "content://media/external/downloads/7", 42))
        assertFalse(ShareLogic.canShare(DownloadState.FAILED, "content://media/external/downloads/7", 42))
    }

    @Test
    fun typeOf_keepsConcreteDeclaredType() {
        assertEquals("video/mp4", typeOf("video/mp4", "第 3 课.mp4"))
        assertEquals("application/pdf", typeOf("application/pdf", "手册.pdf"))
        // 前后空白不该把具体类型降级成兜底。
        assertEquals("video/mp4", typeOf(" video/mp4 ", "第 3 课.mp4"))
    }

    @Test
    fun typeOf_fallsBackToExtension() {
        assertEquals("video/mp4", typeOf(ShareLogic.DEFAULT_TYPE, "第 3 课.MP4"))
        assertEquals("application/pdf", typeOf("", "资料.PDF"))
        // 通配类型部分接收方会拒绝，按扩展名收敛成具体类型。
        assertEquals("video/mp4", typeOf("video/*", "第 3 课.mp4"))
    }

    @Test
    fun typeOf_degradesToOctetStreamWhenUnknown() {
        assertEquals(ShareLogic.DEFAULT_TYPE, typeOf(ShareLogic.DEFAULT_TYPE, "没有扩展名"))
        assertEquals(ShareLogic.DEFAULT_TYPE, typeOf("video/*", "包.xyz"))
        // 扩展名表返回空串同样视为未知。
        assertEquals(ShareLogic.DEFAULT_TYPE, ShareLogic.typeOf("", "包.zip") { "" })
        assertEquals(ShareLogic.DEFAULT_TYPE, ShareLogic.typeOf("", "包.zip") { null })
    }

    @Test
    fun unavailableMessage_distinguishesPublishingFromMissingFile() {
        assertEquals(
            "文件正在保存到下载目录，稍后再试",
            ShareLogic.unavailableMessage(DownloadState.SUCCESS, publishing = true),
        )
        assertEquals(
            "找不到文件，可能已被清理，请重新下载",
            ShareLogic.unavailableMessage(DownloadState.SUCCESS, publishing = false),
        )
        assertEquals("下载失败的记录不能分享", ShareLogic.unavailableMessage(DownloadState.FAILED, false))
        assertEquals("下载完成后才能分享", ShareLogic.unavailableMessage(DownloadState.RUNNING, false))
    }
}
