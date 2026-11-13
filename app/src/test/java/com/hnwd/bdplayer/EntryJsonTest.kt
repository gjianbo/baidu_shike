package com.hnwd.bdplayer

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 条目列表与 JSON 的互转（[encodeEntries] / [decodeEntries]）。
 *
 * 这条链用于收藏、播放进度与备份恢复，是"关掉 App 之后数据还在不在"的唯一依据。
 * 重点钉住三件事：
 * 1. 往返后条目身份（origin / gid / messageId / fileId）不变；
 * 2. 网盘归属（[PanEntry.provider]）不靠存储字段、只靠 gid 前缀推导，往返后必须仍然正确；
 * 3. 解码是容错的，但容错边界在哪要说清楚（见下面"整表丢弃"的用例）。
 */
class EntryJsonTest {

    private fun entry(
        name: String = "第1讲.mp3",
        path: String = "/课程/第1讲.mp3",
        gid: String = "1001",
        messageId: String = "m1",
        owner: String = "2002",
        fileId: String = "f1",
        size: String = "1024",
        directory: Boolean = false,
        origin: EntryOrigin = EntryOrigin.GROUP,
        md5: String = "0123456789abcdef0123456789abcdef",
        previewUrl: String = "",
    ) = PanEntry(
        gid = gid, messageId = messageId, owner = owner, fileId = fileId, name = name,
        path = path, size = size, directory = directory, origin = origin, md5 = md5,
        previewUrl = previewUrl,
    )

    // ===================== 往返 =====================

    @Test
    fun `群组条目往返后字段与顺序都不变`() {
        val first = entry()
        val second = entry(name = "第2讲.mp3", path = "/课程/第2讲.mp3", fileId = "f2", md5 = "")

        val restored = decodeEntries(encodeEntries(listOf(first, second)))

        assertEquals(2, restored.size)
        assertEquals(first, restored[0])
        assertEquals(second, restored[1])
        // 顺序即播放队列顺序，不能被解码打乱
        assertEquals(listOf("f1", "f2"), restored.map { it.fileId })
    }

    @Test
    fun `空列表往返仍为空`() {
        assertEquals(emptyList<PanEntry>(), decodeEntries(encodeEntries(emptyList())))
    }

    @Test
    fun `恢复后仍按最近更新时间排序`() {
        val older = entry(fileId = "older").copy(modifiedAt = 1_750_000_000L)
        val newer = entry(fileId = "newer").copy(modifiedAt = 1_760_000_000L)
        val restored = decodeEntries(encodeEntries(listOf(older, newer)))
        assertEquals(listOf(newer, older), orderCourses(restored, CourseOrder.NEWEST))
    }

    @Test
    fun `目录条目往返后仍是目录且大小为原始字符串`() {
        val dir = entry(name = "课程", path = "/课程", directory = true, size = "")
        val restored = decodeEntries(encodeEntries(listOf(dir))).single()

        assertTrue(restored.directory)
        assertEquals("", restored.size)
        assertEquals("目录", formatOf(restored))
    }

    // ===================== 网盘归属：只靠 gid 前缀推导 =====================

    @Test
    fun `编码不保存 provider 但往返后归属不变`() {
        val cases = listOf(
            // 百度个人网盘：gid 为空，靠 origin=DISK 判定百度
            entry(gid = "", origin = EntryOrigin.DISK) to SourceProvider.BAIDU,
            // 夸克个人网盘：gid 固定为 disk
            entry(gid = DISK_SOURCE_ID, origin = EntryOrigin.DISK) to SourceProvider.QUARK,
            // 夸克群组：gid 带 quark: 前缀
            entry(gid = "quark:abc12345") to SourceProvider.QUARK,
            // 百度群组：纯数字 gid
            entry(gid = "1234567") to SourceProvider.BAIDU,
        )
        val encoded = encodeEntries(cases.map { it.first })
        // 确认真的没写 provider 字段（而不是写对了）
        assertFalse(encoded.contains("provider"))

        decodeEntries(encoded).forEachIndexed { index, restored ->
            assertEquals("第 $index 条的网盘归属变了", cases[index].second, restored.provider)
        }
    }

    @Test
    fun `老数据无前缀的来源 id 解码后仍按百度处理`() {
        // 兼容性：升级前存的收藏没有 quark: 前缀
        val legacy = JSONArray().put(
            JSONObject()
                .put("gid", "1234567").put("message", "m1").put("owner", "u1")
                .put("file", "f1").put("name", "a.mp3").put("path", "/a.mp3")
                .put("size", "1").put("directory", false).put("origin", "GROUP"),
        )
        assertEquals(SourceProvider.BAIDU, decodeEntries(legacy.toString()).single().provider)
    }

    // ===================== 容错边界 =====================

    @Test
    fun `输入为空或不是 JSON 时返回空表而不抛异常`() {
        assertEquals(emptyList<PanEntry>(), decodeEntries(null))
        assertEquals(emptyList<PanEntry>(), decodeEntries(""))
        assertEquals(emptyList<PanEntry>(), decodeEntries("not json at all"))
        assertEquals(emptyList<PanEntry>(), decodeEntries("{}"))
    }

    @Test
    fun `未知的 origin 值退化为群组而不是崩溃`() {
        val raw = JSONArray().put(
            JSONObject()
                .put("gid", "1").put("message", "m").put("owner", "o")
                .put("file", "f").put("name", "a.mp3").put("path", "/a.mp3")
                .put("origin", "SOMETHING_NEW"),
        )
        assertEquals(EntryOrigin.GROUP, decodeEntries(raw.toString()).single().origin)
    }

    @Test
    fun `缺失的可选字段退化为空字符串或 false`() {
        val raw = JSONArray().put(
            JSONObject().put("gid", "1").put("message", "m").put("owner", "o")
                .put("file", "f").put("name", "a.mp3"),
        )
        val restored = decodeEntries(raw.toString()).single()

        assertEquals("", restored.path)
        assertEquals("", restored.size)
        assertEquals("", restored.md5)
        assertEquals(0L, restored.modifiedAt)
        assertFalse(restored.directory)
    }

    @Test
    fun `单条缺必填字段只跳过这一条`() {
        // 收藏/进度是整份覆盖写的：一条脏数据丢掉整表等于「收藏全没了」，
        // 所以解码必须逐条容错，只丢坏的那条。
        val raw = JSONArray()
            .put(
                JSONObject().put("gid", "1").put("message", "m").put("owner", "o")
                    .put("file", "ok-1").put("name", "ok1.mp3"),
            )
            .put(JSONObject().put("name", "missing-gid.mp3"))
            .put(
                JSONObject().put("gid", "3").put("message", "m").put("owner", "o")
                    .put("file", "ok-3").put("name", "ok3.mp3"),
            )

        val restored = decodeEntries(raw.toString())

        assertEquals(2, restored.size)
        assertEquals(listOf("ok-1", "ok-3"), restored.map { it.fileId })
    }

    @Test
    fun `全部条目都坏时返回空表而不抛异常`() {
        val raw = JSONArray()
            .put(JSONObject().put("name", "a.mp3"))
            .put(JSONObject().put("name", "b.mp3"))
        assertEquals(emptyList<PanEntry>(), decodeEntries(raw.toString()))
    }

    @Test
    fun `预览地址不参与持久化`() {
        // previewUrl 是网盘返回的临时签名大图地址，编码时被有意丢弃；
        // 恢复后为空，图片链路会退回按文件取址（这是设计，不是 bug）。
        val restored = decodeEntries(
            encodeEntries(listOf(entry(previewUrl = "https://pan.baidu.com/x?size=c2560"))),
        ).single()

        assertEquals("", restored.previewUrl)
    }
}
