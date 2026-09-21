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
 * 预览条的**真实布局测量**（票 #105 AC1/AC3）：`PreviewStrip` 的两条实现前提在这里真量一遍——
 * 它们不是本仓库的代码，而是 Compose 的行为；一旦某次升级改了它们，AC1/AC3 会**静默失效**
 * （预览项高度不再等于预览区高度、短内容不再居中），所以拿可执行断言钉住：
 *
 * 1. `BoxWithConstraints` 放在 `LazyRow` 的条目里时，`maxHeight` = **整条 LazyRow 的高度**
 *    ⇒ `PreviewItem` 用 `maxHeight` 当「预览区高度」成立（AC1「单格高度撑满预览区」的前提）；
 * 2. `LazyRow` 的 `horizontalArrangement = Arrangement.spacedBy(间隙, Alignment.CenterHorizontally)`
 *    在**内容比视口窄**时把条目整体居中（而不是靠左贴边）⇒ AC3「横向项水平居中、不得靠左贴边」的落地机制；
 * 3. 条目的宽度由条目自己定（不被拉满视口宽）⇒ 宽度可以按页面比例算。
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
        val itemLeftPx: Int,
        val itemWidthPx: Int,
        val itemHeightPx: Int,
        val itemMaxHeightDp: Float,
    )

    private fun measure(): Measured {
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
                    items(count = 1, key = { it }) {
                        BoxWithConstraints(Modifier.fillMaxSize()) {
                            maxHeightDp = maxHeight.value
                            Box(
                                Modifier
                                    .width(itemWidth)
                                    .height(maxHeight)
                                    .onGloballyPositioned {
                                        val frame = it.boundsInWindow()
                                        itemLeft = frame.left.roundToInt()
                                        itemWidthPx = it.size.width
                                        itemHeightPx = it.size.height
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
            "预览区高 ${stripHeight.value}dp，条目拿到的 maxHeight ${measured.itemMaxHeightDp}dp 必须相等（AC1 的前提）",
            stripHeight.value,
            measured.itemMaxHeightDp,
            0.01f,
        )
        assertEquals("条目高度 = maxHeight = 预览区高度", stripHeight.value.toInt(), measured.itemHeightPx)
    }

    @Test
    fun `内容比视口窄时条目整体水平居中 不靠左贴边`() {
        val measured = measure()
        val itemCenter = measured.itemLeftPx + measured.itemWidthPx / 2f
        val stripCenter = measured.lazyWidthPx / 2f
        assertTrue(
            "条目中心 ${itemCenter}px 必须落在预览区中心 ${stripCenter}px 上（AC3：横向项水平居中）",
            abs(itemCenter - stripCenter) <= 1f,
        )
        assertTrue("不得靠左贴边", measured.itemLeftPx > 0)
    }

    @Test
    fun `条目宽度由条目自己定 不被拉满视口宽`() {
        val measured = measure()
        assertEquals("宽度 = 我们算出来的宽度（不是视口宽）", itemWidth.value.toInt(), measured.itemWidthPx)
        assertTrue("条目宽度必须小于预览区宽度", measured.itemWidthPx < measured.lazyWidthPx)
    }
}
