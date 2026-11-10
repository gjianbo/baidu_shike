package com.hnwd.bdplayer

/**
 * 多网盘分派点收敛处（首版骨架）。
 *
 * 两个独立状态：
 * - 状态① [activeSourceId]：首页「群最新更新」与群组页的来源，带 provider 前缀；由 MainActivity 注入读取。
 * - 状态② [diskProvider]：网盘页所属网盘，首版恒为百度，Step 6 再接入持久化。
 *
 * 本骨架只做「判断收口」，数据访问仍直调 [PanApi] / [QuarkPersonalDrive]，
 * 后续可平滑替换为分层 Provider（见 多网盘融合-重构实施方案.md v0.2）。
 */
internal object DriveSourceRouter {

    /** 由 MainActivity 在 onCreate 注入，返回当前活跃来源 id。 */
    private var activeSourceReader: () -> String = { "" }

    fun bindActiveSource(reader: () -> String) {
        activeSourceReader = reader
    }

    /** 状态①：首页 / 群组的来源 id。 */
    fun activeSourceId(): String = activeSourceReader()

    /** 从前缀解析所属网盘；无前缀视为百度。 */
    fun providerOf(sourceId: String): SourceProvider = providerOfSourceId(sourceId)

    /** 状态②：网盘页所属网盘。首版只有百度，Step 6 接入切换。 */
    fun diskProvider(): SourceProvider = SourceProvider.BAIDU

    @Suppress("UNUSED_PARAMETER")
    fun setDiskProvider(provider: SourceProvider) {
        // Step 6 落持久化与列表重载
    }
}
