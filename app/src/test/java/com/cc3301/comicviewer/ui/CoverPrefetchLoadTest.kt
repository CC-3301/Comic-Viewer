package com.cc3301.comicviewer.ui

import com.cc3301.comicviewer.core.view.CoverByteRequests
import com.cc3301.comicviewer.core.view.CoverDecode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * 封面预取的落地（票 #108 r6）：**取字节 + 解码进封面分区**，键与可见行同一把。
 *
 * 判别用例：第 1 条（预取后 `cachedCover` 命中 ⇒ 可见行不再重解/重取字节）与第 2 条
 * （并发预取同一 id 只取一次字节）——改动前（r5 只取字节、来源无在飞去重）两条都会红。
 *
 * 走 `GraphicsMode.NATIVE` 的 AOSP 原生 `BitmapFactory`（Robolectric 的影子实现不按真实尺寸解图），
 * 因此这里量到的「位图入缓存」是真的解出了一张。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class CoverPrefetchLoadTest {

    private val cropTarget = CoverDecode.CropTarget.GridCell
    private val decodeWidthPx = 480

    @Test
    fun `预取后位图在封面分区 可见行不再取字节`() = runBlocking {
        val entryId = "prefetch-hit"
        val key = CoverDecode.key(entryId, 0, decodeWidthPx, cropTarget)
        val requests = CoverByteRequests()
        var reads = 0

        val prefetched = prefetchCoverBitmap(entryId, key, decodeWidthPx, cropTarget, requests) {
            reads++
            SyntheticPng.of(600, 800)
        }
        assertTrue("预取成功", prefetched)
        assertNotNull("预取后 cachedCover 直接命中（可见行不必再解）", PageDecoder.cachedCover(key))

        // 可见行这一刻走的是 `CoverThumb`：先查 cachedCover，命中就不再 loadBytes。这里按同一顺序验一次
        val visibleRowHit = PageDecoder.cachedCover(key) != null
        assertTrue(visibleRowHit)
        assertEquals("同一 id 只取一次字节（可见行没有另发一次）", 1, reads)
    }

    @Test
    fun `并发预取同一 id 只取一次字节`() = runBlocking {
        val entryId = "prefetch-race"
        val key = CoverDecode.key(entryId, 0, decodeWidthPx, cropTarget)
        val requests = CoverByteRequests()
        var reads = 0

        val results = List(3) {
            async(Dispatchers.IO) {
                prefetchCoverBitmap(entryId, key, decodeWidthPx, cropTarget, requests) {
                    reads++
                    Thread.sleep(50) // 拉开窗口：另外两个调用者必然在飞
                    SyntheticPng.of(600, 800)
                }
            }
        }.awaitAll()

        assertTrue("三条都拿到可用位图", results.all { it })
        assertEquals("并发同一 id 只取一次字节", 1, reads)
        assertNotNull(PageDecoder.cachedCover(key))
    }

    @Test
    fun `取不到字节时不算成功 也不进缓存`() = runBlocking {
        val entryId = "prefetch-miss"
        val key = CoverDecode.key(entryId, 0, decodeWidthPx, cropTarget)
        val requests = CoverByteRequests()

        val result = prefetchCoverBitmap(entryId, key, decodeWidthPx, cropTarget, requests) { null }

        assertFalse("拿不到字节 ⇒ 记一次没拿到（由有界退避重试）", result)
        assertNull("没有位图入缓存", PageDecoder.cachedCover(key))
    }
}
