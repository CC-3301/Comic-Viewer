package com.cc3301.comicviewer.ui

import androidx.compose.ui.input.pointer.AwaitPointerEventScope
import androidx.compose.ui.input.pointer.PointerEvent
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerInputChange
import com.cc3301.comicviewer.core.input.PointerFrame
import com.cc3301.comicviewer.core.input.PointerState

/** 一帧指针事件 → 簿记的输入（判定在 `core/input/PointerLedger.kt`） */
internal fun PointerEvent.toPointerFrame(): PointerFrame = PointerFrame(
    changes.map { PointerState(id = it.id.value, pressed = it.pressed, previousPressed = it.previousPressed) },
)

/**
 * 等一根**新按下**的指针：本仓所有「按住 + 只跟踪自己那根指针」的手势通道的共用入口
 * （阅读页的点按识别器与放大态平移/捏合、列表的鼠标拖动滚动、下拉更新、顶栏菜单按钮的长按、菜单滑条）。
 *
 * 「新按下」的判据是 [PointerFrame.freshDownIds]（`core/input/PointerLedger.kt`）。
 * **为什么不用 Compose 的 `awaitFirstDown()`**：它等的条件是「本次事件里**所有** change 都是新按下」
 * （`event.changes.fastAll { it.changedToDown*() }`）。系统没认到鼠标松手时会留下一个一直「按着」的指针，
 * 于是之后**每一次**事件都不满足这个条件、`awaitFirstDown()` 永远不返回——该通道的手势从此全死
 * （设备现象：鼠标双击失败之后，连用手指双击/捏合也一起失效）。`requireUnconsumed = false` 也救不了：
 * 那条支路判的是 `changedToDownIgnoreConsumed()`，卡住的指针照样不是「新按下」。
 *
 * 这里只看「事件里有没有**某一根**新按下的指针」，别的指针是什么状态一概不管；各调用点只跟踪自己那一根
 * 指针的抬起（`event.changes.firstOrNull { it.id == down.id }`），因此卡住的指针既不会阻塞本段开始，
 * 也不会拖住本段结束。
 */
internal suspend fun AwaitPointerEventScope.awaitFreshPointerDown(
    pass: PointerEventPass = PointerEventPass.Main,
): PointerInputChange {
    while (true) {
        val event = awaitPointerEvent(pass)
        val freshIds = event.toPointerFrame().freshDownIds
        event.changes.firstOrNull { it.id.value in freshIds }?.let { return it }
    }
}
