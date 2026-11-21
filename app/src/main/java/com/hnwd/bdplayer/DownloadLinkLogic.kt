package com.hnwd.bdplayer

import org.json.JSONArray
import org.json.JSONObject

/**
 * 群组文件下载取址（`POST /api/sharedownload`）的响应判定，纯 JVM 逻辑。
 *
 * 百度对「共享文件库」的大视频不返回明文 `list[].dlink`，而是把整个 `list` 换成一段
 * 加密字符串（实测 4,504 字符，见 `docs/百度网盘群组图片预览分析.md` §2.3 / §8.6，
 * 网页端同样拿不到原始文件地址）。在线播放走 `/mbox/msg/streaming` 的 HLS 转码流，
 * 我的网盘下载走 `/api/download`，都不经过这里，所以「群组视频能播不能下、网盘正常」。
 *
 * 这类失败是网盘服务端限制，不是本地网络故障；此前 `parseMediaLink` 抛出的
 * IllegalStateException 被下载中心兜底成「请检查网络后重试」，反而掩盖了真实原因。
 */
internal object DownloadLinkLogic {
    enum class GroupLinkIssue { ENCRYPTED_LIST, EMPTY_LIST, MISSING_FILE, NO_DLINK }

    /** 仅在 `PanApi.parseMediaLink` 取不到 dlink 之后调用，用来说明「为什么没有地址」。 */
    fun groupLinkIssue(json: JSONObject, fileId: String): GroupLinkIssue {
        val list = json.opt("list")
        // JSONObject.optString 会把 JSONArray 也转成字符串，必须先看原始类型。
        if (list is String && list.isNotBlank()) return GroupLinkIssue.ENCRYPTED_LIST
        val array = list as? JSONArray ?: return GroupLinkIssue.EMPTY_LIST
        if (array.length() == 0) return GroupLinkIssue.EMPTY_LIST
        val hasMatch = (0 until array.length()).mapNotNull { array.optJSONObject(it) }
            .any { it.optString("fs_id") == fileId }
        if (!hasMatch) return GroupLinkIssue.MISSING_FILE
        // 走到这里说明 dlink 为空，或地址未通过 isFileLink 校验（域名/路径不合契约）。
        return GroupLinkIssue.NO_DLINK
    }

    fun failureText(issue: GroupLinkIssue): String = when (issue) {
        // 用户可执行的最短路径：群组页已有「转存」入口，转存后回到网盘页下载。
        GroupLinkIssue.ENCRYPTED_LIST ->
            "群组大视频无法直接下载：网盘未返回原文件地址，请先转存到「我的网盘」再下载"
        GroupLinkIssue.EMPTY_LIST, GroupLinkIssue.MISSING_FILE, GroupLinkIssue.NO_DLINK ->
            "网盘未返回此文件的下载地址，请稍后重试"
    }

    /**
     * 降级到 HLS 转码流的准入条件，抽成纯函数便于单测。
     *
     * 图片 / 文档 / 音频 / 我的网盘都没有转码流，一律按服务端限制如实报错；
     * `MISSING_FILE` 说明响应里的 fs_id 与下载记录对不上（记录串了或文件已删），
     * 拿同一份可疑参数去拉转码流也不该算成功，先让用户回列表重新下载。
     */
    fun shouldTryPlaylist(isVideo: Boolean, issue: GroupLinkIssue): Boolean =
        isVideo && issue != GroupLinkIssue.MISSING_FILE

    /**
     * 「自动转存到我的网盘」的准入，比 [shouldTryPlaylist] 更严一档。
     *
     * 转存是**会往用户网盘写文件**的操作（还可能再删掉刚写的副本），不能在任意取址
     * 失败时都触发：空 list / 无 dlink 多为临时状态，fs_id 对不上是记录问题，
     * 非视频文件更没有「原始 mp4」这回事。只有确认是「群组视频 + list 被加密」
     * —— 即服务端明确不发原始直链时，才值得花一次转存额度去换原始文件。
     */
    fun shouldTryPrefetch(isVideo: Boolean, issue: GroupLinkIssue): Boolean =
        isVideo && issue == GroupLinkIssue.ENCRYPTED_LIST

    const val ACCOUNT_ERROR = "无法确认当前百度网盘账号，请重新登录后重试"
    fun responseError(code: Int) = "百度网盘取址失败（HTTP $code，响应格式异常）"
}
