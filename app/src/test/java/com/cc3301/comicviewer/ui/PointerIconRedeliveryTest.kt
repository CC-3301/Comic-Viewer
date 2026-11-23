package com.cc3301.comicviewer.ui

import android.os.Looper
import android.view.PointerIcon
import android.view.View
import androidx.activity.ComponentActivity
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * 隐藏态透明图标的重发（spec §指针自动隐藏）：什么时候重发、一次重发按几次、什么时候不按、什么时候撤掉。
 *
 * 怎么测的：Robolectric 起 [ComponentActivity]，把记录 `setPointerIcon` 调用的 [RecordingView] 设为内容视图
 * （挂上窗口后 [View.post] 才进主线程消息队列，`idle()` 才跑得到排的那次）。断言的是**调用形状**：
 * 还在队列里时视图上一次调用都没有（证明跑在事件分发之外），一连串请求只按一次，恢复之后一次也不按。
 *
 * 不覆盖：真机上 ViewRootImpl 先把图标换成默认、再被本类按回去的那一跳——Robolectric 探不到光标行为，
 * 由设备验收兜住（「隐藏 → 失焦再回焦 → 未达到恢复距离的微动」）。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PointerIconRedeliveryTest {

    /** 记录 `View.setPointerIcon` 每次调用的视图 */
    private class RecordingView(context: android.content.Context) : View(context) {
        val icons = mutableListOf<PointerIcon?>()
        override fun setPointerIcon(pointerIcon: PointerIcon?) {
            icons += pointerIcon
        }
    }

    /** 显隐判据的现值：用例里按场景改 */
    private var hidden = true

    /** 透明图标：与生产同一个来源（系统的 TYPE_NULL 图标） */
    private val icon: PointerIcon =
        PointerIcon.getSystemIcon(RuntimeEnvironment.getApplication(), PointerIcon.TYPE_NULL)

    private fun view(): RecordingView {
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup().get()
        return RecordingView(activity).also { activity.setContentView(it) }
    }

    private fun delivery(view: View) = PointerIconRedelivery(view, icon) { hidden }

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    @Test
    fun `窗口回焦且仍隐藏时立即重发透明图标`() {
        val view = view()
        delivery(view).onWindowFocusGained()

        assertEquals("回焦要当场重发，不进队列等下一帧", listOf(icon), view.icons)
    }

    @Test
    fun `窗口回焦时已恢复则不重发`() {
        hidden = false
        val view = view()
        delivery(view).onWindowFocusGained()
        idle()

        assertEquals("已恢复还重发透明图标会让可见的指针乍隐", emptyList<PointerIcon?>(), view.icons)
    }

    @Test
    fun `事件处理完仍隐藏时排在事件分发之外重发一次`() {
        val view = view()
        delivery(view).onPointerEventHandled()

        assertEquals("重发要出事件分发：请求当下不能已经按过", emptyList<PointerIcon?>(), view.icons)
        idle()
        assertEquals("排的那次落地后正好按一次", listOf(icon), view.icons)
    }

    @Test
    fun `一连串事件请求只重发一次 落地后的下一次请求重新排`() {
        val view = view()
        val delivery = delivery(view)
        repeat(3) { delivery.onPointerEventHandled() }
        idle()

        assertEquals("同一帧的多次请求只按一次", 1, view.icons.size)

        delivery.onPointerEventHandled()
        idle()
        assertEquals("上一次次落地后，新的一帧可以再排一次", 2, view.icons.size)
    }

    @Test
    fun `事件处理完已恢复则不重发`() {
        hidden = false
        val view = view()
        delivery(view).onPointerEventHandled()
        idle()

        assertEquals("恢复判据已经满足时不得再按透明图标", emptyList<PointerIcon?>(), view.icons)
    }

    @Test
    fun `排进队列之后指针恢复了就不按`() {
        val view = view()
        delivery(view).onPointerEventHandled()
        hidden = false
        idle()

        assertEquals("落地那一刻已恢复，透明图标不得晚一步盖回来", emptyList<PointerIcon?>(), view.icons)
    }

    @Test
    fun `退出阅读器撤掉还没执行的重发`() {
        val view = view()
        val delivery = delivery(view)
        delivery.onPointerEventHandled()
        delivery.cancel()
        idle()

        assertEquals("退出后不得再往已经离开的阅读器按图标", emptyList<PointerIcon?>(), view.icons)
    }

    @Test
    fun `回焦重发不叠上已经排进队列的那次`() {
        val view = view()
        val delivery = delivery(view)
        delivery.onPointerEventHandled()
        delivery.onWindowFocusGained()
        idle()

        assertEquals("同一次回焦里排队的那次要被撤掉，只按一次", listOf(icon), view.icons)
    }
}
