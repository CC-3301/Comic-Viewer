package com.cc3301.comicviewer.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 组合期槽位的摘除守卫（票 25 第 2 项）：抽自五处逐字重复的 DisposableEffect（浏览列表滚轮、
 * 阅读器滚轮/右键/音量键、前进侧键）。守卫只能按引用身份判——界面切换时两个界面的组合期短暂重叠，
 * 先前界面的 onDispose 若无条件写 null，就会抹掉后进入界面刚注册的处理器。
 */
class SlotReleaseTest {

    /** equals 按值相等（任意两个同值实例 `==`）：用来证明守卫取引用身份（`!==`）而非 equals。 */
    private class Handler(val id: Int) {
        override fun equals(other: Any?): Boolean = other is Handler && other.id == id
        override fun hashCode(): Int = id
    }

    @Test
    fun `仍挂着自己那一份时清空`() {
        val slot = HandlerSlot<Handler>()
        val mine = Handler(1)
        slot.value = mine

        assertTrue(clearSlotIfCurrent(slot.value, mine) { slot.value = null })
        assertNull(slot.value)
    }

    @Test
    fun `槽位已换成后继注册者时不动`() {
        val slot = HandlerSlot<Handler>()
        val mine = Handler(1)
        val successor = Handler(1) // 与 mine equals 相等但引用不同
        slot.value = successor

        assertFalse(clearSlotIfCurrent(slot.value, mine) { slot.value = null })
        assertEquals(successor, slot.value)
    }

    @Test
    fun `槽位已被清空时不重复清`() {
        var cleared = 0

        assertFalse(clearSlotIfCurrent<Handler>(null, Handler(1)) { cleared++ })
        assertEquals(0, cleared)
    }

    @Test
    fun `槽位未注册时初始为空`() {
        assertNull(HandlerSlot<Handler>().value)
    }
}
