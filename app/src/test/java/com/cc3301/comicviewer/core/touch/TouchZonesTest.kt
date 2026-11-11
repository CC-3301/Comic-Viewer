package com.cc3301.comicviewer.core.touch

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** 触摸区域类型 3 纯函数（分区判定、条漫页位、条漫音量键目标、条漫触摸区目标均有单元测试） */
class TouchZonesTest {

    private val w = 1080f

    // ---------- 三等分判定 ----------

    @Test
    fun `左中右三分边界`() {
        assertEquals(TouchZone.LEFT, touchZoneAt(0f, w))
        assertEquals(TouchZone.LEFT, touchZoneAt(359.9f, w))
        assertEquals(TouchZone.CENTER, touchZoneAt(360f, w))
        assertEquals(TouchZone.CENTER, touchZoneAt(719.9f, w))
        assertEquals(TouchZone.RIGHT, touchZoneAt(720f, w))
        assertEquals(TouchZone.RIGHT, touchZoneAt(1079f, w))
    }

    @Test
    fun `非法宽度防御返回中区`() {
        assertEquals(TouchZone.CENTER, touchZoneAt(0f, 0f))
        assertEquals(TouchZone.CENTER, touchZoneAt(100f, -5f))
    }

    // ---------- 条漫滚动目标 ----------

    @Test
    fun `左区上一张 越界钳到首页`() {
        assertEquals(2, webtoonPrevTarget(3, 10))
        assertEquals(0, webtoonPrevTarget(0, 10))
        assertEquals(0, webtoonPrevTarget(1, 10))
    }

    @Test
    fun `右区下一张 越界钳到末页`() {
        assertEquals(4, webtoonNextTarget(3, 10))
        assertEquals(9, webtoonNextTarget(9, 10))
        assertEquals(9, webtoonNextTarget(8, 10))
    }

    @Test
    fun `单页书两区都停在0`() {
        assertEquals(0, webtoonPrevTarget(0, 1))
        assertEquals(0, webtoonNextTarget(0, 1))
    }

    // ---------- 条漫页位（视口中线所在的页）----------

    @Test
    fun `页位取中线所在的页`() {
        // 视口 2000px、三页各 800px：中线 1000 落在第二页
        val pages = listOf(VisiblePage(0, 0), VisiblePage(1, 800), VisiblePage(2, 1600))
        assertEquals(1, webtoonCurrentPage(pages, 1000f))
        // 第一页已滚出视口顶（偏移为负）：中线 300 落在第二页（页顶在 100）
        assertEquals(4, webtoonCurrentPage(listOf(VisiblePage(3, -700), VisiblePage(4, 100)), 300f))
    }

    @Test
    fun `中线压在交界处取后一页`() {
        val pages = listOf(VisiblePage(0, 0), VisiblePage(1, 800), VisiblePage(2, 1600))
        assertEquals(0, webtoonCurrentPage(pages, 799f))
        assertEquals(1, webtoonCurrentPage(pages, 800f))
        assertEquals(2, webtoonCurrentPage(pages, 1600f))
    }

    @Test
    fun `滚到书末不再特判末页`() {
        // 末页矮于视口、已滚到底：中线落在倒数第二页里就报倒数第二页——
        // 不再有「已滚到书末即报末页」的特判
        val pages = listOf(VisiblePage(7, -100), VisiblePage(8, 700), VisiblePage(9, 1500))
        assertEquals(8, webtoonCurrentPage(pages, 1000f))
    }

    @Test
    fun `中线落在所有页之下时报最靠后的那一页`() {
        // 整本不足一屏：页只排在上半屏，中线落在页之后的空白处
        assertEquals(
            2,
            webtoonCurrentPage(listOf(VisiblePage(0, 0), VisiblePage(1, 300), VisiblePage(2, 600)), 1000f),
        )
    }

    @Test
    fun `一页都不在屏时报首页`() {
        // 首帧还没量出版式，没有在屏页可判
        assertEquals(0, webtoonCurrentPage(emptyList(), 500f))
    }

    // ---------- 条漫音量键目标（一次按压 = 跳到下一页/上一页的页首）----------
    //
    // 本段只断言「本方向上还有没有页可跳」（null = 没有）。拿到 null 的调用方（阅读器层）会去弹
    // 跨书确认并**仍然消费按键**——「按键被实际消费、不改系统音量」在 Compose 层（ReaderScreen 恒返回
    // true + MainActivity 分发），本纯函数层没有接缝，由设备验收把关。

    @Test
    fun `音量下前进一页到下一页页首`() {
        // 一屏装 2~3 页时，旧口径「滚一屏」会一次跳 2~3 页（设备上出现的症状）
        assertEquals(4, webtoonVolumeTarget(3, 10, canScrollForward = true, canScrollBackward = true, forward = true))
        assertEquals(1, webtoonVolumeTarget(0, 10, canScrollForward = true, canScrollBackward = false, forward = true))
    }

    @Test
    fun `音量上回退一页到上一页页首`() {
        assertEquals(2, webtoonVolumeTarget(3, 10, canScrollForward = true, canScrollBackward = true, forward = false))
        // 首页页内回退 = 回到首页页首（与左区同一份语义），再往前无目标
        assertEquals(0, webtoonVolumeTarget(0, 10, canScrollForward = true, canScrollBackward = true, forward = false))
    }

    @Test
    fun `条漫书末无页可跳`() {
        // 已滚到书末（滚不动）：没有下一页可跳（拿到 null 的阅读页会弹与右区同一份跨书确认，
        // 而不是把按键交还系统）
        assertNull(webtoonVolumeTarget(8, 10, canScrollForward = false, canScrollBackward = true, forward = true))
        // 页位已是末页：同样无页可跳（即便列表还能滚）
        assertNull(webtoonVolumeTarget(9, 10, canScrollForward = true, canScrollBackward = true, forward = true))
    }

    @Test
    fun `音量上在首页页首无页可跳`() {
        assertNull(webtoonVolumeTarget(0, 10, canScrollForward = true, canScrollBackward = false, forward = false))
    }

    @Test
    fun `书末回退一页`() {
        // 页位是末页（索引 9）：回退落索引 8。列表在书末滚不动不影响回退。
        assertEquals(8, webtoonVolumeTarget(9, 10, canScrollForward = false, canScrollBackward = true, forward = false))
    }

    @Test
    fun `整本不满一屏时两个方向都无页可跳`() {
        assertNull(webtoonVolumeTarget(0, 5, canScrollForward = false, canScrollBackward = false, forward = true))
        assertNull(webtoonVolumeTarget(0, 5, canScrollForward = false, canScrollBackward = false, forward = false))
    }

    @Test
    fun `空书与越界页位防御`() {
        assertNull(webtoonVolumeTarget(0, 0, canScrollForward = false, canScrollBackward = false, forward = true))
        // 越界页位：钳到末页后照旧按末页判定，不越界返回
        assertNull(webtoonVolumeTarget(7, 5, canScrollForward = true, canScrollBackward = true, forward = true))
        assertEquals(3, webtoonVolumeTarget(7, 5, canScrollForward = true, canScrollBackward = true, forward = false))
    }

    // ---------- 条漫触摸区目标（左/右区与页位同一套口径）----------

    @Test
    fun `末页点左区退一页`() {
        // 页位是末页（索引 9）：左区退 **一页** → 索引 8 = 第 9 页。
        assertEquals(8, webtoonTapTarget(9, 10, canScrollForward = false, canScrollBackward = true, forward = false))
    }

    @Test
    fun `一屏装多页时左右区各按页位走一页`() {
        // 页矮于视口（一屏 2~3 页）：左右区都相对 **页位** ±1 页，不按屏也不按屏底走
        assertEquals(4, webtoonTapTarget(3, 10, canScrollForward = true, canScrollBackward = true, forward = true))
        assertEquals(2, webtoonTapTarget(3, 10, canScrollForward = true, canScrollBackward = true, forward = false))
    }

    @Test
    fun `书首左区与书末右区交给跨书确认`() {
        // 书首（滚不动）：左区 → null = 跨书两段式确认；书末（滚不动）：右区 → null
        assertNull(webtoonTapTarget(0, 10, canScrollForward = true, canScrollBackward = false, forward = false))
        assertNull(webtoonTapTarget(8, 10, canScrollForward = false, canScrollBackward = true, forward = true))
    }

    @Test
    fun `末页高过一屏时右区照旧跳末页页首`() {
        // 触摸区的既有语义：还能往下滚就不算到端点，右区把读者带回末页页首，不弹跨书确认。
        // 与音量键的差别正在这里：同状态下 webtoonVolumeTarget 返回 null（阅读页据此弹跨书确认）
        assertEquals(9, webtoonTapTarget(9, 10, canScrollForward = true, canScrollBackward = true, forward = true))
    }

    @Test
    fun `首页页内回退等于回到首页页首`() {
        // 页位是第 1 页：左区目标 = 钳在 0 的上一页页首 = 回到当前图起始
        assertEquals(0, webtoonTapTarget(0, 10, canScrollForward = true, canScrollBackward = true, forward = false))
    }

    @Test
    fun `整本不满一屏与空书防御`() {
        // 前后都不可滚 = 整本同屏：两个方向都无页可翻 → 交跨书确认
        assertNull(webtoonTapTarget(0, 5, canScrollForward = false, canScrollBackward = false, forward = true))
        assertNull(webtoonTapTarget(0, 5, canScrollForward = false, canScrollBackward = false, forward = false))
        assertNull(webtoonTapTarget(0, 0, canScrollForward = false, canScrollBackward = false, forward = true))
        // 越界页位：钳到末页后照旧按末页判定，不越界返回
        assertEquals(3, webtoonTapTarget(7, 5, canScrollForward = true, canScrollBackward = true, forward = false))
    }

    // ---------- 区域意图映射（两模式、两方向统一）----------

    @Test
    fun `区域意图左上一页 中菜单 右下一页`() {
        assertEquals(TapIntent.PREV_PAGE, tapIntentAt(0f, w))
        assertEquals(TapIntent.PREV_PAGE, tapIntentAt(359.9f, w))
        assertEquals(TapIntent.MENU, tapIntentAt(360f, w))
        assertEquals(TapIntent.MENU, tapIntentAt(719.9f, w))
        assertEquals(TapIntent.NEXT_PAGE, tapIntentAt(720f, w))
        assertEquals(TapIntent.NEXT_PAGE, tapIntentAt(1079f, w))
    }

    @Test
    fun `非法宽度落中区`() {
        // 映射签名不含方向参数：LTR/RTL 与条漫/单页下点击区语义恒定（spec 故事 26）
        assertEquals(TapIntent.MENU, tapIntentAt(100f, 0f))
    }

    // ---------- 单页翻页目标 ----------

    @Test
    fun `单页左区上一页 书首返回空`() {
        assertNull(pagedPrevTarget(0, 10))
        assertEquals(0, pagedPrevTarget(1, 10)!!)
        assertEquals(8, pagedPrevTarget(9, 10)!!)
    }

    @Test
    fun `单页右区下一页 书末返回空`() {
        assertNull(pagedNextTarget(9, 10))
        assertEquals(1, pagedNextTarget(0, 10)!!)
        assertEquals(9, pagedNextTarget(8, 10)!!)
    }

    @Test
    fun `单页空书两区都返回空`() {
        assertNull(pagedPrevTarget(0, 0))
        assertNull(pagedNextTarget(0, 0))
    }
}
