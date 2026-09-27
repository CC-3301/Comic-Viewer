package com.cc3301.comicviewer.ui

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * 封面位图的**组合期命中**（票 #146 ③）：位图已在内存里时，[CoverThumb] 的位图状态**初值**就是那一张，
 * 于是首帧有图、骨架只在真没缓存时出现。
 *
 * 改动前的判别缺陷（真机日志：退出阅读器返回浏览页时 `coverSource` 有计数、`browseCoverLoad=0` ⇒ 封面
 * 全是内存命中，屏幕上却是整屏骨架再淡入）：初值恒为 `null`，命中查询写在 `LaunchedEffect` 里 ⇒ 头一帧
 * 必然是骨架；走 uri 那条更晚——缓存查询还在 `decodeCoverUri` **内部**，要等协程派发到 IO 才发生。
 *
 * 这里钉两件事：
 * 1. **两条路各自的组合期查询键**（[coverCacheKey]）：uri 那条用 `uriKey`（不带重取键）、来源字节那条用
 *    `bytesKey`——写反或统一成一把键，下面的命中用例就会红（两把键的串不同）；
 * 2. **命中即初值非 null**（[cachedCoverBitmap]）：真解一张进封面分区，再按同一条路查询，必须拿到
 *    **同一张**（不重解、也不换一张）；没缓存时仍是 null（骨架照旧出现，异步取解那条路不变）。
 *
 * 走 `GraphicsMode.NATIVE` 的 AOSP 原生解码器（影子实现不按真实尺寸解图），因此「进缓存」是真的解出了一张；
 * 合成 PNG 由 [SyntheticPng] 给（仓库不存二进制 fixture）。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class CoverThumbInitialBitmapTest {

    private val plan = CoverPlan.of(CoverSizing.GridCell(156.dp), density = 2.75f, reloadKey = 0)

    /** 真解一张进**封面分区**，键由调用方给（与生产两条路入缓存用的键同一把） */
    private fun decodeInto(key: String): ImageBitmap =
        requireNotNull(PageDecoder.decodeCoverBytes(key, SyntheticPng.of(600, 800), plan.widthPx, plan.cropTarget)) {
            "合成 PNG 应能解出封面位图"
        }

    @Test
    fun `来源字节那条路命中内存缓存 初值即非 null`() {
        val entryId = "cover-initial-source"
        val decoded = decodeInto(plan.keyOf(entryId))

        val initial = cachedCoverBitmap(plan.route(entryId, coverUri = null))

        assertNotNull("命中即首帧有图（不再先整屏骨架）", initial)
        assertSame("初值就是缓存里那一张（不重解、也不换一张）", decoded, initial)
    }

    @Test
    fun `走 uri 那条路命中内存缓存 初值即非 null`() {
        val entryId = "cover-initial-uri"
        val route = plan.route(entryId, coverUri = "file:///cover.png")
        val decoded = decodeInto(route.uriKey)

        val initial = cachedCoverBitmap(route)

        assertNotNull("uri 那条路的命中口就是这条路的内存缓存键（不再只由 decodeCoverUri 内部查）", initial)
        assertSame("同一张位图", decoded, initial)
    }

    @Test
    fun `真没缓存时初值仍是 null`() {
        val entryId = "cover-initial-cold"

        assertNull("没缓存就照旧为 null，仍走异步取解", cachedCoverBitmap(plan.route(entryId, coverUri = null)))
        assertNull(cachedCoverBitmap(plan.route(entryId, "file:///cold.png")))
    }

    @Test
    fun `两条路各按自己那条键查询`() {
        val entryId = "cover-initial-key"
        val bytesKey = plan.route(entryId, coverUri = null)
        val uriKey = plan.route(entryId, coverUri = "content://cover.png")

        assertEquals("走来源字节的用 bytesKey", bytesKey.bytesKey, coverCacheKey(bytesKey))
        assertEquals("走 uri 的用 uriKey", uriKey.uriKey, coverCacheKey(uriKey))
    }
}
