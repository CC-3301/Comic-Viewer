package com.cc3301.comicviewer.ui

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 指针落在哪一页（纯函数，判定见 [readerPageIndexAt]）。
 *
 * 这条判定锁的是设备上实测过的一个 bug：条漫滚到后面双击放大**毫无反应**——因为 [readerPageIndexAt]
 * 的入参 `bounds` 里躺着离屏页的旧矩形（离屏页从不删旧值），而「第一个命中的」往往是早就不在屏上的
 * 那几页。日志实证：13 次双击里 9 次目标页是第 0 页，而屏上实际是第 19~24 页。
 */
class ReaderPageHitTest {

    private val viewport = Rect(0f, 0f, 1600f, 1200f)

    /** 一页的显示矩形（窗口坐标）：页宽满视口，页高按 [top] 往下排 */
    private fun pageRect(top: Float, height: Float = 2400f) = Rect(0f, top, 1600f, top + height)

    private fun hit(
        pos: Offset,
        current: Int,
        visible: Set<Int>,
        bounds: Map<Int, Rect>,
        viewportLeft: Float = viewport.left,
        viewportTop: Float = viewport.top,
    ) = readerPageIndexAt(pos, current, visible, bounds, viewportLeft, viewportTop)

    @Test
    fun `当前页在屏且含指针时取当前页`() {
        val bounds = mapOf(19 to pageRect(-800f), 20 to pageRect(1600f))
        assertEquals(19, hit(Offset(700f, 800f), current = 19, visible = setOf(19, 20), bounds = bounds))
    }

    @Test
    fun `当前页不含指针时取在屏页里命中的那一页`() {
        // 第 19~21 页都在屏上，指针落在第 20 页上（第 19 页在它上面、第 21 页在下面）
        val bounds = mapOf(19 to pageRect(-2400f), 20 to pageRect(0f), 21 to pageRect(2400f))
        assertEquals(20, hit(Offset(700f, 800f), current = 19, visible = setOf(19, 20, 21), bounds = bounds))
    }

    @Test
    fun `离屏页的旧矩形不得胜出`() {
        // 回归（设备实测的那个 bug）：第 0 页早就滚出屏幕，但它最后一次布局的矩形仍盖着视口顶部；
        // 当前页在屏、矩形却还没重测（布局刚变）。旧口径会返回 0（=> 放大写给一页看不见的页），
        // 现在只能退到当前页。
        val bounds = mapOf(
            0 to pageRect(0f),            // 幽灵矩形：第 0 页离屏后的旧值
            19 to pageRect(-4000f),       // 在屏页，但矩形是旧的、不含指针
            20 to pageRect(1000f),
        )
        assertEquals(
            19,
            hit(Offset(700f, 800f), current = 19, visible = setOf(19, 20), bounds = bounds),
        )
    }

    @Test
    fun `一个在屏页都不含指针时回退当前页`() {
        val bounds = mapOf(19 to pageRect(-4000f), 0 to pageRect(0f))
        assertEquals(19, hit(Offset(700f, 800f), current = 19, visible = setOf(19), bounds = bounds))
    }

    @Test
    fun `视口不在窗口原点时按视口局部坐标比较`() {
        // gesture 节点（= 视口）在窗口里的位置 (0, 300)：页矩形与指针都按这个换算
        val bounds = mapOf(7 to Rect(0f, 300f, 1600f, 2700f))
        assertEquals(
            7,
            hit(
                pos = Offset(700f, 800f),
                current = 7,
                visible = setOf(7),
                bounds = bounds,
                viewportTop = 300f,
            ),
        )
    }

    @Test
    fun `多页同时命中时取页号小的 结果确定`() {
        // 只在布局重叠的那一瞬可能发生；取小页号保证同一输入同一结果
        val bounds = mapOf(21 to pageRect(0f), 20 to pageRect(0f))
        assertEquals(20, hit(Offset(700f, 800f), current = 99, visible = setOf(20, 21), bounds = bounds))
    }

    @Test
    fun `单页模式 在屏的只有当前页`() {
        val bounds = mapOf(5 to Rect(100f, 200f, 900f, 1100f))
        assertEquals(5, hit(Offset(500f, 600f), current = 5, visible = setOf(5), bounds = bounds))
    }
}
