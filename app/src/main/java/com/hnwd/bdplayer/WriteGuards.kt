package com.hnwd.bdplayer

/**
 * 网盘写操作的互斥门。
 *
 * 网盘接口没有事务，删除又是「先核对、再提交、再回读确认」的三段式，
 * 所以同一时刻只允许一笔写操作或一笔删除核对在进行 —— 否则两笔操作会互相
 * 覆盖列表缓存，删除核对还可能拿着过期列表去比对 path -> fs_id。
 *
 * 这条不变式原来散在 [DiskMutationSection] 的两个布尔字段里，任何一个入口
 * 忘了判断就会破防；抽成一个小类之后，规则只有一处实现，也便于单测
 * （见 `WriteGuardsTest`）。
 *
 * 注意：这里只管「谁在占用」，不管「什么时候该释放」。
 * 释放时机与账号代次（probeId）绑定，仍然由调用方决定。
 */
internal class WriteGuards {
    var writeInFlight = false
        private set
    var deleteInFlight = false
        private set

    /** 有任一操作在飞。 */
    val busy: Boolean get() = writeInFlight || deleteInFlight

    /** 尝试开始一笔写操作；已被占用则返回 false，由调用方给用户提示。 */
    fun beginWrite(): Boolean {
        if (busy) return false
        writeInFlight = true
        return true
    }

    /** 尝试开始一次删除核对；已被占用则返回 false。 */
    fun beginDelete(): Boolean {
        if (busy) return false
        deleteInFlight = true
        return true
    }

    fun endWrite() {
        writeInFlight = false
    }

    fun endDelete() {
        deleteInFlight = false
    }

    /** 账号失效 / 切换：两把锁一起清，否则后续写操作会被「已有操作在处理」永久挡住。 */
    fun reset() {
        writeInFlight = false
        deleteInFlight = false
    }
}
