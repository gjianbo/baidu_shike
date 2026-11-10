package com.hnwd.bdplayer

import android.net.Uri
import android.util.Base64
import java.net.URLEncoder
import java.security.MessageDigest
import org.json.JSONArray
import org.json.JSONObject

internal enum class EntryOrigin { GROUP, DISK }

internal const val DISK_SOURCE_ID = "disk"
internal const val CONTACT_SOURCE_PREFIX = "contact-"

/** 来源所属网盘。首版只支持百度 / 夸克两种。 */
internal enum class SourceProvider { BAIDU, QUARK }

internal const val PREFIX_BAIDU = "baidu:"
internal const val PREFIX_QUARK = "quark:"

/** 带前缀的来源 id，供跨网盘混装时区分；裸 id 视为百度，保证老数据兼容。 */
internal fun qualifySourceId(provider: SourceProvider, rawId: String): String =
    when {
        rawId.startsWith(PREFIX_BAIDU) || rawId.startsWith(PREFIX_QUARK) -> rawId
        provider == SourceProvider.QUARK -> PREFIX_QUARK + rawId
        else -> PREFIX_BAIDU + rawId
    }

/** 剥掉 provider 前缀，得到可传给各网盘 API 的裸 id（百度 gid / 夸克 fid）。 */
internal fun rawSourceId(id: String): String =
    when {
        id.startsWith(PREFIX_BAIDU) -> id.removePrefix(PREFIX_BAIDU)
        id.startsWith(PREFIX_QUARK) -> id.removePrefix(PREFIX_QUARK)
        else -> id
    }

/** 从前缀推断所属网盘；无前缀（老数据）一律视为百度。 */
internal fun providerOfSourceId(id: String): SourceProvider =
    if (id.startsWith(PREFIX_QUARK)) SourceProvider.QUARK else SourceProvider.BAIDU

internal fun isContactSource(id: String) = rawSourceId(id).startsWith(CONTACT_SOURCE_PREFIX)

/**
 * 来源 id 合法性：带前缀时按前缀校验本体，无前缀时走老规则。
 * 百度：可选 contact- 前缀 + 1~24 位数字；夸克：8~64 位十六进制 fid。
 */
internal fun isShareSourceId(id: String): Boolean {
    val raw = rawSourceId(id)
    return when {
        id.startsWith(PREFIX_QUARK) -> raw.matches(Regex("[0-9a-fA-F]{8,64}"))
        raw.matches(Regex("(?:contact-)?[0-9]{1,24}")) -> true
        else -> false
    }
}

internal data class PanEntry(
    val gid: String,
    val messageId: String,
    val owner: String,
    val fileId: String,
    val name: String,
    val path: String,
    val size: String,
    val directory: Boolean,
    val origin: EntryOrigin = EntryOrigin.GROUP,
    val md5: String = "",
    /** 列表自带的免 Cookie 大图地址，缺失时可按文件取址。 */
    val previewUrl: String = "",
) {
    val sourceId: String get() = if (origin == EntryOrigin.DISK) DISK_SOURCE_ID else gid
    val isDisk: Boolean get() = origin == EntryOrigin.DISK
    // 百度个人网盘的 gid 为空；夸克个人网盘的条目（含已保存的收藏）使用 disk。
    val provider: SourceProvider get() =
        if (isDisk && gid == DISK_SOURCE_ID) SourceProvider.QUARK else providerOfSourceId(gid)
    val isContact: Boolean get() = isContactSource(gid)

    fun contactRecipient(viewer: String): String =
        if (owner == viewer) rawSourceId(gid).removePrefix(CONTACT_SOURCE_PREFIX) else viewer
}

private val thumbSize = Regex("([?&])size=[^&#]*")

/** 我的网盘 / 搜索 / 群文件响应里缩略图字段位置不统一，逐层容错找出可用的大图地址。 */
private fun previewUrlOf(item: JSONObject): String {
    // 群文件接口可能把对象再次序列化成 JSON 字符串。
    fun objectAt(container: JSONObject?, key: String): JSONObject? =
        container?.optJSONObject(key)
            ?: container?.optString(key)?.takeIf { it.trimStart().startsWith('{') }
                ?.let { runCatching { JSONObject(it) }.getOrNull() }
    val meta = objectAt(item, "meta")
    val thumbnails = listOfNotNull(objectAt(item, "thumb"), objectAt(item, "thumbs"), objectAt(meta, "thumbs"))
    val candidates = listOf("url3", "url2", "url1", "icon").flatMap { size ->
        thumbnails.map { it.optString(size) }
    } + listOf(
        item.optString("url3"), item.optString("thumbnail"),
        meta?.optString("url3"), meta?.optString("thumbs"),
        item.optJSONArray("thumbs")?.optString(0),
        item.optString("thumbs"), item.optString("thumb"),
    )
    val url = candidates.firstOrNull { it?.startsWith("https://") == true }.orEmpty()
    return PanApi.thumbResize(url, "c2560_u2560")
}

/**
 * 把夸克文件映射为统一条目。夸克侧没有消息/转存语义，messageId/owner 留空；
 * size 用字节数字符串，与百度侧保持一致由渲染层格式化。
 */
internal fun quarkEntry(
    file: QuarkFile,
    sourceId: String,
    origin: EntryOrigin = EntryOrigin.GROUP,
    parentPath: String = "",
): PanEntry = PanEntry(
    gid = sourceId,
    messageId = "",
    owner = "",
    fileId = file.id,
    name = file.name,
    path = if (origin == EntryOrigin.DISK) PanApi.diskPath(parentPath, file.name) else file.name,
    size = if (file.directory) "" else file.size.toString(),
    directory = file.directory,
    origin = origin,
    previewUrl = file.previewUrl,
)

internal data class PanSearchHit(
    val entry: PanEntry,
    val parentPath: String,
    val shareFsId: String,
)

/** 文档《媒体取址技术方案》§2：按扩展名分流后的媒体类别。 */
internal enum class MediaKind { VIDEO, AUDIO, IMAGE, PDF, DOCUMENT, DIRECTORY, OTHER }

internal fun mediaKind(name: String, directory: Boolean): MediaKind {
    if (directory) return MediaKind.DIRECTORY
    return when (name.substringAfterLast('.', "").lowercase()) {
        in VIDEO_EXT -> MediaKind.VIDEO
        in AUDIO_EXT -> MediaKind.AUDIO
        in ImageTypes.EXTENSIONS -> MediaKind.IMAGE
        "pdf" -> MediaKind.PDF
        in DOC_EXT -> MediaKind.DOCUMENT
        else -> MediaKind.OTHER
    }
}

private val VIDEO_EXT = setOf("mp4", "mkv", "mov", "avi", "flv", "wmv", "webm", "ts", "m4v")
private val AUDIO_EXT = setOf("mp3", "m4a", "aac", "flac", "wav", "wma", "ogg", "m4b", "amr")
private val DOC_EXT = setOf("doc", "docx", "ppt", "pptx", "xls", "xlsx", "txt", "zip", "rar", "7z")

internal object PanApi {
    const val HOST = "pan.baidu.com"
    const val MOBILE_HOME = "https://pan.baidu.com/wap/home#/"
    const val CONTACTS = "https://pan.baidu.com/disk/main#/im/addrBook"
    private const val SEARCH_SIGN_SALT = "D3BA5E6D3B16D9202E10DE5D662CFC15"

    /** §5.3 / §6.3：dlink 直链要带这个 UA 才 302 到 CDN；签名自带，不需要 Cookie。 */
    const val DLINK_USER_AGENT = "pan.baidu.com"

    fun kindOf(entry: PanEntry): MediaKind = mediaKind(entry.name, entry.directory)

    /** 百度缩略图的 size 不参与签名，原始查询参数的编码与顺序保留。 */
    fun thumbResize(raw: String, size: String): String =
        thumbSize.replace(raw) { "${it.groupValues[1]}size=$size" }

    /**
     * 登录页地址。
     *
     * 用 by lazy 而不是直接初始化：`Uri.parse` 是 Android API，放进对象初始化块会让
     * 整个 PanApi 在类加载时就依赖 android.net.Uri —— 纯函数（kindOf / md5Key / 路径拼接）
     * 因此无法在 JVM 单测里调用。
     */
    val login: String by lazy {
        Uri.parse("https://wappass.baidu.com/passport?login")
            .buildUpon()
            .appendQueryParameter("authsite", "1")
            .appendQueryParameter("tpl", "netdisk")
            .appendQueryParameter("smsLoginLink", "1")
            .appendQueryParameter("display", "mobile")
            .appendQueryParameter(
                "u",
                "https://pan.baidu.com/wap/home?loginform=pass&realName=1&wapBehaviorLog=wap_click_welcome_login&branchType=SMSlogin#/",
            )
            .fragment("/password_login")
            .build().toString()
    }

    fun recent(gid: String) = api(
        "/imbox/file/recentv2", "channel" to "chunlei", "id" to "1", "session_id" to "04_${rawSourceId(gid)}",
    )

    fun roots(gid: String, cursor: String = ""): String {
        if (isContactSource(gid)) {
            val query = mutableListOf("to_uk" to rawSourceId(gid).removePrefix(CONTACT_SOURCE_PREFIX), "type" to "2")
            if (cursor.isNotEmpty()) query.add("cursor" to cursor)
            return api("/mbox/msg/sessioninfo", *query.toTypedArray())
        }
        val query = mutableListOf("gid" to rawSourceId(gid), "type" to "2", "limit" to "50", "desc" to "1")
        if (cursor.isNotEmpty()) query.add("last_msg_time" to cursor)
        return api("/mbox/group/listshare", *query.toTypedArray())
    }

    fun children(entry: PanEntry, page: Int, directoryId: String = entry.fileId): String {
        return shareInfo(entry, page, directoryId)
    }

    fun imageShareInfo(entry: PanEntry): String = shareInfo(entry, 1, entry.fileId, "chunlei")

    private fun shareInfo(entry: PanEntry, page: Int, fileId: String, channel: String? = null): String {
        val query = mutableListOf(
            "msg_id" to entry.messageId, "from_uk" to entry.owner, "fs_id" to fileId,
            "page" to page.toString(), "num" to "100",
        )
        channel?.let { query += "channel" to it }
        if (entry.isContact) {
            query += listOf("type" to "1", "to_uk" to entry.contactRecipient(AccountScope.current))
        } else {
            query += listOf("type" to "2", "gid" to rawSourceId(entry.gid), "limit" to "100", "desc" to "1")
        }
        return api("/mbox/msg/shareinfo", *query.toTypedArray())
    }

    fun parseImageShareInfo(json: JSONObject, fileId: String): String {
        val records = json.optJSONArray("records") ?: return ""
        val file = records.optJSONObject(0) ?: return ""
        if (records.length() != 1 || file.optInt("isdir") == 1) return ""
        val id = file.optString("fs_id", file.optString("fsid"))
        return if (id.isEmpty() || id == fileId) previewUrlOf(file) else ""
    }

    fun searchGroup(gid: String, keyword: String): String {
        val data = JSONObject()
            .put("gid", JSONArray().put(gid))
            .put("key_word", keyword)
            .put("sign", searchSign(gid, keyword))
            .toString()
        return api("/basembox/group/search", "data" to data)
    }

    fun parseSearch(json: JSONObject, gid: String): List<PanSearchHit> =
        json.optJSONArray("result")?.objects().orEmpty().mapNotNull { item ->
            val group = item.optString("groupId").ifEmpty { gid }
            if (group != gid) return@mapNotNull null
            val fileId = item.optString("fsid").ifEmpty { item.optString("fs_id") }
            val name = item.optString("server_filename")
            val path = readablePath(item.optString("path"))
            if (fileId.isEmpty() || name.isEmpty() || path.isEmpty()) return@mapNotNull null
            PanSearchHit(
                entry = PanEntry(
                    gid = group,
                    messageId = item.optString("msgId").ifEmpty { item.optString("msg_id") },
                    owner = item.optString("uk").ifEmpty { item.optString("from_uk") },
                    fileId = fileId,
                    name = name,
                    path = path,
                    size = item.optString("size"),
                    directory = item.optInt("is_dir") == 1,
                ),
                parentPath = readablePath(item.optString("parent_path"))
                    .ifEmpty { path.substringBeforeLast('/') },
                shareFsId = item.optString("share_fs_id"),
            )
        }

    private fun searchSign(gid: String, keyword: String): String {
        val hex = MessageDigest.getInstance("MD5")
            .digest((SEARCH_SIGN_SALT + "_" + gid + keyword).toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }
        return Base64.encodeToString(hex.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
    }

    fun viewer() = api("/api/gettemplatevariable", "fields" to "[\"uk\",\"username\"]")

    fun groupListSession() = api("/api/gettemplatevariable", "fields" to "[\"uk\",\"bdstoken\"]")

    fun groups(token: String, start: Int, limit: Int) = api(
        "/mbox/group/list", "channel" to "chunlei", "bdstoken" to token,
        "start" to start.toString(), "limit" to limit.toString(),
    )

    fun contacts(token: String, start: Int, limit: Int) = api(
        "/mbox/relation/getfollowlist", "channel" to "chunlei", "bdstoken" to token,
        "start" to start.toString(), "limit" to limit.toString(),
    )

    fun mediaLinkRequest() = api("/api/sharedownload", "sign" to "", "timestamp" to "")

    fun mediaCaptchaRequest() = api(
        "/api/getcaptcha", "prod" to "share", "channel" to "chunlei", "bdstoken" to "",
    )

    data class MediaCaptcha(val image: Uri, val token: String)

    fun parseMediaCaptcha(json: JSONObject): MediaCaptcha? {
        val rawImage = sequenceOf("vcode_img", "img")
            .mapNotNull { json.opt(it) as? String }
            .firstOrNull { it.isNotBlank() } ?: return null
        val image = Uri.parse(
            when {
                rawImage.startsWith("//") -> "https:$rawImage"
                rawImage.startsWith("/") -> "https://$HOST$rawImage"
                rawImage.startsWith("http://") -> "https://" + rawImage.removePrefix("http://")
                else -> rawImage
            },
        )
        if (image.scheme != "https" || image.userInfo != null ||
            image.port !in listOf(-1, 443) ||
            image.host !in setOf(HOST, "passport.baidu.com", "wappass.baidu.com")
        ) return null
        val token = (json.opt("vcode_str") as? String)?.takeIf { it.isNotBlank() } ?: return null
        return MediaCaptcha(image, token)
    }

    fun mediaCaptchaForm(captcha: MediaCaptcha, answer: String): String =
        "vcode_input=${URLEncoder.encode(answer, "UTF-8")}&vcode_str=${URLEncoder.encode(captcha.token, "UTF-8")}"

    fun mediaLinkBody(entry: PanEntry, viewer: String): String {
        val extra = if (entry.isContact) {
            JSONObject().put("type", "single").put("from_uk", entry.owner)
                .put("to_uk", entry.contactRecipient(viewer))
        } else JSONObject().put("type", "group").put("gid", entry.gid)
        return listOf(
            "encrypt" to "0", "uk" to viewer, "product" to "mbox", "timestamp" to "",
            "sign" to "", "primaryid" to entry.messageId,
            "fid_list" to JSONArray().put(entry.fileId).toString(), "extra" to extra.toString(),
        ).joinToString("&") { (key, value) -> "$key=${URLEncoder.encode(value, "UTF-8")}" }
    }

    fun parseMediaLink(json: JSONObject, entry: PanEntry): Uri {
        val match = json.optJSONArray("list")?.objects()?.firstOrNull {
            it.optString("fs_id") == entry.fileId
        }
        val uri = Uri.parse(match?.optString("dlink").orEmpty())
        check(isFileLink(uri)) {
            "网盘未返回此文件的播放地址。"
        }
        return uri
    }

    /**
     * 网盘列表/搜索返回的 md5 常把第 10 位换成非十六进制字母做混淆，
     * 同一文件不同接口可能给出不同字母；比对时去掉这一位，其余 31 位 + size 足够定位。
     */
    fun md5Key(raw: String): String {
        val value = raw.trim().lowercase()
        if (value.length != 32) return value
        val marker = value[9]
        val hex = marker in '0'..'9' || marker in 'a'..'f'
        return if (hex) value else value.removeRange(9, 10)
    }

    fun isFileLink(uri: Uri): Boolean =
        uri.scheme == "https" && uri.host == "d.pcs.baidu.com" && uri.path.orEmpty().startsWith("/file/")

    fun isCachedFileLink(uri: Uri): Boolean =
        uri.scheme == "https" && uri.path.orEmpty().startsWith("/file/") &&
            (uri.host == "d.pcs.baidu.com" || uri.host?.endsWith(".baidupcs.com") == true)

    fun diskList(token: String, dir: String, page: Int, num: Int = 100) = api(
        "/api/list", "channel" to "chunlei", "bdstoken" to token, "dir" to dir,
        "order" to "time", "desc" to "1", "num" to num.toString(), "page" to page.toString(),
    )

    fun parseDiskList(json: JSONObject): List<PanEntry> =
        json.getJSONArray("list").objects().mapNotNull(::diskEntry)

    fun diskSearch(token: String, keyword: String, dir: String = "/", page: Int = 1, num: Int = 100) = api(
        "/api/search", "channel" to "chunlei", "bdstoken" to token, "dir" to dir,
        "recursion" to "1", "order" to "time", "desc" to "1", "showempty" to "0",
        "page" to page.toString(), "num" to num.toString(), "key" to keyword,
    )

    fun parseDiskSearch(json: JSONObject): List<PanSearchHit> =
        json.optJSONArray("list")?.objects().orEmpty().mapNotNull { item ->
            val entry = diskEntry(item) ?: return@mapNotNull null
            PanSearchHit(entry, entry.path.substringBeforeLast('/').ifEmpty { "/" }, entry.fileId)
        }

    private fun diskEntry(item: JSONObject): PanEntry? {
        val fileId = item.optString("fs_id")
        val name = item.optString("server_filename")
        val path = readablePath(item.optString("path"))
        if (fileId.isEmpty() || name.isEmpty() || path.isEmpty()) return null
        return PanEntry(
            gid = "", messageId = "", owner = "", fileId = fileId, name = name, path = path,
            size = item.optString("size"), directory = item.optInt("isdir") == 1,
            origin = EntryOrigin.DISK, md5 = item.optString("md5"),
            previewUrl = previewUrlOf(item),
        )
    }

    fun hasMore(json: JSONObject): Boolean =
        json.optBoolean("has_more") || json.optInt("has_more") != 0

    fun diskHasMore(json: JSONObject, num: Int): Boolean =
        json.getJSONArray("list").length() >= num || hasMore(json)

    fun diskVideoPlaylist(entry: PanEntry): Uri = diskStreaming(entry, "M3U8_AUTO_480", "")

    fun diskAudioPlaylist(entry: PanEntry, token: String): Uri =
        diskStreaming(entry, "M3U8_MP3_128", token)

    fun diskDownloadSession() = api(
        "/api/gettemplatevariable",
        "fields" to "[\"sign1\",\"sign2\",\"sign3\",\"timestamp\",\"is_vip\"]",
    )

    fun diskDownload(entry: PanEntry, token: String, sign: String, timestamp: String, vip: String) = api(
        "/api/download", "channel" to "chunlei", "bdstoken" to token, "sign" to sign,
        "timestamp" to timestamp, "fidlist" to "[${entry.fileId}]", "vip" to vip, "type" to "dlink",
    )

    fun parseDiskDownloadLink(json: JSONObject): Uri {
        val uri = Uri.parse(json.getJSONArray("dlink").getJSONObject(0).getString("dlink"))
        check(isFileLink(uri)) { "网盘未返回有效的原始文件地址。" }
        return uri
    }

    // ===================== 网盘文件管理（写操作） =====================
    // 所有写操作都在隐藏 WebView 内同源 fetch（Cookie 自动带），需要 bdstoken；
    // errno=0 视为成功。接口无契约，失败都要能回退成明确提示、不破坏已有缓存。

    /** 写操作请求：完整 URL + x-www-form-urlencoded 表单体。 */
    internal data class WriteRequest(val url: String, val form: String)

    /** 拼接网盘路径，dir 为 "/" 或空时返回根下的 name。 */
    fun diskPath(dir: String, name: String): String {
        val base = dir.trimEnd('/')
        return if (base.isEmpty()) "/$name" else "$base/$name"
    }

    /** 取父目录；"/a/b.txt" -> "/a"，"/a" -> "/"。 */
    fun parentPath(path: String): String =
        path.trimEnd('/').substringBeforeLast('/', "").ifEmpty { "/" }

    private fun form(vararg pairs: Pair<String, String>): String =
        pairs.joinToString("&") { (key, value) -> "$key=${URLEncoder.encode(value, "UTF-8")}" }

    /** fs_id 用数字提交更稳；非数字时退回字符串。 */
    private fun fsidValue(id: String): Any = id.toLongOrNull() ?: id

    private fun fsidArray(ids: List<String>): JSONArray {
        val array = JSONArray()
        ids.forEach { id -> array.put(fsidValue(id)) }
        return array
    }

    /** 新建文件夹：POST /api/create，isdir=1。 */
    fun createFolder(token: String, path: String): WriteRequest = WriteRequest(
        api("/api/create", "channel" to "chunlei", "bdstoken" to token),
        form("path" to path, "isdir" to "1", "block_list" to "[]", "size" to "0"),
    )

    /** /api/filemanager 统一入口；filelist 为 JSON 数组字符串。 */
    fun fileManager(
        token: String, opera: String, filelist: String, ondup: String = "",
    ): WriteRequest = WriteRequest(
        api("/api/filemanager", "channel" to "chunlei", "bdstoken" to token, "opera" to opera),
        if (ondup.isEmpty()) form("async" to "0", "filelist" to filelist)
        else form("async" to "0", "filelist" to filelist, "ondup" to ondup),
    )

    fun renameItem(token: String, entry: PanEntry, newName: String): WriteRequest =
        fileManager(
            token, "rename",
            JSONArray().put(
                JSONObject().put("id", fsidValue(entry.fileId)).put("path", entry.path).put("newname", newName),
            ).toString(),
        )

    fun moveItem(token: String, entry: PanEntry, destDir: String): WriteRequest =
        fileManager(
            token, "move",
            JSONArray().put(
                JSONObject().put("id", fsidValue(entry.fileId)).put("path", entry.path)
                    .put("dest", destDir).put("newname", entry.name),
            ).toString(),
            ondup = "fail",
        )

    fun copyItem(token: String, entry: PanEntry, destDir: String, newName: String): WriteRequest =
        fileManager(
            token, "copy",
            JSONArray().put(
                JSONObject().put("path", entry.path).put("dest", destDir)
                    .put("newname", newName).put("ondup", "newcopy"),
            ).toString(),
            ondup = "newcopy",
        )

    fun moveItems(token: String, entries: List<PanEntry>, destDir: String): WriteRequest {
        val list = JSONArray()
        entries.forEach { entry ->
            list.put(
                JSONObject().put("id", fsidValue(entry.fileId)).put("path", entry.path)
                    .put("dest", destDir).put("newname", entry.name),
            )
        }
        return fileManager(token, "move", list.toString(), ondup = "fail")
    }

    fun copyItems(token: String, entries: List<PanEntry>, destDir: String): WriteRequest {
        val list = JSONArray()
        entries.forEach { entry ->
            list.put(
                JSONObject().put("path", entry.path).put("dest", destDir)
                    .put("newname", entry.name).put("ondup", "newcopy"),
            )
        }
        return fileManager(token, "copy", list.toString(), ondup = "newcopy")
    }

    /** 删除走后台任务；errno=0 只代表请求已受理。 */
    fun deleteItems(token: String, entries: List<PanEntry>): WriteRequest {
        val paths = JSONArray()
        entries.forEach { entry -> paths.put(entry.path) }
        return WriteRequest(
            api(
                "/api/filemanager", "channel" to "chunlei", "bdstoken" to token,
                "opera" to "delete", "async" to "2", "onnest" to "fail", "newVerify" to "1",
            ),
            form("filelist" to paths.toString()),
        )
    }

    /** filemanager 可能在外层成功时仍返回单项错误。 */
    fun writeErrno(json: JSONObject): Int {
        val errno = json.getInt("errno")
        if (errno != 0) return errno
        val items = json.optJSONArray("info") ?: return 0
        return (0 until items.length()).asSequence()
            .map { items.optJSONObject(it)?.optInt("errno") ?: 0 }
            .firstOrNull { it != 0 } ?: 0
    }

    /** 创建分享链接：POST /share/set。period：0 永久 / 1 天 / 7 天。 */
    fun shareSet(token: String, fileIds: List<String>, period: Int, pwd: String): WriteRequest =
        WriteRequest(
            api("/share/set", "channel" to "chunlei"),
            form(
                "fid_list" to fsidArray(fileIds).toString(), "schannel" to "4",
                "period" to period.toString(), "pwd" to pwd, "bdstoken" to token,
            ),
        )

    internal data class ShareInfo(val link: String, val pwd: String)

    fun parseShare(json: JSONObject): ShareInfo? {
        val sources = listOfNotNull(json, json.optJSONObject("data"))
        val link = sources.asSequence()
            .flatMap { obj -> sequenceOf("link", "shortlink", "shorturl", "url").map(obj::optString) }
            .firstOrNull { it.startsWith("http") } ?: return null
        val pwd = sources.asSequence()
            .flatMap { obj -> sequenceOf("pwd", "passwd", "extract_code").map(obj::optString) }
            .firstOrNull { it.isNotEmpty() }.orEmpty()
        return ShareInfo(link, pwd)
    }

    /**
     * 群文件转存到我的网盘（best-effort）。
     * 群文件不是公开分享链接，这里按 messageId/owner 走 /api/transfer，属于待设备回归的接口。
     */
    fun transferFromGroup(token: String, entry: PanEntry, destDir: String): WriteRequest = WriteRequest(
        api("/api/transfer", "channel" to "chunlei", "bdstoken" to token),
        form(
            "shareid" to entry.messageId, "from" to entry.owner,
            "ondup" to "newcopy", "async" to "2", "path" to destDir,
            "fsidlist" to fsidArray(listOf(entry.fileId)).toString(),
        ),
    )

    /** 写操作错误码 -> 中文提示（op 用于补充上下文）。 */
    fun writeError(op: String, errno: Int, json: JSONObject): String {
        val show = sequenceOf("show_msg", "error_msg", "message")
            .map { json.optString(it) }.firstOrNull { it.isNotBlank() }?.trim()?.take(60).orEmpty()
        val hint = when (errno) {
            0 -> ""
            -6 -> "登录态已失效，请重新登录百度网盘"
            -7, -8 -> "同名文件或文件夹已存在"
            -9 -> "文件不存在或已被移动"
            3, 31066 -> "文件名不合法（不能包含 / \\ : * ? \" < > | 等字符）"
            4 -> "网盘空间不足"
            12 -> "已有转存任务在进行，请稍后再试"
            111 -> "分享/转存任务已存在"
            132 -> "百度网盘要求安全验证（平台风控）"
            -20 -> "百度网盘拒绝了此次请求"
            else -> "网盘请求失败"
        }
        val base = if (hint.isEmpty()) "「$op」失败" else "$op：$hint"
        val tail = buildString {
            append("（错误码 $errno）")
            if (show.isNotEmpty()) append(" · 网盘提示：$show")
        }
        return base + tail
    }

    private fun diskStreaming(entry: PanEntry, streamType: String, token: String): Uri {
        val query = mutableListOf(
            "path" to entry.path, "fsid" to entry.fileId, "channel" to "chunlei",
            "type" to streamType, "isplayer" to "1", "check_blue" to "1",
            "adToken" to "", "src_id" to "1",
        )
        if (token.isNotEmpty()) query.add("bdstoken" to token)
        return Uri.parse(api("/api/streaming", *query.toTypedArray()))
    }

    fun isDiskStreaming(uri: Uri, entry: PanEntry): Boolean =
        uri.scheme == "https" && uri.host == HOST && uri.path == "/api/streaming" &&
            uri.getQueryParameter("path") == entry.path &&
            uri.getQueryParameter("fsid").orEmpty().let { it.isEmpty() || it == entry.fileId }

    fun diskDocview(entry: PanEntry, viewer: String) = api(
        "/rest/2.0/docview/dlink", "channel" to "chunlei", "scene" to "3", "fuk" to viewer,
        "path" to entry.path, "fsid" to entry.fileId, "file_type" to "pdf",
    )

    fun parseDocviewLink(json: JSONObject): Uri {
        check(json.optInt("errno", -1) == 0) { "网盘未返回文档地址。" }
        check(json.optInt("need_passwd", 0) == 0) { "该文档需要密码，当前版本暂不支持。" }
        val uri = Uri.parse(json.optString("dlink"))
        check(uri.scheme == "https" && !uri.host.isNullOrEmpty()) { "文档地址无效，请重试。" }
        return uri
    }

    fun groupPdfPreview(entry: PanEntry) = api(
        "/mbox/fileinfo/preview", "channel" to "chunlei",
    )

    fun groupPdfPreviewForm(entry: PanEntry): String = listOf(
        "fuk" to entry.owner, "scene" to "1", "gid" to rawSourceId(entry.gid),
        "fsid" to entry.fileId, "ftype" to "pdf", "password" to "",
    ).joinToString("&") { (key, value) -> "$key=${URLEncoder.encode(value, "UTF-8")}" }

    fun parseGroupPreviewLink(json: JSONObject): Uri? {
        val data = json.optJSONObject("data") ?: return null
        val uri = Uri.parse(data.optString("dlink"))
        return uri.takeIf { it.scheme == "https" && !it.host.isNullOrEmpty() }
    }

    // 群目录根接口 listshare 返回的 path 整条是百分号编码（%2F%E5%B7%A5%E4%BD%9C...），
    // recentv2 / shareinfo 返回的是明文中文。只在确认为编码路径时解码，
    // 避免误伤明文路径里合法的 % 或 +（Uri.decode 不会把 + 当空格）。
    fun readablePath(raw: String): String =
        if (raw.startsWith("%2F", ignoreCase = true)) Uri.decode(raw) else raw

    fun videoPlaylist(entry: PanEntry): Uri = playlist(entry, "M3U8_AUTO_480")

    fun audioPlaylist(entry: PanEntry): Uri = playlist(entry, "M3U8_MP3_128")

    private fun playlist(entry: PanEntry, streamType: String): Uri = Uri.parse(api(
        "/mbox/msg/streaming",
        "from_uk" to entry.owner,
        "to" to if (entry.isContact) entry.contactRecipient(AccountScope.current) else rawSourceId(entry.gid),
        "msg_id" to entry.messageId, "fs_id" to entry.fileId,
        "type" to if (entry.isContact) "1" else "2", "stream_type" to streamType,
    ))

    fun parseRecent(json: JSONObject): List<PanEntry> {
        val data = json.getJSONObject("data")
        // Some groups return an explicit JSON null when there are no recent updates.
        if (data.has("recentlist") && data.isNull("recentlist")) return emptyList()
        return data.getJSONArray("recentlist").objects().map { item ->
            entry(item, item.getString("gid"), item.getString("msg_id"), item.getString("fuk"), "fsid")
        }
    }

    fun parseRoots(json: JSONObject, gid: String): List<PanEntry> {
        val records = json.getJSONObject("records")
        if (isContactSource(gid)) {
            // 联系人文件按消息分组返回，同一份文件常出现在多条消息里（重复发送 / 多次转发）。
            // 去重必须跨消息做，否则列表里会出现两行同一个文件、点哪行都进同一个目录。
            // key 用「发送者 + 文件 id」：fs_id 只在自己网盘内唯一，不同发送者可能撞号。
            return records.getJSONArray("list").objects().flatMap { message ->
                val messageId = message.optString("msg_id")
                val owner = message.optString("from_uk")
                if (message.isNull("msg_id") || messageId.isBlank() ||
                    message.isNull("from_uk") || owner.isBlank()
                ) return@flatMap emptyList()
                listOfNotNull(
                    message.optJSONObject("filelist")?.optJSONArray("list"),
                    message.optJSONObject("xfilelist")?.optJSONArray("list"),
                    message.optJSONArray("file_list"),
                ).flatMap { it.objects() }
                    .distinctBy { it.getString("fs_id") }
                    .map { file -> entry(file, gid, messageId, owner) }
            }.distinctBy { it.owner to it.fileId }
        }
        return records.getJSONArray("msg_list").objects().flatMap { message ->
            message.getJSONArray("file_list").objects().map { file ->
                entry(file, gid, message.getString("msg_id"), message.getString("uk"))
            }
        }
    }

    fun parseChildren(json: JSONObject, parent: PanEntry): List<PanEntry> =
        json.getJSONArray("records").objects().map { file ->
            entry(file, parent.gid, parent.messageId, parent.owner)
        }

    private fun entry(
        item: JSONObject, gid: String, messageId: String, owner: String, idKey: String = "fs_id",
    ): PanEntry {
        val meta = item.optJSONObject("meta")
        return PanEntry(
            gid, messageId, owner, item.getString(idKey), item.getString("server_filename"),
            readablePath(item.getString("path")), item.optString("size", meta?.optString("size") ?: ""),
            item.getString("isdir") == "1", md5 = item.optString("md5", meta?.optString("md5") ?: ""),
            previewUrl = previewUrlOf(item),
        )
    }

    private fun JSONArray.objects() = List(length(), ::getJSONObject)

    private fun api(path: String, vararg query: Pair<String, String>) = url(
        path, "clienttype" to "0", "app_id" to "250528", "web" to "1", *query,
    )

    private fun url(path: String, vararg query: Pair<String, String>): String {
        val builder = Uri.Builder().scheme("https").authority(HOST).path(path)
        query.forEach { (key, value) -> builder.appendQueryParameter(key, value) }
        return builder.build().toString()
    }
}
