package com.cc3301.comicviewer.ui

import androidx.compose.ui.graphics.asAndroidBitmap
import com.cc3301.comicviewer.core.source.BookHandle
import com.cc3301.comicviewer.core.source.PageData
import com.cc3301.comicviewer.core.view.ReaderMenuLayout
import kotlin.math.abs
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * 按**目标高度**取页并解码（票 #105 的预览项通路）：阅读菜单的预览项高度固定、宽度随页面真实比例，
 * 所以解码目标由高度给出。
 *
 * 用 `GraphicsMode.NATIVE` 走 AOSP 原生的 `BitmapFactory`（Robolectric 的影子实现不按真实尺寸解图），
 * 因此这里量到的宽高是真实解码结果：
 * ① 解出的高度 ≥ 目标高度（格子不会被拉伸变糊）、解出的宽高比 = 源图比例（AC3「按图片实际比例」在解码这一层）；
 * ② 横向页在同一目标高度下解得更宽（宽度确实随比例走，不是固定宽）；
 * ③ 内存键按高度（同一高度第二次调用命中缓存、**不再取字节**，票 07 AC「二次呼出不重新取图」）；
 * ④ 坏字节回 null（不抛异常，调用方按 `runCatching` 兜底）。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PageDecoderHeightTest {

    /**
     * 只提供 id/pageCount 的假书：字节由 `load` 接缝给，取了几次由它数。
     * [id] 每本不同——解码内存缓存是 `PageDecoder` 的全局单例（键含书 id），共用 id 的用例会互相命中。
     */
    private class FakeBook(override val id: String, private val bytes: ByteArray) : BookHandle {
        override val pageCount: Int = 1
        var loads = 0

        override suspend fun loadPage(index: Int): PageData {
            loads++
            return PageData(bytes, "image/png")
        }
    }

    /** 目标高度按生产口径分桶后传给解码器（与 `ReaderMenu` 的调用一致） */
    private fun decode(book: FakeBook, itemHeightPx: Float) = runBlocking {
        PageDecoder.decodePageByHeight(
            book,
            0,
            ReaderMenuLayout.previewDecodeHeightPx(itemHeightPx),
        ) { book.loadPage(0).bytes }
    }

    @Test
    fun `解出的高度不低于目标高度 且宽高比等于源图比例`() {
        val book = FakeBook("portrait", SyntheticPng.of(1000, 1500)) // 2:3 竖版页
        val decoded = decode(book, itemHeightPx = 300f)
        assertTrue("必须解得出位图", decoded != null)
        val bitmap = decoded!!.asAndroidBitmap()
        assertTrue("解出高度 ${bitmap.height} 必须 ≥ 目标高度 300", bitmap.height >= 300)
        assertEquals("宽高比必须与源图一致（上下不留白的前提）", 2f / 3f, bitmap.width.toFloat() / bitmap.height, 0.01f)
        assertEquals("取字节只一次", 1, book.loads)
    }

    @Test
    fun `横向页在同一目标高度下解得更宽`() {
        val portrait = decode(FakeBook("p", SyntheticPng.of(1000, 1500)), itemHeightPx = 300f)!!.asAndroidBitmap()
        val landscape = decode(FakeBook("l", SyntheticPng.of(1500, 1000)), itemHeightPx = 300f)!!.asAndroidBitmap()
        assertTrue(
            "横向页解出宽度 ${landscape.width} 必须大于竖版页 ${portrait.width}（宽度按比例走）",
            landscape.width > portrait.width,
        )
        assertEquals("横向页比例", 1.5f, landscape.width.toFloat() / landscape.height, 0.02f)
        assertTrue("横向页高度同样不低于目标高度", landscape.height >= 300)
        assertTrue("两者高度同量级（差不超过一个分桶）", abs(landscape.height - portrait.height) <= 32 * 4)
    }

    @Test
    fun `同一高度第二次调用命中内存缓存 不再取字节`() {
        val book = FakeBook("twice", SyntheticPng.of(1000, 1500))
        val first = decode(book, itemHeightPx = 300f)
        val second = decode(book, itemHeightPx = 300f)
        assertSame("内存命中必须返回同一张位图", first, second)
        assertEquals("二次呼出不重新取图（第二次走内存命中，`load` 不再被调）", 1, book.loads)
    }

    @Test
    fun `坏字节回 null 不抛异常`() {
        val book = FakeBook("broken", byteArrayOf(1, 2, 3, 4, 5))
        assertNull(decode(book, itemHeightPx = 300f))
    }
}
