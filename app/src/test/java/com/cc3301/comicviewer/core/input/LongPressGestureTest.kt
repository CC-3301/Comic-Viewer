package com.cc3301.comicviewer.core.input

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 长按判定（票 #147 AC：长按顶栏「排序」按钮 = 浏览列表回顶部，点按仍是开菜单）。
 *
 * 边界全在 [LongPressGesture] 的状态里，因此这里能钉住两件事：
 * **长按只成立一次**（跳顶一次）、**未到分界的点按一个事件都不消费**（内层按钮照旧收到那一下点按 ⇒ 开菜单）。
 * 「长按成立后事件要被消费掉」由 [LongPressGesture.longPressed] 表达，真机手感（长按不弹菜单）走手动验收。
 */
class LongPressGestureTest {

    /** 分界 500ms（平台默认量级；值由调用方给，本类不写死） */
    private fun gesture() = LongPressGesture(timeoutMillis = 500)

    /** 有事件可推的一路：按住 [millis]，每 100ms 一个事件（与真机手指轻微抖动同形） */
    private fun LongPressGesture.advanceBy(millis: Long): List<Boolean> {
        val fired = mutableListOf<Boolean>()
        var t = 0L
        while (t < millis) {
            t += 100
            fired += advance(t)
        }
        return fired
    }

    @Test
    fun `按住不动到点即判长按 且只判一次`() {
        val g = gesture()
        g.down(0)

        assertTrue("到点这一刻刚成立（调用方据此跳顶一次）", g.onTimeout())
        assertTrue("成立之后一直是长按语义（最后那一下抬起也要消费）", g.longPressed)
        assertFalse("同一次按住不再成立第二次（跳顶只有一次）", g.onTimeout())
    }

    @Test
    fun `事件推进到分界也判长按 不依赖有没有到点那一下`() {
        val g = gesture()
        g.down(0)

        val fired = g.advanceBy(500)

        assertEquals("只有越过分界的那一个事件成立", listOf(false, false, false, false, true), fired)
        assertTrue(g.longPressed)
        assertFalse("成立之后不再重复", g.advance(700))
    }

    @Test
    fun `未到分界的事件一律不成立`() {
        val g = gesture()
        g.down(0)

        val fired = g.advanceBy(400)

        assertEquals("400ms 还没到 500ms：一次都没有成立", List(4) { false }, fired)
        assertFalse("不成立 ⇒ 调用方不消费任何事件 ⇒ 点按照旧落到按钮的 onClick 上（开菜单）", g.longPressed)
    }

    @Test
    fun `提前抬起不成立 点按照旧交给按钮`() {
        val g = gesture()
        g.down(0)
        g.advance(120)
        g.release()

        assertFalse(g.onTimeout())
        assertFalse("短按不是长按 ⇒ 不消费 ⇒ 开菜单那一路照旧", g.longPressed)
    }

    @Test
    fun `抬起之后到点也不再成立`() {
        val g = gesture()
        g.down(0)
        g.release()
        g.advance(900)

        assertFalse(g.onTimeout())
        assertFalse(g.longPressed)
    }

    @Test
    fun `抬起那一刻仍报长按 供调用方消费掉那一下抬起`() {
        val g = gesture()
        g.down(0)
        g.onTimeout()

        assertFalse("抬起本身不是新的一次成立", g.advance(600))
        assertTrue("抬起这一刻 longPressed 仍为 true：它要被消费，否则内层按钮开菜单", g.longPressed)
    }

    @Test
    fun `上一串判过长按之后 下一串按下重新开始`() {
        val g = gesture()
        g.down(0)
        g.onTimeout()
        g.release()

        g.down(10_000)

        assertFalse("新的一串：结论归零（分界从这一次按下重新算）", g.longPressed)
        assertFalse("未按下 600ms（只是时间往前走）不成立", g.advance(10_400))
        assertTrue("新的一串按够久照样成立", g.onTimeout())
    }
}
