package com.cc3301.comicviewer.core.view

/**
 * 可见行封面加载的**有界重试**（纯策略，由 `CoverRetryTest` 锁定）。
 * 同一套口径也用在**阅读菜单预览格的缩略图**上（`ui/PagePreviewLoad`）：策略只有这一处声明。
 *
 * 为什么要有：来源对「真的没有封面」与「这次取不到」都给 null（`Source.coverBytes` 的契约，
 * 两个实现都这么写），界面因此无法把瞬断与永久缺失分开。不重试的后果是**一格永久的灰底**：
 * 一次性失败被当成终态，格子不会再自己试；而快速拖动时一次失败的概率并不低。
 *
 * 口径：**首发 1 次 + 重试 3 次**（最多 [maxAttempts] = 4 次尝试），
 * 退避 [BASE_DELAYS_MS]（500ms → 1.5s → 4s），每个值 ±[JITTER_FRACTION] 抖动。
 * 抖动是为了不让一屏里同时失败的几十格在同一刻一起重发（重试风暴）——
 * 因此它必须真的随机，测试则注入固定随机源钉住上下界。
 *
 * 计数与退避只在本类里：调用方（`ui/CoverThumb` 与 `ui/PagePreviewLoad`）只问「下一次等多久」。
 */
internal class CoverRetry(
    /** 抖动随机源（`[0,1)`）：**可注入**，测试给固定值就能钉住退避的确切毫秒 */
    private val random: () -> Double = Math::random,
    val maxAttempts: Int = MAX_ATTEMPTS,
) {

    /**
     * 第 [attempt] 次（1-based）**已经失败**：返回下一次重试前的等待毫秒；
     * 次数已用完（[attempt] >= [maxAttempts]）返回 null ⇒ 调用方停止重试。
     */
    fun nextDelayMs(attempt: Int): Long? {
        if (attempt >= maxAttempts) return null
        val base = BASE_DELAYS_MS[attempt - 1]
        val jitter = 1.0 - JITTER_FRACTION + random().coerceIn(0.0, 1.0) * 2 * JITTER_FRACTION
        return (base * jitter).toLong().coerceAtLeast(1L)
    }

    companion object {
        /** 最多几次尝试（首发 1 + 重试 3） */
        const val MAX_ATTEMPTS: Int = 4

        /** 三次重试的退避基准（毫秒）：500ms → 1.5s → 4s */
        val BASE_DELAYS_MS: LongArray = longArrayOf(500L, 1_500L, 4_000L)

        /** 退避的抖动幅度（每个值 ±25%） */
        const val JITTER_FRACTION: Double = 0.25
    }
}

/**
 * 一次封面取解尝试的结果。三态而不是两态：**「这次没拿到」与「这条路根本解不出来」要分开**——
 * 字节到手但解码失败是确定性的，重试只是白花三次请求（有界上限兜不住的是「本来就不该试」）。
 *
 * [T] = 位图类型（生产是 `ui/PageDecoder` 那份封面图）：本包不引 Compose 类型，型参交给调用方填。
 */
internal sealed interface CoverLoadOutcome<out T> {

    /** 位图到手（可能是内存命中、也可能是这次取解出来的） */
    data class Loaded<T>(val bitmap: T) : CoverLoadOutcome<T>

    /** 这次没拿到（来源给了 null / 抛了异常）：值得重试，次数由 [CoverRetry] 兜 */
    data object Retryable : CoverLoadOutcome<Nothing>

    /** 确定性失败（字节到手但解不出来）：重试没有意义，到此为止 */
    data object Fatal : CoverLoadOutcome<Nothing>
}

/**
 * 有界重试的驱动（`CoverRetryTest` 锁定）：反复调 [attempt]（1-based 次数）直到拿到结果、
 * 或碰上 [CoverLoadOutcome.Fatal]、或次数用完；两次之间用 [await] 等 [CoverRetry] 给的时间。
 *
 * 为什么抽成挂起函数而不是写在组合里：退避与上限是**可单测的策略**，而 `LaunchedEffect` 里的循环
 * 在 JVM 单测里跑不到（`CoverThumb` 是 composable）。取消（格子滑走）不在本函数里判——
 * 它靠调用方协程被取消后 [await] / [attempt] 抛出的 `CancellationException` 自然终止。
 */
internal suspend fun <T> driveCoverRetries(
    retry: CoverRetry,
    attempt: suspend (attemptNumber: Int) -> CoverLoadOutcome<T>,
    await: suspend (millis: Long) -> Unit,
): CoverLoadOutcome<T> {
    var attemptNumber = 1
    while (true) {
        when (val outcome = attempt(attemptNumber)) {
            is CoverLoadOutcome.Loaded -> return outcome
            CoverLoadOutcome.Fatal -> return CoverLoadOutcome.Fatal
            CoverLoadOutcome.Retryable -> {
                val delayMs = retry.nextDelayMs(attemptNumber) ?: return CoverLoadOutcome.Retryable
                await(delayMs)
                attemptNumber++
            }
        }
    }
}
