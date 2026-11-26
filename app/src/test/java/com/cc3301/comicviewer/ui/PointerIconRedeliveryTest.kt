package com.cc3301.comicviewer.ui

import android.os.Looper
import android.view.PointerIcon
import android.view.View
import androidx.activity.ComponentActivity
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowChoreographer
import java.util.concurrent.TimeUnit

/** 一帧的时长（毫秒）：帧回调按它延时落地 */
private const val FRAME_MILLIS = 16

/**
 * 隐藏态透明图标的重发（spec §指针自动隐藏）：什么时候重发、一次重发按几次、什么时候不按、什么时候撤掉。
 *
 * 帧边界怎么来的：Robolectric 的帧回调默认隔 1ms 就落地，`idle()` 就能跑到，分不出「同一帧」；
 * 本文件把 `ShadowChoreographer.setPostFrameCallbackDelay` 设成一帧（16ms），于是
 * `idle()`（不推进时钟）＝**还是同一帧**，`idleFor(16ms)`＝**下一帧**。
 * 这条边界正是「同一帧至多一次」与「至多一次在途」的分水岭：重发若落到消息队列上，`idle()` 就会执行；
 * 落到帧回调上则要等下一帧。
 *
 * 怎么测的：Robolectric 起 [ComponentActivity]，把记录 `setPointerIcon` 调用的 [RecordingView] 设为内容视图。
 * 断言的是**调用形状**：同一帧内请求之后视图上一次调用都没有，下一帧正好一次，恢复之后一次也不按。
 *
 * 不覆盖：真机上 native 图标的实际类型与可见性——Robolectric 探不到光标行为，
 * 由设备验收兜住（「隐藏 → 失焦即恢复可见，回焦后仍可见」）。
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

    @Before
    fun scheduleFrameCallbacksOneFrameLate() {
        ShadowChoreographer.setPostFrameCallbackDelay(FRAME_MILLIS)
    }

    @After
    fun restoreFrameCallbacks() {
        ShadowChoreographer.reset()
    }

    private fun view(): RecordingView {
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup().get()
        return RecordingView(activity).also { activity.setContentView(it) }
    }

    private fun delivery(view: View) = PointerIconRedelivery(view, icon) { hidden }

    /** 还在同一帧：不推进时钟，只跑到期的消息 */
    private fun idleSameFrame() = shadowOf(Looper.getMainLooper()).idle()

    /** 推进一帧：帧回调在这一步落地 */
    private fun nextFrame() = shadowOf(Looper.getMainLooper()).idleFor(FRAME_MILLIS.toLong(), TimeUnit.MILLISECONDS)

    @Test
    fun `事件处理完仍隐藏时排在事件分发与消息队列之外 要等下一帧`() {
        val view = view()
        delivery(view).onPointerEventHandled()

        idleSameFrame()
        assertEquals("请求当下不能已经按过，也不能在同一帧里按", emptyList<PointerIcon?>(), view.icons)
        nextFrame()
        assertEquals("排的那次在下一帧落地，正好按一次", listOf(icon), view.icons)
    }

    @Test
    fun `同一帧内的多次请求只按一次`() {
        val view = view()
        val delivery = delivery(view)
        repeat(3) { delivery.onPointerEventHandled() }
        idleSameFrame()
        nextFrame()

        assertEquals("同一帧的多次请求合并成一次重发", 1, view.icons.size)
    }

    @Test
    fun `落地后同一帧内的新请求要等到下一帧`() {
        val view = view()
        val delivery = delivery(view)
        delivery.onPointerEventHandled()
        nextFrame()
        assertEquals("第一帧按一次", 1, view.icons.size)

        delivery.onPointerEventHandled()
        idleSameFrame()
        assertEquals("帧回调已经落过场的这一帧里，新请求不得在同一帧里再按一次", 1, view.icons.size)

        nextFrame()
        assertEquals("再等一帧才按第二次", 2, view.icons.size)
    }

    @Test
    fun `事件处理完已恢复则不重发`() {
        hidden = false
        val view = view()
        delivery(view).onPointerEventHandled()
        nextFrame()

        assertEquals("恢复判据已经满足时不得再按透明图标", emptyList<PointerIcon?>(), view.icons)
    }

    @Test
    fun `排进队列之后指针恢复了就不按`() {
        val view = view()
        delivery(view).onPointerEventHandled()
        hidden = false
        nextFrame()

        assertEquals("落地那一刻已恢复，透明图标不得晚一步盖回来", emptyList<PointerIcon?>(), view.icons)
    }

    @Test
    fun `退出阅读器撤掉还没执行的重发`() {
        val view = view()
        val delivery = delivery(view)
        delivery.onPointerEventHandled()
        delivery.cancel()
        nextFrame()

        assertEquals("退出后不得再往已经离开的阅读器按图标", emptyList<PointerIcon?>(), view.icons)
    }
}
