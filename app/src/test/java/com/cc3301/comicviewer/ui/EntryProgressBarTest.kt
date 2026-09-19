package com.cc3301.comicviewer.ui

import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 进度条高度（票 #92 需求 2）。
 *
 * 口径：进度条**压在封面下缘**，条高就是它遮住封面的画面高度（4dp）；这个值只在一处定义
 * （[EntryProgressBar] 的 `.height(...)` 是唯一读取点），两档调用点不再各写一份。
 *
 * 本仓库没有 compose-ui-test 基建（`androidTest` 只有一条冒烟用例），因此「条叠在封面下缘」
 * 「不占布局、无进度不绘制」「条宽 = 封面宽」这三件事无法在此测量——它们由两个调用点的一个
 * `if (progress != null)` + 盒宽取封面宽保证，理由见实施证据；为此新加测试依赖属另一票。
 */
class EntryProgressBarTest {

    @Test
    fun `进度条高度是 4dp`() {
        assertEquals(4.dp, PROGRESS_BAR_HEIGHT)
    }
}
