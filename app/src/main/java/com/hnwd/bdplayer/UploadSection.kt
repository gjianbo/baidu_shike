package com.hnwd.bdplayer

import android.content.Context
import android.os.Handler
import android.os.Looper
import java.io.File
import java.io.IOException

/**
 * 上传编排：规划分片 → 预上传 → 逐片流式上传 → 创建文件 → 保存服务端确认。
 *
 * 源文件是 [UploadCenter] 预先复制进私有缓存的 File，不再持有系统文件选择器的 Uri：
 * 前台 Activity 销毁后 Uri 的临时读取授权可能被回收，私有文件则不受影响。
 * 全程在 [AppIo] 的后台线程里线性执行，不占用 WebView 的请求通道，
 * 也不与列表 / 播放的 RequestLane 相互顶掉。
 */
internal class UploadSection(
    context: Context,
    session: TransferSession,
    private val accountId: String,
    private val file: File,
    private val displayName: String,
    private val dir: String,
    private val commit: UploadCommit,
    private val onProgress: (percent: Int, label: String) -> Unit,
    private val onResult: (success: Boolean, message: String) -> Unit,
) : UploadWorker {
    private val uploader = PanUploader(context.applicationContext, session)
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
                android.util.Log.w("ShikeUpload", "upload failed", it)
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
        val (token, vip) = uploader.session(accountId)
        val plan = UploadLogic.plan(size, vip.toString())
        check(plan.allowed) { plan.reason }
        val path = PanApi.diskPath(dir, displayName)
        progress(0, "正在校验文件…")

        val localMd5 = uploader.localMd5Parts(file, plan, ::checkActive)
        checkActive()
        val host = uploader.locateHost()
        checkActive()
        val pre = uploader.precreate(token, path, size, localMd5)
        val toUpload = UploadLogic.partsToUpload(plan.chunks, pre.returnType, pre.neededParts)
        val returnedMd5 = MutableList<String>(plan.chunks) { localMd5[it] }
        // 服务端已有的分片不需要重传，但它们的字节要算进进度起点，否则多分片会卡在 50%。
        var doneBytes = UploadLogic.skippedBytes(size, plan.chunks, toUpload, plan.chunkBytes)
        // 99% 留给 create 阶段，字节传完不提前报 100%。
        if (doneBytes < size) {
            progress(UploadLogic.progress(doneBytes, size).coerceAtMost(99), "上传中 1/${plan.chunks}")
        }

        for (index in 0 until plan.chunks) {
            checkActive()
            if (index !in toUpload) continue
            val chunk = UploadLogic.chunkAt(size, index, plan.chunkBytes)
            val beforePart = doneBytes
            val md5 = uploader.uploadPart(
                host, token, path, pre.uploadId, index, file,
                chunk.offset, chunk.length, displayName,
            ) { bytes ->
                doneBytes += bytes
                progress(
                    UploadLogic.progress(doneBytes, size).coerceAtMost(99),
                    "上传中 ${index + 1}/${plan.chunks}",
                )
            }
            doneBytes = beforePart + chunk.length
            returnedMd5[index] = md5
        }
        commit.begin()
        progress(99, "正在创建文件…")
        val fsId = try {
            uploader.create(token, path, size, pre.uploadId, returnedMd5)
        } catch (rejected: PanUploader.Rejected) {
            if (rejected.errno !in setOf(12, 111)) commit.rejected()
            throw rejected
        }
        commit.accepted(fsId.takeIf { it > 0 }?.toString().orEmpty())
        progress(100, "完成")
        return displayName
    }

    /** 百分比只增不减：分片传完可能已到 100，创建文件阶段的 99 不能让进度倒退。 */
    private fun progress(percent: Int, label: String) {
        lastPercent = maxOf(lastPercent, percent.coerceAtLeast(0))
        val value = lastPercent
        main.post { onProgress(value, label) }
    }
}
