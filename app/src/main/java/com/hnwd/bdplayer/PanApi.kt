package com.hnwd.bdplayer

import android.net.Uri
import java.net.URLEncoder
import org.json.JSONArray
import org.json.JSONObject

internal data class PanEntry(
    val gid: String,
    val messageId: String,
    val owner: String,
    val fileId: String,
    val name: String,
    val path: String,
    val size: String,
    val directory: Boolean,
)

/** 文档《媒体取址技术方案》§2：按扩展名分流后的媒体类别。 */
internal enum class MediaKind { VIDEO, AUDIO, PDF, DOCUMENT, DIRECTORY, OTHER }

internal object PanApi {
    const val HOST = "pan.baidu.com"
    const val MOBILE_HOME = "https://pan.baidu.com/wap/home#/"
    const val CONTACTS = "https://pan.baidu.com/disk/main#/im/addrBook"

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

    fun children(entry: PanEntry, page: Int) = api(
        "/mbox/msg/shareinfo",
        "gid" to entry.gid, "msg_id" to entry.messageId, "from_uk" to entry.owner,
        "fs_id" to entry.fileId, "type" to "2", "page" to page.toString(),
        "num" to "100", "limit" to "100", "desc" to "1",
    )

    fun viewer() = api("/api/gettemplatevariable", "fields" to "[\"uk\"]")

    fun groupListSession() = api("/api/gettemplatevariable", "fields" to "[\"uk\",\"bdstoken\"]")

    fun groups(token: String, start: Int, limit: Int) = api(
        "/mbox/group/list", "channel" to "chunlei", "bdstoken" to token,
        "start" to start.toString(), "limit" to limit.toString(),
    )

    fun mediaLinkRequest() = api("/api/sharedownload", "sign" to "", "timestamp" to "")

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

    fun isFileLink(uri: Uri): Boolean =
        uri.scheme == "https" && uri.host == "d.pcs.baidu.com" && uri.path.orEmpty().startsWith("/file/")

    // 群目录根接口 listshare 返回的 path 整条是百分号编码（%2F%E5%B7%A5%E4%BD%9C...），
    // recentv2 / shareinfo 返回的是明文中文。只在确认为编码路径时解码，
    // 避免误伤明文路径里合法的 % 或 +（Uri.decode 不会把 + 当空格）。
    fun readablePath(raw: String): String =
        if (raw.startsWith("%2F", ignoreCase = true)) Uri.decode(raw) else raw

    fun videoPlaylist(entry: PanEntry): Uri = Uri.parse(api(
        "/mbox/msg/streaming",
        "from_uk" to entry.owner, "to" to entry.gid, "msg_id" to entry.messageId,
        "fs_id" to entry.fileId, "type" to "2", "stream_type" to "M3U8_AUTO_480",
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
            item.getString("isdir") == "1",
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
