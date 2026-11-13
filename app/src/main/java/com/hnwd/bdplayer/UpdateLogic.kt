package com.hnwd.bdplayer

/**
 * 版本检测里不依赖 Android / OkHttp 的纯逻辑：Atom 解析、语义化版本比较、
 * 候选筛选、下载直链拼装、更新说明清洗、自动检查节流与忽略判定。
 *
 * UpdateManager 只负责发请求 / 下载 / 验签 / 安装，所有判定落在这里，便于 JVM 单测。
 *
 * 版本源刻意选 releases.atom 而不是 api.github.com：后者匿名调用有 60 次/小时/IP
 * 的限流（实测当前出口 IP 直接 403），而 Atom 是 github.com 的静态 feed。
 * 另外 GitHub 的 /releases/latest 不等于「版本号最大」（实测指向带 Latest 标记的
 * v0.1.21，而 Atom 里已有 v1.0.1），所以必须自己按版本号排序 + 探测 APK 是否存在。
 */

internal const val UPDATE_REPO_OWNER = "gjianbo"
internal const val UPDATE_REPO_NAME = "baidu_shike"
internal const val UPDATE_ATOM_URL =
    "https://github.com/$UPDATE_REPO_OWNER/$UPDATE_REPO_NAME/releases.atom"
internal const val UPDATE_APK_NAME_PREFIX = "baidu-shike-"
internal const val UPDATE_APK_NAME_SUFFIX = "-release.apk"
internal const val UPDATE_CHECK_INTERVAL_MS = 24L * 60 * 60 * 1000
internal const val UPDATE_MAX_PROBE = 5

/** 语义化版本，只取前三段数字；tag 形如 v1.2.0，versionName 形如 1.2.0。 */
internal data class AppVersion(val major: Int, val minor: Int, val patch: Int) :
    Comparable<AppVersion> {
    override fun compareTo(other: AppVersion): Int = when {
        major != other.major -> major.compareTo(other.major)
        minor != other.minor -> minor.compareTo(other.minor)
        else -> patch.compareTo(other.patch)
    }

    override fun toString(): String = "$major.$minor.$patch"
}

private val VERSION_RE = Regex("""[vV]?(\d+)\.(\d+)\.(\d+)""")

internal fun parseAppVersion(text: String): AppVersion? =
    VERSION_RE.find(text)?.let { match ->
        runCatching {
            AppVersion(
                match.groupValues[1].toInt(),
                match.groupValues[2].toInt(),
                match.groupValues[3].toInt(),
            )
        }.getOrNull()
    }

internal data class AtomRelease(
    val tag: String,
    val version: AppVersion,
    val title: String,
    val notesHtml: String,
    val updated: String,
)

private val ENTRY_RE = Regex("""<entry\b[^>]*>(.*?)</entry>""", RegexOption.DOT_MATCHES_ALL)
private val TAG_LINK_RE = Regex("""href="[^"]*/releases/tag/([^"/?#]+)"""")
private val TITLE_RE = Regex("""<title[^>]*>(.*?)</title>""", RegexOption.DOT_MATCHES_ALL)
private val CONTENT_RE = Regex("""<content[^>]*>(.*?)</content>""", RegexOption.DOT_MATCHES_ALL)
private val UPDATED_RE = Regex("""<updated>(.*?)</updated>""", RegexOption.DOT_MATCHES_ALL)
private val ENTITY_RE = Regex("""&(#x[0-9a-fA-F]+|#[0-9]+|[a-zA-Z]+);""")

private val NAMED_ENTITIES = mapOf(
    "lt" to "<", "gt" to ">", "amp" to "&", "quot" to "\"", "apos" to "'",
    "nbsp" to " ",
)

/** 解 Atom 的 XML 转义；content 里是二次转义的 HTML，[/stripHtmlNotes] 会再解一层 HTML 实体。 */
internal fun xmlUnescape(text: String): String = ENTITY_RE.replace(text) { match ->
    val entity = match.groupValues[1]
    val code = when {
        entity.startsWith("#x") || entity.startsWith("#X") -> entity.substring(2).toIntOrNull(16)
        entity.startsWith("#") -> entity.substring(1).toIntOrNull()
        else -> null
    }
    when {
        code != null -> runCatching { String(Character.toChars(code)) }.getOrDefault(match.value)
        else -> NAMED_ENTITIES[entity] ?: match.value
    }
}

/**
 * 从 releases.atom 里抽 release。tag 只认 .../releases/tag/<tag> 链接，
 * 因为 release 的 <title> 可能是自定义标题（如「拾课 v1.0.1」）而不是 tag。
 * 解析不出版本号（例如 tag 叫 nightly）的条目直接跳过。
 */
internal fun parseReleaseAtom(xml: String): List<AtomRelease> =
    ENTRY_RE.findAll(xml).mapNotNull { entry ->
        val body = entry.groupValues[1]
        val tag = TAG_LINK_RE.find(body)?.groupValues?.get(1)?.let(::xmlUnescape)
            ?: return@mapNotNull null
        val version = parseAppVersion(tag) ?: return@mapNotNull null
        AtomRelease(
            tag = tag,
            version = version,
            title = TITLE_RE.find(body)?.groupValues?.get(1)
                ?.let(::xmlUnescape)?.trim().orEmpty(),
            notesHtml = CONTENT_RE.find(body)?.groupValues?.get(1)
                ?.let(::xmlUnescape).orEmpty(),
            updated = UPDATED_RE.find(body)?.groupValues?.get(1)?.trim().orEmpty(),
        )
    }.toList()

/** 按版本号降序，返回严格新于 [current] 的 release；同名 tag 去重。 */
internal fun candidateReleases(
    entries: List<AtomRelease>,
    current: AppVersion?,
): List<AtomRelease> {
    if (current == null) return emptyList()
    return entries
        .filter { it.version > current }
        .distinctBy { it.tag }
        .sortedWith(
            compareByDescending<AtomRelease> { it.version }
                .thenByDescending { it.updated }
        )
}

/** workflow 里 APK 文件名固定为 baidu-shike-<versionName>-release.apk，tag 固定为 v<versionName>。 */
internal fun apkDownloadUrl(tag: String, version: String): String =
    "https://github.com/$UPDATE_REPO_OWNER/$UPDATE_REPO_NAME/releases/download/$tag/" +
        "$UPDATE_APK_NAME_PREFIX$version$UPDATE_APK_NAME_SUFFIX"

internal fun releasePageUrl(tag: String): String =
    "https://github.com/$UPDATE_REPO_OWNER/$UPDATE_REPO_NAME/releases/tag/$tag"

private val BLOCK_BREAK_RE = Regex(
    """(?i)<\s*br\s*/?>|</\s*(p|li|h[1-6]|ul|ol|blockquote|div)\s*>"""
)
private val TAG_RE = Regex("""<[^>]*>""")

/** 把 --generate-notes 生成的 HTML 说明压成几行纯文本；空说明交给调用方显示兜底文案。 */
internal fun stripHtmlNotes(html: String): String {
    if (html.isBlank()) return ""
    val withBreaks = BLOCK_BREAK_RE.replace(html, "\n")
    val plain = xmlUnescape(TAG_RE.replace(withBreaks, ""))
    return plain.lineSequence()
        .map { it.trim() }
        .filter { it.isNotEmpty() }
        .joinToString("\n")
        .trim()
}

/** 启动自动检查的 24h 节流；时钟回拨（差值为负）当作「还没到时间」。 */
internal fun shouldAutoCheck(
    lastCheckAt: Long,
    now: Long,
    intervalMs: Long = UPDATE_CHECK_INTERVAL_MS,
): Boolean = now - lastCheckAt >= intervalMs

/** 「忽略此版本」只在非手动检查时生效；出了更新的新版本后仍会提示。 */
internal fun shouldNotify(latestVersion: String, ignoredVersion: String?, force: Boolean): Boolean =
    force || ignoredVersion != latestVersion

/** total 未知时返回 -1，由 UI 显示「下载中…」而不是百分比。 */
internal fun downloadPercent(done: Long, total: Long): Int = when {
    total <= 0L -> -1
    done >= total -> 100
    else -> ((done * 100) / total).toInt().coerceIn(0, 99)
}

/** 下载进度里的体积文案；手写而不用 String.format，避免依赖默认 Locale。 */
internal fun formatDownloadSize(bytes: Long): String {
    if (bytes < 0L) return "--"
    val kb = 1024L
    val mb = kb * 1024L
    return when {
        bytes < mb -> "${bytes / kb} KB"
        else -> {
            val whole = bytes / mb
            val tenth = (bytes % mb) * 10 / mb
            "$whole.$tenth MB"
        }
    }
}
