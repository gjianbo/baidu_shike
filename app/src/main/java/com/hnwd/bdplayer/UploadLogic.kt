package com.hnwd.bdplayer

import org.json.JSONArray
import org.json.JSONObject

/**
 * 上传的「分片规划」纯逻辑：切多少片、每片范围、block_list 规格、秒传阈值、账号上限。
 *
 * 与「分片传输」严格分开：本文件只做计算与判定，不碰 Android / 网络 / 文件 IO，
 * 因此可在 JVM 单测里把文档 `百度网盘与夸克网盘上传接口分析.md` 的口径逐条钉死。
 * 真正发 multipart 的实现放在 PanUploader，编排放在 UploadSection。
 *
 * 口径（网页内部接口，非开放平台 xpan）：
 * - 分片固定 4MB（网页上传器 JS `chunkSize: 4194304`）；
 * - `precreate.block_list` = 本地每片 MD5，长度必须等于 `ceil(size/4MB)`，空数组会 errno 2；
 * - `create.block_list` = 各分片上传成功后**服务端返回的 MD5**，与 precreate 的不可混用；
 * - >256KB 可先试 `/api/rapidupload` 秒传；
 * - 分片数 ≤1024，对应普通账号 4GB 上限。
 */
internal object UploadLogic {
    const val CHUNK_BYTES = 4L * 1024 * 1024
    const val RAPID_MIN_BYTES = 256L * 1024
    const val MAX_PARTS = 1024
    const val PLAIN_MAX_BYTES = 4L * 1024 * 1024 * 1024
    const val VIP_MAX_BYTES = 10L * 1024 * 1024 * 1024
    const val SVIP_MAX_BYTES = 20L * 1024 * 1024 * 1024
    const val RTYPE_RENAME = 1
    const val RTYPE_OVERWRITE = 3

    data class Chunk(val index: Int, val offset: Long, val length: Int)

    data class Plan(
        val size: Long,
        val chunks: Int,
        val chunkBytes: Long,
        val rapid: Boolean,
        val allowed: Boolean,
        val reason: String,
    )

    fun vipLevel(raw: String): Int = raw.trim().toIntOrNull()?.coerceIn(0, 2) ?: 0

    fun maxBytes(vipRaw: String): Long = when (vipLevel(vipRaw)) {
        1 -> VIP_MAX_BYTES
        2 -> SVIP_MAX_BYTES
        else -> PLAIN_MAX_BYTES
    }

    /** 空文件按 1 片（0 字节）处理，与 `block_list` 至少一个元素的要求一致。 */
    fun chunkCount(size: Long): Int = when {
        size <= 0 -> 1
        else -> ((size + CHUNK_BYTES - 1) / CHUNK_BYTES).toInt()
    }

    fun chunkAt(size: Long, index: Int): Chunk {
        require(index in 0 until chunkCount(size)) { "分片序号越界：$index" }
        val offset = index * CHUNK_BYTES
        val length = if (size <= 0) 0 else minOf(CHUNK_BYTES, size - offset).toInt()
        return Chunk(index, offset, length)
    }

    fun rapidEligible(size: Long): Boolean = size > RAPID_MIN_BYTES

    fun plan(size: Long, vipRaw: String): Plan {
        val chunks = chunkCount(size)
        val reason = when {
            size < 0 -> "文件大小无效"
            chunks > MAX_PARTS ->
                "需要 $chunks 个分片，超过网页会话链路的 1024 片上限（4MB×1024=4GB）"
            size > maxBytes(vipRaw) -> "超过当前账号单文件上限 ${formatBytes(maxBytes(vipRaw))}"
            else -> ""
        }
        return Plan(
            size = size,
            chunks = chunks,
            chunkBytes = CHUNK_BYTES,
            rapid = rapidEligible(size),
            allowed = reason.isEmpty(),
            reason = reason,
        )
    }

    /** create 阶段的 block_list：只能用上传成功后返回的 MD5 列表。 */
    fun createBlockList(returnedMd5: List<String>): String = JSONArray(returnedMd5).toString()

    /**
     * precreate 之后，哪些分片真的需要上传。
     *
     * 实测单分片返回 `return_type:1, block_list:[0]`；多分片时 block_list 只列服务端仍缺的序号。
     * `return_type=2` 是秒传命中（服务端已有全部块），此时一片都不该再传；
     * block_list 为空但并非秒传时保守起见全部重传，避免把「响应缺字段」误判成「已完成」。
     * 越界序号直接丢弃，防止服务端返回脏数据让 chunkAt 抛异常。
     */
    fun partsToUpload(chunks: Int, returnType: Int, neededParts: Set<Int>): Set<Int> = when {
        returnType == 2 -> emptySet()
        neededParts.isEmpty() -> (0 until chunks).toSet()
        else -> neededParts.filter { it in 0 until chunks }.toSet()
    }

    /** 跳过分片（秒传命中或服务端已有）的字节数，作为进度起点，避免多分片进度卡在 50%。 */
    fun skippedBytes(size: Long, chunks: Int, toUpload: Set<Int>): Long =
        (0 until chunks).filter { it !in toUpload }.sumOf { chunkAt(size, it).length.toLong() }

    data class Precreate(val uploadId: String, val returnType: Int, val neededParts: Set<Int>)

    /** 实测响应：{errno:0, uploadid, return_type:1, block_list:[0]}，block_list 是仍需上传的分片序号。 */
    fun parsePrecreate(json: JSONObject): Precreate {
        val id = json.optString("uploadid")
        check(id.isNotEmpty()) { "预上传未返回 uploadid" }
        val parts = json.optJSONArray("block_list")?.let { array ->
            (0 until array.length())
                .map { array.optInt(it, -1) }
                .filter { it >= 0 }
                .toSet()
        }.orEmpty()
        return Precreate(id, json.optInt("return_type", 1), parts)
    }

    /** superfile2 成功响应里必须有 md5；create 的 block_list 要用它，不能用本地算的。 */
    fun parsePartMd5(json: JSONObject): String {
        val md5 = json.optString("md5")
        check(md5.isNotBlank()) { "分片上传未返回 md5" }
        return md5
    }

    /** locateupload 返回的 host 是动态上传域名（如 c.pcs.baidu.com）。 */
    fun parseUploadHost(json: JSONObject): String {
        val host = json.optString("host")
        check(host.isNotBlank()) { "未获取到上传域名" }
        return host
    }

    fun parseSession(json: JSONObject): Pair<String, Int> {
        val result = json.optJSONObject("result")
        val token = result?.optString("bdstoken").orEmpty()
        check(token.isNotBlank()) { "网盘会话未就绪，请重新登录" }
        val vip = result?.optString("is_vip").orEmpty().ifBlank { "0" }
        return token to vipLevel(vip)
    }

    /** 冲突策略：默认重命名，绝不覆盖用户已有文件。 */
    fun rtype(overwrite: Boolean): Int = if (overwrite) RTYPE_OVERWRITE else RTYPE_RENAME

    fun progress(done: Long, total: Long): Int = when {
        total <= 0 -> -1
        done >= total -> 100
        else -> ((done * 100) / total).toInt().coerceIn(0, 99)
    }

    fun uploadError(errno: Int, stage: String): String = when (errno) {
        2 -> "$stage：分片参数不匹配，block_list 长度必须等于 ceil(大小/4MB)"
        9019 -> "$stage：网盘风控拦截（9019），不要混用开放平台 xpan 接口"
        12, 111 -> "$stage：批量操作部分失败，请刷新目录确认"
        31023, 31362 -> "$stage：签名或校验失败，请重新取址后重试"
        else -> "$stage 失败（错误码 $errno），请稍后重试"
    }

    /** 私有缓存里的临时文件名：去掉路径分隔符等非法字符，避免文件名把文件写到缓存目录之外。 */
    fun cacheFileName(name: String): String {
        val cleaned = name.replace(Regex("""[/\\:*?"<>|]"""), "_")
            .trim().trimStart('.').take(80).trim()
        return cleaned.ifEmpty { "上传文件" }
    }

    fun formatBytes(bytes: Long): String {
        if (bytes < 1024) return "$bytes B"
        val units = listOf("KB", "MB", "GB", "TB")
        var value = bytes / 1024.0
        var unit = 0
        while (value >= 1024 && unit < units.lastIndex) {
            value /= 1024
            unit++
        }
        return "%.1f %s".format(java.util.Locale.US, value, units[unit])
    }
}
