package com.cc3301.comicviewer.ui

import android.os.Looper
import android.view.PointerIcon
import android.view.View
import androidx.activity.ComponentActivity
import java.util.concurrent.TimeUnit
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

/** 一帧的时长（毫秒）：帧回调按它延时落地 */
private const val FRAME_MILLIS = 16

/**
 * 窗口指针图标的落地（spec §指针自动隐藏）：什么时候按、按哪个图标、什么时候撤掉。
 *
 * 帧边界怎么来的：Robolectric 的帧回调默认隔 1ms 就落地，`idle()` 就能跑到，分不出「同一帧」；
 * 本文件把 `ShadowChoreographer.setPostFrameCallbackDelay` 设成一帧（16ms），于是
 * `idle()`（不推进时钟）＝**还是同一帧**，`idleFor(16ms)`＝**下一帧**。
 * 这条边界正是「同一帧至多一次」与「至多一次在途」的分水岭：重发若落到消息队列上，`idle()` 就会执行；
 * 落到帧回调上则要等下一帧。
 *
 * 怎么测的：Robolectric 起 [ComponentActivity]，把记录 `setPointerIcon` 调用的 [RecordingView] 设为内容视图。
 * 断言的是**调用形状**：隐藏按透明图标、恢复交还系统（null）、同一帧多次请求只按一次。
 *
 * 不覆盖：真机上 native 图标的实际类型与可见性——Robolectric 探不到光标行为，由设备验收兜住。
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

    /** 透明图标：与生产同一个来源（系统的 TYPE_NULL 图标） */
    private val transparent: PointerIcon =
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

    private fun delivery(view: View) = PointerIconRedelivery(view, transparent)

    /** 还在同一帧：不推进时钟，只跑到期的消息 */
    private fun idleSameFrame() = shadowOf(Looper.getMainLooper()).idle()

    /** 推进一帧：帧回调在这一步落地 */
    private fun nextFrame() = shadowOf(Looper.getMainLooper()).idleFor(FRAME_MILLIS.toLong(), TimeUnit.MILLISECONDS)

    @Test
    fun `隐藏时当场按透明图标`() {
        val view = view()
        delivery(view).onHiddenChanged(true)
        assertEquals("停满 3 秒隐藏那一刻就要按透明图标，不等下一次事件", listOf(transparent), view.icons)
    }

    @Test
    fun `恢复时当场把图标交还系统`() {
        val view = view()
        val delivery = delivery(view)
        delivery.onHiddenChanged(true)
        view.icons.clear()
        delivery.onHiddenChanged(false)
        assertEquals("恢复＝换回系统箭头：交还窗口图标（null 即不覆盖）", listOf<PointerIcon?>(null), view.icons)
    }

    @Test
    fun `事件处理完仍隐藏时排在事件分发之外 要等下一帧`() {
        val view = view()
        val delivery = delivery(view)
        delivery.onHiddenChanged(true)
        view.icons.clear()
        delivery.onPointerEventHandled()
        idleSameFrame()
        assertEquals("请求当下不能已经按过，也不能在同一帧里按", emptyList<PointerIcon?>(), view.icons)
        nextFrame()
        assertEquals("排的那次在下一帧落地，正好按一次", listOf(transparent), view.icons)
    }

    @Test
    fun `同一帧内的多次请求只按一次`() {
        val view = view()
        val delivery = delivery(view)
        delivery.onHiddenChanged(true)
        view.icons.clear()
        repeat(3) { delivery.onPointerEventHandled() }
        idleSameFrame()
        nextFrame()
        assertEquals("同一帧的多次请求合并成一次重发", 1, view.icons.size)
    }

    @Test
    fun `可见时事件处理完不按`() {
        val view = view()
        val delivery = delivery(view)
        delivery.onHiddenChanged(false)
        view.icons.clear()
        delivery.onPointerEventHandled()
        nextFrame()
        assertEquals("指针可见时不必重发，每次鼠标移动都按一次是白花", emptyList<PointerIcon?>(), view.icons)
    }

    @Test
    fun `显隐变了撤掉在途的那次`() {
        val view = view()
        val delivery = delivery(view)
        delivery.onHiddenChanged(true)
        delivery.onPointerEventHandled()
        delivery.onHiddenChanged(false)
        view.icons.clear()
        nextFrame()
        assertEquals("已经交还系统，在途的那次透明图标不得晚一步盖回来", emptyList<PointerIcon?>(), view.icons)
    }
}
