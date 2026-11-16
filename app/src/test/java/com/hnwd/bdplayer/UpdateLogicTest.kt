package com.hnwd.bdplayer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 版本检测纯逻辑的 JVM 单测。
 *
 * 重点钉住三件容易被「看起来能跑」蒙混过去的事：
 * ① 语义化版本比较不是字符串比较（1.10.0 必须大于 1.9.0）；
 * ② Atom 的 tag 必须取自 releases/tag 链接而不是 release 标题，且 content 是二次转义的 HTML；
 * ③ GitHub 的 /releases/latest 不等于版本号最大，候选列表要自己按版本号降序。
 */
class UpdateLogicTest {

    @Test
    fun `备用 API 按版本号选出已上传正式安装包的新版本`() {
        val json = """
            [
              {"tag_name":"v1.9.0","assets":[{"name":"baidu-shike-1.9.0-release.apk","state":"uploaded"}]},
              {"tag_name":"v2.0.0","assets":[{"name":"baidu-shike-2.0.0-debug.apk","state":"uploaded"}]},
              {"tag_name":"v3.0.0","assets":[{"name":"baidu-shike-3.0.0-release.apk","state":"starter"}]},
              {"tag_name":"v4.0.0","draft":true,"assets":[{"name":"baidu-shike-4.0.0-release.apk","state":"uploaded"}]},
              {"tag_name":"v1.10.0","name":"拾课","body":"更新说明",
               "assets":[{"name":"baidu-shike-1.10.0-release.apk","state":"uploaded"}]}
            ]
        """.trimIndent()

        assertEquals(
            ApiRelease("v1.10.0", AppVersion(1, 10, 0), "拾课", "更新说明"),
            newestApiRelease(json, AppVersion(1, 2, 2)),
        )
        assertNull(newestApiRelease(json, AppVersion(1, 10, 0)))
    }

    private val sampleAtom = """
        <?xml version="1.0" encoding="UTF-8"?>
        <feed xmlns="http://www.w3.org/2005/Atom" xml:lang="en-US">
          <id>tag:github.com,2008:https://github.com/gjianbo/baidu_shike/releases</id>
          <title>Release notes from baidu_shike</title>
          <entry>
            <id>tag:github.com,2008:Repository/1/v1.0.1</id>
            <updated>2026-11-09T07:42:04Z</updated>
            <link rel="alternate" type="text/html" href="https://github.com/gjianbo/baidu_shike/releases/tag/v1.0.1"/>
            <title>拾课 v1.0.1</title>
            <content type="html">&lt;p&gt;加入了网盘文件的管理 &amp;amp; 修复播放&lt;/p&gt;</content>
          </entry>
          <entry>
            <id>tag:github.com,2008:Repository/1/v0.1.24</id>
            <updated>2026-11-09T01:10:08Z</updated>
            <link rel="alternate" type="text/html" href="https://github.com/gjianbo/baidu_shike/releases/tag/v0.1.24"/>
            <title>v0.1.24</title>
            <content type="html">&lt;p&gt;优化播放历史和查询&lt;/p&gt;</content>
          </entry>
          <entry>
            <id>tag:github.com,2008:Repository/1/nightly</id>
            <updated>2026-11-10T01:00:00Z</updated>
            <link rel="alternate" type="text/html" href="https://github.com/gjianbo/baidu_shike/releases/tag/nightly"/>
            <title>nightly</title>
            <content type="html">&lt;p&gt;非正式构建&lt;/p&gt;</content>
          </entry>
        </feed>
    """.trimIndent()

    // ---------- 版本号解析与比较 ----------

    @Test
    fun `解析带 v 前缀与不带前缀的版本号`() {
        assertEquals(AppVersion(1, 2, 0), parseAppVersion("v1.2.0"))
        assertEquals(AppVersion(1, 2, 0), parseAppVersion("1.2.0"))
        assertEquals(AppVersion(1, 10, 3), parseAppVersion("V1.10.3"))
        assertNull(parseAppVersion("1.2"))
        assertNull(parseAppVersion("nightly"))
        assertNull(parseAppVersion(""))
        assertNull(parseAppVersion("99999999999999999999.1.1"))
    }

    @Test
    fun `版本号按数值比较而不是字符串比较`() {
        assertTrue(parseAppVersion("1.10.0")!! > parseAppVersion("1.9.0")!!)
        assertTrue(parseAppVersion("2.0.0")!! > parseAppVersion("1.99.99")!!)
        assertTrue(parseAppVersion("1.2.1")!! > parseAppVersion("1.2.0")!!)
        assertEquals(0, parseAppVersion("v1.2.0")!!.compareTo(parseAppVersion("1.2.0")!!))
        assertEquals("1.2.0", AppVersion(1, 2, 0).toString())
    }

    // ---------- Atom 解析 ----------

    @Test
    fun `从 releases tag 链接提取 tag 并按 feed 顺序返回`() {
        val releases = parseReleaseAtom(sampleAtom)
        assertEquals(2, releases.size)
        assertEquals("v1.0.1", releases[0].tag)
        assertEquals(AppVersion(1, 0, 1), releases[0].version)
        assertEquals("拾课 v1.0.1", releases[0].title)
        assertEquals("2026-11-09T07:42:04Z", releases[0].updated)
        assertEquals("v0.1.24", releases[1].tag)
    }

    @Test
    fun `版本号不合规的 tag 被跳过`() {
        val releases = parseReleaseAtom(sampleAtom)
        assertTrue(releases.none { it.tag == "nightly" })
    }

    @Test
    fun `content 已解 XML 转义得到真正的 HTML`() {
        val notes = parseReleaseAtom(sampleAtom).first().notesHtml
        assertEquals("<p>加入了网盘文件的管理 &amp; 修复播放</p>", notes)
    }

    @Test
    fun `feed 级别的标题不会混进 entry`() {
        val releases = parseReleaseAtom(sampleAtom)
        assertTrue(releases.none { it.title == "Release notes from baidu_shike" })
    }

    @Test
    fun `没有 releases tag 链接的 entry 被忽略`() {
        val xml = """
            <feed><entry><title>v9.9.9</title><content type="html">&lt;p&gt;x&lt;/p&gt;</content></entry></feed>
        """.trimIndent()
        assertTrue(parseReleaseAtom(xml).isEmpty())
        assertTrue(parseReleaseAtom("not xml at all").isEmpty())
    }

    @Test
    fun `xmlUnescape 处理命名实体与数字实体`() {
        assertEquals("<p>A & B</p>", xmlUnescape("&lt;p&gt;A &amp; B&lt;/p&gt;"))
        assertEquals("'\"", xmlUnescape("&#39;&quot;"))
        assertEquals(" ", xmlUnescape("&nbsp;"))
        assertEquals("中", xmlUnescape("&#x4E2D;"))
        assertEquals("A", xmlUnescape("&#65;"))
        assertEquals("&unknown;", xmlUnescape("&unknown;"))
    }

    @Test
    fun `stripHtmlNotes 保留换行并解掉 HTML 实体`() {
        val html = "<h2>What's Changed</h2><ul><li>修复 A &amp; B</li><li><br>新增 C</li></ul>"
        assertEquals("What's Changed\n修复 A & B\n新增 C", stripHtmlNotes(html))
        assertEquals("", stripHtmlNotes(""))
        assertEquals("", stripHtmlNotes("   \n  "))
    }

    // ---------- 候选筛选 ----------

    @Test
    fun `候选版本按降序且只保留比当前新的`() {
        val entries = listOf(
            AtomRelease("v1.0.1", AppVersion(1, 0, 1), "", "", "2026-11-09T07:42:04Z"),
            AtomRelease("v0.1.21", AppVersion(0, 1, 21), "", "", "2026-11-01T00:00:00Z"),
            AtomRelease("v1.2.0", AppVersion(1, 2, 0), "", "", "2026-11-13T00:00:00Z"),
            AtomRelease("v1.10.0", AppVersion(1, 10, 0), "", "", "2026-11-14T00:00:00Z"),
        )
        val result = candidateReleases(entries, AppVersion(1, 0, 1))
        assertEquals(listOf("v1.10.0", "v1.2.0"), result.map { it.tag })
    }

    @Test
    fun `当前版本无法解析或无更新时返回空`() {
        val entry = AtomRelease("v1.2.0", AppVersion(1, 2, 0), "", "", "")
        assertTrue(candidateReleases(listOf(entry), null).isEmpty())
        assertTrue(candidateReleases(listOf(entry), AppVersion(1, 2, 0)).isEmpty())
        assertTrue(candidateReleases(listOf(entry), AppVersion(1, 3, 0)).isEmpty())
    }

    @Test
    fun `同名 tag 去重`() {
        val a = AtomRelease("v1.2.0", AppVersion(1, 2, 0), "", "", "2026-11-13T00:00:00Z")
        val b = a.copy(updated = "2026-11-14T00:00:00Z")
        assertEquals(1, candidateReleases(listOf(a, b), AppVersion(1, 1, 0)).size)
    }

    // ---------- 链接拼装 ----------

    @Test
    fun `下载直链按 workflow 的固定命名规则拼装`() {
        assertEquals(
            "https://github.com/gjianbo/baidu_shike/releases/download/v1.2.0/baidu-shike-1.2.0-release.apk",
            apkDownloadUrl("v1.2.0", "1.2.0"),
        )
        assertEquals(
            "https://github.com/gjianbo/baidu_shike/releases/tag/v1.2.0",
            releasePageUrl("v1.2.0"),
        )
    }

    // ---------- 节流与忽略 ----------

    @Test
    fun `自动检查 24 小时节流`() {
        val day = UPDATE_CHECK_INTERVAL_MS
        assertTrue(shouldAutoCheck(0L, day))
        assertFalse(shouldAutoCheck(1000L, day))
        assertTrue(shouldAutoCheck(1000L, day + 1000L))
        assertFalse(shouldAutoCheck(day + 5000L, 1000L)) // 时钟回拨
        assertFalse(shouldAutoCheck(0L, 0L))
    }

    @Test
    fun `忽略此版本只在自动检查时生效`() {
        assertFalse(shouldNotify("1.2.0", "1.2.0", force = false))
        assertTrue(shouldNotify("1.3.0", "1.2.0", force = false))
        assertTrue(shouldNotify("1.2.0", "1.2.0", force = true))
        assertTrue(shouldNotify("1.2.0", null, force = false))
    }

    @Test
    fun `下载百分比`() {
        assertEquals(-1, downloadPercent(100, 0))
        assertEquals(0, downloadPercent(0, 1000))
        assertEquals(50, downloadPercent(500, 1000))
        assertEquals(99, downloadPercent(999, 1000))
        assertEquals(100, downloadPercent(1000, 1000))
        assertEquals(100, downloadPercent(1200, 1000))
    }

    @Test
    fun `下载体积文案`() {
        assertEquals("0 KB", formatDownloadSize(0L))
        assertEquals("1 KB", formatDownloadSize(1536L))
        assertEquals("1.0 MB", formatDownloadSize(1024L * 1024L))
        assertEquals("34.9 MB", formatDownloadSize(34L * 1024L * 1024L + 960L * 1024L))
        assertEquals("--", formatDownloadSize(-1L))
    }
}
