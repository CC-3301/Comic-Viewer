package com.cc3301.comicviewer.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 页面按路由 connId 解析来源的两个纯函数（票 25 第 1 项）：抽自浏览列表与书柜柜内原本各持一份的
 * 「连接查询 → 解析来源 → 局部 source/sourceError」样板。判定与提示口径都收在这里，
 * 两侧的界面接线（Composable）走真机清单。
 */
class ConnectionSourceTest {

    // ---------- 连接被删除即退栈（原 BrowserScreen / CabinetScreen 各写一遍的判定） ----------

    @Test
    fun `连接列表已加载完却查不到该 id 视为已删除`() {
        assertTrue(connectionVanished(listOf(1L, 2L), connId = 7))
    }

    @Test
    fun `连接还在时不退栈`() {
        assertFalse(connectionVanished(listOf(1L, 2L), connId = 2))
    }

    @Test
    fun `连接列表为空视为还没加载完 不退栈`() {
        // 首帧 collectAsState(initial = emptyList()) 与空库都会走到这里：不能把两者当成「连接被删」
        assertFalse(connectionVanished(emptyList(), connId = 1))
    }

    // ---------- 解析失败提示（原来两侧各写一份同样的兜底串） ----------

    @Test
    fun `来源构造器的中文提示原样展示`() {
        assertEquals(
            "SMB 连接配置损坏，请重新添加",
            sourceFailureMessage(IllegalArgumentException("SMB 连接配置损坏，请重新添加")),
        )
    }

    @Test
    fun `来源构造失败没有消息时用统一兜底串`() {
        assertEquals("连接配置不可用", sourceFailureMessage(IllegalStateException()))
    }
}
