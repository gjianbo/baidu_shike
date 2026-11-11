package com.hnwd.bdplayer

import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/**
 * 应用级后台执行器，替代散落在各 Section / Activity 里的裸 `Thread { … }.start()`。
 *
 * 刻意用 **cached 池而不是 fixed/single 池**：原实现是「每任务一线程、无并发上限」，
 * 换成固定大小的池会把并行的网盘列表 IO 串行化，改变请求时序和超时表现。
 * cached 池保留并发语义，同时复用空闲线程，避免每次点击都新建一个 Thread。
 *
 * 线程池是进程级共享的，不随 Activity 生命周期 shutdown；任务内部仍按原有约定
 * 用 runCatching 兜底，并在回主线程前检查 isFinishing / isDestroyed / 代次。
 */
internal object AppIo {
    private val ids = AtomicInteger(0)

    private val pool = Executors.newCachedThreadPool { runnable ->
        Thread(runnable, "bdplayer-io-${ids.incrementAndGet()}").apply {
            isDaemon = true
        }
    }

    fun execute(block: () -> Unit) {
        pool.execute(block)
    }
}
