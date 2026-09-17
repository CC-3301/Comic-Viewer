package com.cc3301.comicviewer.core.input

import com.cc3301.comicviewer.core.reader.ReadingMode
import com.cc3301.comicviewer.core.reader.wheelSurface
import com.cc3301.comicviewer.core.touch.TapIntent
import com.cc3301.comicviewer.core.touch.tapIntentAt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** 鼠标输入映射纯函数（票 17，spec 故事 22/35/36/37） */
class MouseInputTest {

    private val w = 1080f

    // ---------- AC1 滚轮三场景（spec 故事 22/35） ----------

    @Test
    fun `列表滚轮交给列表自身滚动`() {
        assertEquals(WheelAction.SCROLL_SELF, wheelAction(WheelSurface.LIST, 1f))
        assertEquals(WheelAction.SCROLL_SELF, wheelAction(WheelSurface.LIST, -1f))
    }

    @Test
    fun `条漫滚轮交给条漫自身滚动`() {
        assertEquals(WheelAction.SCROLL_SELF, wheelAction(WheelSurface.WEBTOON, 1f))
        assertEquals(WheelAction.SCROLL_SELF, wheelAction(WheelSurface.WEBTOON, -1f))
    }

    @Test
    fun `单页滚轮一格翻一页`() {
        assertEquals(WheelAction.NEXT_PAGE, wheelAction(WheelSurface.PAGED, 1f))   // 向下滚 = 下一页
        assertEquals(WheelAction.PREV_PAGE, wheelAction(WheelSurface.PAGED, -1f))  // 向上滚 = 上一页
        // 一格即一页：方向只看符号，幅度不参与
        assertEquals(WheelAction.NEXT_PAGE, wheelAction(WheelSurface.PAGED, 3f))
        assertEquals(WheelAction.PREV_PAGE, wheelAction(WheelSurface.PAGED, -0.5f))
    }

    @Test
    fun `无纵向分量不处理`() {
        assertEquals(WheelAction.IGNORE, wheelAction(WheelSurface.PAGED, 0f))
        assertEquals(WheelAction.IGNORE, wheelAction(WheelSurface.LIST, 0f))
    }

    @Test
    fun `阅读模式映射滚轮界面`() {
        assertEquals(WheelSurface.WEBTOON, ReadingMode.WEBTOON.wheelSurface)
        assertEquals(WheelSurface.PAGED, ReadingMode.PAGED.wheelSurface)
    }

    // ---------- AC2 左右键与触摸区域等价（spec 故事 36） ----------

    @Test
    fun `左右键命中同一触摸区域`() {
        // 左区 / 中区 / 右区各取一点：两键结果必须与触摸输入完全一致
        listOf(10f, w / 2f, w - 10f).forEach { x ->
            val touch = tapIntentAt(x, w)
            assertEquals(touch, mouseTapIntent(MOUSE_BUTTON_PRIMARY, x, w))
            assertEquals(touch, mouseTapIntent(MOUSE_BUTTON_SECONDARY, x, w))
        }
    }

    @Test
    fun `左右键跨区意图覆盖三分区`() {
        assertEquals(TapIntent.PREV_PAGE, mouseTapIntent(MOUSE_BUTTON_SECONDARY, 10f, w))
        assertEquals(TapIntent.MENU, mouseTapIntent(MOUSE_BUTTON_SECONDARY, w / 2f, w))
        assertEquals(TapIntent.NEXT_PAGE, mouseTapIntent(MOUSE_BUTTON_SECONDARY, w - 10f, w))
    }

    @Test
    fun `非左右键不触发触摸区域`() {
        assertNull(mouseTapIntent(MOUSE_BUTTON_BACK, 10f, w))
        assertNull(mouseTapIntent(MOUSE_BUTTON_FORWARD, 10f, w))
        assertNull(mouseTapIntent(0, 10f, w))
    }

    // ---------- AC3 侧键（spec 故事 37） ----------

    @Test
    fun `侧键映射浏览历史方向`() {
        assertEquals(HistoryAction.BACK, sideButtonAction(MOUSE_BUTTON_BACK))
        assertEquals(HistoryAction.FORWARD, sideButtonAction(MOUSE_BUTTON_FORWARD))
    }

    @Test
    fun `左右键不是侧键`() {
        assertNull(sideButtonAction(MOUSE_BUTTON_PRIMARY))
        assertNull(sideButtonAction(MOUSE_BUTTON_SECONDARY))
    }
}
