package com.cc3301.comicviewer.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.unit.dp
import com.cc3301.comicviewer.core.view.ReaderMenuLayout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * 预览条的**真实布局测量**：`PreviewStrip` 依赖的四条布局行为在这里真量一遍——
 * 它们不是本仓库的代码，而是 Compose 的行为；一旦某次升级改了它们，本用例钉住的预期会**静默失效**
 * （预览项高度不再等于预览条高度、短内容不再居中），所以拿可执行断言钉住：
 *
 * 1. `BoxWithConstraints` 放在 `LazyRow` 的条目里时，`maxHeight` = **整条 LazyRow 的高度**
 *    ⇒ `PreviewItem` 用 `maxHeight` 当「预览条高度（含页数那一行）」成立——此后，
 *    格内高 = 预览条高 − 页数行高，本用例量的是那个基准量；
 * 2. `LazyRow` 的 `horizontalArrangement = Arrangement.spacedBy(间隙, Alignment.CenterHorizontally)`
 *    在**内容比视口窄**时把条目整体居中（而不是靠左贴边）⇒ 「横向项水平居中」的第一种情形；
 * 3. 条目的宽度由条目自己定（不被拉满视口宽）；
 * 4. **内容比视口宽时条目从视口左缘开始排**（可滑动列表的固有行为）——预览条的居中偏移依赖它：
 *    格宽固定（`ReaderMenuLayout.previewCellWidthDp`）后单格不可能比预览区宽，因此它不再有
 *    「超宽页必须自己收口」那层用途；保留它钉的是 LazyList 自己的排布行为（升级改了它，
 *    左缘起排与两端夹取会跟着变）。
 *
 * 为什么不直接测生产 `PreviewStrip`：条目内部没有可注入的 `modifier` 钩子（仓库没有 Compose UI 测试库），
 * 因此这里复刻的是**结构**（LazyRow + spacedBy + BoxWithConstraints 条目），量的是该结构的真实几何；
 * `PreviewStrip` 自身的组装由 `ReaderMenuLayoutTest` 的纯函数口径与设备目视覆盖。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PreviewStripLayoutTest {

    /** 复刻的预览条：宽 300dp、高 200dp（设备上来自 `weight(1f)`，尺寸是确定的） */
    private val stripWidth = 300.dp

    private val stripHeight = 200.dp

    /** 复刻的单个预览项宽度（本用例自设；生产是常量 = 格内高 × 3/4） */
    private val itemWidth = 80.dp

    /** 组合宽度（列宽 300dp 之外再留一倍余量，与原来同值） */
    private val layoutWidthPx = (stripWidth.value * 2).roundToInt()

    private data class Measured(
        val lazyWidthPx: Int,
        val firstItemLeftPx: Int,
        val firstItemWidthPx: Int,
        val firstItemHeightPx: Int,
        val firstItemMaxHeightDp: Float,
        /** 条子左右各让出的量（px）= 一个框宽；复刻必须与生产同值，否则量的不是生产结构 */
        val framePadPx: Int,
    )

    /**
     * 组合复刻结构并量第一个条目。[count] × [itemWidth] 小于预览条宽时是「内容窄」情形，
     * 大于时是「内容宽」情形（可滑动）。
     */
    private fun measure(count: Int = 1, width: androidx.compose.ui.unit.Dp = itemWidth): Measured {
        var lazyWidth = -1
        var itemLeft = -1
        var itemWidthPx = -1
        var itemHeightPx = -1
        var maxHeightDp = -1f
        var framePadPx = -1
        val view = composeViewInActivity {
            Box(Modifier.fillMaxSize()) {
                framePadPx = with(LocalDensity.current) { ReaderMenuLayout.PREVIEW_FRAME_DP.dp.roundToPx() }
                LazyRow(
                    modifier = Modifier
                        .width(stripWidth)
                        .height(stripHeight)
                        .onGloballyPositioned { lazyWidth = it.size.width },
                    contentPadding = PaddingValues(horizontal = ReaderMenuLayout.PREVIEW_FRAME_DP.dp),
                    horizontalArrangement = Arrangement.spacedBy(
                        space = ReaderMenuLayout.PREVIEW_GAP_DP.dp,
                        alignment = Alignment.CenterHorizontally,
                    ),
                ) {
                    items(count = count, key = { it }) { index ->
                        BoxWithConstraints(Modifier.fillMaxSize()) {
                            if (index == 0) maxHeightDp = maxHeight.value
                            Box(
                                Modifier
                                    .width(width)
                                    .height(maxHeight)
                                    .onGloballyPositioned {
                                        if (index == 0) {
                                            val frame = it.boundsInWindow()
                                            itemLeft = frame.left.roundToInt()
                                            itemWidthPx = it.size.width
                                            itemHeightPx = it.size.height
                                        }
                                    },
                            ) { Text("x") }
                        }
                    }
                }
            }
        }
        view.layoutOnce(layoutWidthPx)
        assertTrue("没量到布局（测量没生效），本次断言无意义", lazyWidth > 0 && itemWidthPx > 0)
        return Measured(lazyWidth, itemLeft, itemWidthPx, itemHeightPx, maxHeightDp, framePadPx)
    }

    @Test
    fun `条目里的 BoxWithConstraints 拿到的是整条 LazyRow 的高度`() {
        val measured = measure()
        assertEquals(
            "预览条高 ${stripHeight.value}dp，条目拿到的 maxHeight ${measured.firstItemMaxHeightDp}dp 必须相等（AC1 的前提）",
            stripHeight.value,
            measured.firstItemMaxHeightDp,
            0.01f,
        )
        assertEquals("条目高度 = maxHeight = 预览条高度（缩略图与页数行都从这里分）", stripHeight.value.toInt(), measured.firstItemHeightPx)
    }

    @Test
    fun `内容比视口窄时条目整体水平居中 不靠左贴边`() {
        val measured = measure()
        val itemCenter = measured.firstItemLeftPx + measured.firstItemWidthPx / 2f
        val stripCenter = measured.lazyWidthPx / 2f
        assertTrue(
            "条目中心 ${itemCenter}px 必须落在预览条中心 ${stripCenter}px 上（AC3：横向项水平居中）",
            abs(itemCenter - stripCenter) <= 1f,
        )
        assertTrue("不得靠左贴边", measured.firstItemLeftPx > 0)
    }

    @Test
    fun `条目宽度由条目自己定 不被拉满视口宽`() {
        val measured = measure()
        assertEquals("宽度 = 我们算出来的宽度（不是视口宽）", itemWidth.value.toInt(), measured.firstItemWidthPx)
        assertTrue("条目宽度必须小于预览区宽度", measured.firstItemWidthPx < measured.lazyWidthPx)
    }

    @Test
    fun `内容比视口宽时条目从视口左缘开始排 所以超宽单格必须自己收口`() {
        // 3 个 140dp 的条目 + 2×6dp 间隙 = 432dp > 300dp：可滑动，第一条贴**内容区**左缘
        //（内容区 = 预览区左右各内缩一个框宽：框画在格子外侧，条子不内缩的话贴边那格的框线会被裁）。
        // 生产格宽固定为格内高的 3/4（远窄于预览区），所以这里量的是 LazyList 本身的排布行为
        val measured = measure(count = 3, width = 140.dp)
        assertEquals("内容比视口宽时第一条贴内容区左缘", measured.framePadPx, measured.firstItemLeftPx)
        assertEquals("条目宽度 = 我们设的 140dp（不被拉满、也不被压窄）", 140, measured.firstItemWidthPx)
    }
}
