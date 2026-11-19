package com.hnwd.bdplayer

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 群转存「保存到我的网盘」全屏目录选择页的纯逻辑。
 *
 * 与 [folderPickerUi]（移动/复制的小窗选择器）平行保留，判状态、校验名称、格式化时间
 * 都放这里，UI 只负责接线 —— 便于 JVM 单测，也不碰百度链路上已验证过的判据。
 */
internal data class TransferFolderUi(
    val upEnabled: Boolean,
    val saveEnabled: Boolean,
    val createEnabled: Boolean,
    val moreVisible: Boolean,
    val moreText: String,
    val moreEnabled: Boolean,
    val listEnabled: Boolean,
)

/** [empty] 表示当前目录已加载成功但没有任何子目录，此时把「更多」行换成空态提示。 */
internal fun transferFolderUi(
    loading: Boolean,
    current: String,
    page: Int,
    hasMore: Boolean,
    empty: Boolean,
): TransferFolderUi = TransferFolderUi(
    upEnabled = !loading && current != "/",
    saveEnabled = !loading && page > 0,
    createEnabled = !loading && page > 0,
    moreVisible = loading || page == 0 || hasMore || empty,
    moreText = when {
        loading -> "正在读取目录…"
        page == 0 -> "重试读取目录"
        empty -> "（暂无子目录，可直接保存到此或新建文件夹）"
        else -> "加载更多目录"
    },
    moreEnabled = !loading && !empty && (page == 0 || hasMore),
    listEnabled = !loading,
)

/**
 * 提交新建文件夹前的最后一道校验：读目录期间先拦住（避免路径还没确定就创建），
 * 名称规则复用网盘统一的 [diskNameError]，不另写一套。
 */
internal fun transferFolderCreateError(name: String, loading: Boolean): String? = when {
    loading -> "正在读取目录，请稍候"
    else -> diskNameError(name.trim())
}

/** 目录行的修改时间；百度返回秒级 `server_mtime`，0 / 负数表示缺失。 */
internal fun folderPickerTime(mtimeSeconds: Long): String =
    if (mtimeSeconds <= 0) ""
    else SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.CHINA).format(Date(mtimeSeconds * 1000))
