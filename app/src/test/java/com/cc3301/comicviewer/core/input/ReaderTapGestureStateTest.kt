package com.cc3301.comicviewer.core.input

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 阅读页单击 / 双击识别器的判定（票 #129 r4/r5，纯逻辑，由 [ReaderTapGestureState] 承担）。
 *
 * 这一层存在的理由与 `core/input/` 另外三处手写手势（`MouseDragScrollGesture` / `PullRefreshGesture` /
 * `QuickScrollBarGesture`）**共有的那一点**一致：**判定不是接线**——判定进 `core/input/`、`ui/` 只做事件翻译。
 * （形状不必相同：那三处是「sealed 输入 + 单个 `handle(input)`」的事件流状态机，本类由界面侧的挂起流程
 * 顺序喂六个事件方法，六方法形态对「按下 → 抬起 → 等第二下 → 抬起」这种**序列**更直白。）r4 把这套判定留在
 * `ui/ReaderTapGesture.kt` 的挂起函数里 ⇒ 除常量与谓词外不可测；搬进状态机后，下面每一条口径都由用例直接驱动
 * （不需要组合、帧时钟、指针事件）。
 *
 * 钉住的口径：
 * 1. **两个时间量都是构造参数**：窗口（产品口径的声明在 `ui/ReaderTapGesture.kt`）与最小间隔
 *    （**平台量** `viewConfiguration.doubleTapMinTimeMillis`，由界面侧读出后传进来）—— 本类不写死任何一个，
 *    下面用例一律用**测试自带的合成档**驱动；
 * 2. **一条规则只有一个下界**：第二下按下要落在 `[最小间隔, 窗口]` 内才算「就是第二下」——
 *    两个边界值都由构造参数传入，本文件不写死任何数值：
 *    早于最小间隔 ⇒ [ReaderTapEffect.WaitForAnotherDown]（丢掉这一下、窗口不重置、继续等），
 *    晚于窗口 ⇒ 单击（事件晚到一帧：按输入时钟判，不按协程时钟判）。r4 那两个谓词对同一对入参给出相反结论
 *    （前者把「最小间隔以内」也算「窗口内」，后者把同一段判「太早」）；
 * 3. **「受理第二下」与「什么都不做」是两个效果**：前者是 [ReaderTapEffect.SecondDownAccepted]（界面据此转去
 *    等抬手），后者是 [ReaderTapEffect.None]——界面不再靠一个哨兵的双关决定手势是否结束；
 * 4. **单击只发一次、且必在第二下判定之后**：双击路径全程不出现 [ReaderTapEffect.SingleTap]
 *    （维护者口径「不允许闪」：单击立即响应 + 双击撤销那条路已否决）；
 * 5. **位置来源**：单击取**第一下抬起**处、双击取**第二下抬起**处（与上游 `detectTapGestures` 同支）。
 *
 * 时间戳与坐标一律用合成值（`firstUpMillis = 1000`），不含任何真实设备/主机信息。
 */
class ReaderTapGestureStateTest {

    private val firstUpMillis = 1_000L

    /**
     * **合成档**：最小间隔取 40ms 只为写出下面几条边界用例，**不是**生产口径——
     * 生产值由界面侧读 `viewConfiguration.doubleTapMinTimeMillis` 传进来（Android 默认 40ms，
     * OEM 可不同；本仓无单测能读平台量，因此这一档由真机/设备决定，不由本文件钉住）。
     */
    private val syntheticMinIntervalMillis = 40L

    /**
     * **合成档**：窗口取 200ms 只为写出下面几条边界用例，**不是**生产口径的第二份声明——
     * 生产窗口是 `ui/ReaderTapGesture.kt` 的 `ReaderTapGesture.DOUBLE_TAP_WINDOW_MILLIS`（由
     * `ui/ReaderTapGestureTest` 钉）；本文件与它**没有机械联系**（core 不 import ui），那边改成别的值本文件
     * 照旧全绿——「窗口由构造参数决定」这件事由下面 `secondDownTimeoutMillis` 那条断言守。
     */
    private fun state(
        windowMillis: Long = 200,
        minIntervalMillis: Long = syntheticMinIntervalMillis,
    ) = ReaderTapGestureState(
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

        assertEquals(
            "9_100 - 9_000 = 100ms：落在这一段窗口内",
            ReaderTapEffect.SecondDownAccepted,
            state.onSecondDown(9_100L),
        )
    }

    // --- 双击 ---

    @Test
    fun `窗口内第二下抬起即双击 位置取第二下抬起处`() {
        val state = state().firstTapDone()

        assertEquals(ReaderTapEffect.SecondDownAccepted, state.onSecondDown(firstUpMillis + 100))
        assertEquals(ReaderTapEffect.DoubleTap(x = 300f, y = 400f), state.onSecondUp(x = 300f, y = 400f))
    }

    @Test
    fun `双击全程不发单击 不允许闪`() {
        val state = state().firstTapDone()

        val effects = listOf(
            state.onSecondDown(firstUpMillis + 100),
            state.onSecondUp(x = 300f, y = 400f),
        )

        assertEquals(
            listOf(ReaderTapEffect.SecondDownAccepted, ReaderTapEffect.DoubleTap(x = 300f, y = 400f)),
            effects,
        )
        assertTrue(
            "双击路径出现 SingleTap 就意味着菜单会先亮后撤（维护者明确否决的那条路）",
            effects.none { it is ReaderTapEffect.SingleTap },
        )
    }

    // --- 「受理第二下」与「什么都不做」是两支不同的效果 ---

    @Test
    fun `受理第二下与什么都不做是两个不同的效果`() {
        val accepted = state().firstTapDone()
        assertEquals(
            "受理：界面据此转去等第二下抬起",
            ReaderTapEffect.SecondDownAccepted,
            accepted.onSecondDown(firstUpMillis + 100),
        )

        val idle = state()
        assertEquals("按下之前喂「第二下按下」：什么都不做", ReaderTapEffect.None, idle.onSecondDown(firstUpMillis + 100))
        assertEquals("按下之前喂「第二下抬起」：什么都不做", ReaderTapEffect.None, idle.onSecondUp(x = 1f, y = 2f))
        assertEquals("按下之前喂「窗口过期」：什么都不做", ReaderTapEffect.None, idle.onWindowExpired())
        assertEquals("按下之前喂「第一下抬起」：什么都不做", ReaderTapEffect.None, idle.onFirstUp(x = 1f, y = 2f, uptimeMillis = firstUpMillis))
        assertEquals("按下之前喂「接管」：什么都不做", ReaderTapEffect.None, idle.onCancel())
    }

    // --- 第二下的两个边界（一条规则、一个下界）---

    @Test
    fun `窗口边界 第 200ms 是第二下 第 201ms 越界判单击`() {
        val atWindow = state().firstTapDone()
        assertEquals(ReaderTapEffect.SecondDownAccepted, atWindow.onSecondDown(firstUpMillis + 200))

        val late = state().firstTapDone()
        assertEquals(
            "晚到一帧的按下不算第二下：按输入时钟判，按协程时钟会漏掉这一支",
            ReaderTapEffect.SingleTap(x = 100f, y = 200f),
            late.onSecondDown(firstUpMillis + 201),
        )
    }

    @Test
    fun `最小间隔边界 合成档 40ms 时 第 40ms 是第二下 第 39ms 太早`() {
        val atMin = state().firstTapDone()
        assertEquals(ReaderTapEffect.SecondDownAccepted, atMin.onSecondDown(firstUpMillis + syntheticMinIntervalMillis))

        val tooEarly = state().firstTapDone()
        assertEquals(
            "与第一下抬起几乎同一时刻的按下（同一帧里的多指 / 合成事件）不算第二下",
            ReaderTapEffect.WaitForAnotherDown,
            tooEarly.onSecondDown(firstUpMillis + syntheticMinIntervalMillis - 1),
        )
    }

    @Test
    fun `太早的第二下丢掉之后 窗口不重置 仍能等到正常第二下或判单击`() {
        val thenDouble = state().firstTapDone()
        assertEquals(ReaderTapEffect.WaitForAnotherDown, thenDouble.onSecondDown(firstUpMillis + 10))
        assertEquals(
            "丢掉的是那一下，不是整个窗口",
            ReaderTapEffect.SecondDownAccepted,
            thenDouble.onSecondDown(firstUpMillis + 100),
        )
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

    // --- 两个时间量都是构造参数（窗口的单一出处）---

    @Test
    fun `窗口与最小间隔都从构造参数来 换一组值就换一条规则`() {
        val custom = state(windowMillis = 150, minIntervalMillis = 30)

        assertEquals("界面拿这个值给「等第二下」上闸 ⇒ 窗口只有这一处声明", 150L, custom.secondDownTimeoutMillis)
        assertEquals(
            "下限也是参数：30ms 算第二下",
            ReaderTapEffect.SecondDownAccepted,
            custom.firstTapDone().onSecondDown(firstUpMillis + 30),
        )
        assertEquals(
            "窗口也是参数：151ms 越界判单击",
            ReaderTapEffect.SingleTap(x = 100f, y = 200f),
            state(windowMillis = 150, minIntervalMillis = 30).firstTapDone().onSecondDown(firstUpMillis + 151),
        )
    }
}
