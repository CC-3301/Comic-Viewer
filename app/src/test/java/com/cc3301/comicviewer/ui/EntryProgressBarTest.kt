package com.cc3301.comicviewer.ui

import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 网格格子内「进度条区域」的占位高度（票 #92 需求 2）。
 *
 * 口径：进度条本身的高度与**无进度时的占位高度**是同一个常量。占位高度一旦与条高不同
 * （或干脆不占位），同排有进度 / 无进度的格子就差出一条进度条的高度，行内元素（封面顶边之后的
 * 名称与进度条区域）重新错位。界面侧的两个调用点——[EntryProgressBar] 的 `.height(...)` 与
 * [EntryProgressSlot] 的占位盒——都读这一份，本用例钉住的正是「两处不会各自漂移」的那一个值。
 *
 * 可抽的只有这个高度常量与「高度不依赖有没有进度」这条口径（[progressSlotHeight]）；名称左对齐（需求 1）
 * 是 [EntryNameText] 调用点上的 `TextAlign` 默认值，本仓库没有 compose-ui-test 依赖
 * （`androidTest` 只有一条冒烟用例），无法在此钉住，见实施证据。
 */
class EntryProgressBarTest {

    @Test
    fun `进度条高度是 4dp`() {
        assertEquals(4.dp, PROGRESS_BAR_HEIGHT)
    }

    @Test
    fun `无进度时的占位高度与有进度时完全相同`() {
        assertEquals(progressSlotHeight(hasProgress = true), progressSlotHeight(hasProgress = false))
        // 占位高度就是条本身的高度（同一常量），不是另一个凑出来的数
        assertEquals(PROGRESS_BAR_HEIGHT, progressSlotHeight(hasProgress = false))
    }
}
