package com.hnwd.bdplayer

import okhttp3.Call
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.json.JSONException
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

internal data class ExtensionReleaseSource(val repository: String, val apkName: String) {
    val latestUrl: String get() = "https://api.github.com/repos/$repository/releases/latest"
    val latestPage: String get() = "https://github.com/$repository/releases/latest"

    fun asset(tag: String, bytes: Long): ExtensionRelease? {
        if (!Regex("""v[0-9]+\.[0-9]+\.[0-9]+""").matches(tag) || bytes <= 0) return null
        return ExtensionRelease(tag.removePrefix("v"), bytes,
            "https://github.com/$repository/releases/download/$tag/$apkName")
    }

    fun findAsset(release: JSONObject): ExtensionRelease? {
        val tag = release.optString("tag_name")
        if (release.optBoolean("draft") || release.optBoolean("prerelease")) return null
        val assets = release.optJSONArray("assets") ?: return null
        for (index in 0 until assets.length()) {
            val asset = assets.optJSONObject(index) ?: continue
            val size = asset.optLong("size")
            if (asset.optString("name") == apkName && asset.optString("state") == "uploaded" && size > 0) {
                return asset(tag, size)
            }
        }
        return null
    }
}

internal data class ExtensionRelease(val version: String, val bytes: Long, val apkUrl: String)

internal class ExtensionReleaseClient(private val http: OkHttpClient) {
    companion object {
        val github = ExtensionReleaseClient(
            OkHttpClient.Builder()
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(30, TimeUnit.SECONDS)
                .followSslRedirects(false)
                .build(),
        )
    }

    private val lookupClient = http.newBuilder().callTimeout(15, TimeUnit.SECONDS).build()

    class Requests {
        private var active: Call? = null
        private var cancelled = false

        @Synchronized
        fun cancel() {
            cancelled = true
            active?.cancel()
        }

        fun <T> execute(client: OkHttpClient, request: Request, read: (Response) -> T): T {
            val call = client.newCall(request)
            synchronized(this) {
                if (cancelled) throw IOException("操作已取消")
                active = call
            }
            try {
                return call.execute().use(read)
            } finally {
                synchronized(this) { active = null }
            }
        }
    }

    fun latest(source: ExtensionReleaseSource, requests: Requests = Requests()): ExtensionRelease =
        try {
            latestFromApi(source, requests)
        } catch (_: IOException) {
            latestFromPage(source, requests)
        } catch (_: JSONException) {
            latestFromPage(source, requests)
        }

    private fun latestFromApi(source: ExtensionReleaseSource, requests: Requests): ExtensionRelease {
        val request = Request.Builder().url(source.latestUrl)
            .header("Accept", "application/vnd.github+json").build()
        return requests.execute(lookupClient, request) { response ->
            if (!response.isSuccessful) throw IOException("查询扩展发布失败（HTTP ${response.code}）")
            val body = response.body ?: throw IOException("扩展发布响应为空")
            checkNotNull(source.findAsset(JSONObject(body.string()))) { "发布页暂未提供扩展 APK" }
        }
    }

    private fun latestFromPage(source: ExtensionReleaseSource, requests: Requests): ExtensionRelease {
        val request = Request.Builder().url(source.latestPage).head().build()
        val candidate = requests.execute(lookupClient, request) { response ->
            val url = response.request.url
            val prefix = "/${source.repository}/releases/tag/"
            check(response.isSuccessful && url.host == "github.com" && url.encodedPath.startsWith(prefix)) {
                if (response.code == 404 || response.isSuccessful)
                    "暂未发布可下载的扩展"
                else "查询扩展发布失败（HTTP ${response.code}）"
            }
            checkNotNull(source.asset(url.encodedPath.removePrefix(prefix), 1)) { "扩展发布标签无效" }
        }
        val probe = Request.Builder().url(candidate.apkUrl)
            .header("Range", "bytes=0-0").header("Accept-Encoding", "identity").build()
        return requests.execute(lookupClient, probe) { response ->
            check(response.code != 404) { "发布页暂未提供扩展 APK" }
            check(response.code == 200 || response.code == 206) { "查询安装包失败（HTTP ${response.code}）" }
            val size = if (response.code == 206) {
                val range = response.header("Content-Range").orEmpty()
                Regex("""bytes 0-0/([0-9]+)""").matchEntire(range)?.groupValues?.get(1)?.toLongOrNull()
            } else response.body?.contentLength()
            check(size != null && size > 0) { "无法读取扩展安装包大小" }
            candidate.copy(bytes = size)
        }
    }

    fun download(release: ExtensionRelease, destination: File, requests: Requests = Requests(), progress: (Int) -> Unit) {
        var offset = destination.length()
        if (offset > release.bytes) {
            check(destination.delete()) { "无法重置安装包下载" }
            offset = 0
        }
        if (offset == release.bytes) return
        check(destination.parentFile?.usableSpace?.let { it >= release.bytes - offset } == true) { "存储空间不足" }
        if (!transfer(release, destination, offset, requests, progress)) {
            transfer(release, destination, 0, requests, progress)
        }
    }

    private fun transfer(
        release: ExtensionRelease, destination: File, offset: Long,
        requests: Requests, progress: (Int) -> Unit,
    ): Boolean {
        val request = Request.Builder().url(release.apkUrl).header("Accept-Encoding", "identity")
        if (offset > 0) request.header("Range", "bytes=$offset-")
        return requests.execute(http, request.build()) { response ->
            if (offset > 0 && (response.code == 416 || response.code == 206 &&
                    response.header("Content-Range") != "bytes $offset-${release.bytes - 1}/${release.bytes}")) {
                return@execute false
            }
            val start = when (response.code) {
                200 -> 0L
                206 -> {
                    check(offset > 0) { "安装包续传范围与发布信息不一致" }
                    offset
                }
                else -> error("扩展下载失败（HTTP ${response.code}）")
            }
            val body = checkNotNull(response.body) { "扩展下载响应为空" }
            val contentLength = body.contentLength()
            check(contentLength == -1L || contentLength == release.bytes - start) { "安装包大小与发布信息不一致" }
            var done = start
            var last = downloadPercent(start, release.bytes) / 5 * 5
            progress(last)
            body.byteStream().use { input ->
                java.io.FileOutputStream(destination, start > 0).use { output ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        val count = input.read(buffer)
                        if (count == -1) break
                        check(count <= release.bytes - done) { "安装包大小与发布信息不一致" }
                        output.write(buffer, 0, count)
                        done += count
                        val percent = downloadPercent(done, release.bytes) / 5 * 5
                        if (percent != last) {
                            last = percent
                            progress(percent)
                        }
                    }
                }
            }
            check(done == release.bytes) { "安装包未完整下载，请重试" }
            true
        }
    }
}
