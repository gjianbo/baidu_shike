package com.hnwd.bdplayer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * EntryUtils 的行为约束。
 *
 * 这些函数原来埋在 MainActivity 里，靠手点验证；抽出来之后用单测钉住，
 * 重点是两类曾经踩过的坑：课程名里的数字排序、相邻条目的目录边界。
 */
class EntryUtilsTest {

    private fun entry(
        name: String,
        path: String = "/dir/$name",
        gid: String = "1001",
        messageId: String = "m1",
        fileId: String = "f-$name",
        directory: Boolean = false,
        size: String = "1024",
        origin: EntryOrigin = EntryOrigin.GROUP,
        md5: String = "",
    ) = PanEntry(
        gid = gid,
        messageId = messageId,
        owner = "1001",
        fileId = fileId,
        name = name,
        path = path,
        size = size,
        directory = directory,
        origin = origin,
        md5 = md5,
    )

    // ===================== 课程排序 =====================

    @Test
    fun `名称里的数字按数值比较而不是字典序`() {
        val names = listOf("第10讲.mp3", "第2讲.mp3", "第1讲.mp3")
        val sorted = orderCourses(names.map { entry(it) }, CourseOrder.ASCENDING).map { it.name }
        assertEquals(listOf("第1讲.mp3", "第2讲.mp3", "第10讲.mp3"), sorted)
    }

    @Test
    fun `降序是升序的反向`() {
        val names = listOf("第2讲.mp3", "第10讲.mp3", "第1讲.mp3")
        val sorted = orderCourses(names.map { entry(it) }, CourseOrder.DESCENDING).map { it.name }
        assertEquals(listOf("第10讲.mp3", "第2讲.mp3", "第1讲.mp3"), sorted)
    }

    @Test
    fun `网盘顺序不做任何重排`() {
        val names = listOf("b.mp3", "a.mp3", "c.mp3")
        val sorted = orderCourses(names.map { entry(it) }, CourseOrder.NETWORK).map { it.name }
        assertEquals(names, sorted)
    }

    // ===================== 相邻条目（上一节 / 下一节） =====================

    @Test
    fun `只取同目录同类型的兄弟条目`() {
        val current = entry("第2讲.mp3", path = "/课程A/第2讲.mp3")
        val siblings = listOf(
            entry("第1讲.mp3", path = "/课程A/第1讲.mp3"),
            current,
            entry("第3讲.mp3", path = "/课程A/第3讲.mp3"),
            entry("其它课程.mp3", path = "/课程B/其它课程.mp3"),
            entry("封面.jpg", path = "/课程A/封面.jpg"),
        )
        val result = courseSiblings(current, siblings).map { it.name }
        assertEquals(listOf("第1讲.mp3", "第2讲.mp3", "第3讲.mp3"), result)
    }

    @Test
    fun `当前条目不在列表里时退化为只有自己`() {
        val current = entry("第9讲.mp3", path = "/课程A/第9讲.mp3")
        val others = listOf(entry("第1讲.mp3", path = "/课程A/第1讲.mp3"))
        assertEquals(listOf("第9讲.mp3"), courseSiblings(current, others).map { it.name })
    }

    @Test
    fun `路径不是绝对路径时不做同目录推断`() {
        val current = entry("a.mp3", path = "a.mp3")
        val others = listOf(current, entry("b.mp3", path = "b.mp3"))
        assertEquals(listOf("a.mp3"), courseSiblings(current, others).map { it.name })
    }

    // ===================== 条目同一性 =====================

    @Test
    fun `同一文件不同消息不算同一条目`() {
        val first = entry("a.mp3", messageId = "m1", fileId = "f1")
        val second = entry("a.mp3", messageId = "m2", fileId = "f1")
        assertFalse(sameEntry(first, second))
        assertNotEquals(entryKey(first), entryKey(second))
    }

    @Test
    fun `网盘条目只比来源与文件 id`() {
        val first = entry("a.mp3", gid = "", messageId = "", fileId = "f1", origin = EntryOrigin.DISK)
        val second = entry("a.mp3", gid = "", messageId = "", fileId = "f1", origin = EntryOrigin.DISK)
        assertTrue(sameEntry(first, second))
    }

    // ===================== 移动 / 复制的目标校验 =====================

    @Test
    fun `自己与子目录都算非法目标`() {
        val folder = entry("课程A", path = "/课程A", directory = true)
        assertTrue(isSelfOrDescendant("/课程A", listOf(folder)))
        assertTrue(isSelfOrDescendant("/课程A/第1章", listOf(folder)))
    }

    @Test
    fun `同前缀的不同目录不算子目录`() {
        val folder = entry("课程A", path = "/课程A", directory = true)
        assertFalse(isSelfOrDescendant("/课程AB", listOf(folder)))
    }

    // ===================== 展示文案 =====================

    @Test
    fun `容量格式化覆盖三个量级`() {
        assertEquals("1023 B", formatSize(1023))
        assertEquals("1.0 KB", formatSize(1024))
        assertEquals("1.0 MB", formatSize(1024L * 1024))
    }

    @Test
    fun `扩展名缺失时按文件处理`() {
        assertEquals("文件", formatOf(entry("无扩展名")))
        assertEquals("MP3", formatOf(entry("第1讲.mp3")))
        assertEquals("目录", formatOf(entry("课程A", directory = true)))
    }

    @Test
    fun `类型标签与筛选器一致`() {
        assertEquals("音频", mediaKindLabel(MediaKind.AUDIO, false, "MP3"))
        assertEquals("目录", mediaKindLabel(MediaKind.DIRECTORY, true, ""))
        assertTrue(MediaFilter.AUDIO.includes(MediaKind.AUDIO))
        assertFalse(MediaFilter.AUDIO.includes(MediaKind.VIDEO))
        assertTrue(MediaFilter.DOCUMENT.includes(MediaKind.IMAGE))
    }
}
