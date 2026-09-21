package com.cc3301.comicviewer.ui

import android.os.Looper
import android.view.View
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.unit.dp
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
 * 预览条的**真实布局测量**（票 #105 AC1/AC3）：`PreviewStrip` 依赖的三条布局行为在这里真量一遍——
 * 它们不是本仓库的代码，而是 Compose 的行为；一旦某次升级改了它们，AC1/AC3 会**静默失效**
 * （预览项高度不再等于预览区高度、短内容不再居中），所以拿可执行断言钉住：
 *
 * 1. `BoxWithConstraints` 放在 `LazyRow` 的条目里时，`maxHeight` = **整条 LazyRow 的高度**
 *    ⇒ `PreviewItem` 用 `maxHeight` 当「预览区高度」成立（AC1「单格高度撑满预览区」的前提）；
 * 2. `LazyRow` 的 `horizontalArrangement = Arrangement.spacedBy(间隙, Alignment.CenterHorizontally)`
 *    在**内容比视口窄**时把条目整体居中（而不是靠左贴边）⇒ AC3「横向项水平居中」的第一种情形；
 * 3. 条目的宽度由条目自己定（不被拉满视口宽）⇒ 宽度可以按页面比例算；
 * 4. **内容比视口宽时条目从视口左缘开始排**（可滑动列表的固有行为）⇒ 比视口还宽的单格会被右缘裁掉，
 *    因此 AC3 的第二种情形（超宽页）必须由条目自己收口宽度
 *    （`ReaderMenuLayout.previewItemHeight`，纯函数层已断言）——本用例钉的是「为什么必须收口」。
 *
 * 为什么不直接测生产 `PreviewStrip`：条目内部没有可注入的 `modifier` 钩子（仓库没有 Compose UI 测试库），
 * 因此这里复刻的是**结构**（LazyRow + spacedBy + BoxWithConstraints 条目），量的是该结构的真实几何；
 * `PreviewStrip` 自身的组装由 `ReaderMenuLayoutTest` 的纯函数口径与真机目视覆盖。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PreviewStripLayoutTest {

    /** 复刻的预览区：宽 300dp、高 200dp（真机上来自 `weight(1f)`，尺寸是确定的） */
    private val stripWidth = 300.dp

    private val stripHeight = 200.dp

    /** 复刻的单个预览项宽度（真机上 = 高度 × 页面比例） */
    private val itemWidth = 80.dp

    private data class Measured(
        val lazyWidthPx: Int,
        val firstItemLeftPx: Int,
        val firstItemWidthPx: Int,
        val firstItemHeightPx: Int,
        val firstItemMaxHeightDp: Float,
    )

    /**
     * 组合复刻结构并量第一个条目。[count] × [itemWidth] 小于预览区宽时是「内容窄」情形，
     * 大于时是「内容宽」情形（可滑动）。
     */
    private fun measure(count: Int = 1, width: androidx.compose.ui.unit.Dp = itemWidth): Measured {
        var lazyWidth = -1
        var itemLeft = -1
        var itemWidthPx = -1
        var itemHeightPx = -1
        var maxHeightDp = -1f
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup().get()
        val view = ComposeView(activity)
        activity.setContentView(view)
        view.setContent {
            Box(Modifier.fillMaxSize()) {
                LazyRow(
                    modifier = Modifier
                        .width(stripWidth)
                        .height(stripHeight)
                        .onGloballyPositioned { lazyWidth = it.size.width },
                    horizontalArrangement = Arrangement.spacedBy(
                        space = 6.dp,
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
        view.measure(
            View.MeasureSpec.makeMeasureSpec((stripWidth.value * 2).roundToInt(), View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
        )
        view.layout(0, 0, view.measuredWidth, view.measuredHeight)
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue("没量到布局（测量没生效），本次断言无意义", lazyWidth > 0 && itemWidthPx > 0)
        return Measured(lazyWidth, itemLeft, itemWidthPx, itemHeightPx, maxHeightDp)
    }

    @Test
    fun `条目里的 BoxWithConstraints 拿到的是整条 LazyRow 的高度`() {
        val measured = measure()
        assertEquals(
            "预览区高 ${stripHeight.value}dp，条目拿到的 maxHeight ${measured.firstItemMaxHeightDp}dp 必须相等（AC1 的前提）",
            stripHeight.value,
            measured.firstItemMaxHeightDp,
            0.01f,
        )
        assertEquals("条目高度 = maxHeight = 预览区高度", stripHeight.value.toInt(), measured.firstItemHeightPx)
    }

    @Test
    fun `内容比视口窄时条目整体水平居中 不靠左贴边`() {
        val measured = measure()
        val itemCenter = measured.firstItemLeftPx + measured.firstItemWidthPx / 2f
        val stripCenter = measured.lazyWidthPx / 2f
        assertTrue(
            "条目中心 ${itemCenter}px 必须落在预览区中心 ${stripCenter}px 上（AC3：横向项水平居中）",
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
        // 3 个 140dp 的条目 + 2×6dp 间隙 = 432dp > 300dp：可滑动，第一条贴视口左缘——
        // 比视口还宽的单格会被右缘裁掉（「靠左贴边」），因此 AC3 的第二种情形靠
        // `ReaderMenuLayout.previewItemHeight` 把宽度收口到预览区宽（纯函数层已断言）
        val measured = measure(count = 3, width = 140.dp)
        assertEquals("内容比视口宽时第一条贴左缘", 0, measured.firstItemLeftPx)
        assertEquals("条目宽度 = 我们设的 140dp（不被拉满、也不被压窄）", 140, measured.firstItemWidthPx)
    }
}
