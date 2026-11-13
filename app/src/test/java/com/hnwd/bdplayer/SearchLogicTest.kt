package com.hnwd.bdplayer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 全局搜索纯逻辑单测：范围装配、恢复、面板尺寸、去重 key，
 * 以及最容易出边界错的「搜索结果定位到目录」路径计算。
 */
class SearchLogicTest {

    // ---------- 可搜索群组与目标装配 ----------

    @Test
    fun `群组范围只保留百度普通群组`() {
        val sources = listOf(
            ShareSource("12345", "群A"),
            ShareSource("contact-678", "联系人"),
            ShareSource("baidu:contact-999", "带前缀联系人"),
            ShareSource("abcd1234", "夸克目录", provider = SourceProvider.QUARK),
            ShareSource("baidu:777", "带前缀群B"),
        )
        assertEquals(
            listOf(
                SearchTarget(SearchScope.GROUP, "12345", "群A"),
                SearchTarget(SearchScope.GROUP, "baidu:777", "带前缀群B"),
            ),
            searchGroupTargets(sources),
        )
    }

    @Test
    fun `网盘目录来源不参与全局搜索`() {
        val sources = listOf(
            ShareSource("12345", "群 A"),
            ShareSource(directorySourceId("/课程资料"), "课程资料"),
            ShareSource("contact-678", "联系人"),
        )
        assertEquals(
            listOf(SearchTarget(SearchScope.GROUP, "12345", "群 A")),
            searchGroupTargets(sources),
        )
    }

    @Test
    fun `没有来源时可搜索群组为空`() {
        assertTrue(searchGroupTargets(emptyList()).isEmpty())
    }

    @Test
    fun `我的网盘范围只搜盘`() {
        val groups = listOf(SearchTarget(SearchScope.GROUP, "g1", "群1"))
        assertEquals(
            listOf(SearchTarget(SearchScope.DISK, DISK_SOURCE_ID, "我的网盘")),
            searchTargets(SearchScope.DISK, groups),
        )
    }

    @Test
    fun `群组范围不搜我的网盘`() {
        val groups = listOf(SearchTarget(SearchScope.GROUP, "g1", "群1"))
        assertEquals(groups, searchTargets(SearchScope.GROUP, groups))
    }

    @Test
    fun `全部范围先群组后我的网盘`() {
        val groups = listOf(
            SearchTarget(SearchScope.GROUP, "g1", "群1"),
            SearchTarget(SearchScope.GROUP, "g2", "群2"),
        )
        assertEquals(
            groups + SearchTarget(SearchScope.DISK, DISK_SOURCE_ID, "我的网盘"),
            searchTargets(SearchScope.ALL, groups),
        )
    }

    @Test
    fun `无群组时全部范围退化为只搜盘`() {
        assertEquals(
            listOf(SearchTarget(SearchScope.DISK, DISK_SOURCE_ID, "我的网盘")),
            searchTargets(SearchScope.ALL, emptyList()),
        )
    }

    // ---------- 范围恢复 ----------

    @Test
    fun `无群组时只提供我的网盘范围`() {
        assertEquals(listOf(SearchScope.DISK), availableScopes(false))
    }

    @Test
    fun `有群组时提供三个范围且顺序固定`() {
        assertEquals(
            listOf(SearchScope.DISK, SearchScope.GROUP, SearchScope.ALL),
            availableScopes(true),
        )
    }

    @Test
    fun `恢复上次范围`() {
        val scopes = availableScopes(true)
        assertEquals(SearchScope.GROUP, restoreScope("GROUP", scopes))
        assertEquals(SearchScope.ALL, restoreScope("ALL", scopes))
        assertEquals(SearchScope.DISK, restoreScope("DISK", scopes))
    }

    @Test
    fun `上次范围已不可用时回落到我的网盘`() {
        assertEquals(SearchScope.DISK, restoreScope("GROUP", availableScopes(false)))
        assertEquals(SearchScope.DISK, restoreScope("ALL", availableScopes(false)))
    }

    @Test
    fun `存储值非法或缺失时回落到我的网盘`() {
        assertEquals(SearchScope.DISK, restoreScope("WHATEVER", availableScopes(true)))
        assertEquals(SearchScope.DISK, restoreScope(null, availableScopes(true)))
        assertEquals(SearchScope.DISK, restoreScope("", availableScopes(true)))
    }

    @Test
    fun `范围标题副标题与提示文案`() {
        assertEquals("搜索我的网盘", scopeTitle(SearchScope.DISK))
        assertEquals("搜索群组", scopeTitle(SearchScope.GROUP))
        assertEquals("搜索全部", scopeTitle(SearchScope.ALL))
        assertEquals("在我的网盘中搜索", scopeSubtitle(SearchScope.DISK))
        assertEquals("在已关联的群组文件库中搜索", scopeSubtitle(SearchScope.GROUP))
        assertEquals("在群组和我的网盘中搜索", scopeSubtitle(SearchScope.ALL))
        assertEquals("将搜索 3 个群 + 我的网盘，结果可能较多", scopeHintText(3))
    }

    // ---------- 面板尺寸 / 汇总文案 ----------

    @Test
    fun `大屏结果面板高度取七成但不超过上限`() {
        assertEquals(640, searchResultHeight(1000, 320, 640, 24))
        assertEquals(640, searchResultHeight(3000, 320, 640, 24))
    }

    @Test
    fun `小屏结果面板高度不低于下限`() {
        assertEquals(320, searchResultHeight(400, 320, 640, 24))
    }

    @Test
    fun `极小屏让出边距后仍至少为 1`() {
        assertEquals(76, searchResultHeight(100, 320, 640, 24))
        assertEquals(1, searchResultHeight(10, 320, 640, 24))
        assertEquals(1, searchResultHeight(0, 320, 640, 24))
    }

    @Test
    fun `搜索进度文案`() {
        assertEquals("已搜 2/5 · 已找到 7 项", searchProgressText(2, 5, 7))
    }

    @Test
    fun `汇总文案区分零结果与来源失败`() {
        assertEquals("0 个结果", searchSummaryText(0, 0))
        assertEquals("0 个结果 · 2 个来源失败", searchSummaryText(0, 2))
        assertEquals("已找到 5 项 · 点选打开", searchSummaryText(5, 0))
        assertEquals("已找到 5 项 · 2 个来源失败", searchSummaryText(5, 2))
    }

    @Test
    fun `结果上限固定为 1000`() {
        assertEquals(1000, MAX_GLOBAL_SEARCH_HITS)
    }

    // ---------- 去重 key ----------

    @Test
    fun `盘与群组同 fileId 的去重 key 不冲突`() {
        assertFalse(diskHitKey("f1") == groupHitKey("g1", "f1"))
        assertEquals("D:f1", diskHitKey("f1"))
        assertEquals("G:g1:f1", groupHitKey("g1", "f1"))
    }

    @Test
    fun `不同群组的同 fileId 去重 key 不冲突`() {
        assertFalse(groupHitKey("g1", "f1") == groupHitKey("g2", "f1"))
    }

    // ---------- 目录定位：目标路径 ----------

    @Test
    fun `目录命中用自身路径并去掉尾部斜杠`() {
        assertEquals("/课程/第一节", normalizeTargetPath("/课程/第一节/", true, "/忽略"))
    }

    @Test
    fun `文件命中用父路径`() {
        assertEquals("/课程", normalizeTargetPath("/课程/01.mp3", false, "/课程/"))
    }

    @Test
    fun `空目标路径归一为根`() {
        assertEquals("/", normalizeTargetPath("", true, ""))
        assertEquals("/", normalizeTargetPath("/文件.mp4", false, "/"))
    }

    // ---------- 目录定位：child 匹配 ----------

    @Test
    fun `文件命中靠 fileId 精确匹配`() {
        assertTrue(matchesTrailChild("/任意/01.mp3", false, "f1", "/课程", true, "f1"))
        assertFalse(matchesTrailChild("/任意/01.mp3", false, "f2", "/课程", true, "f1"))
    }

    @Test
    fun `文件命中时目录仍可走路径相等分支`() {
        // fileId 分支要求 child 非目录；但命中项若是目录，路径相等依然算命中（原逻辑如此）。
        assertTrue(matchesTrailChild("/课程", true, "f1", "/课程", true, "f1"))
        assertFalse(matchesTrailChild("/课程/子目录", true, "f1", "/课程", true, "f1"))
    }

    @Test
    fun `目录命中路径相等或为目标祖先`() {
        assertTrue(matchesTrailChild("/a/b", true, "d1", "/a/b", false, ""))
        assertTrue(matchesTrailChild("/a", true, "d1", "/a/b/c", false, ""))
    }

    @Test
    fun `祖先匹配要求路径分隔符边界`() {
        assertFalse(matchesTrailChild("/a", true, "d1", "/ab/c", false, ""))
    }

    @Test
    fun `child 路径尾部斜杠与空路径边界`() {
        assertTrue(matchesTrailChild("/a/", true, "d1", "/a", false, ""))
        assertFalse(matchesTrailChild("", true, "d1", "/abc", false, ""))
    }

    // ---------- 目录定位：面包屑 ----------

    @Test
    fun `多级命中推导父路径与根名`() {
        assertEquals("/a" to "a", trailRoot("/a/b"))
        assertEquals("/文件库/课程" to "课程", trailRoot("/文件库/课程/第 1 节"))
    }

    @Test
    fun `一级目录的根固定为文件库`() {
        assertEquals("/" to "文件库", trailRoot("/a"))
        assertEquals("/" to "文件库", trailRoot("/a/"))
        assertEquals("/" to "文件库", trailRoot("/"))
        assertEquals("/" to "文件库", trailRoot(""))
    }

    @Test
    fun `目标路径去掉根前缀后得到层级名`() {
        assertEquals(listOf("b", "c"), trailParts("/a/b/c", "/a"))
        assertEquals(listOf("x", "y"), trailParts("/x/y", "/"))
        assertEquals(listOf("b"), trailParts("/a/b/", "/a"))
        assertTrue(trailParts("/a", "/a").isEmpty())
    }

    @Test
    fun `逐层下钻的期望路径不受尾部斜杠影响`() {
        assertEquals("/a/b", trailExpectedPath("/a", "b"))
        assertEquals("/a/b", trailExpectedPath("/a/", "b"))
        assertEquals("/x", trailExpectedPath("/", "x"))
    }
}
