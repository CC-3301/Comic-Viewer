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
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import kotlin.math.roundToInt

/**
 * 列表档「名称文本盒宽 = 进度条宽」（票 #92 需求 2 的右缘口径）：**真量宽度**，不看推理。
 *
 * 背景：维护者真机看到「进度条右边没对齐」。可能的解释是 M3 `LinearProgressIndicator` 内部
 * `Modifier.size(LinearIndicatorWidth = 240.dp, …)` 把条夹成固定宽（360dp 屏上比名称块短约 20dp）——
 * 所以本用例**实测**条的放置宽度，而不是只读源码判断。
 *
 * 怎么测的：照搬 `EntryNameTextTest`（票 #94）的路子——用 Robolectric 起 [ComponentActivity]，
 * 把**与 `BrowserScreen.BrowseRow` 同构**的骨架（固定宽度的 `Row` = 行内容区；固定宽度占位的封面 +
 * `Column(weight(1f))` 名称列）组合起来，读两个 `onGloballyPositioned` 报上来的**真实放置宽度**。
 * `BrowseRow` 是私有组件，测试不直接调它；这里复刻的是它的宽度链（同样的 `Row` + 权重列 + 两个
 * `fillMaxWidth` 子件）。
 *
 * 判别力：若 M3 把条夹成 `240.dp`，`条宽 == 名称盒宽` 与 `条宽 == 行宽 − 封面 − 列间距` 两条都会变红
 * （232dp 行内容里条会是 240dp）。实测值见 `.pi-implement/92/evidence-impl.md`（r7）。
 *
 * 不覆盖的部分（写明，避免读成全覆盖）：本用例钉的是**复刻件**的几何 + M3 的固定宽语义，
 * **不覆盖生产侧接线**（`BrowserScreen.BrowseRow` 里名称与条共用同一份 `Modifier.fillMaxWidth()`）——
 * 把生产侧那处共享摘掉时本用例仍会绿；那条接线由代码结构与真机目视把守。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class BrowseRowWidthTest {

    /** 行内容区宽度（生产里 = 屏宽 − 行 `padding(horizontal = 16.dp)` 的两侧） */
    private val rowContentWidth = 300.dp

    /** 封面宽度：生产常量 `BrowserScreen.LIST_COVER_WIDTH`（私有常量，按仓内先例用字面量代入） */
    private val coverWidth = 56.dp

    /** 名称列与封面之间的间距：生产常量 `Arrangement.spacedBy(12.dp)` */
    private val columnSpacing = 12.dp

    private val nameStyle = TextStyle(fontSize = 14.sp, lineHeight = 20.sp)

    /** 组合同构骨架并读回两个宽度（px） */
    private data class Widths(val row: Int, val name: Int, val bar: Int)

    private fun measureWidths(): Widths {
        var rowWidth = -1
        var nameWidth = -1
        var barWidth = -1
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup().get()
        val view = ComposeView(activity)
        activity.setContentView(view)
        view.setContent {
            Row(
                // requiredWidth：固定行内容宽（`width` 会被传入的精确约束盖掉，测不出确定的列宽）
                modifier = Modifier.requiredWidth(rowContentWidth).onGloballyPositioned { rowWidth = it.size.width },
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
                            .onGloballyPositioned { nameWidth = it.size.width },
                    )
                    EntryProgressBar(
                        progress = ReadingProgress(pageIndex = 1, totalPages = 10, updatedAtMs = 0L),
                        modifier = Modifier
                            .fillMaxWidth()
                            .onGloballyPositioned { barWidth = it.size.width },
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
        assertTrue("行/名称盒/条没被放置（测量没生效），本次断言无意义", rowWidth > 0 && nameWidth > 0 && barWidth > 0)
        return Widths(rowWidth, nameWidth, barWidth)
    }

    @Test
    fun `条的实测宽等于名称文本盒宽`() {
        val w = measureWidths()
        assertEquals("进度条右缘必须与名称文本盒右缘落在同一条线上", w.name, w.bar)
    }

    @Test
    fun `条的实测宽等于名称列宽 不是 M3 的固定 240dp`() {
        val density = Robolectric.buildActivity(ComponentActivity::class.java).setup().get()
            .resources.displayMetrics.density
        val w = measureWidths()
        val expectedPx = w.row - ((coverWidth + columnSpacing).value * density).roundToInt()
        assertEquals("条宽 = 行内容宽 − 封面宽 − 列间距", expectedPx, w.bar)
        // 直接钉住「M3 的 LinearIndicatorWidth(240dp) 目标没有生效」：本行内容宽下名称列 = 232dp ≠ 240dp
        assertNotEquals("条被 M3 的 240dp 目标夹短了", (240f * density).roundToInt(), w.bar)
    }
}
