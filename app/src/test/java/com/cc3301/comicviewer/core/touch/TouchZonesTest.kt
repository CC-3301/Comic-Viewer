package com.cc3301.comicviewer.core.touch

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** 触摸区域类型 3 纯函数（票 05，AC：分区判定有单元测试；票 #87 加条漫页位；票 #89 加条漫音量键目标） */
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

    // ---------- 条漫页位（票 #87：书末页位必须走到末页）----------

    @Test
    fun `书末且内容超过一屏时页位是最后一页`() {
        // 末页矮于视口时滚到底：顶部可见页是倒数第二页（10 页书报 8），
        // 但读者停在最后一页——菜单预览高亮与页码都必须落在末页。
        assertEquals(
            9,
            webtoonCurrentPage(firstVisibleIndex = 8, pageCount = 10, canScrollForward = false, canScrollBackward = true),
        )
    }

    @Test
    fun `末页高过一屏时页位照旧是末页`() {
        assertEquals(
            9,
            webtoonCurrentPage(firstVisibleIndex = 9, pageCount = 10, canScrollForward = false, canScrollBackward = true),
        )
    }

    @Test
    fun `中间页与书首页位不回归`() {
        assertEquals(4, webtoonCurrentPage(4, 10, canScrollForward = true, canScrollBackward = true))
        assertEquals(0, webtoonCurrentPage(0, 10, canScrollForward = true, canScrollBackward = false))
        assertEquals(1, webtoonCurrentPage(1, 10, canScrollForward = true, canScrollBackward = true))
    }

    @Test
    fun `整本不满一屏时仍报顶部可见页`() {
        // 前后都不可滚 = 全书同屏「从头看起」，不是「读到末页」，不得判成末页
        assertEquals(0, webtoonCurrentPage(0, 3, canScrollForward = false, canScrollBackward = false))
        assertEquals(0, webtoonCurrentPage(0, 1, canScrollForward = false, canScrollBackward = false))
    }

    @Test
    fun `空书页位钳到0`() {
        assertEquals(0, webtoonCurrentPage(0, 0, canScrollForward = false, canScrollBackward = false))
    }

    // ---------- 条漫音量键目标（票 #89：一次按压 = 翻到下一页/上一页的页首）----------

    @Test
    fun `音量下前进一页到下一页页首`() {
        // 一屏装 2~3 页时，旧口径「滚一屏」会一次跳 2~3 页（票 #89 的真机症状）
        assertEquals(4, webtoonVolumeTarget(3, 10, canScrollForward = true, canScrollBackward = true, forward = true))
        assertEquals(1, webtoonVolumeTarget(0, 10, canScrollForward = true, canScrollBackward = false, forward = true))
    }

    @Test
    fun `音量上回退一页到上一页页首`() {
        assertEquals(2, webtoonVolumeTarget(3, 10, canScrollForward = true, canScrollBackward = true, forward = false))
        // 首页页内回退 = 回到首页页首（与左区同一份语义），再往前无目标
        assertEquals(0, webtoonVolumeTarget(0, 10, canScrollForward = true, canScrollBackward = true, forward = false))
        assertNull(webtoonVolumeTarget(0, 10, canScrollForward = true, canScrollBackward = false, forward = false))
    }

    @Test
    fun `条漫书末不消费按键`() {
        // 末页矮于视口：滚到底时顶部可见页停在倒数第二页，但已无下一页可翻
        assertNull(webtoonVolumeTarget(8, 10, canScrollForward = false, canScrollBackward = true, forward = true))
        // 末页自身高过一屏：顶部可见页已是末页
        assertNull(webtoonVolumeTarget(9, 10, canScrollForward = true, canScrollBackward = true, forward = true))
    }

    @Test
    fun `整本不满一屏时两个方向都不消费`() {
        assertNull(webtoonVolumeTarget(0, 5, canScrollForward = false, canScrollBackward = false, forward = true))
        assertNull(webtoonVolumeTarget(0, 5, canScrollForward = false, canScrollBackward = false, forward = false))
    }

    @Test
    fun `空书与越界首可见页防御`() {
        assertNull(webtoonVolumeTarget(0, 0, canScrollForward = false, canScrollBackward = false, forward = true))
        // 越界（尺寸还没量完等）：钳到末页后照旧按末页判定，不越界返回
        assertNull(webtoonVolumeTarget(7, 5, canScrollForward = true, canScrollBackward = true, forward = true))
        assertEquals(3, webtoonVolumeTarget(7, 5, canScrollForward = true, canScrollBackward = true, forward = false))
    }

    // ---------- 区域意图映射（票 07：两模式、两方向统一）----------

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

    // ---------- 单页翻页目标（票 07）----------

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
