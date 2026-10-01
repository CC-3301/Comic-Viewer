package com.cc3301.comicviewer.core.view

import kotlinx.coroutines.CompletableDeferred

/**
 * 一条封面取字节请求的**优先级**：可见格是「用户此刻正看着的那张」，预取是「提前弄好」。
 * 闸上按它决定排队序——不是抢占（已经在飞的往返不打断，往返是扣不回来的成本）。
 */
internal enum class CoverBytePriority {
    /** 可见格要的那张（当前屏上正显示 / 正在滚进来的） */
    Visible,

    /** 预取要的那张（可见区 ±1 屏里、用户还没滚到的那几张） */
    Prefetch,
}

/**
 * 封面**字节**取数的并发闸（纯内存状态，由 [CoverByteGateTest] 锁定）。
 *
 * 为什么需要它（维护者 2026-09-27：冷启动退出阅读器落到浏览页，**头 5~7 秒**滑动不顺畅、之后封面进缓存就顺了）：
 * 一屏 12~18 张封面在冷缓存期同时「取字节 + 解码 + 上屏」，单张 300~460ms（实测取字节占 81%），
 * 全叠在一起就是那几秒里「整窗都在超预算」。压到同时 2~4 张在飞、越界的排队，整窗超预算那种形态就消掉了；
 * 代价是封面逐张出现（已有的骨架 + 淡入兜住观感）。
 *
 * 口径（票面「下一步三件事」第 3 条）：
 * - **只给取字节上闸，解码放行**——调用方（`CoverByteRequests.load`）把闸包在取字节那一段上，
 *   解码在闸外发生，因此不是「同时解码几张」被限，而是「同时对来源发几次请求」被限；
 * - **可见优先**：取牌顺序上可见格插到**已排队的**预取之前（预取排队时让位）；已经在飞的那张不抢占；
 * - 许可数是**每份调用方各一份**（`BrowserScreen` 每次进容器 new 一个 `CoverByteRequests`，
 *   闸在它里面）——两屏的封面互不挤占，与「一屏一份在飞去重表」同一作用域。
 *
 * 取消安全：等待方被取消时把自己的排队位摘掉；牌子若已被交给它（取消与释放撞在一起），
 * 就顺手往下传，**不让名额漏掉**（漏一张就等于闸位少一个，滑久了会把并发压到 1）。
 *
 * 状态用 `synchronized` 而不是 `Mutex`：临界区全是非挂起代码（挂起点只在 `await` 上），
 * 用互斥锁会把「释放」变成挂起函数，而释放发生在 `finally` 里（取消路径上不能再挂起）。
 */
internal class CoverByteGate(
    maxConcurrent: Int = MAX_CONCURRENT_BYTE_LOADS,
) {

    private val monitor = Any()

    /** 当前可用的牌数（上限非法时按 1 兜底：宁慢勿冲） */
    private var permits = maxConcurrent.coerceAtLeast(1)

    /** 等牌的**可见**请求（按先来后到） */
    private val visibleQueue = ArrayDeque<CompletableDeferred<Unit>>()

    /** 等牌的**预取**请求（按先来后到；只有可见队列空时才轮到） */
    private val prefetchQueue = ArrayDeque<CompletableDeferred<Unit>>()

    /**
     * 拿一张牌执行 [block]（取其返回值），结束或抛异常时归还。
     * [block] 里不要挂太久——它决定这张牌多久不放。
     */
    suspend fun <T> withPermit(priority: CoverBytePriority, block: suspend () -> T): T {
        acquire(priority)
        try {
            return block()
        } finally {
            synchronized(monitor) { handOverLocked() }
        }
    }

    /** 取一张牌：有空牌**且**（自己是可见的，或没有可见的在等）就直接拿，否则排队等交接 */
    private suspend fun acquire(priority: CoverBytePriority) {
        val waiter = CompletableDeferred<Unit>()
        synchronized(monitor) {
            if (permits > 0 && (priority == CoverBytePriority.Visible || visibleQueue.isEmpty())) {
                permits--
                return
            }
            if (priority == CoverBytePriority.Visible) visibleQueue.addLast(waiter) else prefetchQueue.addLast(waiter)
        }
        try {
            waiter.await()
        } catch (t: Throwable) {
            synchronized(monitor) {
                if (waiter.isCompleted) {
                    // 牌已经交到这次调用手上（交接与取消撞在一起）：往下传，不让名额漏掉
                    handOverLocked()
                } else {
                    visibleQueue.remove(waiter)
                    prefetchQueue.remove(waiter)
                }
            }
            throw t
        }
    }

    /** 调用方已持 [monitor]：把这张牌交给下一位等牌的（可见优先），没人等才收回来 */
    private fun handOverLocked() {
        val next = visibleQueue.removeFirstOrNull() ?: prefetchQueue.removeFirstOrNull()
        if (next == null) permits++ else next.complete(Unit)
    }

    companion object {
        /**
         * 同时最多几张封面字节在飞（票面「下一步三件事」第 3 条给的 4）：
         * 冷缓存期一屏 12~18 张要取，4 张一轮约三四轮取完，既摊平了绘制窗口，
         * 又不至于把「逐张出现」拖成肉眼可感的慢。
         */
        const val MAX_CONCURRENT_BYTE_LOADS: Int = 4
    }
}
