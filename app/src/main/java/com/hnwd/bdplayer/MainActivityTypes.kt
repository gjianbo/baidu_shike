package com.hnwd.bdplayer

/**
 * MainActivity 从「单文件私有嵌套类型」拆出的共享类型。
 *
 * 拆文件前这些类型必须先在包内可见：`Screen`/`RequestLane` 这类枚举被渲染、网络、
 * 写操作三块共用，留在 Activity 内部会导致任何 Section 抽取都要把整个 Activity 传过去。
 * 只做可见性提升，成员定义与语义完全不变。
 */

internal enum class Screen { HOME, DISK, GROUPS, FAVORITES, SETTINGS }

/** 播放取址走哪条链路：系统音频会话，还是网页兜底播放。 */
internal enum class PlaybackRoute { AUDIO, WEB_AUDIO }

internal enum class MediaFilter(val label: String) {
    ALL("全部"), AUDIO("音频"), VIDEO("视频"), DOCUMENT("文档"), DIRECTORY("目录");

    fun includes(kind: MediaKind): Boolean = when (this) {
        ALL -> true
        DIRECTORY -> kind == MediaKind.DIRECTORY
        DOCUMENT -> kind == MediaKind.PDF || kind == MediaKind.DOCUMENT || kind == MediaKind.IMAGE
        AUDIO -> kind == MediaKind.AUDIO
        VIDEO -> kind == MediaKind.VIDEO
    }
}

internal enum class CourseOrder(val label: String) {
    ASCENDING("名称升序"),
    DESCENDING("名称降序"),
    NETWORK("网盘顺序"),
}

/** 请求通道：同一通道的新请求会作废旧请求的回调。 */
internal enum class RequestLane { LIST, SEARCH, COURSE, AUDIO, RENEWAL, WRITE, DELETE_CHECK }

internal enum class SearchScope(val label: String) { DISK("我的网盘"), GROUP("群组"), ALL("全部") }

internal data class SearchTarget(val scope: SearchScope, val sourceId: String, val name: String)

/** 已配置的来源（百度群组 / 联系人 / 夸克监控目录）。 */
internal data class ShareSource(
    val id: String,
    val name: String,
    val sessionUrl: String = "",
    val provider: SourceProvider = SourceProvider.BAIDU,
)

internal data class SheetChoice(
    val title: String,
    val meta: String = "",
    val checked: Boolean = false,
    val tag: Any? = null,
)

internal data class FolderPosition(
    val scrollY: Int,
    val filter: MediaFilter,
    val transferredOnly: Boolean,
)

internal data class QueueSelection(val entry: PanEntry, val entries: List<PanEntry>, val listKey: String?)

internal data class RetainedPlayback(
    val video: VideoPlaybackPage.Snapshot?,
    val selection: QueueSelection?,
)
