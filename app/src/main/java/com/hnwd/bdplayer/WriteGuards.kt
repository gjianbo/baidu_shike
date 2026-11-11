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
 * 每次抢锁会发一个单调递增的 token，回调必须把 token 带回来才能释放。
 * 删除链路是异步的：账号切换或 Activity 销毁之后，上一笔核对的回调仍可能抵达。
 * 若旧回调无差别地清锁，会把新会话刚抢到的锁一起带走，于是两笔删除核对
 * 得以并行；反过来若旧回调干脆不释放，锁又会一直卡到 reset()。
 * 有了 token，「释放的是不是自己那把锁」由本类保证，失效分支可以安全地主动释放。
 *
 * 释放时机仍由调用方决定；[reset] 只是账号失效时的兜底，不再是唯一出路。
 */
internal class WriteGuards {
    var writeInFlight = false
        private set
    var deleteInFlight = false
        private set

    /** 只增不减，[reset] 也不会归零 —— 保证旧 token 永远不会被后来者复用。 */
    private var serial = 0L

    /** 当前持锁代次，0 表示空闲；业务回调靠它判断自己是否还有效。 */
    var writeToken = 0L
        private set
    var deleteToken = 0L
        private set

    /** 有任一操作在飞。 */
    val busy: Boolean get() = writeInFlight || deleteInFlight

    /** 抢写锁；被占用返回 null，成功时返回本次持锁 token。 */
    fun beginWrite(): Long? {
        if (busy) return null
        writeInFlight = true
        writeToken = ++serial
        return writeToken
    }

    /** 抢删除核对通道；被占用返回 null，成功时返回本次持锁 token。 */
    fun beginDelete(): Long? {
        if (busy) return null
        deleteInFlight = true
        deleteToken = ++serial
        return deleteToken
    }

    /** 只有 token 匹配才释放；旧回调、重复回调、错误 token 都会被拒绝。 */
    fun endWrite(token: Long): Boolean {
        if (token == 0L || token != writeToken) return false
        writeInFlight = false
        writeToken = 0L
        return true
    }

    /** 只有 token 匹配才释放；旧回调、重复回调、错误 token 都会被拒绝。 */
    fun endDelete(token: Long): Boolean {
        if (token == 0L || token != deleteToken) return false
        deleteInFlight = false
        deleteToken = 0L
        return true
    }

    /** 账号失效 / 切换：两把锁一起清，否则后续写操作会被「已有操作在处理」永久挡住。 */
    fun reset() {
        writeInFlight = false
        deleteInFlight = false
        writeToken = 0L
        deleteToken = 0L
    }
}
