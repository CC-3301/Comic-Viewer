package com.cc3301.comicviewer.ui

import androidx.compose.ui.input.pointer.AwaitPointerEventScope
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.changedToDownIgnoreConsumed

/**
 * 等一根**新按下**的指针：阅读页两条手势通道（点按识别器、放大态平移/捏合）的共用入口。
 *
 * **为什么不用 Compose 的 `awaitFirstDown()`**：它等的条件是「本次事件里**所有** change 都是新按下」
 * （`event.changes.fastAll { it.changedToDown*() }`）。系统没认到鼠标松手时会留下一个一直「按着」的指针，
 * 于是之后**每一次**事件都不满足这个条件、`awaitFirstDown()` 永远不返回——手指的双击、捏合、平移从此
 * 全死（设备现象：鼠标双击失败之后，连用手指也一起失效）。`requireUnconsumed = false` 也救不了：
 * 那条支路判的是 `changedToDownIgnoreConsumed()`，卡住的指针照样不是「新按下」。
 *
 * 这里只看「事件里有没有**某一根**新按下的指针」，别的指针是什么状态一概不管：卡住的指针既不阻塞本段开始，
 * 也不影响本段结束（本段只认自己那几个指针，见各调用点）。
 */
internal suspend fun AwaitPointerEventScope.awaitReaderPointerDown(
    pass: PointerEventPass = PointerEventPass.Main,
): PointerInputChange {
    while (true) {
        val event = awaitPointerEvent(pass)
        event.changes.firstOrNull { it.changedToDownIgnoreConsumed() }?.let { return it }
    }
}
