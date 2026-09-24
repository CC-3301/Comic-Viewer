package com.cc3301.comicviewer.core.input

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 阅读页单击 / 双击识别器的判定（票 #129 r4/r5，纯逻辑，由 [ReaderTapGestureState] 承担）。
 *
 * 这一层存在的理由与仓库既有两处手写手势同形（`MouseDragScrollGesture` / `QuickScrollBarGesture`）：
 * **判定不是接线**。r4 把这套判定留在 `ui/ReaderTapGesture.kt` 的挂起函数里 ⇒ 除常量与谓词外不可测；
 * 搬进状态机后，下面每一条口径都由用例直接驱动（不需要组合、帧时钟、指针事件）。
 *
 * 钉住的口径（对应 r5 批次的 P2-1 / P2-2）：
 * 1. **两个时间量都是构造参数**：窗口（本票产品口径 200ms）与最小间隔（平台量 `doubleTapMinTimeMillis`）
 *    —— 换一组值就换一条规则，本类不写死任何一个（P2-1：平台量由界面侧读 `viewConfiguration` 传进来）；
 * 2. **一条规则只有一个下界**：第二下按下要落在 `[最小间隔, 窗口]` 内才算「就是第二下」；
 *    早于最小间隔 ⇒ [ReaderTapEffect.WaitForAnotherDown]（丢掉这一下、窗口不重置、继续等），
 *    晚于窗口 ⇒ 单击（事件晚到一帧：按输入时钟判，不按协程时钟判）。r4 那两个谓词对同一对入参给出相反结论
 *    （`isSecondDownWithinWindow` 把 0–39ms 算「窗口内」，`isSecondDownTooEarly` 把 <40ms 判「太早」）⇒ P2-2；
 * 3. **单击只发一次、且必在第二下判定之后**：双击路径全程不出现 [ReaderTapEffect.SingleTap]
 *    （维护者口径「不允许闪」：单击立即响应 + 双击撤销那条路已否决）；
 * 4. **位置来源**：单击取**第一下抬起**处、双击取**第二下抬起**处（与上游 `detectTapGestures` 同支）。
 *
 * 时间戳一律用合成值（`firstUpMillis = 1000`），不含任何真实设备/主机信息。
 */
class ReaderTapGestureStateTest {

    private val firstUpMillis = 1_000L

    /** 平台默认那一档：窗口 200ms（本票产品口径）、最小间隔 40ms（`doubleTapMinTimeMillis`） */
    private fun state(windowMillis: Long = 200, minIntervalMillis: Long = 40) =
        ReaderTapGestureState(
            doubleTapWindowMillis = windowMillis,
            doubleTapMinIntervalMillis = minIntervalMillis,
        )

    /** 走完「第一下按下 → 抬起」，停在「窗口内等第二下」 */
    private fun ReaderTapGestureState.firstTapDone(): ReaderTapGestureState = apply {
        onDown()
        onFirstUp(x = 100f, y = 200f, uptimeMillis = firstUpMillis)
    }

    // --- 单击 ---

    @Test
    fun `窗口内没有第二下 就在窗口过期时发单击 位置取第一下抬起处`() {
        val state = state().firstTapDone()

        assertEquals(ReaderTapEffect.SingleTap(x = 100f, y = 200f), state.onWindowExpired())
    }

    @Test
    fun `窗口从这一段的第一下抬起算起 不背上一段的状态`() {
        val state = state()
        state.onDown()
        state.onFirstUp(x = 0f, y = 0f, uptimeMillis = firstUpMillis)
        state.onWindowExpired()
        state.onDown()
        state.onFirstUp(x = 0f, y = 0f, uptimeMillis = 9_000L)

        assertEquals("9_100 - 9_000 = 100ms：落在这一段窗口内", ReaderTapEffect.None, state.onSecondDown(9_100L))
    }

    // --- 双击 ---

    @Test
    fun `窗口内第二下抬起即双击 位置取第二下抬起处`() {
        val state = state().firstTapDone()

        assertEquals(ReaderTapEffect.None, state.onSecondDown(firstUpMillis + 100))
        assertEquals(ReaderTapEffect.DoubleTap(x = 300f, y = 400f), state.onSecondUp(x = 300f, y = 400f))
    }

    @Test
    fun `双击全程不发单击 不允许闪`() {
        val state = state().firstTapDone()

        val effects = listOf(
            state.onSecondDown(firstUpMillis + 100),
            state.onSecondUp(x = 300f, y = 400f),
        )

        assertEquals(listOf(ReaderTapEffect.None, ReaderTapEffect.DoubleTap(x = 300f, y = 400f)), effects)
        assertTrue(
            "双击路径出现 SingleTap 就意味着菜单会先亮后撤（维护者明确否决的那条路）",
            effects.none { it is ReaderTapEffect.SingleTap },
        )
    }

    // --- 第二下的两个边界（P2-2：一条规则、一个下界）---

    @Test
    fun `窗口边界 第 200ms 是第二下 第 201ms 越界判单击`() {
        val atWindow = state().firstTapDone()
        assertEquals(ReaderTapEffect.None, atWindow.onSecondDown(firstUpMillis + 200))

        val late = state().firstTapDone()
        assertEquals(
            "晚到一帧的按下不算第二下：按输入时钟判，按协程时钟会漏掉这一支",
            ReaderTapEffect.SingleTap(x = 100f, y = 200f),
            late.onSecondDown(firstUpMillis + 201),
        )
    }

    @Test
    fun `最小间隔边界 第 40ms 是第二下 第 39ms 太早`() {
        val atMin = state().firstTapDone()
        assertEquals(ReaderTapEffect.None, atMin.onSecondDown(firstUpMillis + 40))

        val tooEarly = state().firstTapDone()
        assertEquals(
            "与第一下抬起几乎同一时刻的按下（同一帧里的多指 / 合成事件）不算第二下",
            ReaderTapEffect.WaitForAnotherDown,
            tooEarly.onSecondDown(firstUpMillis + 39),
        )
    }

    @Test
    fun `太早的第二下丢掉之后 窗口不重置 仍能等到正常第二下或判单击`() {
        val thenDouble = state().firstTapDone()
        assertEquals(ReaderTapEffect.WaitForAnotherDown, thenDouble.onSecondDown(firstUpMillis + 10))
        assertEquals("丢掉的是那一下，不是整个窗口", ReaderTapEffect.None, thenDouble.onSecondDown(firstUpMillis + 100))
        assertEquals(ReaderTapEffect.DoubleTap(x = 5f, y = 6f), thenDouble.onSecondUp(x = 5f, y = 6f))

        val thenSingle = state().firstTapDone()
        assertEquals(ReaderTapEffect.WaitForAnotherDown, thenSingle.onSecondDown(firstUpMillis + 10))
        assertEquals(ReaderTapEffect.SingleTap(x = 100f, y = 200f), thenSingle.onWindowExpired())
    }

    // --- 被别的手势接管（拖动 / 双指缩放消费了事件）---

    @Test
    fun `第一下抬手前被接管 什么也不发`() {
        val state = state()
        state.onDown()

        assertEquals(ReaderTapEffect.None, state.onCancel())
        assertEquals("接管之后窗口过期也不补发单击", ReaderTapEffect.None, state.onWindowExpired())
    }

    @Test
    fun `第二下被接管 按上游同一支仍算单击`() {
        val state = state().firstTapDone()
        state.onSecondDown(firstUpMillis + 100)

        assertEquals(ReaderTapEffect.SingleTap(x = 100f, y = 200f), state.onCancel())
    }

    // --- 一次点按只发一次 ---

    @Test
    fun `重复输入不重复发动作`() {
        val single = state().firstTapDone()
        assertEquals(ReaderTapEffect.SingleTap(x = 100f, y = 200f), single.onWindowExpired())
        assertEquals("窗口过期只算一次（超时那一支与取消那一支可能前后脚到）", ReaderTapEffect.None, single.onWindowExpired())

        val double = state().firstTapDone()
        double.onSecondDown(firstUpMillis + 100)
        assertEquals(ReaderTapEffect.DoubleTap(x = 1f, y = 2f), double.onSecondUp(x = 1f, y = 2f))
        assertEquals("抬起只算一次", ReaderTapEffect.None, double.onSecondUp(x = 1f, y = 2f))
    }

    // --- 两个时间量都是构造参数（P2-1 / P2-2 的单一出处）---

    @Test
    fun `窗口与最小间隔都从构造参数来 不是写死的 200ms 与 40ms`() {
        val custom = state(windowMillis = 150, minIntervalMillis = 30)

        assertEquals("界面拿这个值给「等第二下」上闸 ⇒ 窗口只有这一处声明", 150L, custom.secondDownTimeoutMillis)
        assertEquals(
            "下限也是参数：30ms 算第二下",
            ReaderTapEffect.None,
            custom.firstTapDone().onSecondDown(firstUpMillis + 30),
        )
        assertEquals(
            "窗口也是参数：151ms 越界判单击",
            ReaderTapEffect.SingleTap(x = 100f, y = 200f),
            state(windowMillis = 150, minIntervalMillis = 30).firstTapDone().onSecondDown(firstUpMillis + 151),
        )
    }
}
