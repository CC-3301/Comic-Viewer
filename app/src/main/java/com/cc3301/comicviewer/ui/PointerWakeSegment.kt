package com.cc3301.comicviewer.ui

/**
 * 唤醒按下那一整段的界面侧标记（spec §指针自动隐藏）。
 *
 * 判据在 `core/input/PointerAutoHide.onWakeClick`：隐藏时的左键或右键按下恢复指针，这一段（按下、按住移动、抬起）
 * 整段被消费。本类只是让同段的其余通道知道「这一段不归我」——根容器在 `PointerEventPass.Initial` 判定出唤醒后置位，
 * 指针抬起（或这一段被迫中断）时清位；点按识别器与放大通道读到它即整段让位（它们拿到的事件排在根容器之后）。
 */
internal class PointerWakeSegment {

    /** 当前这一段属于唤醒：不喂点击状态机、不平移、不滚动 */
    var active: Boolean = false
}
