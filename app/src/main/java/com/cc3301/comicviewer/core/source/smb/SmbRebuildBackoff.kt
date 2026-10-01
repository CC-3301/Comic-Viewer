package com.cc3301.comicviewer.core.source.smb

/**
 * 重建失败的退避（修法 1；纯逻辑，由 [SmbRebuildBackoffTest] 锁死）。
 *
 * 为什么需要它（2026-09-29 的 SMB 设备日志）：一次会话失效会让**一批**读各自去建会话。
 * [SmbSessionGate] 只挡住了「重建期间不放行」，而重建**失败**之后闸必须放行（不然等着的读永远醒不来），
 * 于是下一批读看到 `share` 是空的，**再各自建一遍**……日志上就是十几秒里连续失败十几次，
 * 每一次都要把建连的等待（[SmbjTransport] 里 10 秒）付一遍。
 *
 * 退避把「一条会话连不上」的代价摊开：失败一次就安静一整个窗口，窗口内进来的读**就地失败**
 * （不排队、不再建，见 `SmbjTransport.withSession`），下一次真正的尝试要等窗口过去。
 *
 * 三条承重语义：
 * 1. **相邻失败翻倍**：1s → 2s → 4s，封顶 8s（数字是 2026-09-29 的，不是可调参数）；
 * 2. **成功即清零**（[recordSuccess]）：会话建起来了，退避不该再拦任何读；
 * 3. **尝试计数按「连续失败」算**（[nextAttempt] = 连续失败次数 + 1）：`smbRebuild attempt=` 报的就是它，
 *    一次成功之后下一轮重建从 1 重新数（设备上「一共试了几次」看的是同一轮里的那几个数）。
 *
 * 时间源可注入（[nanoTime]）：这是个纯状态机，用例拿假时钟推进窗口，不靠 sleep 决定成败。
 * 线程安全：每一条失败的读都可能在记录失败，因此每个方法都加锁。
 */
internal class SmbRebuildBackoff(
    private val nanoTime: () -> Long = System::nanoTime,
) {

    /** 连续失败次数（一次成功清零）：退避时长与 [nextAttempt] 都由它推出来 */
    private var consecutiveFailures = 0

    /** 退避窗口的结束时刻（[nanoTime] 口径）；`0` = 没有窗口 */
    private var blockedUntilNanos = 0L

    /** 下一次重建是第几次尝试（连续失败次数 + 1）：进 `smbRebuild` 行 */
    @Synchronized
    fun nextAttempt(): Int = consecutiveFailures + 1

    /** 是不是还在退避窗口里（窗口内不许开始任何重建） */
    @Synchronized
    fun isBackingOff(): Boolean = nanoTime() < blockedUntilNanos

    /** 一次重建失败：连续失败 +1，并按 [delayMsFor] 开一个退避窗口（从**这一次失败的时刻**起算） */
    @Synchronized
    fun recordFailure() {
        consecutiveFailures++
        blockedUntilNanos = nanoTime() + delayMsFor(consecutiveFailures) * NANOS_PER_MS
    }

    /** 一次重建成功：退避立刻清零（连续失败归零、窗口马上结束） */
    @Synchronized
    fun recordSuccess() {
        consecutiveFailures = 0
        blockedUntilNanos = 0L
    }

    /** 第 `failures` 次连续失败对应的退避时长：1s → 2s → 4s，封顶 [MAX_DELAY_MS] */
    private fun delayMsFor(failures: Int): Long =
        (BASE_DELAY_MS shl (failures - 1).coerceAtMost(MAX_SHIFT)).coerceAtMost(MAX_DELAY_MS)

    companion object {
        /** 第一次重建失败的退避（2026-09-29 ：1 → 2 → 4，封顶 8 秒） */
        const val BASE_DELAY_MS: Long = 1_000L

        /** 退避上限 8 秒：再久就不是「压住失败次数」而是「会话一直不可用」了（也用例钉住） */
        const val MAX_DELAY_MS: Long = 8_000L

        private const val NANOS_PER_MS: Long = 1_000_000L

        /** 移位上限：封顶前不让 `BASE_DELAY_MS shl shift` 溢出（1s shl 13 已远超 8 秒上限） */
        private const val MAX_SHIFT: Int = 13
    }
}

/**
 * 一次会话建立（新建或重建）里可以失败的**四段**（打点 2 的 `smbRebuild failed=` 取值）：
 * 关旧会话 / 连接 / 认证 / 进共享。顺序就是 `SmbjTransport.connectedShare` 里的执行顺序，
 * 因此「失败在哪一段」直接指到那一行代码。
 */
internal enum class SmbRebuildSegment(val token: String) {
    CLOSE("close"),
    CONNECT("connect"),
    AUTH("auth"),
    SHARE("share"),
}

/**
 * 退避期内被就地拒掉的读（修法 1）。
 *
 * 单独一个类型只为让 `smbReadFail kind=` 分得出 [SmbReadFailKind.BACKOFF]：设备上判读「修法 1 生效了没有」
 * 靠的就是这一类与真读失败分开（`ms≈0` + 紧跟在一条 `smbRebuild failed=` 之后）。
 * 它是 [SmbException] 的子类：装饰器与上层的失败归类路径一行都不用改（`asSmbException` 原样透传）。
 *
 * **不是可重连故障**：它的 kind 是 [SmbFailureKind.OTHER]（消息里没有超时/认证/不存在任一标记），
 * 因此 `retryOnce` 不会对它做无意义的重试——重试又会被退避挡回来。
 */
internal class SmbRebuildBackedOffException(
    message: String,
) : SmbException(SmbFailureKind.OTHER, message)
