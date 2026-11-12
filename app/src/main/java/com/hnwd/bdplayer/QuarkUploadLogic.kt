package com.hnwd.bdplayer

import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import java.security.MessageDigest
import java.util.Base64

/**
 * 夸克网盘上传的纯逻辑层：分片规划、请求体/响应解析、OSS 签名材料与合并 XML。
 *
 * 契约来自 `docs/夸克网盘上传回调修复-实测通过.md`（真实夸克会话端到端跑通）：
 * - `pre` 的 `metadata.part_size` 才是分片大小（`data.part_size` 是 null），bucket 动态；
 * - **合并前必须先 `POST /file/update/hash` 登记整文件 md5/sha1**，否则 OSS 合并时
 *   回调夸克的 `content_hash` 为空 → 夸克回调 400 → OSS `203 CallbackFailed` → finish `43001`；
 * - `auth_meta` 是 OSS V1 StringToSign（分片 PUT / 合并 POST 两套），不是哈希；
 * - 合并的 `x-oss-callback` 由 `pre.data.callback` 对象紧凑 JSON + base64 得到，
 *   `Content-MD5 = base64(md5(实际发送的 XML 字节))`。
 *
 * 本文件只做计算与判定，不碰 Android / 网络 / 文件 IO，因此可在 JVM 单测里逐条钉死口径。
 */
internal object QuarkUploadLogic {
    const val API = "https://drive-pc.quark.cn/1/clouddrive"
    const val HOME = "https://pan.quark.cn/"
    const val CHUNK_BYTES = 4L * 1024 * 1024
    /** pre.metadata.part_size 的保底值，实测为 4194304。 */
    const val DEFAULT_PART_SIZE = 4 * 1024 * 1024
    /** 实测单文件上限；101GB 会返回 32004 file is too big。 */
    const val MAX_SINGLE_FILE = 100L * 1024 * 1024 * 1024
    /** 实测通过的 OSS UA；auth_meta 与实际 PUT/POST 头必须逐字一致。 */
    const val OSS_USER_AGENT = "aliyun-sdk-js/6.6.1 Chrome 98.0.4758.80 on Windows 10 64-bit"
    const val OSS_XML_TYPE = "application/xml"
    const val OCTET_STREAM = "application/octet-stream"
    /** pre.upload_url 为空/异常时的保底主机（实测为 pds.quark.cn）。 */
    const val DEFAULT_OSS_HOST = "pds.quark.cn"

    data class Chunk(val index: Int, val offset: Long, val length: Int)

    data class Pre(
        val taskId: String,
        val uploadId: String,
        val objKey: String,
        val bucket: String,
        val uploadUrl: String,
        val fid: String,
        val authInfo: String,
        val authInfoExpired: Long,
        val finished: Boolean,
        val callback: JSONObject,
        val partSize: Int,
        val partThread: Int,
    )

    /** update/hash 的返回：finish=true 即秒传命中，直接用 fid。 */
    data class HashResult(val finished: Boolean, val fid: String, val objKey: String)

    /**
     * 统一按 partSize 分片（含单分片文件也要走 CompleteMultipartUpload）。
     * Q0 依据的旧文档写过「<5MB 单分片」，实测流程并无此特例，按 metadata.part_size 切即可。
     */
    fun chunkCount(size: Long, partSize: Int = DEFAULT_PART_SIZE): Int =
        maxOf(1, ((size + partSize - 1) / partSize).toInt())

    fun isSinglePart(size: Long, partSize: Int = DEFAULT_PART_SIZE): Boolean =
        chunkCount(size, partSize) == 1

    fun chunkAt(size: Long, index: Int, partSize: Int = DEFAULT_PART_SIZE): Chunk {
        val count = chunkCount(size, partSize)
        require(index in 0 until count) { "分片序号越界：$index" }
        val offset = index.toLong() * partSize
        return Chunk(index, offset, minOf(partSize.toLong(), size - offset).toInt())
    }

    /** 已传字节占比；钳到 99，把 100% 留给合并与 finish 阶段。 */
    fun progress(done: Long, total: Long): Int = when {
        total <= 0 -> 0
        done >= total -> 100
        else -> ((done * 100) / total).toInt().coerceIn(0, 99)
    }

    fun formatType(fileName: String): String = when (
        fileName.substringAfterLast('.', "").lowercase()
    ) {
        "mp4", "m4v", "mov" -> "video/mp4"
        "mkv" -> "video/x-matroska"
        "avi" -> "video/x-msvideo"
        "mp3" -> "audio/mpeg"
        "m4a" -> "audio/mp4"
        "wav" -> "audio/wav"
        "flac" -> "audio/flac"
        "jpg", "jpeg" -> "image/jpeg"
        "png" -> "image/png"
        "gif" -> "image/gif"
        "pdf" -> "application/pdf"
        "txt", "md", "log" -> "text/plain"
        "json" -> "application/json"
        "zip" -> "application/zip"
        else -> OCTET_STREAM
    }

    fun preUrl(): String = "$API/file/upload/pre?pr=ucpro&fr=pc&uc_param_str="

    fun preBody(
        fileName: String, size: Long, pdirFid: String, formatType: String, nowMs: Long,
    ): JSONObject = JSONObject()
        .put("ccp_hash_update", true)
        // multi-part 必须声明 parallel_upload=true（kuake_cli 实测口径）；服务端据此启用
        // parallel multipart，第 2 片起要求 X-Oss-Hash-Ctx 增量 SHA1 上下文，见 [QuarkSha1]。
        .put("parallel_upload", true)
        .put("pdir_fid", pdirFid)
        .put("dir_name", "")
        .put("size", size)
        .put("file_name", fileName)
        .put("format_type", formatType)
        .put("l_updated_at", nowMs)
        .put("l_created_at", nowMs)

    /** code=0 才成功；finish=true 表示 pre 阶段就已秒传命中。 */
    fun parsePre(json: JSONObject): Pre {
        val code = json.optInt("code", -1)
        check(code == 0) { "预上传失败：code=$code ${json.optString("message")}" }
        val data = json.optJSONObject("data")
            ?: throw IllegalArgumentException("预上传响应缺少 data")
        val taskId = data.optString("task_id")
        check(taskId.isNotEmpty()) { "预上传未返回 task_id" }
        val metadata = json.optJSONObject("metadata")
        return Pre(
            taskId = taskId,
            uploadId = data.optString("upload_id"),
            objKey = data.optString("obj_key"),
            bucket = data.optString("bucket"),
            uploadUrl = data.optString("upload_url"),
            fid = data.optString("fid"),
            authInfo = data.optString("auth_info"),
            authInfoExpired = data.optLong("auth_info_expried", 0),
            finished = data.optBoolean("finish", false),
            callback = data.optJSONObject("callback") ?: JSONObject(),
            partSize = metadata?.optInt("part_size", DEFAULT_PART_SIZE)
                ?.takeIf { it > 0 } ?: DEFAULT_PART_SIZE,
            partThread = metadata?.optInt("part_thread", 1)
                ?.takeIf { it > 0 } ?: 1,
        )
    }

    // ===================== 关键修复：合并前登记整文件哈希 =====================

    fun updateHashUrl(): String = "$API/file/update/hash?pr=ucpro&fr=pc&uc_param_str="

    fun updateHashBody(taskId: String, md5: String, sha1: String): JSONObject = JSONObject()
        .put("task_id", taskId)
        .put("md5", md5)
        .put("sha1", sha1)

    fun parseUpdateHash(json: JSONObject): HashResult {
        val code = json.optInt("code", -1)
        check(code == 0) { "登记文件哈希失败：code=$code ${json.optString("message")}" }
        val data = json.optJSONObject("data") ?: JSONObject()
        return HashResult(
            finished = data.optBoolean("finish", false),
            fid = data.optString("fid"),
            objKey = data.optString("obj_key"),
        )
    }

    fun finishUrl(): String = "$API/file/upload/finish?pr=ucpro&fr=pc&uc_param_str="

    fun finishBody(objKey: String, taskId: String): JSONObject = JSONObject()
        .put("obj_key", objKey)
        .put("task_id", taskId)

    /** finish=true 才算落盘；返回最终的 fid。 */
    fun parseFinish(json: JSONObject): String {
        val code = json.optInt("code", -1)
        check(code == 0) { "完成上传失败：code=$code ${json.optString("message")}" }
        val data = json.optJSONObject("data") ?: JSONObject()
        check(data.optBoolean("finish", false)) { "夸克尚未确认上传完成（finish=false）" }
        return data.optString("fid")
    }

    /** 验证探针用：上传成功后删除测试文件，避免污染网盘。 */
    fun deleteUrl(): String = "$API/file/delete?pr=ucpro&fr=pc&uc_param_str="

    fun deleteBody(fids: List<String>): JSONObject = JSONObject()
        .put("action_type", 2)
        .put("filelist", JSONArray(fids))

    // ===================== OSS 地址与签名材料 =====================

    /** OSS 主机 = `{bucket}.{pre.upload_url 去 scheme}`，不能写死域名（实测文档 §1）。 */
    fun ossHost(uploadUrl: String): String =
        uploadUrl.removePrefix("https://").removePrefix("http://")
            .trimEnd('/').takeIf { it.isNotBlank() } ?: DEFAULT_OSS_HOST

    /** obj_key 是 OSS 对象路径，斜杠必须保留；只有 query 里的 uploadId 需要转义。 */
    fun ossPutUrl(
        bucket: String, objKey: String, uploadId: String, partNumber: Int, uploadUrl: String,
    ): String =
        "https://$bucket.${ossHost(uploadUrl)}/${objKey.trimStart('/')}" +
            "?partNumber=$partNumber&uploadId=${URLEncoder.encode(uploadId, "UTF-8")}"

    fun ossCompleteUrl(
        bucket: String, objKey: String, uploadId: String, uploadUrl: String,
    ): String =
        "https://$bucket.${ossHost(uploadUrl)}/${objKey.trimStart('/')}" +
            "?uploadId=${URLEncoder.encode(uploadId, "UTF-8")}"

    /**
     * AList 原样的合并 XML（带声明与换行）。Content-MD5 必须基于**实际发送的这些字节**。
     * ETag 用 PUT 分片响应头原值（统一包一层引号）。
     */
    fun completeXml(parts: List<Pair<Int, String>>): String = buildString {
        append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
        append("<CompleteMultipartUpload>\n")
        parts.sortedBy { it.first }.forEach { (number, etag) ->
            val clean = etag.trim().trim('"')
                .replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
            append("  <Part>\n")
            append("    <PartNumber>$number</PartNumber>\n")
            append("    <ETag>\"$clean\"</ETag>\n")
            append("  </Part>\n")
        }
        append("</CompleteMultipartUpload>")
    }

    fun putResource(bucket: String, objKey: String, uploadId: String, partNumber: Int): String =
        "/$bucket/${objKey.trimStart('/')}?partNumber=$partNumber&uploadId=${URLEncoder.encode(uploadId, "UTF-8")}"

    fun commitResource(bucket: String, objKey: String, uploadId: String): String =
        "/$bucket/${objKey.trimStart('/')}?uploadId=${URLEncoder.encode(uploadId, "UTF-8")}"

    /** OSS Content-MD5：body 的 MD5 再 base64。 */
    fun contentMd5(bytes: ByteArray): String =
        Base64.getEncoder().encodeToString(MessageDigest.getInstance("MD5").digest(bytes))

    /** pre.data.callback 对象紧凑 JSON 再 base64；不能对字符串再套引号。 */
    fun callbackBase64(callback: JSONObject): String =
        Base64.getEncoder().encodeToString(callback.toString().toByteArray(Charsets.UTF_8))

    /**
     * OSS V1 StringToSign。canonical x-oss-* headers 按 key 字母序拼接并以换行结尾；
     * 合并时 x-oss-callback 必须出现在签名串里（删了 auth 会 14001）。
     * 分片：PUT\n<md5>\n<mime>\n<GMT>\nx-oss-date…\nx-oss-user-agent…\n/<bucket>/<obj>?partNumber=N&uploadId=…
     * 合并：POST\n<xmlMd5>\napplication/xml\n<GMT>\nx-oss-callback…\nx-oss-date…\nx-oss-user-agent…\n/<bucket>/<obj>?uploadId=…
     */
    fun authMeta(
        method: String, contentMd5Value: String, contentType: String, gmt: String,
        callbackValue: String = "", hashCtxValue: String = "", resource: String,
    ): String {
        val headers = mutableListOf("x-oss-date" to gmt, "x-oss-user-agent" to OSS_USER_AGENT)
        if (callbackValue.isNotEmpty()) headers += "x-oss-callback" to callbackValue
        // 真实客户端用大写 X；按码点排序会排在 x-oss-date / x-oss-user-agent 之前，
        // 与 kuake_cli 的 auth_meta 拼接顺序一致。
        if (hashCtxValue.isNotEmpty()) headers += "X-Oss-Hash-Ctx" to hashCtxValue
        val canonical = headers.sortedBy { it.first }
            .joinToString("\n") { "${it.first}:${it.second}" } + "\n"
        return "${method.uppercase()}\n$contentMd5Value\n$contentType\n$gmt\n$canonical$resource"
    }

    fun uploadError(code: Int, message: String): String = when (code) {
        32004 -> "文件超过夸克单文件上限（100GB）"
        20002 -> "夸克账号已失效，请重新登录"
        14001 -> "上传签名校验失败，请重试"
        43001 -> "分片合并未完成，请重试"
        else -> "夸克上传失败（code $code）${message.takeIf { it.isNotBlank() }?.let { "：$it" }.orEmpty()}"
    }
}
