package com.hnwd.bdplayer

import android.content.Context
import android.webkit.CookieManager
import android.webkit.WebSettings
import okhttp3.Call
import okhttp3.FormBody
import okhttp3.MediaType
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okio.BufferedSink
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.net.URLEncoder
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * 百度网盘上传的「数据面」：所有 HTTP 都走原生 OkHttp，不经过隐藏 WebView。
 *
 * 实测契约（2026-11-12 真机 WebView 会话端到端跑通）：
 * - api 系列接口在 pan.baidu.com 同源，Cookie 由 `.baidu.com` 自动带；
 * - pcs 上传接口 **必须**走 `locateupload` 返回的动态域名（如 c.pcs.baidu.com），
 *   pan.baidu.com 下该路径根本不通；
 * - `superfile2` 的 path / uploadid / partseq / bdstoken 必须放 **query**，
 *   multipart body 只放 `file`，否则 400 / 31023 param error；
 * - `create` 的 block_list 用 superfile2 返回的 md5，不是本地算的。
 *
 * 与 [UploadLogic]（纯规划）和 [UploadSection]（编排）严格分开，便于单点替换。
 */
internal class PanUploader(private val context: Context) {
    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .writeTimeout(90, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()
    private val currentCall = AtomicReference<Call?>(null)
    private val userAgent = WebSettings.getDefaultUserAgent(context)

    private val cookie: String
        get() = CookieManager.getInstance().getCookie("https://pan.baidu.com").orEmpty()

    fun cancel() {
        currentCall.getAndSet(null)?.cancel()
    }

    private fun Request.Builder.common(pcs: Boolean = false): Request.Builder {
        header("User-Agent", userAgent)
        header("Cookie", cookie)
        if (!pcs) header("Referer", "https://pan.baidu.com/disk/main")
        return this
    }

    private fun enc(value: String): String = URLEncoder.encode(value, "UTF-8")

    private fun execute(request: Request): JSONObject {
        val call = client.newCall(request)
        currentCall.set(call)
        try {
            call.execute().use { response ->
                val body = response.body?.string().orEmpty()
                check(response.isSuccessful) { "HTTP ${response.code}: ${body.take(160)}" }
                val json = runCatching { JSONObject(body) }
                    .getOrElse { throw IOException("网盘响应不是 JSON：${body.take(160)}") }
                val errno = json.optInt("errno", Int.MIN_VALUE)
                if (errno != Int.MIN_VALUE && errno != 0) {
                    throw IOException(UploadLogic.uploadError(errno, "网盘请求"))
                }
                return json
            }
        } finally {
            currentCall.compareAndSet(call, null)
        }
    }

    /** bdstoken + 账号等级；等级决定单文件上限。 */
    fun session(): Pair<String, Int> {
        android.util.Log.d("ShikeUpload", "cookieLen=${cookie.length}")
        val fields = enc("[\"bdstoken\",\"is_vip\"]")
        val request = Request.Builder()
            .url("https://pan.baidu.com/api/gettemplatevariable?$COMMON&fields=$fields")
            .get().common().build()
        return UploadLogic.parseSession(execute(request))
    }

    /** 动态上传域名；实测返回 host=c.pcs.baidu.com，每次可能不同。 */
    fun locateHost(): String {
        val request = Request.Builder()
            .url("https://d.pcs.baidu.com/rest/2.0/pcs/file?method=locateupload&$COMMON&channel=chunlei")
            .get().common(pcs = true).build()
        val json = execute(request)
        check(json.optInt("error_code", 0) == 0) { "locateupload 失败：${json.optString("error_msg")}" }
        return UploadLogic.parseUploadHost(json)
    }

    /** 按 4MB 分片算本地 MD5，供 precreate 的 block_list 使用。源文件已在私有缓存里。 */
    fun localMd5Parts(file: File, size: Long): List<String> {
        val digest = MessageDigest.getInstance("MD5")
        val buffer = ByteArray(64 * 1024)
        val result = ArrayList<String>(UploadLogic.chunkCount(size))
        FileInputStream(file).use { stream ->
            for (index in 0 until UploadLogic.chunkCount(size)) {
                var remaining = UploadLogic.chunkAt(size, index).length
                digest.reset()
                while (remaining > 0) {
                    val count = stream.read(buffer, 0, minOf(buffer.size, remaining))
                    if (count < 0) throw IOException("文件在读取过程中变短")
                    digest.update(buffer, 0, count)
                    remaining -= count
                }
                result.add(digest.digest().joinToString("") { "%02x".format(it) })
            }
        }
        return result
    }

    fun precreate(token: String, path: String, size: Long, localMd5: List<String>): UploadLogic.Precreate {
        val form = FormBody.Builder()
            .add("path", path)
            .add("size", size.toString())
            .add("autoinit", "1")
            .add("block_list", UploadLogic.createBlockList(localMd5))
            .build()
        val request = Request.Builder()
            .url("https://pan.baidu.com/api/precreate?$COMMON&channel=chunlei&bdstoken=${enc(token)}")
            .post(form).common().build()
        return UploadLogic.parsePrecreate(execute(request))
    }

    /** 单分片流式上传；参数全放 query，body 只有 file。返回服务端 md5。 */
    fun uploadPart(
        host: String, token: String, path: String, uploadId: String, partseq: Int,
        source: File, offset: Long, length: Int, name: String, onBytes: (Int) -> Unit,
    ): String {
        val query = "method=upload&type=tmpfile&$COMMON&channel=chunlei" +
            "&bdstoken=${enc(token)}&path=${enc(path)}&uploadid=${enc(uploadId)}&partseq=$partseq"
        val body = object : RequestBody() {
            override fun contentType(): MediaType? = "application/octet-stream".toMediaType()
            override fun contentLength(): Long = length.toLong()
            override fun writeTo(sink: BufferedSink) {
                FileInputStream(source).use { input ->
                    val jump = ByteArray(64 * 1024)
                    var skip = offset
                    while (skip > 0) {
                        val count = input.read(jump, 0, minOf(jump.size.toLong(), skip).toInt())
                        if (count < 0) throw IOException("文件已变短")
                        skip -= count
                    }
                    val buffer = ByteArray(64 * 1024)
                    var remaining = length
                    while (remaining > 0) {
                        val count = input.read(buffer, 0, minOf(buffer.size, remaining))
                        if (count < 0) throw IOException("文件读取中断")
                        sink.write(buffer, 0, count)
                        remaining -= count
                        onBytes(count)
                    }
                }
            }
        }
        val multipart = MultipartBody.Builder().setType(MultipartBody.FORM)
            .addFormDataPart("file", name, body).build()
        val request = Request.Builder()
            .url("https://$host/rest/2.0/pcs/superfile2?$query")
            .post(multipart).common(pcs = true).build()
        return UploadLogic.parsePartMd5(execute(request))
    }

    fun create(token: String, path: String, size: Long, uploadId: String, returnedMd5: List<String>): Long {
        val form = FormBody.Builder()
            .add("path", path)
            .add("size", size.toString())
            .add("isdir", "0")
            .add("uploadid", uploadId)
            .add("block_list", UploadLogic.createBlockList(returnedMd5))
            .add("rtype", UploadLogic.RTYPE_RENAME.toString())
            .build()
        val request = Request.Builder()
            .url("https://pan.baidu.com/api/create?$COMMON&channel=chunlei&bdstoken=${enc(token)}&isdir=0")
            .post(form).common().build()
        return execute(request).optLong("fs_id", -1L)
    }

    /** create 的 errno 0 不代表一定落盘，回读目录确认。 */
    fun exists(token: String, path: String): Boolean {
        val dir = PanApi.parentPath(path)
        val request = Request.Builder()
            .url(
                "https://pan.baidu.com/api/list?$COMMON&channel=chunlei&bdstoken=${enc(token)}" +
                    "&dir=${enc(dir)}&order=time&desc=1&num=100&page=1",
            )
            .get().common().build()
        val list = execute(request).optJSONArray("list") ?: return false
        return (0 until list.length()).any { list.optJSONObject(it)?.optString("path") == path }
    }

    private companion object {
        const val COMMON = "clienttype=0&app_id=250528&web=1"
    }
}
