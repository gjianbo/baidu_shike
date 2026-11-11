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
 *
 * 注入的 lambda 都捕获 Activity，进程级单例长期持有会泄漏整个界面树；
 * 因此绑定记录 owner，由 MainActivity.onDestroy 调 [unbind] 清理。owner 令牌保证
 * 旧实例销毁（配置变更 / standard 启动模式下的多实例）不会误清新实例刚建立的绑定。
 */
internal object DriveSourceRouter {

    /** 由 MainActivity 在 onCreate 注入，返回当前活跃来源 id。 */
    private var activeSourceOwner: Any? = null
    private var activeSourceReader: () -> String = { "" }

    fun bindActiveSource(owner: Any, reader: () -> String) {
        activeSourceOwner = owner
        activeSourceReader = reader
    }

    /** 状态①：首页 / 群组的来源 id。 */
    fun activeSourceId(): String = activeSourceReader()

    /** 从前缀解析所属网盘；无前缀视为百度。 */
    fun providerOf(sourceId: String): SourceProvider = providerOfSourceId(sourceId)

    /** 状态②：网盘页所属网盘，由 MainActivity 注入并持久化。 */
    private var diskProviderOwner: Any? = null
    private var diskProviderReader: () -> SourceProvider = { SourceProvider.BAIDU }
    private var diskProviderWriter: (SourceProvider) -> Unit = {}

    fun bindDiskProvider(owner: Any, reader: () -> SourceProvider, writer: (SourceProvider) -> Unit) {
        diskProviderOwner = owner
        diskProviderReader = reader
        diskProviderWriter = writer
    }

    fun diskProvider(): SourceProvider = diskProviderReader()

    fun setDiskProvider(provider: SourceProvider) {
        diskProviderWriter(provider)
    }

    /** 只清理该 owner 自己注入的闭包，回到安全的默认实现。 */
    fun unbind(owner: Any) {
        if (activeSourceOwner === owner) {
            activeSourceOwner = null
            activeSourceReader = { "" }
        }
        if (diskProviderOwner === owner) {
            diskProviderOwner = null
            diskProviderReader = { SourceProvider.BAIDU }
            diskProviderWriter = {}
        }
    }
}
