package com.cc3301.comicviewer.ui

import android.os.Looper
import android.view.View
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.material3.Text
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.unit.dp
import com.cc3301.comicviewer.core.view.ReaderMenuLayout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * 预览条的**当前页居中**（票 #105 AC17，第 13 轮）：`PreviewStrip` 的滚动手势是
 * 「先 `scrollToItem(目标页)` 把它带进视口，再按 [ReaderMenuLayout.previewCenterScrollOffsetPx] 补一个居中偏移」，
 * 这里把同一条序列在 Robolectric 里跑一遍并量真几何，钉三件事：
 *
 * 1. 中间页：目标项**中心落在预览区中心**（±1px）——偏移符号错（正负相反）或漏掉第二步都会红；
 * 2. 首页：目标项贴**左缘**且完全可见（居中量为负、列表滚不动 ⇒ 由 `LazyList` 夹掉）；
 * 3. 末页：目标项贴**右缘**且完全可见（同上，夹在最大滚动量上）。
 *
 * 为什么复刻结构而不是直接测生产 `PreviewStrip`：条目内部没有可注入的 `modifier` 钩子、且需要
 * `BookHandle` / 位图解码才能组合（仓库没有 Compose UI 测试库）——与 [PreviewStripLayoutTest] 同一套做法，
 * 量的是「该结构 + 该偏移」下的真实几何；纯函数层由 `ReaderMenuLayoutTest` 覆盖。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PreviewStripCenterTest {

    /** 预览条宽（真机上来自面板的 `weight(1f)` / `fillMaxWidth`，尺寸是确定的） */
    private val stripWidth = 300.dp

    private val stripHeight = 200.dp

    /** 单格宽度（真机上 = 图片高 × 该页真实比例） */
    private val itemWidth = 80.dp

    private val gap = 6.dp

    private data class Measured(
        val viewportLeftPx: Int,
        val viewportWidthPx: Int,
        val itemLeftPx: Map<Int, Int>,
        val itemWidthPx: Map<Int, Int>,
    )

    /**
     * 组合「预览条 + 居中滚动」并量目标项的位置。[target] 就是当前页（0-based）。
     *
     * 滚动用**非挂起**的 `requestScrollToItem`（`PreciseScroll` 型 API 在 Robolectric 的暂停 Looper 下
     * 无法驱动挂起的 `scrollToItem`——试过 4 轮「布局 → idle」，目标项始终没被布局出来），
     * 偏移取生产同一个纯函数 [ReaderMenuLayout.previewCenterScrollOffsetPx]：
     * 因此本用例量的是「该偏移 + LazyList 自己夹取两端」的真实几何，生产侧那条 `LaunchedEffect`
     * 的两步序列（先 `scrollToItem` 再补偏移）仍属真机验收项。
     * 格子宽度取**已布局出来的那一个条目**（本用例里所有格子同宽，真机上每格宽度不同、
     * 生产取的是目标项自己的 `size`——见 `PreviewStrip` 的 KDoc）。
     */
    private fun measure(count: Int, target: Int): Measured {
        var viewportLeft = -1
        var viewportWidthPx = -1
        val itemLeft = mutableMapOf<Int, Int>()
        val itemWidthPx = mutableMapOf<Int, Int>()
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup().get()
        val view = ComposeView(activity)
        val listState = LazyListState()
        activity.setContentView(view)
        view.setContent {
            Box(Modifier.fillMaxSize()) {
                LazyRow(
                    state = listState,
                    modifier = Modifier
                        .width(stripWidth)
                        .height(stripHeight)
                        .onGloballyPositioned {
                            viewportLeft = it.boundsInWindow().left.roundToInt()
                            viewportWidthPx = it.size.width
                        },
                    horizontalArrangement = Arrangement.spacedBy(space = gap, alignment = Alignment.CenterHorizontally),
                ) {
                    items(count = count, key = { it }) { index ->
                        Box(
                            Modifier
                                .width(itemWidth)
                                .fillMaxHeight()
                                .onGloballyPositioned {
                                    itemLeft[index] = it.boundsInWindow().left.roundToInt()
                                    itemWidthPx[index] = it.size.width
                                },
                        ) {
                            Text("x")
                        }
                    }
                }
            }
        }
        // ① 先把视口与格子量出来（`requestScrollToItem` 要的是像素偏移）
        layoutOnce(view)
        assertTrue("没量到预览区（测量没生效），本次断言无意义", viewportWidthPx > 0)
        val measuredItemWidth = itemWidthPx[0] ?: -1
        assertTrue("没量到格子宽度（测量没生效），本次断言无意义", measuredItemWidth > 0)
        // ② 按生产偏移请求「目标项居中」，再跑几轮让滚动落到位
        listState.requestScrollToItem(
            index = target,
            scrollOffset = ReaderMenuLayout.previewCenterScrollOffsetPx(measuredItemWidth, viewportWidthPx),
        )
        repeat(4) { layoutOnce(view) }
        assertTrue(
            "目标项 $target 没被布局出来（滚动没生效），本次断言无意义",
            itemLeft.containsKey(target) && itemWidthPx.containsKey(target),
        )
        return Measured(viewportLeft, viewportWidthPx, itemLeft, itemWidthPx)
    }

    /** 跑一轮「测量 → 布局 → idle」：Compose 的滚动请求在后一轮布局里生效 */
    private fun layoutOnce(view: ComposeView) {
        view.measure(
            View.MeasureSpec.makeMeasureSpec((stripWidth.value * 2).roundToInt(), View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
        )
        view.layout(0, 0, view.measuredWidth, view.measuredHeight)
        shadowOf(Looper.getMainLooper()).idle()
    }

    /** 中间页居中：目标项中心 = 预览区中心（这是 AC17 的核心判据，偏移符号/漏第二步都会红） */
    @Test
    fun `跳页后目标项中心落在预览区中心`() {
        val measured = measure(count = 12, target = 5)
        val itemCenter = measured.itemLeftPx.getValue(5) + measured.itemWidthPx.getValue(5) / 2f
        val viewportCenter = measured.viewportLeftPx + measured.viewportWidthPx / 2f
        assertTrue(
            "第 6 页中心 ${itemCenter}px 必须落在预览区中心 ${viewportCenter}px 上（±1px）",
            abs(itemCenter - viewportCenter) <= 1f,
        )
    }

    /** 首页：居中量为负、列表滚不动 ⇒ 目标项贴左缘且完整可见（AC17 的「首页除外」） */
    @Test
    fun `首页贴左缘且完整可见`() {
        val measured = measure(count = 12, target = 0)
        assertEquals("首页必须贴预览区左缘", measured.viewportLeftPx, measured.itemLeftPx.getValue(0))
        assertTrue(
            "首页必须完整可见（右缘不得越过预览区右缘）",
            measured.itemLeftPx.getValue(0) + measured.itemWidthPx.getValue(0) <=
                measured.viewportLeftPx + measured.viewportWidthPx,
        )
    }

    /** 末页：居中量被最大滚动量夹掉 ⇒ 目标项贴右缘且完整可见（AC17 的「末页除外」） */
    @Test
    fun `末页贴右缘且完整可见`() {
        val measured = measure(count = 12, target = 11)
        val itemRight = measured.itemLeftPx.getValue(11) + measured.itemWidthPx.getValue(11)
        assertEquals("末页右缘必须贴预览区右缘", measured.viewportLeftPx + measured.viewportWidthPx, itemRight)
        assertTrue("末页必须完整可见（左缘不得越过预览区左缘）", measured.itemLeftPx.getValue(11) >= measured.viewportLeftPx)
    }
}
