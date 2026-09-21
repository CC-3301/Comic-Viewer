package com.cc3301.comicviewer.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 快速定位滑条的**动画行为**（票 #60 批次 6 定版 **D7-A** 的 AC15/AC16）：
 * 滚动中与按住滑条期间**一直可见且不计时**（不重排计时）；静止 **1.2s** 后**只淡出一次**；出现淡入 **120ms**、淡出 **200ms**。
 *
 * 真机上的「明灭/抽搐」根因是**两条时间线**——每次滚动事件都重新开始淡入，同时上一次的 1.2s 计时仍在跑，
 * 于是滚动中也会熄一次。修法收成两条纯函数（`ui/QuickScrollBar.kt`）：[quickScrollBarTimerArmed]（何时计时）
 * 与 [quickScrollBarVisible]（此刻可不可见），界面侧就调这两个——所以本用例断言的是**行为**（按住/滚动期间
 * 可见、静止走完才淡出、可见期内再次活动不产生跃迁），不是把常量抄一遍。
 *
 * 只保留两条**常量相等**的断言（时长本身无法从行为反推：仓库没有 compose-ui-test 假时钟，`animateFloatAsState`
 * 的单测里起不了帧，同 [QuickScrollBarSizeTest] 的限制），时长常量为此声明成 `internal` 而非文件私有——
 * 与仓内既有的 `QUICK_SCROLL_BAR_MIN_LENGTH` / `EntryProgressBar` 同款先例。
 * r4 新增两条：single-driver 的 alpha 目标（[quickScrollBarAlphaTarget]）在「短时间内多次可见性翻转」下
 * 只产生一次淡入 + 一次淡出，且已可见时再来活动**不重放**淡入（目标不变 ⇒ `animateTo` 直接返回）。
 *
 * 不覆盖的部分（写明，避免读成全覆盖）：淡入/淡出的**插值过程**与「重播淡入」在真机上的观感只能真机验收
 * （「不重播」在代码上由 `animateFloatAsState` 的目标值不变即不重排动画把守，本用例只钉「可见期内不产生
 * 隐藏→出现的跃迁」这个前提）。
 */
class QuickScrollBarTimingTest {

    /** AC16：出现动画 120ms 淡入 */
    @Test
    fun `出现淡入是 120ms`() {
        assertEquals(120, QUICK_SCROLL_BAR_FADE_IN_MS)
    }

    /** AC15：静止 1.2s 后淡出，且淡出是 200ms（只一次，见下面 `静止走完即淡出`） */
    @Test
    fun `静止 1_2 秒后淡出 200ms`() {
        assertEquals(1200L, QUICK_SCROLL_BAR_HIDE_DELAY_MS)
        assertEquals(200, QUICK_SCROLL_BAR_FADE_OUT_MS)
    }

    /** AC16：按住滑条期间保持可见，且**不停表**（松手后按「静止 1.2s」重新计时） */
    @Test
    fun `按住滑条期间保持可见且不计时`() {
        assertTrue(quickScrollBarVisible(active = false, scrolling = false, held = true))
        assertFalse(quickScrollBarTimerArmed(scrolling = false, held = true))
    }

    /** AC15：滚动中保持可见、且不计时（旧实现在滚动中也跑计时，1.2s 熄一次就是「明灭」的来源） */
    @Test
    fun `滚动中保持可见且不计时`() {
        assertTrue(quickScrollBarVisible(active = false, scrolling = true, held = false))
        assertFalse(quickScrollBarTimerArmed(scrolling = true, held = false))
    }

    /** AC15：倒计时只在静止时武装；走完（界面侧把 active 置 false）即隐藏，没有新动作就不再亮起 ⇒ 只淡出一次 */
    @Test
    fun `静止走完即淡出、不再反复`() {
        assertTrue(quickScrollBarTimerArmed(scrolling = false, held = false))
        // 倒计时还在跑：可见
        assertTrue(quickScrollBarVisible(active = true, scrolling = false, held = false))
        // 倒计时走完：隐藏，且之后一直隐藏（下一次出现要等一次新动作 ⇒ 一次淡出一趟）
        assertFalse(quickScrollBarVisible(active = false, scrolling = false, held = false))
    }

    /**
     * AC15：可见期内再次滚动/按住**不改变可见性** ⇒ 不产生「隐藏→出现」的跃迁、因此不重播淡入
     * （`animateFloatAsState` 的目标值没变就不重排动画）。
     */
    @Test
    fun `可见期内再次活动不改变可见性`() {
        val visible = quickScrollBarVisible(active = true, scrolling = false, held = false)
        assertEquals(visible, quickScrollBarVisible(active = true, scrolling = true, held = false))
        assertEquals(visible, quickScrollBarVisible(active = true, scrolling = false, held = true))
    }

    /**
     * r4（真机「抽搐」）：单一 alpha 驱动的目标只在两个不动点上稳定——
     * 已可见（1f）时再来活动目标**不变**（⇒ `Animatable.animateTo` 拿到相同目标直接返回，不重放淡入）、
     * 已熄灭（0f）时再来一次静止结算也**不变**（⇒ 不反复淡出）；只有真正跨越时目标才翻。
     *
     * 判别力：把 `quickScrollBarAlphaTarget` 写成「活动恒返 1f / 结算恒返 0f」（丢了不动点）即变红。
     */
    @Test
    fun `已可见时活动不重放淡入、已熄灭后不反复淡出`() {
        assertEquals(1f, quickScrollBarAlphaTarget(1f, QuickScrollBarFadeInput.Activity))
        assertEquals(0f, quickScrollBarAlphaTarget(0f, QuickScrollBarFadeInput.IdleSettled))
        assertEquals(1f, quickScrollBarAlphaTarget(0f, QuickScrollBarFadeInput.Activity))
        assertEquals(0f, quickScrollBarAlphaTarget(1f, QuickScrollBarFadeInput.IdleSettled))
    }

    /**
     * r4：短窗口内多次可见性翻转（滚动中反复活动 → 静止 → 再落一次结算）**只应发生一次动画序列**：
     * 把输入序列折过目标，目标只变两次（0f → 1f 一次淡入、1f → 0f 一次淡出），
     * 不会出现「淡出一半又淡入」这类中间态（目标再没被抬起来）。
     *
     * 判别力：目标函数把每个 Activity 都当成新的一趟（或结算能被重复消费）时，变化次数会 > 2。
     */
    @Test
    fun `短窗口内多次活动只发生一次淡入与一次淡出`() {
        var target = 0f
        val changes = mutableListOf<Float>()
        listOf(
            QuickScrollBarFadeInput.Activity,
            QuickScrollBarFadeInput.Activity,
            QuickScrollBarFadeInput.Activity,
            QuickScrollBarFadeInput.IdleSettled,
            QuickScrollBarFadeInput.IdleSettled,
        ).forEach { input ->
            val next = quickScrollBarAlphaTarget(target, input)
            if (next != target) changes += next
            target = next
        }
        assertEquals(listOf(1f, 0f), changes)
    }
}
