package com.cc3301.comicviewer.core.view

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

/**
 * 同一 id 的封面字节**在飞合并**（票 #108 r6）：预取与可见行会同时要同一张封面，来源侧只有结果缓存，
 * 不合并就会在 SMB/WebDAV 上把同一张取两遍（慢来源上首屏反而更慢）。
 *
 * 判别用例：第 1 条（并发只执行一次）、第 3 条（主人失败时等待方自己再取一遍——不把别人的失败当成自己的结果）。
 */
class CoverByteRequestsTest {

    @Test
    fun `同一 id 的并发请求只执行一次 且都拿到同一份结果`() = runTest {
        val requests = CoverByteRequests()
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        var calls = 0
        val sizes = mutableListOf<Int?>()

        val jobs = List(3) {
            launch {
                sizes += requests.load("a") {
                    calls++
                    started.complete(Unit)
                    release.await()
                    byteArrayOf(1, 2, 3)
                }?.size
            }
        }
        started.await() // 主人已进入取字节
        runCurrent() // 另外两个调用者挂到同一份在飞请求上
        release.complete(Unit)
        jobs.forEach { it.join() }

        assertEquals("三个并发调用只执行一次取字节", 1, calls)
        assertEquals("三个都拿到同一份结果", listOf(3, 3, 3), sizes)
    }

    @Test
    fun `不同 id 各自执行`() = runTest {
        val requests = CoverByteRequests()
        var calls = 0

        val results = listOf("a", "b").map { id ->
            async { requests.load(id) { calls++; byteArrayOf(1) }?.size }
        }.map { it.await() }

        assertEquals("两条不同条目各取一次", 2, calls)
        assertEquals(listOf(1, 1), results)
    }

    @Test
    fun `主人失败时等待方自己再取一遍`() = runTest {
        val requests = CoverByteRequests()
        val gate = CompletableDeferred<Unit>()
        var calls = 0

        val owner = launch {
            runCatching {
                requests.load("a") {
                    calls++
                    gate.await()
                    throw IllegalStateException("来源断了")
                }
            }
        }
        runCurrent() // 主人进入取字节并挂在 gate 上
        val waiter = async { requests.load("a") { calls++; byteArrayOf(7, 7) } }
        runCurrent() // 等待方并入在飞请求，自己不再取
        assertEquals("等待方并入在飞请求，没有另发一次", 1, calls)

        gate.complete(Unit) // 主人这次失败
        owner.join()

        assertEquals("等待方自己再取一遍并拿到结果", 2, waiter.await()?.size)
        assertEquals("一共两次（主人一次 + 等待方一次）", 2, calls)
    }

    @Test
    fun `主人被取消时等待方自己再取一遍`() = runTest {
        // 预取窗口一变（快速滑动）`collectLatest` 就会取消上一批在飞的请求：等待方（可见行）不能把
        // 别人的取消当成自己的结果，否则那张封面会卡在骨架占位到下一次重键
        val requests = CoverByteRequests()
        val gate = CompletableDeferred<Unit>()
        var calls = 0
        val ownerScope = CoroutineScope(StandardTestDispatcher(testScheduler))

        ownerScope.launch {
            runCatching { requests.load("a") { calls++; gate.await(); byteArrayOf(1) } }
        }
        runCurrent() // 主人进入取字节并挂在 gate 上
        val waiter = async { requests.load("a") { calls++; byteArrayOf(7, 7) } }
        runCurrent() // 等待方并入在飞请求
        assertEquals("等待方并入在飞请求", 1, calls)

        ownerScope.cancel() // 主人在飞请求被取消
        runCurrent()

        assertEquals("等待方自己再取一遍并拿到结果", 2, waiter.await()?.size)
        assertEquals("一共两次（主人一次 + 等待方一次）", 2, calls)
    }

    @Test
    fun `结果不留在在飞表里`() = runTest {
        val requests = CoverByteRequests()
        var calls = 0

        requests.load("a") { calls++; byteArrayOf(1) }
        val second = requests.load("a") { calls++; byteArrayOf(1, 2) }

        assertEquals("取完即出表：下一次是新一次（结果缓存是来源自己的事）", 2, calls)
        assertNotNull(second)
    }
}
