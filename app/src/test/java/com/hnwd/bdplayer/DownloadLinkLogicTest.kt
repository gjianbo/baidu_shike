package com.hnwd.bdplayer

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DownloadLinkLogicTest {

    private fun linkItem(fsId: String = "fs1", dlink: String = "https://d.pcs.baidu.com/file/abc?sign=x") =
        JSONObject().put("fs_id", fsId).put("dlink", dlink)

    private fun payload(list: Any) = JSONObject().put("errno", 0).put("list", list)

    @Test
    fun encryptedList_isRecognizedAsServerRestriction() {
        // 实测：群组大视频的 sharedownload 返回 errno=0，但 list 是一段 4504 字符加密串。
        val issue = DownloadLinkLogic.groupLinkIssue(payload("x".repeat(4504)), "fs1")
        assertEquals(DownloadLinkLogic.GroupLinkIssue.ENCRYPTED_LIST, issue)
        val text = DownloadLinkLogic.failureText(issue)
        assertTrue(text.contains("转存"))
        assertTrue(text.contains("我的网盘"))
        assertFalse(text.contains("检查网络"))
    }

    @Test
    fun encryptedList_keepsTheOldNetworkHintAway() {
        // 这正是截图里「无法获取下载地址，请检查网络后重试」要被替换掉的场景。
        val text = DownloadLinkLogic.failureText(
            DownloadLinkLogic.groupLinkIssue(payload("cipher"), "fs1"),
        )
        assertFalse(text.contains("请检查网络"))
    }

    @Test
    fun listShapeVariants_areClassifiedWithoutThrowing() {
        assertEquals(
            DownloadLinkLogic.GroupLinkIssue.EMPTY_LIST,
            DownloadLinkLogic.groupLinkIssue(payload(JSONArray()), "fs1"),
        )
        assertEquals(
            DownloadLinkLogic.GroupLinkIssue.EMPTY_LIST,
            DownloadLinkLogic.groupLinkIssue(JSONObject().put("errno", 0), "fs1"),
        )
        assertEquals(
            DownloadLinkLogic.GroupLinkIssue.EMPTY_LIST,
            DownloadLinkLogic.groupLinkIssue(payload(JSONObject()), "fs1"),
        )
        assertEquals(
            DownloadLinkLogic.GroupLinkIssue.EMPTY_LIST,
            DownloadLinkLogic.groupLinkIssue(payload(""), "fs1"),
        )
    }

    @Test
    fun missingFsIdAndMissingDlink_areDistinguished() {
        val other = payload(JSONArray().put(linkItem("fs2")))
        assertEquals(
            DownloadLinkLogic.GroupLinkIssue.MISSING_FILE,
            DownloadLinkLogic.groupLinkIssue(other, "fs1"),
        )
        val blank = payload(JSONArray().put(linkItem(dlink = "")))
        assertEquals(
            DownloadLinkLogic.GroupLinkIssue.NO_DLINK,
            DownloadLinkLogic.groupLinkIssue(blank, "fs1"),
        )
        val notAFileLink = payload(JSONArray().put(linkItem(dlink = "https://example.com/x")))
        assertEquals(
            DownloadLinkLogic.GroupLinkIssue.NO_DLINK,
            DownloadLinkLogic.groupLinkIssue(notAFileLink, "fs1"),
        )
    }

    @Test
    fun ordinaryFailures_neverBlameTheNetwork() {
        listOf(
            DownloadLinkLogic.GroupLinkIssue.EMPTY_LIST,
            DownloadLinkLogic.GroupLinkIssue.MISSING_FILE,
            DownloadLinkLogic.GroupLinkIssue.NO_DLINK,
        ).forEach {
            val text = DownloadLinkLogic.failureText(it)
            assertTrue(text.contains("下载地址"))
            assertFalse(text.contains("检查网络"))
        }
    }

    @Test
    fun accountAndResponseErrors_areActionable() {
        assertTrue(DownloadLinkLogic.ACCOUNT_ERROR.contains("重新登录"))
        val text = DownloadLinkLogic.responseError(302)
        assertTrue(text.contains("302"))
        assertFalse(text.contains("检查网络"))
    }

    @Test
    fun playlistFallback_isVideoOnlyAndSkipsMismatchedRecords() {
        // 图片 / 文档 / 音频无论响应长什么样都不许降级到转码流。
        DownloadLinkLogic.GroupLinkIssue.values().forEach { issue ->
            assertFalse(DownloadLinkLogic.shouldTryPlaylist(isVideo = false, issue))
        }
        assertTrue(DownloadLinkLogic.shouldTryPlaylist(true, DownloadLinkLogic.GroupLinkIssue.ENCRYPTED_LIST))
        assertTrue(DownloadLinkLogic.shouldTryPlaylist(true, DownloadLinkLogic.GroupLinkIssue.EMPTY_LIST))
        assertTrue(DownloadLinkLogic.shouldTryPlaylist(true, DownloadLinkLogic.GroupLinkIssue.NO_DLINK))
        // fs_id 对不上是记录问题，不能拿可疑参数去拉转码流充当成功。
        assertFalse(DownloadLinkLogic.shouldTryPlaylist(true, DownloadLinkLogic.GroupLinkIssue.MISSING_FILE))
    }

    @Test
    fun prefetch_isStricterThanPlaylistFallback() {
        // 自动转存会往用户网盘写文件（还可能删副本），准入必须比 HLS 兜底更严：
        // 非视频一律不碰网盘；空 list / 无 dlink 多为临时故障，也不值得白转存一次。
        DownloadLinkLogic.GroupLinkIssue.values().forEach { issue ->
            assertFalse(DownloadLinkLogic.shouldTryPrefetch(isVideo = false, issue))
        }
        assertTrue(
            DownloadLinkLogic.shouldTryPrefetch(true, DownloadLinkLogic.GroupLinkIssue.ENCRYPTED_LIST),
        )
        listOf(
            DownloadLinkLogic.GroupLinkIssue.EMPTY_LIST,
            DownloadLinkLogic.GroupLinkIssue.MISSING_FILE,
            DownloadLinkLogic.GroupLinkIssue.NO_DLINK,
        ).forEach { assertFalse(DownloadLinkLogic.shouldTryPrefetch(true, it)) }
    }
}
