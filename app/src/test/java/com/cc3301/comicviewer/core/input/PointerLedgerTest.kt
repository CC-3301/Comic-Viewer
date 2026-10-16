package com.cc3301.comicviewer.core.input

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 指针簿记的判定（纯逻辑，由 [PointerLedger] 承担）。
 *
 * 这一层存在的理由与 `core/input/` 其余手写手势一致：**判定不是接线**——判定进 `core/input/`、
 * `ui/` 只做事件翻译。下面每一条口径都由用例直接驱动（不需要组合、帧时钟、指针事件）。
 *
 * 钉住的口径：
 * 1. **新按下 = 本帧按下且上一帧没按下**（[PointerFrame.freshDownIds]）：一直「按着」的卡住指针不是新落下；
 * 2. **卡住指针不带死**：它不进本段自己的指针（[PointerLedger.start] / [PointerLedger.advance]），
 *    因此本段该结束就结束——不这么收的话，一个卡住的指针会让双指缩放与平移从此全部失效；
 * 3. **本段自己的指针 = 起手那一帧新落下的 + 本段期间新落下的**：本段之外一直按着的指针不算；
 * 4. **同帧多指**（[PointerFrame.multiDown]）：同一帧一起落下多根 = 双指同落（双指缩放，不是点按），
 *    点按的两处入口据此在受理第二下之前拦掉；
 * 5. **本段之外新落下的指针**（[PointerLedger.foreignNewDown]）：本帧新落下、且起手快照（起手那一刻按着的
 *    指针）里没有它的 id —— 按下那一刻就已经按着的指针不算接管。
 *
 * 数值一律用合成值（指针 id 1/2/3），不含任何真实设备/主机信息。
 */
class PointerLedgerTest {

    /** 一帧里的一根指针：`id`、本帧是否按下、上一帧是否按下（省略 = 与 [pressed] 相同，即「一直按着」） */
    private fun pointer(id: Long, pressed: Boolean, previousPressed: Boolean = pressed) =
        PointerState(id = id, pressed = pressed, previousPressed = previousPressed)

    private fun frame(vararg pointers: PointerState) = PointerFrame(pointers.toList())

    /** 本帧新落下（上一帧没按下） */
    private fun freshDown(id: Long) = pointer(id = id, pressed = true, previousPressed = false)

    // --- 新按下 / 卡住指针（口径 1、2）---

    @Test
    fun `新按下只看本帧按下且上一帧没按下`() {
        val got = frame(
            pointer(id = 1, pressed = true, previousPressed = true), // 卡住：一直按着
            freshDown(id = 2),
            pointer(id = 3, pressed = false, previousPressed = true), // 本帧抬起
        )

        assertEquals("卡住的与抬起都不是新落下", listOf(2L), got.freshDownIds)
    }

    @Test
    fun `卡住的指针不进本段 也不拖住本段结束`() {
        val ledger = PointerLedger()
        // 起手：2 是本帧新落下的，1 是系统没认到松手留下的卡住指针
        ledger.start(frame(pointer(id = 1, pressed = true), freshDown(id = 2)))

        assertTrue(
            "只剩卡住的指针按着 ⇒ 本段结束；把它算进本段会让本段永远结束不了",
            ledger.advance(
                frame(pointer(id = 1, pressed = true), pointer(id = 2, pressed = false, previousPressed = true)),
            ).isEmpty(),
        )
    }

    // --- 本段自己的指针（口径 3）---

    @Test
    fun `本段自己的指针是起手与期间新落下的那些`() {
        val ledger = PointerLedger()
        ledger.start(frame(freshDown(id = 1)))

        assertEquals(
            "第二根在本段期间新落下 ⇒ 收进本段（双指缩放）",
            listOf(1L, 2L),
            ledger.advance(frame(pointer(id = 1, pressed = true), freshDown(id = 2))),
        )
    }

    @Test
    fun `本段之外一直按着的指针不算本段自己的`() {
        val ledger = PointerLedger()
        ledger.start(frame(freshDown(id = 1)))

        assertEquals(
            "3 起手前就按着、本段期间一直按着 ⇒ 不进本段",
            listOf(1L),
            ledger.advance(frame(pointer(id = 1, pressed = true), pointer(id = 3, pressed = true))),
        )
    }

    // --- 同帧多指（口径 4）---

    @Test
    fun `同一帧一起落下多指就是多指同落`() {
        assertTrue(
            "双指同落 = 双指缩放，不是点按",
            frame(freshDown(id = 1), freshDown(id = 2)).multiDown,
        )
        assertFalse(
            "一根新落下 + 一根卡住的：不是多指同落（卡住的没有「落下」这一说）",
            frame(pointer(id = 1, pressed = true), freshDown(id = 2)).multiDown,
        )
        assertFalse("单指", frame(freshDown(id = 1)).multiDown)
    }

    // --- 本段之外新落下的指针（口径 5）---

    @Test
    fun `本段期间新落下的第二指算本段之外`() {
        val ledger = PointerLedger()
        ledger.start(frame(freshDown(id = 1)))

        assertTrue(
            "双指缩放即使没怎么动也不是一次点按",
            ledger.foreignNewDown(frame(pointer(id = 1, pressed = true), freshDown(id = 2))),
        )
    }

    @Test
    fun `起手那一刻就已经按着的指针不算接管`() {
        val ledger = PointerLedger()
        // 起手帧：1 是卡住的指针，2 是新落下的
        ledger.start(frame(pointer(id = 1, pressed = true), freshDown(id = 2)))

        assertFalse(
            "卡住的指针一直按着、不是新落下 ⇒ 不算接管",
            ledger.foreignNewDown(frame(pointer(id = 1, pressed = true), pointer(id = 2, pressed = true))),
        )
        assertFalse(
            "它抬起又落下（同一个 id）也不算：起手快照里已经有它了",
            ledger.foreignNewDown(frame(freshDown(id = 1), pointer(id = 2, pressed = true))),
        )
    }
}
