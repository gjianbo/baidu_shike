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

internal object PanApi {
    const val HOST = "pan.baidu.com"
    const val MOBILE_HOME = "https://pan.baidu.com/wap/home#/"
    const val CONTACTS = "https://pan.baidu.com/disk/main#/im/addrBook"

    val login: String = Uri.parse("https://wappass.baidu.com/passport?login")
        .buildUpon()
        .appendQueryParameter("authsite", "1")
        .appendQueryParameter("tpl", "netdisk")
        .appendQueryParameter("overseas", "1")
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

    fun roots(gid: String) = api(
        "/mbox/group/listshare", "gid" to gid, "type" to "2", "limit" to "50", "desc" to "1",
    )

    fun children(entry: PanEntry, page: Int) = api(
        "/mbox/msg/shareinfo",
        "gid" to entry.gid, "msg_id" to entry.messageId, "from_uk" to entry.owner,
        "fs_id" to entry.fileId, "type" to "2", "page" to page.toString(),
        "num" to "100", "limit" to "100", "desc" to "1",
    )

    fun viewer() = api("/api/gettemplatevariable", "fields" to "[\"uk\"]")

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

    fun isVideoPlaylist(uri: Uri): Boolean =
        uri.scheme == "https" && uri.host == HOST && uri.path == "/mbox/msg/streaming" &&
            uri.getQueryParameter("stream_type").orEmpty().startsWith("M3U8_AUTO_") &&
            uri.getQueryParameter("nom3u8") == null

    fun video(entry: PanEntry) = url(
        "/pfile/mboxvideo",
        "type" to "2", "to_ciduk" to "", "from_ciduk" to "", "to" to entry.gid,
        "from_uk" to entry.owner, "msg_id" to entry.messageId, "from_doc_lib" to "true",
        "fs_id" to entry.fileId, "path" to entry.path, "name" to entry.name,
        "selection_cursor" to System.currentTimeMillis().toString(), "imTransferBlock" to "",
        "download_source" to "im", "size" to entry.size,
    )

    fun parseRecent(json: JSONObject): List<PanEntry> =
        json.getJSONObject("data").getJSONArray("recentlist").objects().map { item ->
            entry(item, item.getString("gid"), item.getString("msg_id"), item.getString("fuk"), "fsid")
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
            item.getString("path"), item.optString("size", meta?.optString("size") ?: ""),
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
