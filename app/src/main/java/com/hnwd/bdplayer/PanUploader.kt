package com.hnwd.bdplayer

import android.content.Context
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
import java.io.RandomAccessFile
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
internal class PanUploader(context: Context, private val session: TransferSession) {
    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .writeTimeout(90, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()
    private val commitClient = client.newBuilder().retryOnConnectionFailure(false).followRedirects(false).build()
    private val currentCall = AtomicReference<Call?>(null)
    @Volatile private var cancelled = false
    private val userAgent = WebSettings.getDefaultUserAgent(context)

    fun cancel() {
        cancelled = true
        currentCall.getAndSet(null)?.cancel()
    }

    private fun Request.Builder.common(pcs: Boolean = false): Request.Builder {
        header("User-Agent", userAgent)
        header("Cookie", session.cookie)
        if (!pcs) header("Referer", "https://pan.baidu.com/disk/main")
        return this
    }

    private fun enc(value: String): String = URLEncoder.encode(value, "UTF-8")

    class Rejected(val errno: Int) : IOException(UploadLogic.uploadError(errno, "网盘请求"))

    private fun execute(request: Request, transport: OkHttpClient = client): JSONObject {
        val call = transport.newCall(request)
        currentCall.set(call)
        if (cancelled) call.cancel()
        try {
            call.execute().use { response ->
                val body = response.body?.string().orEmpty()
                check(response.isSuccessful) { "HTTP ${response.code}: ${body.take(160)}" }
                val json = runCatching { JSONObject(body) }
                    .getOrElse { throw IOException("网盘响应不是 JSON：${body.take(160)}") }
                val errno = json.optInt("errno", Int.MIN_VALUE)
                if (errno != Int.MIN_VALUE && errno != 0) {
                    throw Rejected(errno)
                }
                return json
            }
        } finally {
            currentCall.compareAndSet(call, null)
        }
    }

    /** bdstoken + 账号等级；等级决定单文件上限。 */
    fun session(expectedAccount: String): Pair<String, Int> {
        val fields = enc("[\"bdstoken\",\"is_vip\",\"uk\"]")
        val request = Request.Builder()
            .url("https://pan.baidu.com/api/gettemplatevariable?$COMMON&fields=$fields")
            .get().common().build()
        val json = execute(request)
        TransferSession.requireAccount(
            SourceProvider.BAIDU, expectedAccount, json.optJSONObject("result")?.optString("uk").orEmpty(),
        )
        return UploadLogic.parseSession(json)
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

    /** 按上传规划算本地分片 MD5，供 precreate 的 block_list 使用。 */
    fun localMd5Parts(file: File, plan: UploadLogic.Plan, checkActive: () -> Unit): List<String> {
        val digest = MessageDigest.getInstance("MD5")
        val buffer = ByteArray(64 * 1024)
        val result = ArrayList<String>(plan.chunks)
        FileInputStream(file).use { stream ->
            for (index in 0 until plan.chunks) {
                var remaining = UploadLogic.chunkAt(plan.size, index, plan.chunkBytes).length
                digest.reset()
                while (remaining > 0) {
                    checkActive()
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
                RandomAccessFile(source, "r").use { input ->
                    input.seek(offset)
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
        val response = execute(request, commitClient)
        check(response.optInt("errno", -1) == 0) { "未收到网盘提交确认" }
        return response.optLong("fs_id", -1L)
    }

    private companion object {
        const val COMMON = "clienttype=0&app_id=250528&web=1"
    }
}
