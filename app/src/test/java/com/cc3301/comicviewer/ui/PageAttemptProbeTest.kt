package com.cc3301.comicviewer.ui

import com.cc3301.comicviewer.core.source.BookHandle
import com.cc3301.comicviewer.core.source.PageData
import com.cc3301.comicviewer.core.source.PerfTiming
import com.cc3301.comicviewer.core.source.field
import com.cc3301.comicviewer.core.view.CoverBytePriority
import java.io.IOException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * 页取数的每次尝试一行 `pageAttempt`（判读口径的 home 在 `PageDecoder.loadPageBytes` 的 KDoc）。
 *
 * 为什么要它：`pageBytes` 只在读成功后打，取消与失败的尝试则一行不产（拖动跳页滑条那段时间就是一个盲区）。
 * 本行按尝试记，三态都有，来源分得开。
 *
 * 判别用例：第 1 条（成功一行 + 既有 `pageBytes` 字段数不变）、第 2 条（取消记 `cancelled`，**不是失败**）、
 * 第 3 条（来源抛异常记 `failed`）、第 4 条（同一页两方需要时只取一次、两方各一行且量测一致）、
 * 第 5 条（只有预览格让位）。
 */
class PageAttemptProbeTest {

    private class FakeHandle(
        override val id: String,
        private val bytes: ByteArray,
    ) : BookHandle {
        override val pageCount: Int = 1
        /** 取过几次字节：在飞合并用例据此断言「只向来源取一次」 */
        var loads = 0

        override suspend fun loadPage(index: Int): PageData {
            loads++
            return PageData(bytes, "image/jpeg")
        }
    }

    /** 取字节会一直挂着（等 [release]）的假书：用来在「取数在飞」这一刻做断言 */
    private class HangingHandle(override val id: String) : BookHandle {
        override val pageCount: Int = 1
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        var loads = 0

        override suspend fun loadPage(index: Int): PageData {
            loads++
            entered.complete(Unit)
            release.await()
            return PageData(ByteArray(64) { 9 }, "image/jpeg")
        }
    }

    /** 取字节当场抛非取消异常的假书 */
    private class FailingHandle(override val id: String) : BookHandle {
        override val pageCount: Int = 1

        override suspend fun loadPage(index: Int): PageData = throw IOException("这次取不到")
    }

    private val lines = PerfTiming.newRecordedLinesForTest()

    @Before
    fun 打开量测开关() {
        PerfTiming.forcedForTest = true
        PerfTiming.recordedLinesForTest = lines
    }

    @After
    fun 收口() {
        PerfTiming.forcedForTest = null
        PerfTiming.recordedLinesForTest = null
        lines.clear()
    }

    @Test
    fun `成功的那一次落一行 且既有 pageBytes 的字段数不变`() {
        val handle = FakeHandle("page-attempt-ok", ByteArray(64) { 7 })

        val bytes = runBlocking {
            PageDecoder.loadPageBytes(handle, 3, PageLoadTrace(PageLoadSource.Visible, 1, System.nanoTime()))
        }

        assertEquals("拿到的就是句柄给的字节", 64, bytes.size)
        val attempt = lines.toList().single { it.startsWith("pageAttempt") }
        assertEquals("page-attempt-ok", field(attempt, "book"))
        assertEquals("3", field(attempt, "index"))
        assertEquals("visible", field(attempt, "source"))
        assertEquals("1", field(attempt, "attempt"))
        assertEquals("success", field(attempt, "result"))
        assertEquals("磁盘缓存没初始化 ⇒ 字节当场向来源取", "false", field(attempt, "disk"))
        assertTrue("waitMs 与 ms 都得在（不是缺失字段）", field(attempt, "waitMs").toLong() >= 0)
        assertTrue("waitMs 与 ms 都得在（不是缺失字段）", field(attempt, "ms").toLong() >= 0)
        assertEquals(
            "本行的字段一个不多一个不少（前缀 + book/index/source/attempt/result/disk/waitMs/ms）",
            9,
            attempt.split(' ').size,
        )

        // 既有三行口径一个字不改：`pageBytes` 仍是五个字段（前缀 + book/index/disk/bytes/ms）、仍不发 net=
        val pageBytes = lines.toList().single { it.startsWith("pageBytes") }
        assertEquals(6, pageBytes.split(' ').size)
        assertEquals("64", field(pageBytes, "bytes"))
        assertEquals("false", field(pageBytes, "disk"))
        assertFalse(pageBytes.contains("net="))
    }

    @Test
    fun `取消的那一次落一行 cancelled 而不是失败`() = runTest {
        val handle = HangingHandle("page-attempt-cancelled")

        val job = launch {
            PageDecoder.loadPageBytes(handle, 0, PageLoadTrace(PageLoadSource.Preview, 1, System.nanoTime()))
        }
        handle.entered.await() // 取数已经在飞
        job.cancelAndJoin()

        val line = lines.toList().single { it.startsWith("pageAttempt") }
        assertEquals("取消不是失败尝试", "cancelled", field(line, "result"))
        assertEquals("取消的这一次是预览格发起的", "preview", field(line, "source"))
        assertEquals("第 1 次尝试就是取消的那一次", "1", field(line, "attempt"))
    }

    @Test
    fun `来源抛非取消异常时落一行 failed`() = runTest {
        val handle = FailingHandle("page-attempt-failed")
        var thrown: Throwable? = null

        try {
            PageDecoder.loadPageBytes(handle, 0, PageLoadTrace(PageLoadSource.Visible, 1, System.nanoTime()))
        } catch (t: Throwable) {
            thrown = t
        }

        assertTrue("异常照常上抛（不吞成 null）", thrown is IOException)
        val line = lines.toList().single { it.startsWith("pageAttempt") }
        assertEquals("failed", field(line, "result"))
        assertEquals("第 1 次尝试就抛了", "1", field(line, "attempt"))
    }

    @Test
    fun `同一页被可见页与预览格同时需要时只取一次 两方各落一行`() = runTest {
        val handle = HangingHandle("page-attempt-merged")

        val preview = async {
            PageDecoder.loadPageBytes(handle, 0, PageLoadTrace(PageLoadSource.Preview, 1, System.nanoTime()))
        }
        handle.entered.await() // 预览这一次已经进了取数
        val visible = async {
            PageDecoder.loadPageBytes(handle, 0, PageLoadTrace(PageLoadSource.Visible, 1, System.nanoTime()))
        }
        runCurrent() // 可见页挂到同一份在飞请求上（不再要一次闸位、不再向来源取）
        handle.release.complete(Unit)
        preview.await()
        visible.await()

        assertEquals("同一页只向来源取一次", 1, handle.loads)
        val attempts = lines.toList().filter { it.startsWith("pageAttempt") }
        assertEquals("两方各落一行", 2, attempts.size)
        assertEquals(setOf("visible", "preview"), attempts.map { field(it, "source") }.toSet())
        assertEquals(
            "同一次取数 ⇒ 两行的 ms 相同（各行的 waitMs 按自己的需要图时刻算）",
            1,
            attempts.map { field(it, "ms") }.toSet().size,
        )
        assertTrue("两行都记成功", attempts.all { field(it, "result") == "success" })
    }

    @Test
    fun `页取数闸的闸位是 2`() {
        // 默认值也是口径：`docs/spec/reader.md` 的「页取数」与 `PageDecoder` 的 KDoc 都拿它当数——
        // 把常量改成 1/4 时前面那些用例依然全绿（与 `CoverByteGateTest` 钉封面闸位同一条写法）
        assertEquals("同时最多 2 张页在飞", 2, PAGE_MAX_CONCURRENT_LOADS)
    }

    @Test
    fun `只有预览格让位 可见页与开书前置都是可见档`() {
        assertEquals(CoverBytePriority.Visible, pageLoadPriorityOf(PageLoadSource.Visible))
        assertEquals(CoverBytePriority.Visible, pageLoadPriorityOf(PageLoadSource.Prelude))
        assertEquals("预览格排队排在可见页之后", CoverBytePriority.Prefetch, pageLoadPriorityOf(PageLoadSource.Preview))
    }
}
