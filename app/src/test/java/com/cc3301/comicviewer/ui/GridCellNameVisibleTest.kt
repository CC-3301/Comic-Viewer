package com.cc3301.comicviewer.ui

import android.os.Looper
import android.view.View
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.requiredHeight
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.cc3301.comicviewer.core.view.CoverLayout
import com.cc3301.comicviewer.core.view.gridCellMaxHeight
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * 网格档横屏 2 格的**名字可见性**与封面盒几何（票 #106 AC1/AC2/AC3）：真量格子槽、封面盒与名字块。
 *
 * 现象（票面）：格高只由格宽决定（[CoverLayout.gridCellHeight]），横屏 2 格时格宽很大 ⇒ 封面高超过可视
 * 高度，名字行被顶出屏幕、完全看不到。修法（维护者拍板方案 A）：封面高取「格高」与「可用高度」中的
 * 较小者，宽按格比例反算并水平居中，名字行恒可见。
 *
 * 怎么测的：照搬 `EntryNameTextTest`（票 #94）/`GridProgressScrimTest`（票 #92 r5）的路子——Robolectric
 * 起 [ComponentActivity]，把**与 `BrowserScreen.BrowserGridCell` 同构**的骨架组合起来，读
 * `boundsInWindow()` 报上来的真实放置框：
 * - 格子槽 = 可视高度 − 上下 contentPadding（`BrowserGrid` 里格子实际拿到的纵向空间）；
 * - 格子高度上限走生产纯函数 `gridCellMaxHeight`，`Column.heightIn(max = …)` 与生产同款；
 * - 封面槽 = `BoxWithConstraints` + `Column` 权重（名字块非权重、先测），封面盒尺寸走生产纯函数
 *   `CoverLayout.gridCellSize`；
 * - 名称用真组件 [EntryNameText]（字体度量由真渲染给出，因此本用例不依赖任何行高推算）。
 *
 * 判别力：① 若名字块不再参与封面预算（封面按格高铺满）→ 「名字块完全落在格子槽内」与「封面宽 < 槽宽」
 * 都变红，且第一条的前置断言成立；② 若封面被拉伸成别的比例 → 比例断言变红；③ 若封面不居中 → 两侧留白
 * 不等；④ 若竖屏也开始收缩 → 第三条用例变红（票 #106 AC3）。
 *
 * 不覆盖的部分（写明，避免读成全覆盖）：`BrowserScreen.BrowserGridCell` 是私有组件，本用例钉的是
 * **同构复刻件**的几何 + 生产纯函数的接线；生产侧「`BrowserGrid` 把上限传进格子、封面盒宽度同时用于压条
 * 暗底那一层、CoverThumb 按同一份纯函数给自己的盒子」由代码结构与真机目视把守（同
 * `GridProgressScrimTest` 的口径）。真机目视（手机/平板横屏 2 格）仍是最后一关。
 */
@RunWith(RobolectricTestRunner::class)
// 屏幕限定符：本用例的量级是手机/平板横屏（格宽 ~400dp、竖屏可视 ~700dp），Robolectric 默认屏幕只有
// 320×470dp，比场景还小 ⇒ 约束会被夹小、量到的几何不是本票的场景（density 固定 mdpi，dp 与 px 一一对应）
@Config(sdk = [34], qualifiers = "w1280dp-h800dp-land-mdpi")
class GridCellNameVisibleTest {

    /** 单元格宽度：手机横屏 2 格（屏宽约 800dp）下 `gridCellWidth` 的量级 */
    private val cellWidth = 400.dp

    /** 手机横屏的网格可视高度（已扣掉系统栏与顶栏后的量级） */
    private val phoneLandscapeViewport = 320.dp

    /** 竖屏 3 格的网格可视高度：远大于格高，收缩不得触发 */
    private val portraitViewport = 700.dp

    /** 生产常量 `BrowserScreen.GRID_CONTENT_PADDING` / `GRID_CELL_SPACING`（私有常量，按仓内先例用字面量代入） */
    private val contentPadding = 12.dp
    private val cellSpacing = 6.dp

    /** 名称样式：与 `BrowserScreen` 里网格档用的 M3 `labelLarge` 同量级（14sp/20sp） */
    private val nameStyle = TextStyle(fontSize = 14.sp, lineHeight = 20.sp)

    /** 合成名（脱敏：不得用真实作品名/社团名/作者名） */
    private val sampleName = "サンプル作品 第01巻"

    private data class Frame(val left: Int, val top: Int, val right: Int, val bottom: Int) {
        val width: Int get() = right - left
        val height: Int get() = bottom - top
    }

    private data class Measured(val slot: Frame, val cover: Frame, val name: Frame)

    /** 组合「格子槽 + 封面槽（权重）+ 名字块」并真量三个放置框 */
    private fun measure(viewportHeight: Dp): Measured {
        var slot: Frame? = null
        var cover: Frame? = null
        var name: Frame? = null
        fun frame(scope: LayoutCoordinates): Frame {
            val r = scope.boundsInWindow()
            return Frame(r.left.roundToInt(), r.top.roundToInt(), r.right.roundToInt(), r.bottom.roundToInt())
        }
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup().get()
        val view = ComposeView(activity)
        activity.setContentView(view)
        view.setContent {
            val cellMaxHeight = gridCellMaxHeight(viewportHeight.value, contentPadding.value).dp
            Box(
                Modifier
                    .requiredWidth(cellWidth)
                    .requiredHeight(viewportHeight - contentPadding * 2)
                    .onGloballyPositioned { slot = frame(it) },
            ) {
                Column(
                    // 与生产同款：格子高度上限（可视高度 − 上下留白）
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = cellMaxHeight),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(cellSpacing),
                ) {
                    // 封面槽：名字块（非权重、先测）之外的高度全给封面
                    BoxWithConstraints(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f, fill = false),
                        contentAlignment = Alignment.TopCenter,
                    ) {
                        val coverSize = CoverLayout.gridCellSize(cellWidth.value, maxHeight.value)
                        Box(
                            Modifier
                                .width(coverSize.width.dp)
                                .height(coverSize.height.dp)
                                .onGloballyPositioned { cover = frame(it) },
                        ) {
                            Box(Modifier.fillMaxSize())
                        }
                    }
                    EntryNameText(
                        name = sampleName,
                        style = nameStyle,
                        minLines = entryNameMinLines(gridMode = true),
                        modifier = Modifier
                            .fillMaxWidth()
                            .onGloballyPositioned { name = frame(it) },
                    )
                }
            }
        }
        view.measure(
            View.MeasureSpec.makeMeasureSpec(1000, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
        )
        view.layout(0, 0, view.measuredWidth, view.measuredHeight)
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue("格子槽/封面盒/名字块没被放置（测量没生效），本次断言无意义", slot != null && cover != null && name != null)
        return Measured(slot!!, cover!!, name!!)
    }

    @Test
    fun `横屏缺高度时名字块完全落在格子槽内`() {
        val m = measure(phoneLandscapeViewport)
        val baseGridHeight = CoverLayout.gridCellHeight(cellWidth.value).dp
        assertTrue(
            "前置：本场景必须真的装不下（格高 $baseGridHeight + 间距 + 名字块 ${m.name.height}px 超过槽高 ${m.slot.height}px），" +
                "否则本用例测不到票 #106 的现象",
            baseGridHeight + cellSpacing + m.name.height.dp > phoneLandscapeViewport - contentPadding * 2,
        )
        assertTrue("名字块必须完全落在格子槽内（不需要滚动、不被顶出屏幕）", m.name.bottom <= m.slot.bottom)
        assertTrue("名字块必须在格子槽内起始（封面不得把它挤到槽外）", m.name.top >= m.slot.top)
        assertTrue("封面与名字块不得重叠", m.cover.bottom <= m.name.top)
    }

    @Test
    fun `横屏缺高度时封面等高收缩 居中且不拉伸`() {
        val m = measure(phoneLandscapeViewport)
        assertTrue("收缩必须发生：封面宽必须小于格子槽宽（两侧留白）", m.cover.width < m.slot.width)
        val leftGap = m.cover.left - m.slot.left
        val rightGap = m.slot.right - m.cover.right
        assertTrue("封面必须水平居中（两侧留白相差不得超过 1px）：左 $leftGap / 右 $rightGap", abs(leftGap - rightGap) <= 1)
        // 等比：高宽比仍是固定格比例（不得拉伸成别的比例）；取整误差按 ±0.02 容忍
        val ratio = m.cover.height.toFloat() / m.cover.width
        assertTrue("封面盒子高宽比必须仍是固定格比例，实测 $ratio", abs(ratio - CoverLayout.GRID_CELL_ASPECT) <= 0.02f)
        // 封面高度 = min(格高, 名字块与间距之外的剩余高度)：封面把剩余高度吃干净，名字行因此恰好落回槽内
        val remaining = m.slot.height - cellSpacing.value.roundToInt() - m.name.height
        val expected = min(CoverLayout.gridCellHeight(cellWidth.value).roundToInt(), remaining)
        assertTrue(
            "封面高 = min(格高, 剩余高度)：期望 $expected±2、实测 ${m.cover.height}",
            abs(m.cover.height - expected) <= 2,
        )
    }

    @Test
    fun `竖屏高度充足时封面仍铺满格宽 尺寸与改动前一致`() {
        val m = measure(portraitViewport)
        val baseGridHeight = CoverLayout.gridCellHeight(cellWidth.value).dp
        assertTrue(
            "前置：竖屏场景装得下（格高 $baseGridHeight + 间距 + 名字块 ${m.name.height}px 不超过槽高 ${m.slot.height}px），收缩不该触发",
            baseGridHeight + cellSpacing + m.name.height.dp <= portraitViewport - contentPadding * 2,
        )
        val density = RuntimeEnvironment.getApplication().resources.displayMetrics.density
        assertEquals("竖屏封面高 = 格高（逐像素同改动前）", (baseGridHeight.value * density).roundToInt(), m.cover.height)
        assertEquals("竖屏封面宽 = 格宽（逐像素同改动前）", m.slot.width, m.cover.width)
    }
}
