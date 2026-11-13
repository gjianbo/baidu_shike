package com.hnwd.bdplayer

import android.content.Context
import android.os.Handler
import android.os.Looper
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile

/**
 * 夸克上传编排，与 [UploadSection]（百度）对称，实现同一份 [UploadWorker] 契约。
 *
 * 流程按 `docs/夸克网盘上传回调修复-实测通过.md`：
 * pre → **update/hash（登记整文件 md5/sha1）** → 逐片 auth+PUT → XML 合并 → finish。
 * `update/hash` 命中秒传时零上传，直接 finish 拿 fid。
 *
 * 源文件是 [UploadCenter] 预先复制进私有缓存的 File；全程在 [AppIo] 后台线程线性执行。
 */
internal class QuarkUploadSection(
    context: Context,
    session: TransferSession,
    private val accountId: String,
    private val file: File,
    private val displayName: String,
    private val folderId: String,
    private val commit: UploadCommit,
    private val onProgress: (percent: Int, label: String) -> Unit,
    private val onResult: (success: Boolean, message: String) -> Unit,
) : UploadWorker {
    private val uploader = QuarkUploader(context.applicationContext, session)
    private val main = Handler(Looper.getMainLooper())

    @Volatile private var cancelled = false

    @Volatile private var running = false

    @Volatile private var lastPercent = 0

    override fun cancel() {
        cancelled = true
        uploader.cancel()
    }

    override fun start() {
        if (running) {
            onResult(false, "已有上传正在进行")
            return
        }
        running = true
        cancelled = false
        lastPercent = 0
        progress(0, "准备中…")
        AppIo.execute {
            val result = runCatching { doUpload() }
            result.exceptionOrNull()?.let {
                android.util.Log.w("ShikeUpload", "quark upload failed", it)
            }
            running = false
            val message = when {
                result.isSuccess -> "已上传：${result.getOrNull()}"
                cancelled -> "已取消上传"
                else -> "上传失败：${result.exceptionOrNull()?.message ?: "未知错误"}"
            }
            main.post { onResult(result.isSuccess, message) }
        }
    }

    private fun checkActive() {
        if (cancelled) throw IOException("已取消")
    }

    private fun doUpload(): String {
        checkActive()
        val size = file.length()
        check(size <= QuarkUploadLogic.MAX_SINGLE_FILE) {
            "文件超过夸克单文件上限（100GB）"
        }
        val mime = QuarkUploadLogic.formatType(displayName)
        progress(0, "正在校验文件…")
        uploader.verifyAccount(accountId)

        // 预上传和哈希登记也可能直接落盘（秒传），请求发出前先记录状态。
        commit.begin()
        val pre = try {
            uploader.pre(displayName, size, folderId, mime)
        } catch (rejected: QuarkUploader.Rejected) {
            commit.rejected()
            throw rejected
        }
        if (pre.finished) {
            finishRapid(pre.objKey, pre.taskId, pre.fid)
            return displayName
        }
        commit.rejected()

        val (md5, sha1) = uploader.hashes(file, ::checkActive)
        commit.begin()
        val hash = try {
            uploader.updateHash(pre.taskId, md5, sha1)
        } catch (rejected: QuarkUploader.Rejected) {
            commit.rejected()
            throw rejected
        }
        if (hash.finished) {
            val objKey = hash.objKey.ifBlank { pre.objKey }
            finishRapid(objKey, pre.taskId, hash.fid)
            return displayName
        }
        commit.rejected()

        val count = QuarkUploadLogic.chunkCount(size, pre.partSize)
        val parts = ArrayList<Pair<Int, String>>(count)
        // parallel multipart 要求第 2 片起带「前 N-1 片累积的 SHA1 内部状态」作为
        // X-Oss-Hash-Ctx；分片 1 不带。每片上传成功后把该片喂进增量 SHA1，为下一片准备。
        val sha1State = QuarkSha1()
        var doneBytes = 0L
        for (index in 0 until count) {
            checkActive()
            val chunk = QuarkUploadLogic.chunkAt(size, index, pre.partSize)
            val data = readPart(file, chunk.offset, chunk.length)
            val hashCtx = if (index == 0) null else sha1State.hashCtxBase64()
            val etag = uploader.putPart(pre, pre.taskId, index + 1, data, mime, hashCtx)
            parts += (index + 1) to etag
            sha1State.update(data)
            doneBytes += data.size
            progress(
                QuarkUploadLogic.progress(doneBytes, size),
                "上传中 ${index + 1}/$count",
            )
        }
        commit.begin()
        progress(99, "正在合并分片…")
        uploader.commit(pre, pre.taskId, parts)
        progress(99, "正在创建文件…")
        commit.accepted(uploader.finish(pre.objKey, pre.taskId))
        progress(100, "完成")
        return displayName
    }

    private fun finishRapid(objKey: String, taskId: String, fid: String) {
        if (fid.isNotBlank()) commit.accepted(fid)
        val confirmed = runCatching { uploader.finish(objKey, taskId) }
        if (fid.isBlank()) commit.accepted(confirmed.getOrThrow())
        progress(100, "秒传完成")
    }

    private fun readPart(file: File, offset: Long, length: Int): ByteArray {
        val data = ByteArray(length)
        RandomAccessFile(file, "r").use { raf ->
            raf.seek(offset)
            raf.readFully(data)
        }
        return data
    }

    /** 百分比只增不减：分片传完可能已到 99，收尾阶段不能让进度倒退。 */
    private fun progress(percent: Int, label: String) {
        lastPercent = maxOf(lastPercent, percent.coerceAtLeast(0))
        val value = lastPercent
        main.post { onProgress(value, label) }
    }
}
