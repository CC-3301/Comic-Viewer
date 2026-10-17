package com.cc3301.comicviewer.ui

import com.cc3301.comicviewer.core.source.BookHandle
import com.cc3301.comicviewer.core.source.CacheKeys
import com.cc3301.comicviewer.core.source.PageData
import java.nio.file.Files
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 页字节模块的接缝：**磁盘缓存命中 / 未命中**与**取消传播**。
 *
 * 判别用例：第 1 条（未命中 ⇒ 向来源取一次并落磁盘）、第 2 条（命中 ⇒ 一次来源都不碰）、
 * 第 3 条（取消的那一次上抛，不吞成字节、不落磁盘）——第 2 条是「磁盘缓存真的接上了」的判据：
 * 磁盘缓存没接上（每次调用都向来源取）时它会红。
 */
class PageBytesTest {

    private class FakeHandle(
        override val id: String,
        private val bytes: ByteArray,
    ) : BookHandle {
        override val pageCount: Int = 1

        /** 取过几次字节：命中磁盘缓存的那次应当不涨 */
        var loads = 0

        override suspend fun loadPage(index: Int): PageData {
            loads++
            return PageData(bytes, "image/jpeg")
        }
    }

    /** 取字节一直挂着（等 [release]）的假书：用来在取数在飞时取消 */
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

    /** 本用例的磁盘缓存（临时目录）：用例直接读它断言「这一次有没有落磁盘」 */
    private val diskCache = PageDiskCache(Files.createTempDirectory("page-bytes").toFile(), log = {})

    /** 页字节模块：本用例一份，磁盘缓存指向自己的临时目录 */
    private val pageBytes = PageBytes(diskCache, log = {})

    /** 调用方交的「这是哪条路」：本用例的取数都当可见页走 */
    private fun visibleSource() = PageLoadSource.Visible

    @Test
    fun `未命中磁盘缓存时向来源取一次 并把字节落磁盘`() = runBlocking {
        val bytes = ByteArray(128) { 7 }
        val handle = FakeHandle("page-bytes-miss", bytes)

        val loaded = pageBytes.load(handle, 3, visibleSource())

        assertEquals("拿到的就是句柄给的字节", bytes.toList(), loaded.toList())
        assertEquals("未命中 ⇒ 向来源取一次", 1, handle.loads)
        assertEquals(
            "取回的字节落进磁盘缓存（下一次不必再向来源取）",
            bytes.toList(),
            diskCache.get(CacheKeys.pageDisk("page-bytes-miss", 3))?.toList(),
        )
    }

    @Test
    fun `磁盘缓存命中时一次来源都不碰`() = runBlocking {
        val bytes = ByteArray(128) { 7 }
        pageBytes.load(FakeHandle("page-bytes-hit", bytes), 0, visibleSource())
        // 换一个句柄（内容不同）：这一次若仍向来源取，拿到的就会是另一份字节
        val second = FakeHandle("page-bytes-hit", ByteArray(128) { 8 })

        val loaded = pageBytes.load(second, 0, visibleSource())

        assertEquals("命中 ⇒ 拿的是磁盘里那一份", bytes.toList(), loaded.toList())
        assertEquals("命中 ⇒ 不向来源取", 0, second.loads)
    }

    @Test
    fun `取消传播：取消的那一次上抛 不吞成字节 也不落磁盘`() = runTest {
        val handle = HangingHandle("page-bytes-cancelled")

        val result = withTimeoutOrNull(50) { pageBytes.load(handle, 0, visibleSource()) }

        assertNull("取消上抛（吞掉的话这里会拿到字节）", result)
        assertEquals("取消前已向来源取了一次", 1, handle.loads)
        assertNull("没读完的那一次不落磁盘", diskCache.get(CacheKeys.pageDisk("page-bytes-cancelled", 0)))
    }
}
