package com.cc3301.comicviewer.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 快速定位滑条的**动画行为**：滚动中与按住滑条期间**一直可见且不计时**（不重排计时）；
 * 静止 **1.2s** 后**只淡出一次**；出现淡入 **120ms**、淡出 **200ms**。
 *
 * 这些行为由 [QuickScrollBarVisibility] 一台状态机持有（状态只此一处：alpha 目标 + 动作计数），
 * 本用例按界面侧的驱动方式**同源、同序**地对它下输入——所以断言的是**行为**（按住/滚动期间可见、
 * 静止走完才淡出、可见期内再次活动不产生跃迁），不是把常量抄一遍。
 *
 * 只保留两条**常量相等**的断言（时长本身无法从行为反推：仓库没有 compose-ui-test 假时钟，`Animatable` 的
 * 单测里起不了帧，同 [QuickScrollBarSizeTest] 的限制），时长常量为此声明成 `internal` 而非文件私有——
 * 与仓内既有的 `QUICK_SCROLL_BAR_MIN_LENGTH` / `EntryProgressBar` 同款先例。
 * 其余各条分三组：目标的不动点（已可见再来活动不重放淡入、已熄灭再结算不反复淡出）；
 * 步进判据（**alpha 为 0 时来活动必须是 [QuickScrollBarVisibility.Step.Show]**——只判可见不驱动
 * `Animatable` 的写法下 alpha 恒 0、渲染出隐形吞点击带；**淡出中（alpha 0.4）来活动必须回到 1f**
 * ——不停在中间值）；以及一条按驱动**同源同序**跑的序列用例（alpha=0 → 活动 → 静止 → 再活动：只熄灭一次）。
 * 另有一条 [quickScrollBarStripAttached]：「右缘 32dp 的抓取带只在滑条可见期间存在（可见期间由
 * 它接管右缘手势）／完全隐藏就不挂（没有 `pointerInput` 就不吞任何事件）」。
 *
 * 不覆盖的部分（写明，避免读成全覆盖）：淡入/淡出的**插值过程**只能设备验收（本用例钉的是每一拍的目标与
 * 判定，帧间插值不在可测范围）；「不重放淡入」在代码上由两处把守——[QuickScrollBarVisibility.onActivity]
 * 的不动点，与 `Animatable.animateTo` 拿到相同目标时直接返回。
 */
class QuickScrollBarTimingTest {

    /** 每条用例一台新机器：状态互不串（目标 0f 起步、计数 0） */
    private fun machine(): QuickScrollBarVisibility = QuickScrollBarVisibility()

    /** 出现动画 120ms 淡入 */
    @Test
    fun `出现淡入是 120ms`() {
        assertEquals(120, QUICK_SCROLL_BAR_FADE_IN_MS)
    }

    /** 静止 1.2s 后淡出，且淡出是 200ms（只一次，见下面 `静止走完即淡出`） */
    @Test
    fun `静止 1_2 秒后淡出 200ms`() {
        assertEquals(1200L, QUICK_SCROLL_BAR_HIDE_DELAY_MS)
        assertEquals(200, QUICK_SCROLL_BAR_FADE_OUT_MS)
    }

    /** 按住滑条期间保持可见，且**不停表**（松手后按「静止 1.2s」重新计时） */
    @Test
    fun `按住滑条期间保持可见且不计时`() {
        val m = machine()
        assertTrue(m.visible(scrolling = false, held = true))
        assertFalse(m.timerArmed(scrolling = false, held = true))
    }

    /** 滚动中保持可见、且不计时（滚动中也跑计时的话，1.2s 会熄一次 = 明灭） */
    @Test
    fun `滚动中保持可见且不计时`() {
        val m = machine()
        assertTrue(m.visible(scrolling = true, held = false))
        assertFalse(m.timerArmed(scrolling = true, held = false))
    }

    /** 倒计时只在静止时武装；走完（结算把目标降回 0f）即隐藏，没有新动作就不再亮起 ⇒ 只淡出一次 */
    @Test
    fun `静止走完即淡出、不再反复`() {
        val m = machine()
        assertTrue(m.timerArmed(scrolling = false, held = false))
        // 一次活动把目标抬起来，倒计时在跑：可见
        m.onActivity()
        assertTrue(m.visible(scrolling = false, held = false))
        // 静止计时到点：隐藏
        m.onIdleSettled()
        assertFalse(m.visible(scrolling = false, held = false))
        // 再来一次结算目标也不变（不动点）：不会反复淡出
        m.onIdleSettled()
        assertFalse(m.visible(scrolling = false, held = false))
    }

    /**
     * 可见期内再次滚动/按住**不改变可见性** ⇒ 不产生「隐藏→出现」的跃迁、因此不重播淡入
     * （可见性不变 ⇒ 目标仍是 1f ⇒ `Animatable.animateTo` 空转）。
     */
    @Test
    fun `可见期内再次活动不改变可见性`() {
        val m = machine()
        m.onActivity()
        val visible = m.visible(scrolling = false, held = false)
        assertEquals(visible, m.visible(scrolling = true, held = false))
        assertEquals(visible, m.visible(scrolling = false, held = true))
    }

    /**
     * 目标只在两个不动点上稳定——
     * 已可见（1f）时再来活动目标**不变**（⇒ `Animatable.animateTo` 拿到相同目标直接返回，不重放淡入）、
     * 已熄灭（0f）时再来一次静止结算也**不变**（⇒ 不反复淡出）；只有真正跨越时目标才翻。
     *
     * 判别力：把活动写成「恒抬到 1f / 结算恒降到 0f」（丢了不动点）即变红。
     */
    @Test
    fun `已可见时活动不重放淡入、已熄灭后不反复淡出`() {
        val m = machine()
        assertEquals(0f, m.alphaTarget)
        m.onActivity()
        assertEquals(1f, m.alphaTarget)
        m.onActivity()
        assertEquals("已可见（1f）时再来活动，目标不变", 1f, m.alphaTarget)
        m.onIdleSettled()
        assertEquals(0f, m.alphaTarget)
        m.onIdleSettled()
        assertEquals("已熄灭（0f）时再来一次结算，目标不变", 0f, m.alphaTarget)
    }

    /**
     * 短窗口内多次可见性翻转（滚动中反复活动 → 静止 → 再落一次结算）**只应发生一次动画序列**：
     * 把输入序列折过机器，目标只变两次（0f → 1f 一次淡入、1f → 0f 一次淡出），
     * 不会出现「淡出一半又淡入」这类中间态（目标再没被抬起来）。
     *
     * 判别力：目标把每个活动都当成新的一趟（或结算能被重复消费）时，变化次数会 > 2。
     */
    @Test
    fun `短窗口内多次活动只发生一次淡入与一次淡出`() {
        val m = machine()
        var previous = m.alphaTarget
        val changes = mutableListOf<Float>()

        fun record() {
            val now = m.alphaTarget
            if (now != previous) changes += now
            previous = now
        }

        m.onActivity()
        record()
        m.onActivity()
        record()
        m.onActivity()
        record()
        m.onIdleSettled()
        record()
        m.onIdleSettled()
        record()
        assertEquals(listOf(1f, 0f), changes)
    }

    /**
     * alpha 为 0 时来一次活动（带内滚轮 / 鼠标左键拖动这类不置 `isScrollInProgress` 的活动）
     * 必须判为 [QuickScrollBarVisibility.Step.Show] ⇒ 界面侧把目标交给 `Animatable` 淡入；
     * 只判可见、不驱动 `Animatable` 的写法下 alpha 恒 0（`showing` 为真却渲染出隐形吞点击带）。
     *
     * 判别力：把「该可见」归到 Idle / WaitThenHide（旧的分支顺序）即变红。
     */
    @Test
    fun `alpha 为 0 时来活动必须淡入`() {
        val m = machine()
        m.onActivity()
        assertEquals(
            QuickScrollBarVisibility.Step.Show,
            m.step(scrolling = false, held = false, alpha = 0f),
        )
        assertEquals(
            1f,
            m.fadeTarget(step = QuickScrollBarVisibility.Step.Show, current = 0f),
        )
    }

    /**
     * 淡出中（alpha 0.4）来活动必须回到 **1f**，不停在 0.4。
     *
     * 判别力：把这一步判成 WaitThenHide / 目标返回当前值时变红（那就是「淡出一半卡住」）。
     */
    @Test
    fun `淡出中再次活动必须回到 1f 不停在中间值`() {
        val m = machine()
        m.onActivity()
        val step = m.step(scrolling = false, held = false, alpha = 0.4f)
        assertEquals(QuickScrollBarVisibility.Step.Show, step)
        assertEquals(1f, m.fadeTarget(step = step, current = 0.4f))
    }

    /**
     * 覆盖接线的用例：不把序列折过单条操作，而是按 `QuickScrollBar` 的驱动
     * **同源、同序**跑一遍——报活动（[QuickScrollBarVisibility.onActivity]）→ 问这一步
     * （[QuickScrollBarVisibility.step]）→ `Animatable` 的目标（[QuickScrollBarVisibility.fadeTarget]）——
     * 序列是「alpha=0 → 活动 → 静止结算 → 再活动」。
     *
     * 断言：两次活动都得到 1f（第一次恒 0 ⇒ 整段不现身），整段只熄灭一次，
     * 且没有停在半透明（要没有 Show 把 alpha 抬到 1f，要么完整降到 0f）。
     *
     * 边界（写明）：这是对组合层同一套操作的序列重放，不是真的起动 `Animatable`（仓内无 compose-ui-test
     * 假时钟）；帧间插值与设备观感仍属设备验收。
     */
    @Test
    fun `alpha 为 0 时的活动必须淡入且整段只熄灭一次`() {
        val m = machine()
        var alpha = 0f
        val shows = mutableListOf<Float>()
        val hides = mutableListOf<Float>()

        fun step(): QuickScrollBarVisibility.Step = m.step(
            scrolling = false,
            held = false,
            alpha = alpha,
        )

        // 一次活动（效果接线 / snapshotFlow 那两支）：报给机器，再按判定驱动 Animatable 的目标
        fun activity() {
            m.onActivity()
            val s = step()
            alpha = m.fadeTarget(step = s, current = alpha)
            if (s == QuickScrollBarVisibility.Step.Show) shows += alpha
        }

        // 静止计时到点（效果里 delay 之后的结算）
        fun settle() {
            val s = step()
            if (s == QuickScrollBarVisibility.Step.Idle) return
            m.onIdleSettled()
            alpha = m.fadeTarget(step = QuickScrollBarVisibility.Step.WaitThenHide, current = alpha)
            hides += alpha
        }

        activity()
        settle()
        activity()

        assertEquals("两次活动都应淡入到 1f", listOf(1f, 1f), shows)
        assertEquals("整段只应熄灭一次", listOf(0f), hides)
        assertEquals("结尾仍是亮着", 1f, alpha)
    }

    /**
     * **按住期间从隐藏现身**：按下不算「活动」（不计数、不抬目标），但按住本身让滑条该可见——
     * [QuickScrollBarVisibility.step] 回答 [QuickScrollBarVisibility.Step.Show] 的**同一拍**把目标抬到 1f。
     * 只判可见、不抬目标的写法下，松手那一刻目标仍是 0f ⇒ 没有静止窗、滑条立即淡出（也留不下「变橙」的按住态）。
     *
     * 判别力：step 回答 Show 却不抬目标时，本条后半（松手后仍可见）变红。
     */
    @Test
    fun `按住期间从隐藏现身会把目标抬到 1f 松手后仍有静止窗`() {
        val m = machine()
        assertEquals(0f, m.alphaTarget)
        assertEquals(
            QuickScrollBarVisibility.Step.Show,
            m.step(scrolling = false, held = true, alpha = 0f),
        )
        assertEquals(1f, m.alphaTarget)
        // 松手（held=false）：目标还在 ⇒ 走满静止窗才淡出，而不是立即熄
        assertTrue(m.visible(scrolling = false, held = false))
    }

    /**
     * **活动重启静止计时**：活动计数是倒计时的重启键（每次活动 +1）；静止结算只动目标、**不**动计数。
     * 界面侧把这两个读数读进效果协程的 key——计数变则重启本趟计时，结算则不重启。
     */
    @Test
    fun `活动会重启静止计时 结算不重启`() {
        val m = machine()
        m.onActivity()
        val count = m.activityCount
        assertEquals("结算不改计数", count, run { m.onIdleSettled(); m.activityCount })
        m.onActivity()
        assertEquals("活动使计数 +1（⇒ 倒计时重启）", count + 1, m.activityCount)
    }

    /**
     * **可见期间抓取带才存在 / 完全隐藏就不挂**——第 1 条是「可见期间生效」（带子宽 32dp 的那条
     * 断言在 [QuickScrollBarSizeTest]），第 2 条是「隐藏期间不吞任何事件」。
     *
     * 界面侧就调这一个纯函数决定挂不挂那个 32dp 带盒子：没有盒子就没有 `pointerInput`，也就不吞事件，
     * 因此「隐藏期间右缘照常可点条目、可拖动列表」是结构性的。同时钉住**有几何是前提**：首帧没量到尺寸时
     * 不挂（免得留下一条既没滑条又吞点击的带子）。
     *
     * 判别力：退回只看「该可见」不看 alpha（旧写法）时第 3 条变红；把 alpha > 0 当成挂载条件写成恒真时第 4
     * 条（无几何）变红。
     */
    @Test
    fun `滑条可见期间才有抓取带 隐藏期间没有`() {
        assertTrue(quickScrollBarStripAttached(hasGeometry = true, alpha = 1f))
        assertTrue(quickScrollBarStripAttached(hasGeometry = true, alpha = 0.4f))
        assertFalse("完全隐藏（alpha 到 0）时不该组合抓取带", quickScrollBarStripAttached(hasGeometry = true, alpha = 0f))
        assertFalse("没有几何时不挂", quickScrollBarStripAttached(hasGeometry = false, alpha = 1f))
    }
}
