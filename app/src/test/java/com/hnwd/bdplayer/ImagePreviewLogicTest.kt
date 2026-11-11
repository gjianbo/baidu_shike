package com.hnwd.bdplayer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 图片预览纯逻辑的 JVM 单测。
 *
 * 切图靠列表下标回传，所以画廊的过滤规则与下标计算是这里最关键的契约；
 * 另外钉死「群组图片才去请求分享信息」和「失效链接只重取一次」两条短路。
 */
class ImagePreviewLogicTest {

    private fun entry(
        name: String,
        fileId: String = "f-$name",
        gid: String = "1001",
        origin: EntryOrigin = EntryOrigin.GROUP,
        path: String = "/dir/$name",
        previewUrl: String = "",
    ) = PanEntry(
        gid = gid, messageId = "m1", owner = "1001", fileId = fileId,
        name = name, path = path, size = "1", directory = false,
        origin = origin, previewUrl = previewUrl,
    )

    // ---------- 画廊装配 ----------

    @Test
    fun `画廊只保留同网盘的图片且保持原顺序`() {
        val target = entry("b.jpg")
        val entries = listOf(
            entry("a.jpg"), entry("note.txt"), target, entry("c.png"),
            entry("q.jpg", gid = "quark:abc"),
        )
        val gallery = imageGallery(target, entries)
        assertEquals(listOf("a.jpg", "b.jpg", "c.png"), gallery.items.map { it.name })
        assertEquals(1, gallery.position)
    }

    @Test
    fun `目标不在列表里时插到首位且下标为 0`() {
        val target = entry("z.jpg")
        val gallery = imageGallery(target, listOf(entry("a.jpg"), entry("b.png")))
        assertEquals(listOf("z.jpg", "a.jpg", "b.png"), gallery.items.map { it.name })
        assertEquals(0, gallery.position)
    }

    @Test
    fun `空列表退化为只有自己`() {
        val target = entry("only.jpg")
        val gallery = imageGallery(target, emptyList())
        assertEquals(listOf(target), gallery.items)
        assertEquals(0, gallery.position)
    }

    @Test
    fun `夸克目标只保留夸克图片`() {
        val target = entry("q.jpg", gid = "quark:abc")
        val gallery = imageGallery(
            target,
            listOf(entry("b.jpg"), target, entry("q2.png", gid = "quark:abc")),
        )
        assertEquals(listOf("q.jpg", "q2.png"), gallery.items.map { it.name })
        assertEquals(0, gallery.position)
    }

    @Test
    fun `只有百度盘且缺缩略图时才需要先取原图`() {
        val baiduDisk = entry("a.jpg", origin = EntryOrigin.DISK)
        assertTrue(imageNeedsOriginal(false, baiduDisk))
        assertFalse(imageNeedsOriginal(false, baiduDisk.copy(previewUrl = "https://x/a.jpg")))
        assertFalse(imageNeedsOriginal(true, baiduDisk))
        assertFalse(imageNeedsOriginal(false, entry("a.jpg")))
    }

    // ---------- 查看器条目 ----------

    @Test
    fun `只有非夸克群组图片才请求分享信息，百度盘图片补目录`() {
        var shareCalls = 0
        val baiduDisk = entry("d.jpg", origin = EntryOrigin.DISK, path = "/dir/d.jpg")
        val group = entry("g.jpg")
        // 画廊装配已保证列表与目标同 provider，这里不会混入夸克条目。
        val items = imagePreviewItems(
            listOf(baiduDisk, group), false,
            cacheKey = { "k:${it.fileId}" },
            shareInfo = { shareCalls++; "share:${it.fileId}" },
        )
        assertEquals(1, shareCalls)
        assertEquals("", items[0].shareInfoUrl)
        assertEquals("/dir", items[0].diskDirectory)
        assertEquals("share:f-g.jpg", items[1].shareInfoUrl)
        assertNull(items[1].diskDirectory)
        assertEquals(listOf("k:f-d.jpg", "k:f-g.jpg"), items.map { it.key })
    }

    @Test
    fun `夸克模式一次都不请求分享信息也不补目录`() {
        var calls = 0
        val items = imagePreviewItems(
            listOf(entry("q.jpg", gid = "quark:abc")), true,
            cacheKey = { "k" },
            shareInfo = { calls++; "s" },
        )
        assertEquals(0, calls)
        assertEquals("", items[0].shareInfoUrl)
        assertNull(items[0].diskDirectory)
    }

    @Test
    fun `根目录下的百度盘图片目录记为斜杠`() {
        val root = entry("r.jpg", origin = EntryOrigin.DISK, path = "/r.jpg")
        val items = imagePreviewItems(listOf(root), false, { "k" }, { "" })
        assertEquals("/", items[0].diskDirectory)
    }

    @Test
    fun `查看器下标命中时取真实位置，找不到时退化为 0`() {
        val target = entry("b.jpg")
        val images = listOf(entry("a.jpg"), target)
        assertEquals(1, imageViewerIndex(images, target))
        assertEquals(0, imageViewerIndex(images, entry("z.jpg")))
    }

    @Test
    fun `空画廊回跳时用当前条目兜底`() {
        val target = entry("b.jpg")
        val images = listOf(entry("a.jpg"), target)
        assertEquals(listOf(target), effectiveImageGallery(emptyList(), target))
        assertEquals(images, effectiveImageGallery(images, target))
        assertEquals(0, imageViewerIndex(effectiveImageGallery(emptyList(), target), target))
    }

    // ---------- 原图下载失败 ----------

    @Test
    fun `非失效链接不读宿主状态也不清缓存`() {
        var calls = 0
        val plan = imageFailurePlan(false, { calls++; true }, true)
        assertFalse(plan.clearCachedLink)
        assertFalse(plan.retry)
        assertEquals(0, calls)
    }

    @Test
    fun `失效链接即便不是缓存地址也会读宿主并清缓存`() {
        var calls = 0
        val plan = imageFailurePlan(true, { calls++; true }, false)
        assertTrue(plan.clearCachedLink)
        assertFalse(plan.retry)
        assertEquals(1, calls)
    }

    @Test
    fun `缓存地址失效且宿主就绪时重取一次`() {
        val plan = imageFailurePlan(true, { true }, true)
        assertTrue(plan.clearCachedLink)
        assertTrue(plan.retry)
    }

    @Test
    fun `宿主未就绪时只清缓存不重试`() {
        val plan = imageFailurePlan(true, { false }, true)
        assertTrue(plan.clearCachedLink)
        assertFalse(plan.retry)
    }

    @Test
    fun `失败文案优先用异常自带信息`() {
        assertEquals("自定义错误", imageFailureMessage(IllegalStateException("自定义错误")))
        assertEquals("图片打开失败，请重试。", imageFailureMessage(IllegalStateException()))
    }
}
