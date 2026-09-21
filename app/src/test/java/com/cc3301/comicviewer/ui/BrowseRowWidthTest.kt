package com.cc3301.comicviewer.ui

import android.os.Looper
import android.view.View
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.cc3301.comicviewer.core.source.ReadingProgress
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import kotlin.math.roundToInt

/**
 * 列表档进度条的几何（票 #92 需求 2，r13 口径）：**真量**条与名称盒的放置框。
 *
 * 当前口径（维护者第五次也是最后一次真机反馈）：条在名称正下方，**两边都与名称列对齐**——左缘 = 名称左缘、
 * 右端到名称列右缘（= 行内容右缘），条宽 = 名称列宽 = 名称文字盒宽；**不做内缩**（12dp → 8dp → 4dp 的内缩方案
 * 全程作废，见票面评论），也**不追踪文字行末**（文字行末天然参差，条取文字盒宽）。
 *
 * 背景（为什么还要实测）：更早一轮的假设是「M3 `LinearProgressIndicator` 内部
 * `Modifier.size(LinearIndicatorWidth = 240.dp, …)` 把条夹成固定宽」——本用例因此实测条的放置宽度，
 * 而不是只读源码判断（实测结论是 M3 未夹短：条宽由传入约束决定）。
 *
 * 怎么测的：照搬 `EntryNameTextTest`（票 #94）的路子——Robolectric 起 [ComponentActivity]，把
 * **与 `BrowserScreen.BrowseRow` 同构**的骨架（固定宽度的 `Row` = 行内容区；固定宽度占位的封面 +
 * `Column(weight(1f))` 名称列）组合起来，读 `boundsInWindow()` 报上来的**真实放置框**（宽度与左右缘都要）。
 *
 * 判别力：① 条宽若不再等于名称盒宽（生产侧条上的宽度约束被摘掉/被加宽）→ 宽度断言变红；
 * ② 条左缘若不再与名称左缘对齐 → 左缘断言变红；③ 若 M3 把条夹成 `240.dp` →
 * 宽度断言变红（本行内容宽下条 = 232dp ≠ 240dp）。
 *
 * 不覆盖的部分（写明，避免读成全覆盖）：本用例钉的是**复刻件**的几何 + M3 的固定宽语义；
 * 生产侧 `BrowserScreen.BrowseRow` 的接线（条挂在名称列内、除 `padding(top)` 外不额外约束宽度）
 * 由代码结构与真机目视把守——把生产侧的条挪到别处或加上宽度约束时，本用例不会变红。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class BrowseRowWidthTest {

    /** 行内容区宽度 = 屏宽 336dp − 行左留白 16dp − 行右留白 20dp（票 #60 批次 6 后右留白是 20dp，见 `BrowserScreen` 的 `BrowseRow`） */
    private val rowContentWidth = 300.dp

    /** 封面宽度：生产常量 `BrowserScreen.LIST_COVER_WIDTH`（私有常量，按仓内先例用字面量代入） */
    private val coverWidth = 56.dp

    /** 名称列与封面之间的间距：生产常量 `Arrangement.spacedBy(12.dp)` */
    private val columnSpacing = 12.dp

    private val nameStyle = TextStyle(fontSize = 14.sp, lineHeight = 20.sp)

    /** Robolectric 的显示密度（一次取好：此前三个用例各起一个 Activity 只为读它，属重复样板） */
    private val density: Float = RuntimeEnvironment.getApplication().resources.displayMetrics.density

    /** 放置框（px）：比较宽度用 [width]，比较位置用 [left]/[right] */
    private data class Frame(val left: Int, val top: Int, val right: Int, val bottom: Int) {
        val width: Int get() = right - left
    }

    private data class Frames(val row: Frame, val name: Frame, val bar: Frame)

    private fun measureFrames(): Frames {
        var row: Frame? = null
        var name: Frame? = null
        var bar: Frame? = null
        fun frame(scope: androidx.compose.ui.layout.LayoutCoordinates): Frame {
            val r = scope.boundsInWindow()
            return Frame(r.left.roundToInt(), r.top.roundToInt(), r.right.roundToInt(), r.bottom.roundToInt())
        }
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup().get()
        val view = ComposeView(activity)
        activity.setContentView(view)
        view.setContent {
            Row(
                // requiredWidth：固定行内容宽（`width` 会被传入的精确约束盖掉，测不出确定的列宽）
                modifier = Modifier.requiredWidth(rowContentWidth).onGloballyPositioned { row = frame(it) },
                horizontalArrangement = Arrangement.spacedBy(columnSpacing),
            ) {
                // 封面位置（生产里是 CoverThumb，宽度同为封面列宽）
                Box(Modifier.width(coverWidth))
                Column(modifier = Modifier.weight(1f)) {
                    EntryNameText(
                        name = "短題",
                        style = nameStyle,
                        minLines = entryNameMinLines(gridMode = false),
                        modifier = Modifier
                            .fillMaxWidth()
                            .onGloballyPositioned { name = frame(it) },
                    )
                    EntryProgressBar(
                        progress = ReadingProgress(pageIndex = 1, totalPages = 10, updatedAtMs = 0L),
                        // 生产侧：条 = fillMaxWidth（除 top 间距外不加宽度约束）
                        modifier = Modifier
                            .fillMaxWidth()
                            .onGloballyPositioned { bar = frame(it) },
                    )
                }
            }
        }
        view.measure(
            View.MeasureSpec.makeMeasureSpec(600, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
        )
        view.layout(0, 0, view.measuredWidth, view.measuredHeight)
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue("行/名称盒/条没被放置（测量没生效），本次断言无意义", row != null && name != null && bar != null)
        return Frames(row!!, name!!, bar!!)
    }

    @Test
    fun `条的实测宽等于名称盒宽 两边都与名称列对齐`() {
        val f = measureFrames()
        assertEquals("条宽 = 名称盒宽（不做内缩）", f.name.width, f.bar.width)
        assertEquals("条左缘与名称左缘对齐", f.name.left, f.bar.left)
        assertEquals("条右缘 = 名称盒右缘", f.name.right, f.bar.right)
    }

    @Test
    fun `条的实测宽等于名称列宽 不是 M3 的固定 240dp`() {
        val f = measureFrames()
        val columnPx = f.row.width - ((coverWidth + columnSpacing).value * density).roundToInt()
        assertEquals("名称列宽 = 行内容宽 − 封面宽 − 列间距", columnPx, f.name.width)
        assertEquals("条宽 = 名称列宽", columnPx, f.bar.width)
        // 直接钉住「M3 的 LinearIndicatorWidth(240dp) 目标没有生效」：本行内容宽下条 = 232dp ≠ 240dp
        assertNotEquals("条被 M3 的 240dp 目标夹短了", (240f * density).roundToInt(), f.bar.width)
    }
}
