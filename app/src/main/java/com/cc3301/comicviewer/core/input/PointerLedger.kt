package com.cc3301.comicviewer.core.input

/**
 * 一帧里一根指针的状态（由界面从指针事件翻译而来；core 不认识 Compose 的指针类型）。
 *
 * [previousPressed] 与 [pressed] 一起表达「本帧**新落下**」：两帧都按着的指针不是新落下——系统没认到
 * 鼠标松手时会留下这样一根一直「按着」的指针。
 */
internal data class PointerState(
    /** 指针 id：同一根指针从落下到抬起不变 */
    val id: Long,
    /** 本帧是否按下 */
    val pressed: Boolean,
    /** 上一帧是否按下 */
    val previousPressed: Boolean,
)

/** 一帧的全部指针状态（按事件里的顺序） */
internal data class PointerFrame(val pointers: List<PointerState>) {

    /** 本帧**新落下**的指针 id（同一帧可能多根：双指同落） */
    val freshDownIds: List<Long> get() = pointers.filter { it.pressed && !it.previousPressed }.map { it.id }

    /** 本帧按着的指针 id */
    val pressedIds: List<Long> get() = pointers.filter { it.pressed }.map { it.id }

    /** 本帧是否同帧一起落下多指（双指同落 = 双指缩放，不是点按） */
    val multiDown: Boolean get() = freshDownIds.size > 1
}

/**
 * 一段手势的**指针簿记**（纯逻辑，由 [PointerLedgerTest] 锁定）。判定进 `core/input/`、`ui/` 只做事件翻译
 * （与 `core/input/` 其余手势同一分工）。本模块的判据：
 *
 * - **新按下 / 卡住指针**：[PointerFrame.freshDownIds] 只看「本帧按下、上一帧没按下」。卡住的指针不是新落下
 *   ⇒ 它既不阻塞本段起手（`ui/FreshPointerDown.kt` 的入口据此不用 `awaitFirstDown()`），也不拖住本段结束。
 * - **本段自己的指针**：[start] 从起手那一帧新落下的那些起算，[advance] 把本段期间新落下的收进来。
 *   只认它们——别人（例如系统没认到松手而一直「按着」的鼠标）既不算进双指缩放，也不拖住本段的结束。
 * - **同帧多指**：[PointerFrame.multiDown]。
 * - **本段之外新落下的指针**：[foreignNewDown]——起手快照（[start] 那一刻按着的指针）里没有的 id 才算。
 *
 * 接口只有「喂事件」（[start] / [advance]）与「问判据」（[foreignNewDown] 与 [PointerFrame] 的派生量）两类，
 * 内部集合不外露。
 */
internal class PointerLedger {

    /** 本段自己的指针：起手那一帧新落下的 + 本段期间新落下的 */
    private val ownIds = mutableSetOf<Long>()

    /** 起手那一刻按着的指针（含卡住的）：[foreignNewDown] 的比对快照 */
    private var heldAtStart: Set<Long> = emptySet()

    /** 起手：喂本段的第一帧（上一段的记录作废）；本段自己的指针从本帧新落下的那些起算 */
    fun start(frame: PointerFrame) {
        ownIds.clear()
        ownIds += frame.freshDownIds
        heldAtStart = frame.pressedIds.toSet()
    }

    /**
     * 喂本段的后续一帧：本段期间新落下的指针收进本段。
     * @return 本段自己的、此刻仍按着的指针 id（空 = 本段结束）
     */
    fun advance(frame: PointerFrame): List<Long> {
        ownIds += frame.freshDownIds
        return frame.pointers.filter { it.pressed && it.id in ownIds }.map { it.id }
    }

    /**
     * 本帧有没有**本段之外新落下**的指针（第二指 / 第三指）：本帧新落下、且起手快照里没有它的 id。
     * 起手那一刻就已经按着的指针不算接管——系统没认到松手留下的那根会让之后每一次点按都被它作废。
     */
    fun foreignNewDown(frame: PointerFrame): Boolean = frame.freshDownIds.any { it !in heldAtStart }
}
