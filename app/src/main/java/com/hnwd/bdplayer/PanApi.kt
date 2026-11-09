package com.hnwd.bdplayer

import android.net.Uri
import android.util.Base64
import java.net.URLEncoder
import java.security.MessageDigest
import org.json.JSONArray
import org.json.JSONObject

internal enum class EntryOrigin { GROUP, DISK }

internal const val DISK_SOURCE_ID = "disk"

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
) {
    val sourceId: String get() = if (origin == EntryOrigin.DISK) DISK_SOURCE_ID else gid
    val isDisk: Boolean get() = origin == EntryOrigin.DISK
}

internal data class PanSearchHit(
    val entry: PanEntry,
    val parentPath: String,
    val shareFsId: String,
)

/** 文档《媒体取址技术方案》§2：按扩展名分流后的媒体类别。 */
internal enum class MediaKind { VIDEO, AUDIO, PDF, DOCUMENT, DIRECTORY, OTHER }

internal object PanApi {
    const val HOST = "pan.baidu.com"
    const val MOBILE_HOME = "https://pan.baidu.com/wap/home#/"
    const val CONTACTS = "https://pan.baidu.com/disk/main#/im/addrBook"
    private const val SEARCH_SIGN_SALT = "D3BA5E6D3B16D9202E10DE5D662CFC15"

    /** §5.3 / §6.3：dlink 直链要带这个 UA 才 302 到 CDN；签名自带，不需要 Cookie。 */
    const val DLINK_USER_AGENT = "pan.baidu.com"

    /**
     * §2 取址总决策表：目录 / 视频(HLS) / 音频(原始文件 dlink) / PDF(下载后阅读) /
     * 其它文档(只能 dlink，当前版本没做下载)。只按扩展名分类，不做网络请求。
     */
    fun kindOf(entry: PanEntry): MediaKind {
        if (entry.directory) return MediaKind.DIRECTORY
        return when (entry.name.substringAfterLast('.', "").lowercase()) {
            in VIDEO_EXT -> MediaKind.VIDEO
            in AUDIO_EXT -> MediaKind.AUDIO
            "pdf" -> MediaKind.PDF
            in DOC_EXT -> MediaKind.DOCUMENT
            else -> MediaKind.OTHER
        }
    }

    private val VIDEO_EXT = setOf("mp4", "mkv", "mov", "avi", "flv", "wmv", "webm", "ts", "m4v")
    private val AUDIO_EXT = setOf("mp3", "m4a", "aac", "flac", "wav", "wma", "ogg", "m4b", "amr")
    private val DOC_EXT = setOf(
        "doc", "docx", "ppt", "pptx", "xls", "xlsx", "txt", "zip", "rar", "7z",
        "jpg", "jpeg", "png", "gif", "webp", "bmp",
    )

    val login: String = Uri.parse("https://wappass.baidu.com/passport?login")
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

    fun recent(gid: String) = api(
        "/imbox/file/recentv2", "channel" to "chunlei", "id" to "1", "session_id" to "04_$gid",
    )

    fun roots(gid: String, cursor: String = ""): String {
        val query = mutableListOf("gid" to gid, "type" to "2", "limit" to "50", "desc" to "1")
        if (cursor.isNotEmpty()) query.add("last_msg_time" to cursor)
        return api("/mbox/group/listshare", *query.toTypedArray())
    }

    fun children(entry: PanEntry, page: Int, directoryId: String = entry.fileId) = api(
        "/mbox/msg/shareinfo",
        "gid" to entry.gid, "msg_id" to entry.messageId, "from_uk" to entry.owner,
        "fs_id" to directoryId, "type" to "2", "page" to page.toString(),
        "num" to "100", "limit" to "100", "desc" to "1",
    )

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

    fun viewer() = api("/api/gettemplatevariable", "fields" to "[\"uk\"]")

    fun groupListSession() = api("/api/gettemplatevariable", "fields" to "[\"uk\",\"bdstoken\"]")

    fun groups(token: String, start: Int, limit: Int) = api(
        "/mbox/group/list", "channel" to "chunlei", "bdstoken" to token,
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
        val extra = JSONObject().put("type", "group").put("gid", entry.gid)
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
        json.optJSONArray("list")?.objects().orEmpty().mapNotNull(::diskEntry)

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
        )
    }

    fun diskHasMore(json: JSONObject, num: Int): Boolean =
        json.optJSONArray("list")?.length() == num || json.optInt("has_more") == 1

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
        "fuk" to entry.owner, "scene" to "1", "gid" to entry.gid,
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
        "from_uk" to entry.owner, "to" to entry.gid, "msg_id" to entry.messageId,
        "fs_id" to entry.fileId, "type" to "2", "stream_type" to streamType,
    ))

    fun parseRecent(json: JSONObject): List<PanEntry> {
        val data = json.getJSONObject("data")
        // Some groups return an explicit JSON null when there are no recent updates.
        if (data.has("recentlist") && data.isNull("recentlist")) return emptyList()
        return data.getJSONArray("recentlist").objects().map { item ->
            entry(item, item.getString("gid"), item.getString("msg_id"), item.getString("fuk"), "fsid")
        }
    }

    fun parseRoots(json: JSONObject, gid: String): List<PanEntry> =
        json.getJSONObject("records").getJSONArray("msg_list").objects().flatMap { message ->
            message.getJSONArray("file_list").objects().map { file ->
                entry(file, gid, message.getString("msg_id"), message.getString("uk"))
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
