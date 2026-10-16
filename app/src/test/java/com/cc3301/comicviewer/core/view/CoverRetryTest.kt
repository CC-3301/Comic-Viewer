package com.cc3301.comicviewer.core.view

import com.cc3301.comicviewer.core.concurrent.RetryOutcome
import com.cc3301.comicviewer.core.concurrent.driveRetries
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 可见行封面加载的**有界重试策略**：首发 1 次 + 重试 3 次，退避 500ms → 1.5s → 4s，每个值 ±25% 抖动。
 *
 * 为什么值得钉：来源区分不了「真没有封面」与「这次断了」（两个 `coverBytes` 实现都把失败给成 null），
 * 不重试就等于一格永久的灰底——一次性失败被当成终态。上限与抖动又都必须真的存在：
 * 没有上限会把「真没封面」的条目反复拉，没有抖动会让一屏同时失败的那几十格在同一刻一起重发。
 *
 * 口径只有 [coverRetryPolicy] 一处声明；这里只换掉它的抖动随机源，钉上下界与「四舍五入到毫秒」。
 */
class CoverRetryTest {

    private fun retry(random: Double) = coverRetryPolicy.withRandom { random }

    @Test
    fun `最多四次尝试 首发一次加三次重试`() {
        assertEquals("重试上限也是口径：只改常量而不用例钉住就会静默漂移", 4, coverRetryPolicy.maxAttempts)
        assertEquals("退避基准三个值，与三次重试一一对应", 3, coverRetryPolicy.delaysMs.size)
        val retry = retry(0.5)
        assertEquals(500L, retry.nextDelayMs(1))
        assertEquals(1_500L, retry.nextDelayMs(2))
        assertEquals(4_000L, retry.nextDelayMs(3))
        assertNull("第 4 次已经用完次数：不再给下一次的等待", retry.nextDelayMs(4))
        assertNull("越界的次数同样不给（不会退避到下标越界）", retry.nextDelayMs(9))
    }

    @Test
    fun `退避带正负 25% 抖动`() {
        assertEquals("随机源取 0 ⇒ 下界 75%", 375L, retry(0.0).nextDelayMs(1))
        assertEquals("随机源取 1 ⇒ 上界 125%", 625L, retry(1.0).nextDelayMs(1))
        assertEquals(3_000L, retry(0.0).nextDelayMs(3))
        assertEquals(5_000L, retry(1.0).nextDelayMs(3))
    }

    @Test
    fun `抖动不会给出非正的等待`() {
        // 随机源是注入点：越界值不该让退避变成 0 或负数（那样就成了忙等）
        assertTrue(retry(-5.0).nextDelayMs(1)!! >= 1L)
        assertTrue(retry(5.0).nextDelayMs(1)!! >= 1L)
    }

    @Test
    fun `第一次没拿到就接着试 拿到为止`() = runTest {
        val waits = mutableListOf<Long>()
        val attempts = mutableListOf<Int>()
        val outcome = driveRetries(
            policy = retry(0.5),
            attempt = { n ->
                attempts += n
                if (n < 3) RetryOutcome.Retryable else RetryOutcome.Loaded("图$n")
            },
            await = { waits += it },
        )

        assertEquals("第 3 次才成", RetryOutcome.Loaded("图3"), outcome)
        assertEquals("每次尝试都拿到了自己的次数（1-based）", listOf(1, 2, 3), attempts)
        assertEquals("前两次失败各等一次退避", listOf(500L, 1_500L), waits)
    }

    @Test
    fun `用满四次就不再试 且不再等`() = runTest {
        val waits = mutableListOf<Long>()
        var attempts = 0
        val outcome = driveRetries(
            policy = retry(0.5),
            attempt = { attempts++; RetryOutcome.Retryable },
            await = { waits += it },
        )

        assertEquals("四次用满仍是「这次没拿到」", RetryOutcome.Retryable, outcome)
        assertEquals("尝试次数 = 上限", 4, attempts)
        assertEquals("三次重试各等一次，第 4 次失败后不再等", listOf(500L, 1_500L, 4_000L), waits)
    }

    @Test
    fun `字节到手但解不出来 不重试`() = runTest {
        // 确定性失败：重试只是白花三次请求。三态分开就是为了这一条。
        var attempts = 0
        val waits = mutableListOf<Long>()
        val outcome = driveRetries(
            policy = retry(0.5),
            attempt = { attempts++; RetryOutcome.Fatal },
            await = { waits += it },
        )

        assertEquals(RetryOutcome.Fatal, outcome)
        assertEquals("一次就停", 1, attempts)
        assertEquals("没有等待", emptyList<Long>(), waits)
    }

    @Test
    fun `内存命中直接返回 不产重试`() = runTest {
        var attempts = 0
        val outcome = driveRetries(
            policy = retry(0.5),
            attempt = { attempts++; RetryOutcome.Loaded("已经解好的那张") },
            await = { error("命中不该等") },
        )

        assertEquals(RetryOutcome.Loaded("已经解好的那张"), outcome)
        assertEquals(1, attempts)
    }
}
