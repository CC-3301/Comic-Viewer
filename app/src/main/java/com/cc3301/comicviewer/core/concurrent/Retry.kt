package com.cc3301.comicviewer.core.concurrent

/**
 * **有界重试的策略**（纯参数，无状态）：尝试次数、退避表、抖动比例。
 * 仓里三处重试口径都由它表达，调用点不再自持退避常量：
 *
 * - 封面 / 阅读菜单预览格取图：4 次尝试，退避 500ms → 1.5s → 4s，每个值 ±25% 抖动（见 `core/view/CoverRetry`）；
 * - 网络来源（SMB / WebDAV / Komga）重连：2 次尝试，不退避（见 `core/source/remote/RemoteRetry`）；
 * - SMB 会话重建：退避 1s → 2s → 4s → 8s 封顶（见 `core/source/smb/SmbSessionLifecycle`）。
 *
 * 退避按第几次失败（1-based）取表里的第几项；下标越过表尾时取**最后一项**——最后一项就是稳态，也就是封顶。
 * 抖动是为了不让一屏里同时失败的几十格在同一刻一起重发（重试风暴）；随机源可注入，测试给固定值即可钉住上下界。
 */
internal class RetryPolicy(
    /** 最多尝试几次（首发 1 次 + 重试 maxAttempts − 1 次） */
    val maxAttempts: Int,
    /** 退避表（毫秒）：第 n 次失败后等 `delaysMs[n-1]`；越界取最后一项；空表 = 不退避 */
    val delaysMs: LongArray = LongArray(0),
    /** 退避的抖动幅度（每个值 ±[jitterFraction]） */
    val jitterFraction: Double = 0.0,
    /** 抖动随机源（`[0,1)`）：**可注入**，测试给固定值就能钉住退避的确切毫秒 */
    private val random: () -> Double = Math::random,
) {

    /**
     * 第 [attempt] 次（1-based）**已经失败**：返回下一次重试前的等待毫秒；
     * 次数已用完（[attempt] >= [maxAttempts]）返回 null ⇒ 调用方停止重试。
     */
    fun nextDelayMs(attempt: Int): Long? {
        if (attempt >= maxAttempts) return null
        return backoffMs(attempt)
    }

    /**
     * 第 [attempt] 次连续失败对应的退避时长（毫秒）：下标越过表尾取最后一项（即封顶），空表不退避（0）。
     * 不判次数上限——调用方要「还剩几次」用 [nextDelayMs]。
     */
    fun backoffMs(attempt: Int): Long {
        if (delaysMs.isEmpty()) return 0L
        val base = delaysMs[(attempt - 1).coerceIn(0, delaysMs.size - 1)]
        if (jitterFraction <= 0.0) return base
        val jitter = 1.0 - jitterFraction + random().coerceIn(0.0, 1.0) * 2 * jitterFraction
        return (base * jitter).toLong().coerceAtLeast(1L)
    }
}

/**
 * 一次尝试的结果。三态而不是两态：**「这次没拿到」与「这条路根本走不通」要分开**——
 * 例如封面字节到手但解码失败是确定性的，重试只是白花三次请求。
 *
 * [T] = 成功的产物类型（封面是位图、页是字节）：本模块不引具体类型，型参交给调用方填。
 */
internal sealed interface RetryOutcome<out T> {

    /** 拿到了（可能是内存命中、也可能是这次取解出来的） */
    data class Loaded<T>(val value: T) : RetryOutcome<T>

    /** 这次没拿到（来源给了 null / 抛了异常）：值得重试，次数由 [RetryPolicy] 兜 */
    data object Retryable : RetryOutcome<Nothing>

    /** 确定性失败（路径根本走不通）：重试没有意义，到此为止 */
    data object Fatal : RetryOutcome<Nothing>
}

/**
 * 有界重试的**挂起驱动**：反复调 [attempt]（1-based 次数）直到拿到结果、或碰上 [RetryOutcome.Fatal]、
 * 或次数用完；两次之间用 [await] 等 [policy] 给的时间。
 *
 * 取消不在本函数里判——调用方协程被取消后 [await] / [attempt] 抛出的 `CancellationException` 自然终止。
 */
internal suspend fun <T> driveRetries(
    policy: RetryPolicy,
    attempt: suspend (attemptNumber: Int) -> RetryOutcome<T>,
    await: suspend (millis: Long) -> Unit,
): RetryOutcome<T> {
    var attemptNumber = 1
    while (true) {
        when (val outcome = attempt(attemptNumber)) {
            is RetryOutcome.Loaded -> return outcome
            RetryOutcome.Fatal -> return RetryOutcome.Fatal
            RetryOutcome.Retryable -> {
                val delayMs = policy.nextDelayMs(attemptNumber) ?: return RetryOutcome.Retryable
                await(delayMs)
                attemptNumber++
            }
        }
    }
}

/**
 * 有界重试的**同步（阻塞）驱动**：同一套 [policy] 的次数上限，用于阻塞调用点（网络来源的读）。
 * 两次之间不等待——阻塞点上的重连是即时重试，退避表为空。
 * [isRecoverable] 为假或次数用尽时，最后一次失败原样上抛；[beforeRetry] 在每次重试前跑一次（重连）。
 */
internal inline fun <T> retryBlocking(
    policy: RetryPolicy,
    isRecoverable: (Throwable) -> Boolean,
    beforeRetry: (attempt: Int) -> Unit,
    block: () -> T,
): T {
    var attempt = 1
    while (true) {
        try {
            return block()
        } catch (t: Throwable) {
            if (!isRecoverable(t) || attempt >= policy.maxAttempts) throw t
            attempt++
            beforeRetry(attempt)
        }
    }
}
