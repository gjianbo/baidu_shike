package com.hnwd.bdplayer

import android.content.Context
import android.webkit.WebSettings
import okhttp3.Call
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.io.RandomAccessFile
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * 夸克网盘上传的「数据面」：控制面走 drive-pc.quark.cn（同源、手动挂 CookieManager 会话），
 * 数据面走 OSS（{bucket}.pds.quark.cn，用 auth_key 授权，**不转发夸克 Cookie**）。
 *
 * 完整链路按 `docs/夸克网盘上传回调修复-实测通过.md`（真实会话端到端跑通）：
 * pre → **update/hash（登记整文件 md5/sha1，缺了合并回调必 400）** → 逐片 auth+PUT
 * → CompleteMultipartUpload（XML 合并，带 x-oss-callback）→ finish → 回读。
 * `update/hash` 返回 finish=true 即秒传命中，直接 finish 拿 fid，零上传。
 */
internal class QuarkUploader(context: Context, private val session: TransferSession) {
    private val client = OkHttpClient.Builder()
        .followRedirects(false)
        .retryOnConnectionFailure(false)
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .build()
    private val userAgent = WebSettings.getDefaultUserAgent(context)
    private val currentCall = AtomicReference<Call?>(null)
    @Volatile private var cancelled = false

    fun verifyAccount(expected: String) {
        TransferSession.requireAccount(SourceProvider.QUARK, expected, session.quarkAccount())
    }

    fun cancel() {
        cancelled = true
        currentCall.getAndSet(null)?.cancel()
    }

    class Rejected(code: Int, message: String) : IOException(QuarkUploadLogic.uploadError(code, message))

    /** 通用夸克控制面请求：统一校验 HTTP 与 code==0。 */
    private fun execute(request: Request): JSONObject {
        val call = client.newCall(request)
        currentCall.set(call)
        if (cancelled) call.cancel()
        try {
            call.execute().use { response ->
                val text = response.body?.string().orEmpty()
                check(response.isSuccessful) { "HTTP ${response.code}: ${text.take(160)}" }
                val json = runCatching { JSONObject(text) }
                    .getOrElse { throw IOException("夸克响应不是 JSON：${text.take(160)}") }
                val code = json.optInt("code", 0)
                if (code != 0) throw Rejected(code, json.optString("message"))
                return json
            }
        } finally {
            currentCall.compareAndSet(call, null)
        }
    }

    /**
     * 控制面请求头与 [QuarkPersonalDrive] 的成功通道保持一致：只带 Cookie / UA / Accept /
     * Content-Type。**不能带 Origin**：原生 OkHttp 请求带 Origin 会被夸克网关判为
     * `403 Invalid CORS request`（Q0 在 WebView fetch 里由浏览器自动带头，未暴露这个差异）。
     */
    private fun api(url: String, body: JSONObject): JSONObject {
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", userAgent)
            .header("Accept", "application/json")
            .header("Cookie", session.cookie)
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .build()
        return execute(request)
    }

    /** ① 预上传：返回占位 fid、OSS 对象信息、callback 与临时凭证。 */
    fun pre(fileName: String, size: Long, pdirFid: String, formatType: String): QuarkUploadLogic.Pre {
        val json = api(
            QuarkUploadLogic.preUrl(),
            QuarkUploadLogic.preBody(fileName, size, pdirFid, formatType, System.currentTimeMillis()),
        )
        return QuarkUploadLogic.parsePre(json)
    }

    /**
     * ② 关键修复：把整文件 md5/sha1 登记到 task。
     * OSS 合并时夸克回调带 `content_hash=${sha1}`，未登记时该值为空 → 回调 400
     * → OSS 203 CallbackFailed → finish 43001。
     */
    fun updateHash(taskId: String, md5: String, sha1: String): QuarkUploadLogic.HashResult =
        QuarkUploadLogic.parseUpdateHash(
            api(QuarkUploadLogic.updateHashUrl(), QuarkUploadLogic.updateHashBody(taskId, md5, sha1)),
        )

    /** 整文件 MD5 与 SHA1（hex 小写），流式计算，不整包进内存。 */
    fun hashes(file: File, checkActive: () -> Unit): Pair<String, String> {
        val md5 = MessageDigest.getInstance("MD5")
        val sha1 = MessageDigest.getInstance("SHA-1")
        FileInputStream(file).use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                checkActive()
                val count = input.read(buffer)
                if (count < 0) break
                md5.update(buffer, 0, count)
                sha1.update(buffer, 0, count)
            }
        }
        return hex(md5.digest()) to hex(sha1.digest())
    }

    private fun hex(bytes: ByteArray): String {
        val digits = "0123456789abcdef"
        return buildString(bytes.size * 2) {
            bytes.forEach { byte ->
                val value = byte.toInt() and 0xff
                append(digits[value ushr 4])
                append(digits[value and 0x0f])
            }
        }
    }

    /** ③a OSS V1 StringToSign 换 Authorization；auth 请求本身不带 Cookie（同源控制面要带）。 */
    fun auth(taskId: String, authInfo: String, authMeta: String): String {
        val body = JSONObject()
            .put("task_id", taskId)
            .put("auth_info", authInfo)
            .put("auth_meta", authMeta)
        val json = api(
            "${QuarkUploadLogic.API}/file/upload/auth?pr=ucpro&fr=pc&uc_param_str=", body,
        )
        return json.optJSONObject("data")?.optString("auth_key").orEmpty()
            .takeIf { it.isNotBlank() } ?: throw IOException("upload/auth 未返回 auth_key")
    }

    /**
     * ③b OSS PUT 单个分片：200 + ETag。gmt 必须与 authMeta 用同一个值。
     *
     * PUT 的 auth_meta 第二行（Content-MD5）留空、请求头不带 Content-MD5
     * （文档 §1 ③a/③b、AList `upPart` 口径）。
     * parallel multipart（≥2 片）时第 2 片起必须带 `X-Oss-Hash-Ctx`：前 N-1 片累积的
     * SHA1 中间状态，否则 OSS 返回 400 NoHashContext。合并请求的 Content-MD5 仍保留。
     */
    fun putPart(
        pre: QuarkUploadLogic.Pre, taskId: String, partNumber: Int,
        data: ByteArray, mime: String, hashCtx: String?,
    ): String {
        val gmt = httpDate()
        val authMeta = QuarkUploadLogic.authMeta(
            method = "PUT", contentMd5Value = "", contentType = mime, gmt = gmt,
            hashCtxValue = hashCtx.orEmpty(),
            resource = QuarkUploadLogic.putResource(pre.bucket, pre.objKey, pre.uploadId, partNumber),
        )
        val authKey = auth(taskId, pre.authInfo, authMeta)
        val builder = Request.Builder()
            .url(QuarkUploadLogic.ossPutUrl(
                pre.bucket, pre.objKey, pre.uploadId, partNumber, pre.uploadUrl,
            ))
            .header("Authorization", authKey)
            .header("Content-Type", mime)
            .header("Referer", QuarkUploadLogic.HOME)
            .header("x-oss-date", gmt)
            .header("x-oss-user-agent", QuarkUploadLogic.OSS_USER_AGENT)
        // partNumber>=2 必须带上一检查点的增量 SHA1 上下文，否则 400 NoHashContext。
        if (!hashCtx.isNullOrEmpty()) builder.header("X-Oss-Hash-Ctx", hashCtx)
        val request = builder.put(data.toRequestBody(mime.toMediaType())).build()
        val call = client.newCall(request)
        currentCall.set(call)
        if (cancelled) call.cancel()
        try {
            call.execute().use { response ->
                val text = response.body?.string().orEmpty()
                check(response.code == 200) { "OSS PUT HTTP ${response.code}: ${text.take(160)}" }
                return response.header("ETag").orEmpty()
                    .takeIf { it.isNotBlank() } ?: throw IOException("OSS PUT 未返回 ETag")
            }
        } finally {
            currentCall.compareAndSet(call, null)
        }
    }

    /**
     * ④ CompleteMultipartUpload：XML 用 AList 带换行格式，Content-MD5 基于实际发送的字节，
     * x-oss-callback 由 pre.data.callback 紧凑 JSON + base64 得到，且必须进 auth_meta。
     * 203 视为回调失败（多半是漏了 update/hash 或 XML 与 MD5 不一致）。
     */
    fun commit(
        pre: QuarkUploadLogic.Pre, taskId: String, parts: List<Pair<Int, String>>,
    ) {
        val xml = QuarkUploadLogic.completeXml(parts)
        val xmlBytes = xml.toByteArray(Charsets.UTF_8)
        val contentMd5 = QuarkUploadLogic.contentMd5(xmlBytes)
        val callbackValue = QuarkUploadLogic.callbackBase64(pre.callback)
        val gmt = httpDate()
        val authMeta = QuarkUploadLogic.authMeta(
            method = "POST", contentMd5Value = contentMd5,
            contentType = QuarkUploadLogic.OSS_XML_TYPE, gmt = gmt,
            callbackValue = callbackValue,
            resource = QuarkUploadLogic.commitResource(pre.bucket, pre.objKey, pre.uploadId),
        )
        val authKey = auth(taskId, pre.authInfo, authMeta)
        val request = Request.Builder()
            .url(QuarkUploadLogic.ossCompleteUrl(
                pre.bucket, pre.objKey, pre.uploadId, pre.uploadUrl,
            ))
            .header("Authorization", authKey)
            .header("Content-MD5", contentMd5)
            .header("Content-Type", QuarkUploadLogic.OSS_XML_TYPE)
            .header("Referer", QuarkUploadLogic.HOME)
            .header("x-oss-callback", callbackValue)
            .header("x-oss-date", gmt)
            .header("x-oss-user-agent", QuarkUploadLogic.OSS_USER_AGENT)
            .post(xmlBytes.toRequestBody(QuarkUploadLogic.OSS_XML_TYPE.toMediaType()))
            .build()
        val call = client.newCall(request)
        currentCall.set(call)
        if (cancelled) call.cancel()
        try {
            call.execute().use { response ->
                val text = response.body?.string().orEmpty()
                if (response.code == 203) {
                    throw IOException("OSS 合并回调失败（203）：${text.take(200)}；请检查 update/hash 与 Content-MD5")
                }
                check(response.code == 200) { "OSS 合并 HTTP ${response.code}: ${text.take(200)}" }
            }
        } finally {
            currentCall.compareAndSet(call, null)
        }
    }

    /** ⑤ 通知夸克服务器上传完成，返回最终 fid。 */
    fun finish(objKey: String, taskId: String): String =
        QuarkUploadLogic.parseFinish(
            api(QuarkUploadLogic.finishUrl(), QuarkUploadLogic.finishBody(objKey, taskId)),
        )

    /** 验证探针用：删除刚上传的测试文件（action_type=2）。 */
    fun delete(fids: List<String>): JSONObject =
        api(QuarkUploadLogic.deleteUrl(), QuarkUploadLogic.deleteBody(fids))

    private fun httpDate(): String = java.text.SimpleDateFormat(
        "EEE, dd MMM yyyy HH:mm:ss 'GMT'", java.util.Locale.US,
    ).apply { timeZone = java.util.TimeZone.getTimeZone("GMT") }.format(java.util.Date())
}
