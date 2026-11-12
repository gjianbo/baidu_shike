package com.hnwd.bdplayer

/**
 * 「清空记录」的纯逻辑：菜单文案、失败任务的本机残留文件判定、清理结果文案。
 *
 * 与 DownloadLogic / UploadRecordLogic / TransferFilter 一样不碰 Android API 与文件 IO，
 * 可在 JVM 单测里把「哪些文件能删」的边界钉死 —— 误删活跃任务的缓存会让队列后半段
 * 读不到源文件，属于必须防住的破坏性操作。
 */
internal object TransferCleanup {
    /**
     * 上传缓存文件名是 `"<任务id>-<安全文件名>"`（UploadCenter.copyToCache），
     * 取第一个 '-' 前的纯数字段作任务 id；格式不符一律返回 null。
     */
    fun fileId(name: String): Long? {
        val dash = name.indexOf('-')
        if (dash <= 0) return null
        return name.substring(0, dash).toLongOrNull()
    }

    /**
     * 只删「已失败、且当前没有任务在用」的上传缓存文件。
     * 活跃任务（QUEUED / RUNNING）的缓存绝不能删，否则队列后续任务会读不到源文件；
     * 不在失败集合里的文件（成功任务残留、别的批次临时文件）也不动。
     */
    fun staleFailedFiles(
        names: List<String>, failedIds: Set<Long>, activeIds: Set<Long>,
    ): List<String> = names.filter { name ->
        val id = fileId(name) ?: return@filter false
        id in failedIds && id !in activeIds
    }

    fun menuSuccessLabel(count: Int): String = "清空已完成（$count）"

    fun menuFailedLabel(count: Int): String = "清理失败的记录（$count）"

    fun clearSuccessMessage(count: Int): String =
        if (count > 0) "已清空 $count 条已完成记录" else "没有已完成的记录"

    fun clearFailedMessage(records: Int, files: Int): String = when {
        records <= 0 -> "没有失败的记录"
        files > 0 -> "已清理 $records 条失败记录，删除 $files 个残留文件"
        else -> "已清理 $records 条失败记录"
    }
}
