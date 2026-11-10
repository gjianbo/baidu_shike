package com.hnwd.bdplayer

import android.net.Uri
import android.webkit.CookieManager
import java.net.HttpURLConnection
import java.net.URL
import org.json.JSONArray
import org.json.JSONObject

internal data class QuarkFile(
    val id: String,
    val name: String,
    val directory: Boolean,
    val size: Long = 0,
    /** 更新时间（毫秒），取 l_updated_at 退化到 l_created_at；用于「最新更新」客户端排序。 */
    val mtime: Long = 0,
)
internal data class QuarkPlayback(val uri: Uri, val hls: Boolean)
internal class QuarkLoginRequired : Exception("请登录夸克网盘后重试")
internal class QuarkMutationPending : Exception("网盘仍在处理，请稍后刷新确认结果")

internal class QuarkPersonalDrive private constructor(private val cookie: String) :
    PersonalDriveProtocol<QuarkFile, QuarkFile> {

    companion object {
        const val HOME = "https://pan.quark.cn/"
        const val ROOT = "0"
        private const val API = "https://drive-pc.quark.cn/1/clouddrive"

        // UC 账号登录成功后才会种到 .quark.cn 域的会话标记。
        // 匿名访问也会带 ctoken / b-user-id / _UP_* 等 Cookie，不能用「Cookie 非空」判断已登录。
        private val SESSION_COOKIES = setOf("__pus", "__puus")

        fun hasLoginCookie(cookie: String?): Boolean {
            if (cookie.isNullOrBlank()) return false
            return cookie.split(';').any { part ->
                val name = part.substringBefore('=').trim()
                val value = part.substringAfter('=', "").trim()
                name in SESSION_COOKIES && value.isNotEmpty()
            }
        }

        fun openSession(): QuarkPersonalDrive? =
            CookieManager.getInstance().getCookie("$API/file/sort")
                ?.takeIf { hasLoginCookie(it) }?.let(::QuarkPersonalDrive)
    }

    override fun list(directory: String, page: Int, size: Int) =
        read("file/sort", page, size, "pdir_fid" to directory, "fetch_all_file" to "1", "_fetch_sub_dirs" to "0")

    override fun search(keyword: String, page: Int, size: Int) =
        read("file/search", page, size, "q" to keyword, "_is_hl" to "0")

    private fun read(path: String, page: Int, size: Int, vararg query: Pair<String, String>): DriveRead<QuarkFile> {
        return DriveRead(url(path, "_page" to "$page", "_size" to "$size", "_fetch_total" to "1",
            "_sort" to "file_type:asc,file_name:asc", *query)) { json ->
            val data = json.getJSONObject("data")
            val list = data.getJSONArray("list")
            val items = List(list.length()) { index ->
                val item = list.getJSONObject(index)
                QuarkFile(
                    id = item.getString("fid"),
                    name = item.getString("file_name"),
                    directory = item.getBoolean("dir"),
                    size = item.optLong("size", 0L),
                    mtime = item.optLong("l_updated_at", 0L).takeIf { it > 0 }
                        ?: item.optLong("l_created_at", 0L),
                )
            }
            val metadata = data.optJSONObject("metadata")
            val actualSize = metadata?.optInt("_size", size)?.takeIf { it > 0 } ?: size
            val total = metadata?.optLong("_total", -1) ?: -1
            val hasMore = items.isNotEmpty() &&
                if (total >= 0) (page - 1L) * actualSize + items.size < total else items.size >= actualSize
            DrivePage(items, hasMore)
        }
    }

    fun create(parent: String, name: String) {
        complete(post("file", JSONObject().put("pdir_fid", parent).put("file_name", name)
            .put("dir_path", "").put("dir_init_lock", false)))
    }

    fun rename(file: QuarkFile, name: String) {
        post("file/rename", JSONObject().put("fid", file.id).put("file_name", name))
    }

    fun move(file: QuarkFile, parent: String) {
        complete(post("file/move", mutation(file).put("to_pdir_fid", parent)))
    }

    fun delete(file: QuarkFile) {
        complete(post("file/delete", mutation(file)))
    }

    private fun mutation(file: QuarkFile) = JSONObject().put("action_type", 1)
        .put("filelist", JSONArray().put(file.id)).put("exclude_fids", JSONArray())

    private fun complete(data: JSONObject) {
        if (!data.optBoolean("finish", false)) throw QuarkMutationPending()
    }

    fun playback(file: QuarkFile, video: Boolean): QuarkPlayback {
        if (!video) return QuarkPlayback(download(file), false)
        val data = post("file/v2/play", JSONObject().put("fid", file.id)
            .put("resolutions", "normal,low,high,super,2k,4k").put("supports", "fmp4,m3u8"))
        val variants = data.getJSONArray("video_list")
        val preferred = data.optString("default_resolution")
        val playable = (0 until variants.length()).map { variants.getJSONObject(it) }
            .filter { it.optBoolean("accessable", true) && it.optJSONObject("video_info")
                ?.let { info -> info.optString("url").isNotBlank() && info.optBoolean("success", true) } == true }
        val info = (playable.firstOrNull { it.optString("resolution") == preferred } ?: playable.firstOrNull())
            ?.getJSONObject("video_info") ?: error("当前没有可播放的视频地址")
        val uri = signedUri(info.getString("url"))
        val type = info.optString("hls_type")
        return QuarkPlayback(uri, (type.isNotBlank() && type != "none") ||
            uri.path.orEmpty().endsWith(".m3u8", ignoreCase = true))
    }

    fun download(file: QuarkFile): Uri {
        val items = request(url("file/download"), JSONObject().put("fids", JSONArray().put(file.id)))
            .getJSONArray("data")
        val item = (0 until items.length()).map { items.getJSONObject(it) }
            .firstOrNull { it.optString("fid") == file.id } ?: error("网盘未返回原文件地址")
        return signedUri(item.getString("download_url"))
    }

    private fun signedUri(value: String): Uri {
        val uri = Uri.parse(value)
        val host = uri.host.orEmpty().lowercase()
        check(uri.scheme == "https" && host.endsWith(".quark.cn") && uri.userInfo == null) {
            "网盘返回了无效的文件地址"
        }
        return uri
    }

    private fun url(path: String, vararg query: Pair<String, String>): String =
        Uri.parse("$API/$path").buildUpon().appendQueryParameter("pr", "ucpro")
            .appendQueryParameter("fr", "pc").apply {
                query.forEach { (key, value) -> appendQueryParameter(key, value) }
            }.build().toString()

    private fun post(path: String, body: JSONObject): JSONObject =
        request(url(path), body).getJSONObject("data")

    fun <T> execute(read: DriveRead<T>): DrivePage<T> = read.decode(request(read.url))

    private fun request(url: String, body: JSONObject? = null): JSONObject {
        require(url.startsWith("$API/"))
        val connection = URL(url).openConnection() as HttpURLConnection
        try {
            connection.instanceFollowRedirects = false
            connection.connectTimeout = 12_000
            connection.readTimeout = 15_000
            connection.setRequestProperty("Cookie", cookie)
            connection.setRequestProperty("Accept", "application/json")
            if (body != null) {
                connection.requestMethod = "POST"
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", "application/json")
                connection.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            }
            val status = connection.responseCode
            if (status == 401) throw QuarkLoginRequired()
            val stream = if (status in 200..299) connection.inputStream else connection.errorStream
            val json = stream?.bufferedReader()?.use { reader ->
                runCatching { JSONObject(reader.readText()) }.getOrNull()
            }
            val code = json?.optInt("code", -1) ?: -1
            if (code == 21001) throw QuarkLoginRequired()
            if (code == 21002) error("视频正在转码，请稍后重试")
            val details = if (code == -1) "" else "，错误码 $code"
            check(status in 200..299 && code == 0 && json != null) {
                "夸克请求失败（HTTP $status$details）"
            }
            return json
        } finally {
            connection.disconnect()
        }
    }
}
