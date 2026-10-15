package com.cc3301.comicviewer.ui

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
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
        viewportOrigin: Offset = viewport.topLeft,
    ) = readerPageIndexAt(pos, current, visible, bounds, viewportOrigin)

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
        // 回归（设备实测的那个 bug）：第 0 页早滚出屏幕，但它最后一次布局的矩形仍盖着视口顶部
        // （0..2400）。旧口径「第一个命中的」会返回 0 —— 放大写给了看不见的页；新口径只看在屏页。
        // 本算例里在屏两页的矩形都不含指针，于是走最近页那一步（第 20 页的旧矩形下缘离指针更近）。
        val bounds = mapOf(
            0 to pageRect(0f),            // 幽灵矩形：第 0 页离屏后的旧值，含 (700, 800)
            19 to pageRect(-4000f),       // 在屏页，矩形是旧值：y ∈ [-4000, -1600]
            20 to pageRect(1000f),        // 在屏页，矩形是旧值：y ∈ [1000, 3400]
        )
        val got = hit(Offset(700f, 800f), current = 19, visible = setOf(19, 20), bounds = bounds)
        assertNotEquals("离屏的第 0 页绝不能参与命中：它的旧矩形正盖着指针", 0, got)
        assertEquals("在屏页里离指针最近的（20，下缘 1000 vs 19 的 1600）", 20, got)
        assertTrue("结果必须落在在屏页里：$got", got in setOf(19, 20))
    }

    @Test
    fun `矩形都没重测时 取离指针最近的在屏页`() {
        // 页显示矩形一律 1600x2400（满宽、竖版页）。三页的矩形都还是旧值、谁都不含指针 y=2480：
        // 19 在 [-3000, -600]、20 在 [0, 2400]、21 在 [2500, 4900]。
        // 回退当前页（条漫 = 顶部可见页 19）会把放大写到顶部那页；应取离指针最近的：
        // 到 20 的下缘差 80、到 21 的上缘差 20 ⇒ 21。
        val bounds = mapOf(
            19 to pageRect(-3000f),
            20 to pageRect(0f),
            21 to pageRect(2500f),
        )
        assertEquals(
            21,
            hit(Offset(700f, 2480f), current = 19, visible = setOf(19, 20, 21), bounds = bounds),
        )
    }

    @Test
    fun `离屏页的矩形一个都没有时回退当前页`() {
        assertEquals(19, hit(Offset(700f, 800f), current = 19, visible = setOf(19), bounds = emptyMap()))
    }

    @Test
    fun `距离相同时取页号小的 结果确定`() {
        // 指针 y=0：21 的旧矩形在 [-3000, -600]（到指针差 600）、22 的在 [600, 3000]（也差 600）
        // ⇒ 距离相同，取页号小的；同一个输入必须永远给出同一个答案。
        val bounds = mapOf(22 to pageRect(600f), 21 to pageRect(-3000f))
        assertEquals(21, hit(Offset(700f, 0f), current = 99, visible = setOf(21, 22), bounds = bounds))
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
                viewportOrigin = Offset(0f, 300f),
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
