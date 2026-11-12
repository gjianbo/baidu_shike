package com.hnwd.bdplayer

/**
 * 上传任务的最小契约，供 [UploadCenter] 按 provider 分派：
 * [UploadSection]（百度）与 [QuarkUploadSection]（夸克）各自实现，队列只管 start/cancel。
 */
internal interface UploadWorker {
    fun start()
    fun cancel()
}
