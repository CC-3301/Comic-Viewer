package com.cc3301.comicviewer.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.unit.dp
import com.cc3301.comicviewer.core.view.CoverBytePriority
import com.cc3301.comicviewer.core.view.CoverLayout
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * 封面位图的**组合期命中**：位图已在内存里时，[CoverThumb] 的位图状态**初值**就是那一张，
 * 于是首帧有图、骨架只在真没缓存时出现。
 *
 * 改动前的判别缺陷（设备日志：退出阅读器返回浏览页时 `coverSource` 有计数、`browseCoverLoad=0` ⇒ 封面
 * 全是内存命中，屏幕上却是整屏骨架再淡入）：初值恒为 `null`，命中查询写在 `LaunchedEffect` 里 ⇒ 头一帧
 * 必然是骨架；走 uri 那条更晚——缓存查询还在 `decodeCoverUri` **内部**，要等协程派发到 IO 才发生。
 *
 * 这里钉两件事：
 * 1. **两条路各自的组合期查询键**（[coverCacheKey]）：uri 那条用 `uriKey`（不带重取键）、来源字节那条用
 *    `bytesKey`——写反或统一成一把键，下面的命中用例就会红（两把键的串不同）；
 * 2. **命中即初值非 null**：既直查查询口（[cachedCoverBitmap]），也**真组合 [CoverThumb]**（本文件末尾两条）
 *    ——后者钉的是「初值那一行」（`CoverThumb.kt` 的 `remember(bitmapKey) { mutableStateOf(...) }`）：命中时
 *    首帧量到的盒高就得按**真位图比例**算，而不是占位比例（判据：把那一行还原成 `mutableStateOf(null)` ⇒
 *    **只有命中那条**红，本文件 6 条里 1 failed；冷缓存那条对初值不敏感——两种初值下它首帧都量到占位比例）。
 *
 * 走 `GraphicsMode.NATIVE` 的 AOSP 原生解码器（影子实现不按真实尺寸解图），因此「进缓存」是真的解出了一张；
 * 合成 PNG 由 [SyntheticPng] 给（仓库不存二进制 fixture）。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class CoverThumbInitialBitmapTest {

    private val plan = CoverPlan.of(CoverSizing.GridCell(156.dp), density = 2.75f, reloadKey = 0)

    /** 真解一张进**封面分区**（键由调用方给，与生产两条路入缓存用的键同一把） */
    private fun decodeInto(
        key: String,
        targetPlan: CoverPlan = plan,
        bytes: ByteArray = SyntheticPng.of(600, 800),
    ): ImageBitmap =
        runBlocking {
            requireNotNull(
                PageDecoder.decodeCoverBytes(
                    key,
                    bytes,
                    targetPlan.widthPx,
                    targetPlan.cropTarget,
                    CoverBytePriority.Visible,
                ),
            ) { "合成 PNG 应能解出封面位图" }
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

    // ---------- 组合 CoverThumb（钉「初值那一行」，上面的直查用例钉不到它） ----------

    /**
     * 组合 [`CoverThumb`]（外面套一层 `Box` 读它的**真实放置框**，与 `EntryNameTextTest` / `BrowseRowWidthTest`
     * 同一路子）并回带每轮布局报上来的盒高（px，按报告顺序）。列表档：盒高 = 盒宽 × 比例。
     *
     * 为什么量盒子几何、不量 alpha：位图在 `Image` 上、alpha 在 `graphicsLayer` 里，视图树与语义树都读不到；
     * 而位图的**有无**直接进了盒子几何（未解码 = 占位比例 [CoverLayout.PLACEHOLDER_ASPECT]，解码后 = 真比例）。
     * 取像素那条路仓库只在 `CrossBookBarTest` 做过一次（要 invalidate + requestLayout + 两轮 idle 才有像素，
     * 依赖 Robolectric 帧钟走法）——本用例改钉不依赖帧钟的那一份。
     *
     * 第一个报上来的值就是**首帧**的盒子（组合与布局先于 effect）。一轮 `layoutOnce` 里 `onGloballyPositioned`
     * 会报**三次**（`[56, 470, 56]` / 冷缓存 `[78, 470, 78]`）：中间的 470 是窗约束那一轮（≈窗高，
     * 与内容无关）、**不参与断言**；末轮又回到内容自撑高，因此断言拿**首**（首帧）与**末**（不再变）两个值比。
     */
    private fun composedCoverHeightsPx(entryId: String, plan: CoverPlan): Pair<MutableList<Int>, Int> {
        val heights = mutableListOf<Int>()
        var loads = 0
        val view = composeViewInActivity {
            Box(Modifier.onGloballyPositioned { heights += it.boundsInWindow().height.roundToInt() }) {
                CoverThumb(
                    coverUri = null,
                    cacheKey = entryId,
                    loadBytes = { loads++; null },
                    plan = plan,
                )
            }
        }
        repeat(2) { view.layoutOnce(widthPx = 400) }
        return heights to loads
    }

    /** 本次渲染的密度（一次取好，与 `BrowseRowWidthTest` 同一取法） */
    private val densityPx: Float = RuntimeEnvironment.getApplication().resources.displayMetrics.density

    @Test
    fun `命中缓存时组合 CoverThumb 首帧盒子就按真位图比例`() {
        val entryId = "cover-thumb-hit"
        val plan = CoverPlan.of(CoverSizing.OwnAspect(56.dp), density = 2.75f, reloadKey = 0)
        // 先真解一张进封面分区（键 = 这条路的内存缓存键），模拟「返回浏览页时封面全是内存命中」
        val decoded = decodeInto(plan.keyOf(entryId), plan, SyntheticPng.of(600, 600))
        assertNotNull("前置：缓存已就位", cachedCoverBitmap(plan.route(entryId, coverUri = null)))

        val (heights, loads) = composedCoverHeightsPx(entryId, plan)

        val realAspect = CoverLayout.displayAspect(CoverLayout.aspectOf(decoded.width, decoded.height))
        val realHeightPx = 56f * realAspect * densityPx
        val placeholderHeightPx = 56f * CoverLayout.PLACEHOLDER_ASPECT * densityPx
        assertTrue(
            "判别力前提：真比例 $realAspect 推出的盒高 $realHeightPx 与占位比例推出的 $placeholderHeightPx 必须分得开",
            abs(realHeightPx - placeholderHeightPx) > 2f,
        )
        assertEquals(
            "命中即首帧有图：**首帧**盒高就按真比例（命中缺失时这一帧是占位比例推的 $placeholderHeightPx px）",
            realHeightPx,
            heights.first().toFloat(),
            2f,
        )
        assertEquals(
            "首帧就是终态：命中的帧不再被后续重组改尺寸（占位→撑开那一下不得回来）",
            heights.last().toFloat(),
            heights.first().toFloat(),
            0f,
        )
        assertEquals("命中就不向来源要字节", 0, loads)
    }

    @Test
    fun `真没缓存时组合 CoverThumb 首帧仍是占位比例`() {
        val plan = CoverPlan.of(CoverSizing.OwnAspect(56.dp), density = 2.75f, reloadKey = 0)

        val (heights, _) = composedCoverHeightsPx("cover-thumb-cold", plan)

        val placeholderHeightPx = 56f * CoverLayout.PLACEHOLDER_ASPECT * densityPx
        assertEquals(
            "真没缓存：骨架照旧（占位比例推出 $placeholderHeightPx px），不无中生有一张图",
            placeholderHeightPx,
            heights.first().toFloat(),
            2f,
        )
        assertEquals(
            "也没图可撑开：末帧与首帧同高",
            heights.last().toFloat(),
            heights.first().toFloat(),
            0f,
        )
    }
}
