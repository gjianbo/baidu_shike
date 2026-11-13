package com.hnwd.bdplayer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 第 2 步三分支收口的回归钉子：所有纯决策对 GROUP / CONTACT 的结果必须与收口前的
 * `isContactSource(id)` 二分逐点一致，DIRECTORY 不得被误判成群组。
 */
class SourceKindLogicTest {

    private val group = "12345"
    private val contact = "contact-678"
    private val directory = directorySourceId("/课程资料/2026")

    private fun src(id: String, provider: SourceProvider = SourceProvider.BAIDU) =
        ShareSource(id, "name", provider = provider, kind = inferSourceKind(id, provider))

    @Test
    fun `首页缓存 key 群组走 recent 其余走 roots`() {
        // 收口前：isContactSource=true → roots，false → recent
        assertEquals("recent:$group", homeSourceKeyOf(group, SourceKind.GROUP))
        assertEquals("roots:$contact", homeSourceKeyOf(contact, SourceKind.CONTACT))
        assertEquals("recent:$directory", homeSourceKeyOf(directory, SourceKind.DIRECTORY))
        assertEquals("recent:$group", homeSourceKeyOf(group, sourceKindOf(group)))
        assertEquals("roots:$contact", homeSourceKeyOf(contact, sourceKindOf(contact)))
        assertEquals("recent:$directory", homeSourceKeyOf(directory, sourceKindOf(directory)))
    }

    @Test
    fun `只有联系人需要 POST 根目录请求`() {
        assertTrue(rootNeedsPost(SourceKind.CONTACT))
        assertFalse(rootNeedsPost(SourceKind.GROUP))
        assertFalse(rootNeedsPost(SourceKind.DIRECTORY))
    }

    @Test
    fun `分页与游标结构只有联系人走 records`() {
        assertTrue(rootPaginationIsRecords(SourceKind.CONTACT))
        assertTrue(rootCursorIsRecords(SourceKind.CONTACT))
        assertFalse(rootPaginationIsRecords(SourceKind.GROUP))
        assertFalse(rootCursorIsRecords(SourceKind.GROUP))
        assertFalse(rootPaginationIsRecords(SourceKind.DIRECTORY))
        assertFalse(rootCursorIsRecords(SourceKind.DIRECTORY))
    }

    @Test
    fun `首页后台检查只对群组开启`() {
        assertTrue(shouldCheckHomeInBackground(SourceKind.GROUP))
        assertFalse(shouldCheckHomeInBackground(SourceKind.CONTACT))
        assertFalse(shouldCheckHomeInBackground(SourceKind.DIRECTORY))
    }

    @Test
    fun `来源下拉副标题三分类且老两类文案不变`() {
        assertEquals("百度 · 群组", sourceMetaOf(src(group)))
        assertEquals("百度 · 联系人", sourceMetaOf(src(contact)))
        assertEquals("百度 · 网盘目录", sourceMetaOf(src(directory)))
        assertEquals("夸克 · 监控目录", sourceMetaOf(src("quark:abcd1234", SourceProvider.QUARK)))
        // 判据只看 id：冗余 kind 字段缺失 / 给错都不影响
        assertEquals("百度 · 联系人", sourceMetaOf(ShareSource("baidu:contact-999", "x")))
        assertEquals("百度 · 网盘目录", sourceMetaOf(ShareSource(directory, "x", kind = SourceKind.GROUP)))
    }

    @Test
    fun `全局搜索只纳入群组 联系人与网盘目录都排除`() {
        assertTrue(isGroupSearchSource(src(group)))
        assertFalse(isGroupSearchSource(src(contact)))
        assertFalse(isGroupSearchSource(src(directory)))
        assertFalse(isGroupSearchSource(src("abcd1234", SourceProvider.QUARK)))
        // 单测里手动构造、不带 kind 的老联系人 id 仍被排除
        assertFalse(isGroupSearchSource(ShareSource("baidu:contact-999", "x")))
    }

    @Test
    fun `来源管理弹窗候选不串类型`() {
        val discovered = listOf(src("111"), src("222"))
        val existing = listOf(
            src(group), src(contact), src(directory),
            src("quark:abcd1234", SourceProvider.QUARK),
        )
        // 收口前目录来源会被 isContactSource(id)==false 误当成群组混进来
        assertEquals(
            listOf("111", "222", group),
            sourcePickerCandidates(discovered, existing, contacts = false).map { it.id },
        )
        assertEquals(
            listOf(contact),
            sourcePickerCandidates(emptyList(), existing, contacts = true).map { it.id },
        )
    }

    @Test
    fun `候选按 id 去重且 discovered 不做二次过滤`() {
        val discovered = listOf(src("111"))
        val existing = listOf(src("111"))
        assertEquals(1, sourcePickerCandidates(discovered, existing, contacts = false).size)
        assertEquals(
            listOf(contact),
            sourcePickerCandidates(listOf(src(contact)), emptyList(), contacts = false).map { it.id },
        )
    }

    // ===================== 网盘目录来源的保存 =====================

    @Test
    fun `目录显示名取最后一段 根目录显示斜杠`() {
        assertEquals("/", directorySourceName("/"))
        assertEquals("课程资料", directorySourceName("/课程资料"))
        assertEquals("2026", directorySourceName("/课程资料/2026"))
        assertEquals("2026", directorySourceName("/课程资料/2026/"))
    }

    @Test
    fun `保存目录来源时覆盖旧目录 保留群组联系人与夸克`() {
        val existing = listOf(
            src("12345"),
            src("contact-678"),
            src("quark:abcd1234", SourceProvider.QUARK),
            ShareSource(directorySourceId("/旧目录"), "旧目录", kind = SourceKind.DIRECTORY, path = "/旧目录"),
        )
        val selected = linkedMapOf(
            directorySourceId("/课程资料") to "/课程资料",
            directorySourceId("/音频库") to "/音频库",
        )
        val merged = mergeDirectorySources(existing, selected)
        assertEquals(listOf("12345", "contact-678", "quark:abcd1234"), merged.take(3).map { it.id })
        val dirs = merged.filter { sourceKindOf(it.id) == SourceKind.DIRECTORY }
        assertEquals(2, dirs.size)
        assertEquals(listOf("课程资料", "音频库"), dirs.map { it.name })
        assertTrue(dirs.all { it.kind == SourceKind.DIRECTORY })
    }

    @Test
    fun `目录路径为空时从 id 解码兜底`() {
        val id = directorySourceId("/课程资料/2026")
        val source = mergeDirectorySources(emptyList(), mapOf(id to "")).single()
        assertEquals("/课程资料/2026", source.path)
        assertEquals("2026", source.name)
    }

    @Test
    fun `一级目录勾选只保留前 5 个且 id 与路径自洽`() {
        val paths = (1..7).map { "/目录$it" }
        val selected = directorySelectionOf(paths)
        assertEquals(5, selected.size)
        assertEquals(paths.take(5), selected.values.toList())
        selected.forEach { (id, path) -> assertEquals(path, directorySourcePath(id)) }
    }

    @Test
    fun `清空勾选后只剩其他来源`() {
        val existing = listOf(
            src("12345"),
            ShareSource(directorySourceId("/旧目录"), "旧目录", kind = SourceKind.DIRECTORY, path = "/旧目录"),
        )
        assertEquals(listOf("12345"), mergeDirectorySources(existing, emptyMap()).map { it.id })
    }

    @Test
    fun `目录来源 id 与路径自洽 可被备份校验放行`() {
        val path = "/课程资料/2026 归档"
        val source = mergeDirectorySources(emptyList(), mapOf(directorySourceId(path) to path)).single()
        assertEquals(directorySourceId(path), source.id)
        assertEquals(path, source.path)
        assertEquals("2026 归档", source.name)
        assertTrue(isShareSourceId(source.id))
        assertEquals(path, directorySourcePath(source.id))
    }
}
