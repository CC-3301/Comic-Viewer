package com.cc3301.comicviewer.ui

import android.view.PointerIcon
import android.view.View
import androidx.activity.ComponentActivity
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * 窗口指针图标的落地（spec §指针自动隐藏）：什么时候按、按哪个图标。
 *
 * 怎么测的：Robolectric 起 [ComponentActivity]，把记录 `setPointerIcon` 调用的 [RecordingView] 设为内容视图。
 * 断言的是**调用形状**：隐藏按透明图标、恢复交还系统（null）、隐藏期间每次事件处理完当场补按一次。
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

    private fun view(): RecordingView {
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup().get()
        return RecordingView(activity).also { activity.setContentView(it) }
    }

    private fun delivery(view: View) = PointerIconRedelivery(view, transparent)

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
    fun `事件处理完当场补按 不等下一帧`() {
        val view = view()
        val delivery = delivery(view)
        delivery.onHiddenChanged(true)
        view.icons.clear()
        delivery.onPointerEventHandled()
        assertEquals("处理完就按：压到下一帧会先让系统把箭头画出来", listOf(transparent), view.icons)
    }

    @Test
    fun `可见时事件处理完不按`() {
        val view = view()
        val delivery = delivery(view)
        delivery.onHiddenChanged(false)
        view.icons.clear()
        delivery.onPointerEventHandled()
        assertEquals("指针可见时不必补按，窗口图标本来就是系统箭头", emptyList<PointerIcon?>(), view.icons)
    }
}
