package com.hnwd.bdplayer

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DownloadLogicTest {

    private fun baiduGroup() = PanEntry(
        gid = "123456789", messageId = "msg", owner = "uk", fileId = "fs1",
        name = "第08课.mp4", path = "第08课.mp4", size = "486000000", directory = false,
    )

    private fun baiduDisk() = PanEntry(
        gid = "", messageId = "", owner = "", fileId = "fs2",
        name = "手册.pdf", path = "/课程/手册.pdf", size = "29600000", directory = false,
        origin = EntryOrigin.DISK,
    )

    private fun quarkMonitor() = PanEntry(
        gid = "quark:abcdef123456", messageId = "", owner = "", fileId = "qf1",
        name = "案例.zip", path = "案例.zip", size = "128000000", directory = false,
    )

    private fun quarkDisk() = quarkEntry(
        QuarkFile("qf2", "话术.mp3", false, 46_000_000), DISK_SOURCE_ID, EntryOrigin.DISK,
    )

    @Test
    fun routeOf_splitsFourEntryKinds() {
        assertEquals(DownloadRoute.BAIDU_GROUP, DownloadLogic.routeOf(baiduGroup()))
        assertEquals(DownloadRoute.BAIDU_DISK, DownloadLogic.routeOf(baiduDisk()))
        assertEquals(DownloadRoute.QUARK, DownloadLogic.routeOf(quarkMonitor()))
        assertEquals(DownloadRoute.QUARK, DownloadLogic.routeOf(quarkDisk()))
    }

    @Test
    fun canDownload_rejectsDirectoryAndMissingIds() {
        assertTrue(DownloadLogic.canDownload(baiduGroup()))
        assertFalse(DownloadLogic.canDownload(baiduGroup().copy(directory = true)))
        assertFalse(DownloadLogic.canDownload(baiduGroup().copy(fileId = "")))
        assertFalse(DownloadLogic.canDownload(baiduGroup().copy(name = "")))
    }

    @Test
    fun safeName_removesPathSeparatorsAndIllegalChars() {
        assertEquals("第08课_儿童营养.mp4", DownloadLogic.safeName("第08课/儿童营养.mp4"))
        assertEquals("a_b_c", DownloadLogic.safeName("a\\b:c"))
        assertEquals("下载文件", DownloadLogic.safeName("///"))
        assertEquals("课 程.mp4", DownloadLogic.safeName("  课 程.mp4  "))
    }

    @Test
    fun safeName_keepsExtensionWhenTruncated() {
        val long = "x".repeat(130) + ".pdf"
        val safe = DownloadLogic.safeName(long)
        assertTrue(safe.length <= 120)
        assertTrue(safe.endsWith(".pdf"))
    }

    @Test
    fun relativePath_usesAppSubfolder() {
        assertEquals("拾课/20261112-120000_第08课.mp4", DownloadLogic.relativePath("第08课.mp4", "20261112-120000"))
        assertEquals("拾课/stamp_a_b.mp4", DownloadLogic.relativePath("a/b.mp4", "stamp"))
    }

    @Test
    fun headers_carryWebViewUaAndCorrectReferer() {
        val baidu = DownloadLogic.headers(DownloadRoute.BAIDU_DISK, "Mozilla/5.0 WebView")
        assertEquals("Mozilla/5.0 WebView", baidu.first { it.first == "User-Agent" }.second)
        assertEquals(DownloadLogic.BAIDU_REFERER, baidu.first { it.first == "Referer" }.second)

        val quark = DownloadLogic.headers(DownloadRoute.QUARK, "Mozilla/5.0 WebView")
        assertEquals(DownloadLogic.QUARK_REFERER, quark.first { it.first == "Referer" }.second)
        assertTrue(DownloadLogic.headers(DownloadRoute.QUARK, "").none { it.first == "User-Agent" })
    }

    @Test
    fun mimeType_coversCommonTypes() {
        assertEquals("video/*", DownloadLogic.mimeType("课.MP4"))
        assertEquals("audio/*", DownloadLogic.mimeType("课.mp3"))
        assertEquals("application/pdf", DownloadLogic.mimeType("手册.pdf"))
        assertEquals("application/octet-stream", DownloadLogic.mimeType("无后缀"))
    }

    @Test
    fun progress_handlesUnknownTotalAndBounds() {
        assertEquals(-1, DownloadLogic.progress(0, -1))
        assertEquals(0, DownloadLogic.progress(0, 100))
        assertEquals(50, DownloadLogic.progress(50, 100))
        assertEquals(99, DownloadLogic.progress(99, 100))
        assertEquals(100, DownloadLogic.progress(200, 100))
    }

    @Test
    fun formatAndSpeedAndEta() {
        assertEquals("1.0 KB", DownloadLogic.formatBytes(1024))
        assertEquals("1.0 MB", DownloadLogic.formatBytes(1024 * 1024))
        assertEquals("1.0 MB/s", DownloadLogic.speedText(1024 * 1024.0))
        assertEquals("", DownloadLogic.speedText(0.0))
        assertEquals("约 1 分钟", DownloadLogic.etaText(60 * 1024, 1024.0))
        assertEquals("约 2 小时", DownloadLogic.etaText(7200 * 1024, 1024.0))
    }

    @Test
    fun failureText_explainsQuarkLimitAndExpiredLinks() {
        assertTrue(DownloadLogic.failureText(403, DownloadRoute.BAIDU_GROUP).contains("过期"))
        assertTrue(DownloadLogic.failureText(0, DownloadRoute.QUARK).contains("50MB"))
        assertTrue(DownloadLogic.failureText(404, DownloadRoute.BAIDU_DISK).contains("失效"))
    }

    @Test
    fun taskJson_roundTripKeepsAllFields() {
        val task = DownloadTask(
            id = 123L, name = "第08课.mp4", sizeBytes = 486_000_000,
            provider = SourceProvider.QUARK, sourceLabel = "夸克网盘", createdAt = 1_000L,
            state = DownloadState.RUNNING, downloadId = 42L, url = "https://pan.quark.cn/file",
            userAgent = "UA", referer = DownloadLogic.QUARK_REFERER,
            bytesDone = 100, bytesTotal = 200, error = "", mediaType = "video/*",
        )
        val restored = DownloadLogic.decodeTasks(DownloadLogic.encodeTasks(listOf(task))).single()
        assertEquals(task, restored)
    }

    @Test
    fun decodeTasks_dropsOnlyCorruptedRecords() {
        val good = DownloadLogic.encodeTasks(listOf(DownloadTask(id = 1, name = "好文件.mp4")))
        val array = JSONArray(good).put(JSONObject().put("broken", true))
        val tasks = DownloadLogic.decodeTasks(array.toString())
        assertEquals(1, tasks.size)
        assertEquals("好文件.mp4", tasks.single().name)
        assertTrue(DownloadLogic.decodeTasks("not json").isEmpty())
    }

    @Test
    fun summaryProgress_hidesBarWhenThereAreNoTasks() {
        val display = DownloadLogic.summaryProgress(active = 0, running = 0, done = 0, total = 0)
        assertFalse(display.visible)
        assertFalse(display.indeterminate)
    }

    @Test
    fun summaryProgress_animatesOnlyWhenRunningAndSizeUnknown() {
        val queuedOnly = DownloadLogic.summaryProgress(active = 1, running = 0, done = 0, total = 0)
        assertTrue(queuedOnly.visible)
        assertFalse(queuedOnly.indeterminate)

        val unknownSize = DownloadLogic.summaryProgress(1, 1, 0, -1)
        assertTrue(unknownSize.visible)
        assertTrue(unknownSize.indeterminate)

        val half = DownloadLogic.summaryProgress(1, 1, 50, 100)
        assertFalse(half.indeterminate)
        assertEquals(50, half.percent)
    }

    @Test
    fun taskProgress_failedHiddenQueuedStaticRunningAnimatedOnlyIfUnknown() {
        val failed = DownloadLogic.taskProgress(DownloadState.FAILED, 0, 100)
        assertFalse(failed.visible)

        val queued = DownloadLogic.taskProgress(DownloadState.QUEUED, 0, -1)
        assertTrue(queued.visible)
        assertFalse(queued.indeterminate)
        assertEquals(0, queued.percent)

        val running = DownloadLogic.taskProgress(DownloadState.RUNNING, 0, -1)
        assertTrue(running.visible)
        assertTrue(running.indeterminate)

        val known = DownloadLogic.taskProgress(DownloadState.RUNNING, 25, 100)
        assertFalse(known.indeterminate)
        assertEquals(25, known.percent)
    }
}
