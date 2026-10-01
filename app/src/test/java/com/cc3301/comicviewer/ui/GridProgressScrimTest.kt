package com.cc3301.comicviewer.ui

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.unit.dp
import com.cc3301.comicviewer.core.source.ReadingProgress
import com.cc3301.comicviewer.core.view.CoverLayout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.math.roundToInt

/**
 * 网格档「条底下的暗底」（票 #92 需求 5，维护者选定方案 A）的几何：**真量**暗底与条的放置框。
 *
 * 背景：条压在浅色/白色封面上时，轨道（主题 `onSurface` 30% 不透明度）几乎看不见 → 维护者选定
 * 「条那一小条先铺一层黑 45% 暗底」。暗底只铺在**网格档**，且与条**同宽同高、同一条带**。
 *
 * 怎么测的：照搬 `EntryNameTextTest`（票 #94）/`BrowseRowWidthTest`（票 #92 r7）的路子——Robolectric 起
 * [ComponentActivity]，把**与 `BrowserGridCell` 同构**的封面盒（宽 = 格宽、高 = `CoverLayout.gridCellHeight`）
 * 组合起来，读 `onGloballyPositioned` 报上来的真实放置框：封面盒 / 暗底 / 条三者。
 *
 * 判别力：暗底宽度若被写成任意值（如 `fillMaxWidth` 落在更宽的容器上）、高度若与条高脱钩、或条没有
 * 压在暗底同一条带上（`bottom` 不等），本用例都会变红。不覆盖的部分：`BrowserGridCell` 里
 * 「`if (progress != null)` 才铺」这一接线（本用例只组合同构件，不组合 `BrowserGridCell` 本身），见实施证据。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class GridProgressScrimTest {

    /** 格宽（= 封面宽）：生产的 `gridCellWidth` 结果之一 */
    private val cellWidth = 120.dp

    private data class Bounds(val left: Int, val top: Int, val right: Int, val bottom: Int) {
        val width: Int get() = right - left
        val height: Int get() = bottom - top
    }

    private data class Measured(val cell: Bounds, val scrim: Bounds, val bar: Bounds)

    private fun measure(): Measured {
        var cell: Bounds? = null
        var scrim: Bounds? = null
        var bar: Bounds? = null
        fun bounds(scope: androidx.compose.ui.layout.LayoutCoordinates): Bounds {
            val rect = scope.boundsInWindow()
            return Bounds(rect.left.roundToInt(), rect.top.roundToInt(), rect.right.roundToInt(), rect.bottom.roundToInt())
        }
        val coverHeight = CoverLayout.gridCellHeight(cellWidth.value).dp
        val view = composeViewInActivity {
            // 封面盒（生产里是 `Box(Modifier.width(cellWidth))` + `CoverThumb(GridCell)` 撑高）
            Box(
                Modifier
                    .requiredWidth(cellWidth)
                    .height(coverHeight)
                    .onGloballyPositioned { cell = bounds(it) },
            ) {
                // 封面占位（高度撑满盒子；测试只关心暗底/条的几何）
                Box(Modifier.fillMaxWidth().height(coverHeight))
                GridProgressScrim(
                    Modifier
                        .align(Alignment.BottomCenter)
                        .onGloballyPositioned { scrim = bounds(it) },
                )
                EntryProgressBar(
                    progress = ReadingProgress(pageIndex = 1, totalPages = 10, updatedAtMs = 0L),
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .onGloballyPositioned { bar = bounds(it) },
                )
            }
        }
        view.layoutOnce(400)
        assertTrue("封面盒/暗底/条没被放置（测量没生效），本次断言无意义", cell != null && scrim != null && bar != null)
        return Measured(cell!!, scrim!!, bar!!)
    }

    @Test
    fun `暗底与条同宽 宽度都等于封面宽`() {
        val density = Robolectric.buildActivity(ComponentActivity::class.java).setup().get()
            .resources.displayMetrics.density
        val m = measure()
        val expectedPx = (cellWidth.value * density).roundToInt()
        assertEquals("封面盒宽 = 格宽", expectedPx, m.cell.width)
        assertEquals("暗底宽 = 封面宽", expectedPx, m.scrim.width)
        assertEquals("条宽 = 封面宽", expectedPx, m.bar.width)
    }

    @Test
    fun `暗底与条同高 高度都是 6dp`() {
        val density = Robolectric.buildActivity(ComponentActivity::class.java).setup().get()
            .resources.displayMetrics.density
        val expectedPx = (PROGRESS_BAR_HEIGHT.value * density).roundToInt()
        val m = measure()
        assertEquals("暗底高 = 条高常量", expectedPx, m.scrim.height)
        assertEquals("条高 = 常量", expectedPx, m.bar.height)
    }

    @Test
    fun `条压在暗底同一条带上 两者底边都是封面底边`() {
        val m = measure()
        assertEquals("暗底底边 = 封面盒底边", m.cell.bottom, m.scrim.bottom)
        assertEquals("条底边 = 暗底底边（条画在暗底上面）", m.scrim.bottom, m.bar.bottom)
        assertEquals("左右也同一段", m.scrim.left, m.bar.left)
        assertEquals(m.scrim.right, m.bar.right)
    }
}
