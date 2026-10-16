package com.cc3301.comicviewer.ui

import com.cc3301.comicviewer.core.concurrent.RetryOutcome
import com.cc3301.comicviewer.core.source.BookHandle
import com.cc3301.comicviewer.core.source.PageData
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.nio.file.Files

/**
 * 阅读菜单预览格的**有界重试**（口径与上限在 `core/view/CoverRetry` 的 `coverRetryPolicy`，与封面同一套）。
 *
 * 判别用例：第 1 条钉的就是验收里那条「第一次取不到、第二次拿到的格子会出图」（一次失败不留空格）；
 * 第 2 条钉住「有界」（不能变成无限重试）；第 3 条钉住「作用域被取消就立即停」（菜单关闭 / 换书）；
 * 第 4 条钉住「字节到手但解不出来不重试」（确定性的失败，同封面）。
 *
 * 走 `GraphicsMode.NATIVE` 的 AOSP 原生 `BitmapFactory`（Robolectric 影子实现不按真实尺寸解图），
 * 因此这里的出图是真解码出来的（与 `PageImagesHeightTest` 同一口径）。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PagePreviewLoadTest {

    /** 解码模块与页字节模块：本用例各一份（位图分区、取数闸与磁盘缓存都挂实例，不与其他用例共享） */
    private val images = PageImages()
    private val pageBytes = PageBytes(
        PageDiskCache(Files.createTempDirectory("page-preview-load").toFile()),
    )

    /**
     * 前 [failFirst] 次取字节抛异常（「这一次取不到」），之后给真图。
     * [id] 每本不同——解码内存缓存的键含书 id，共用 id 的用例会互相命中。
     */
    private class FakeBook(override val id: String, private val bytes: ByteArray) : BookHandle {
        override val pageCount: Int = 1
        var loads = 0
        var failFirst = 0

        override suspend fun loadPage(index: Int): PageData {
            loads++
            if (loads <= failFirst) throw IllegalStateException("这一次取不到")
            return PageData(bytes, "image/png")
        }
    }

    @Test
    fun `第一次取不到 第二次拿到的格子会出图`() = runBlocking {
        val book = FakeBook("preview-retry-second", SyntheticPng.of(100, 150)).apply { failFirst = 1 }

        val outcome = loadPreviewPageWithRetry(images, pageBytes, book, 0, 128, await = {})

        assertTrue("第 2 次拿到就该出图（一次失败不留空格）", outcome is RetryOutcome.Loaded)
        assertEquals("首发失败后重试一次即中", 2, book.loads)
    }

    @Test
    fun `次数用满仍取不到就放弃 不再无限重试`() = runBlocking {
        val book = FakeBook("preview-retry-exhausted", SyntheticPng.of(100, 150)).apply { failFirst = 99 }

        val outcome = loadPreviewPageWithRetry(images, pageBytes, book, 0, 128, await = {})

        assertEquals("四次用满仍是「这次没拿到」", RetryOutcome.Retryable, outcome)
        assertEquals("首发 1 次 + 重试 3 次", 4, book.loads)
    }

    @Test
    fun `字节到手但解不出来不重试（确定性的失败）`() = runBlocking {
        val book = FakeBook("preview-retry-garbage", ByteArray(32) { 1 })

        val outcome = loadPreviewPageWithRetry(images, pageBytes, book, 0, 128, await = {})

        assertEquals("解不出来是确定性失败，重试无意义", RetryOutcome.Fatal, outcome)
        assertEquals("一次就够", 1, book.loads)
    }

    @Test
    fun `取消立即停 不再重试`() = runBlocking {
        val book = FakeBook("preview-retry-cancelled", SyntheticPng.of(100, 150)).apply { failFirst = 99 }
        val waiting = CompletableDeferred<Unit>()

        val job = launch {
            // 退避等待是取消点：菜单关闭 / 换书（条子作用域整体取消）就是在这里停下来的
            loadPreviewPageWithRetry(images, pageBytes, book, 0, 128, await = { waiting.complete(Unit); delay(Long.MAX_VALUE) })
        }
        waiting.await()
        job.cancelAndJoin()

        assertEquals("取消即停：只试了首发那一次", 1, book.loads)
    }
}
