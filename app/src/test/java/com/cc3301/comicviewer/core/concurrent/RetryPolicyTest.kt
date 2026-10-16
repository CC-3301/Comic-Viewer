package com.cc3301.comicviewer.core.concurrent

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 有界重试模块的接缝：退避表的封顶（越界取最后一项）、次数用尽、两个驱动的重试次数与取消传播。
 *
 * 三处调用点的口径（封面 4 次 + 抖动、网络来源 2 次不退避、SMB 1s→8s 封顶）各自在 `CoverRetryTest` /
 * `RemoteRetryTest` / `SmbSessionLifecycleTest` 里钉；这里只钉模块本身的规则。
 */
class RetryPolicyTest {

    @Test
    fun `退避表越界取最后一项 即封顶`() {
        val policy = RetryPolicy(maxAttempts = Int.MAX_VALUE, delaysMs = longArrayOf(1_000L, 2_000L, 4_000L, 8_000L))

        assertEquals(1_000L, policy.backoffMs(1))
        assertEquals(2_000L, policy.backoffMs(2))
        assertEquals(4_000L, policy.backoffMs(3))
        assertEquals(8_000L, policy.backoffMs(4))
        assertEquals("再失败也还是封顶（不会无限翻倍）", 8_000L, policy.backoffMs(5))
        assertEquals("很大的次数同样封顶（不会下标越界）", 8_000L, policy.backoffMs(100))
    }

    @Test
    fun `空退避表就是不退避`() {
        val policy = RetryPolicy(maxAttempts = 2)

        assertEquals(0L, policy.backoffMs(1))
        assertEquals("重试前的等待是 0，不是 null", 0L, policy.nextDelayMs(1))
        assertNull("次数用完才停", policy.nextDelayMs(2))
    }

    @Test
    fun `不设上限的策略不会用完次数`() {
        val policy = RetryPolicy.unbounded(delaysMs = longArrayOf(10L))

        assertEquals("表只有一项：每次都取它", 10L, policy.backoffMs(1))
        assertEquals(10L, policy.backoffMs(9))
        assertNotNull("第 100 万次仍给得出退避：这就是「不设上限」", policy.nextDelayMs(1_000_000))
    }

    @Test
    fun `抖动为 0 时退避就是表里的值`() {
        val policy = RetryPolicy(maxAttempts = 3, delaysMs = longArrayOf(500L), jitterFraction = 0.0)

        assertEquals(500L, policy.nextDelayMs(1))
        assertEquals("表只有一项：后面每次都取它", 500L, policy.nextDelayMs(2))
        assertNull(policy.nextDelayMs(3))
    }

    @Test
    fun `挂起驱动把取消原样抛出 不被吞成结果`() = runTest {
        val thrown = runCatching {
            driveRetries<String>(
                policy = RetryPolicy(maxAttempts = 4, delaysMs = longArrayOf(10L)),
                attempt = { throw CancellationException("格子滑走") },
                await = {},
            )
        }.exceptionOrNull()

        assertTrue("取数被取消必须传播，不能变成 Retryable", thrown is CancellationException)
    }

    @Test
    fun `挂起驱动在退避等待里被取消 原样抛出`() = runTest {
        var attempts = 0
        val thrown = runCatching {
            driveRetries<String>(
                policy = RetryPolicy(maxAttempts = 4, delaysMs = longArrayOf(10L)),
                attempt = { attempts++; RetryOutcome.Retryable },
                await = { throw CancellationException("滑走了") },
            )
        }.exceptionOrNull()

        assertTrue(thrown is CancellationException)
        assertEquals("取消发生在第一次退避上：只试了一次", 1, attempts)
    }

    @Test
    fun `同步驱动可恢复失败重连一次后成功`() {
        var reconnects = 0
        var attempts = 0
        val result = retryBlocking(
            policy = RetryPolicy(maxAttempts = 2),
            isRecoverable = { true },
            beforeRetry = { reconnects++ },
            block = {
                attempts++
                if (attempts == 1) throw IllegalStateException("断了") else "page"
            },
        )

        assertEquals("page", result)
        assertEquals("只重连一次", 1, reconnects)
        assertEquals(2, attempts)
    }

    @Test
    fun `同步驱动不可恢复失败不重试`() {
        var reconnects = 0
        assertThrows(IllegalStateException::class.java) {
            retryBlocking(
                policy = RetryPolicy(maxAttempts = 2),
                isRecoverable = { false },
                beforeRetry = { reconnects++ },
                block = { throw IllegalStateException("认证失败") },
            )
        }

        assertEquals("不该做无意义重连", 0, reconnects)
    }

    @Test
    fun `同步驱动重连后仍失败 异常冒泡且只试两次`() {
        var reconnects = 0
        var attempts = 0
        assertThrows(IllegalStateException::class.java) {
            retryBlocking(
                policy = RetryPolicy(maxAttempts = 2),
                isRecoverable = { true },
                beforeRetry = { reconnects++ },
                block = {
                    attempts++
                    throw IllegalStateException("还是断")
                },
            )
        }

        assertEquals(1, reconnects)
        assertEquals("总尝试次数 = 首次 + 一次重试", 2, attempts)
    }
}
