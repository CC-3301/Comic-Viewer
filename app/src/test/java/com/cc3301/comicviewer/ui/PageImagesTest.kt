package com.cc3301.comicviewer.ui

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import com.cc3301.comicviewer.core.source.BookHandle
import com.cc3301.comicviewer.core.source.PageData
import com.cc3301.comicviewer.core.view.CoverBytePriority
import com.cc3301.comicviewer.core.view.CoverDecode
import com.cc3301.comicviewer.core.view.DecodedImageCache
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * 解码模块的接缝：**分区淘汰**与**钉住不被淘汰**。
 *
 * 缓存按小预算注入（生产那两份按 `maxMemory` 算的预算在用例里填不满），因此几条解码就能把分区写爆。
 * 判别用例：第 1 条（页面分区写爆，封面分区一张不掉——共用一份分区时它会红）、
 * 第 2 条（钉住的封面在写下一张时留下；解锁后回到最旧淘汰）。
 *
 * 走 `GraphicsMode.NATIVE` 的 AOSP 原生 `BitmapFactory`（Robolectric 影子实现不按真实尺寸解图），
 * 位图占的 KB 因此与真机同量级。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PageImagesTest {

    private class FakeBook(override val id: String, private val bytes: ByteArray) : BookHandle {
        override val pageCount: Int = 1
        override suspend fun loadPage(index: Int) = PageData(bytes, "image/png")
    }

    /** 解码模块：位图分区按本用例给的小预算建（1KB ⇒ 一张 75KB 的位图就能把分区写爆） */
    private fun images(pageBudgetKb: Int = 1, coverBudgetKb: Int = 1) = PageImages(
        cache = DecodedImageCache(pageBudgetKb, coverBudgetKb) {
            it.asAndroidBitmap().allocationByteCount / 1024
        },
    )

    /** 解一张封面进封面分区（键是不透明字符串，模块不解释它） */
    private suspend fun decodeCover(images: PageImages, key: String) {
        val decoded = images.decodeCoverBytes(
            key = key,
            bytes = SyntheticPng.of(120, 160),
            targetWidthPx = 120,
            cropTarget = CoverDecode.CropTarget.OwnAspect,
            priority = CoverBytePriority.Visible,
        )
        assertNotNull("封面 $key 应当解出位图", decoded)
    }

    @Test
    fun `页面分区写爆也不淘汰封面分区`() = runBlocking {
        val images = images()
        val book = FakeBook("images-partition", SyntheticPng.of(200, 300))

        decodeCover(images, "cover-1")
        assertNotNull("封面进了封面分区", images.cachedCover("cover-1"))

        images.decodePage(book, 0, 200) { SyntheticPng.of(200, 300) }
        images.decodePage(book, 1, 200) { SyntheticPng.of(200, 300) }

        assertNotNull("页面位图进了页面分区", images.cachedPage(book.id, 1, 200))
        assertNull("页面分区自己越界淘汰，第 0 页被挤掉", images.cachedPage(book.id, 0, 200))
        assertNotNull("页面位图写爆页面分区，封面一张都不该掉", images.cachedCover("cover-1"))
    }

    @Test
    fun `钉住的封面不被淘汰 解锁后回到最旧淘汰`() = runBlocking {
        val images = images()

        images.pinCover("cover-pinned")
        decodeCover(images, "cover-pinned")
        decodeCover(images, "cover-next")

        assertNotNull("钉住的那张在写下一张时留下", images.cachedCover("cover-pinned"))
        assertNotNull("刚写入的那张留下", images.cachedCover("cover-next"))

        images.unpinCover("cover-pinned")
        decodeCover(images, "cover-third")

        assertNull("解锁后回到最旧淘汰", images.cachedCover("cover-pinned"))
        assertNotNull("刚写入的那张留下", images.cachedCover("cover-third"))
    }
}
