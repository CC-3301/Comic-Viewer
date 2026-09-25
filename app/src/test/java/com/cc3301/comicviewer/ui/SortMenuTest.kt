package com.cc3301.comicviewer.ui

import com.cc3301.comicviewer.core.sort.SortDirection
import com.cc3301.comicviewer.core.sort.SortSetting
import com.cc3301.comicviewer.core.source.SortMode
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 排序菜单的 6 个选项与文案（票 #80）：选项来源 = 类别 × 方向（恰好 6 项），文案是按钮与菜单项共用的
 * 同一份（名称论升序/降序、时间两类论新→旧/旧→新），打勾判据 = 类别与方向都对上当前设置。
 *
 * 这里只钉纯函数（不依赖 Android）：菜单的渲染与点击由真机清单守护。
 */
class SortMenuTest {

    /** 验收点名的六项文案与顺序 */
    private val expectedLabels = listOf(
        "名称 升序",
        "名称 降序",
        "修改时间 新→旧",
        "修改时间 旧→新",
        "发布时间 新→旧",
        "发布时间 旧→新",
    )

    @Test
    fun `菜单恰好 6 项 文案与顺序固定`() {
        assertEquals("恰好 6 项 = 3 个类别 × 2 个方向", 6, sortMenuOptions.size)
        assertEquals(expectedLabels, sortMenuOptions.map { it.label })
    }

    @Test
    fun `类别与方向的组合不重不漏`() {
        val expectedPairs = SortMode.entries.flatMap { mode ->
            SortDirection.entries.map { direction -> mode to direction }
        }

        assertEquals(expectedPairs, sortMenuOptions.map { it.mode to it.direction })
    }

    @Test
    fun `默认设置下当前项是名称升序`() {
        assertEquals(listOf("名称 升序"), currentLabels(SortSetting()))
    }

    @Test
    fun `名称降序时当前项是名称降序 别的类别那格的方向不影响`() {
        val setting = SortSetting(
            nameDirection = SortDirection.REVERSE,
            releaseDirection = SortDirection.REVERSE,
        )

        assertEquals(
            "打勾只看当前类别（名称）那一格：发布时间的反向不参与",
            listOf("名称 降序"),
            currentLabels(setting),
        )
    }

    @Test
    fun `点了菜单里的一项后当前项跟着那一项走`() {
        val setting = SortSetting().select(SortMode.MODIFIED_TIME, SortDirection.REVERSE)

        assertEquals(listOf("修改时间 旧→新"), currentLabels(setting))
    }

    @Test
    fun `按钮文字与当前项文案是同一份`() {
        val setting = SortSetting().select(SortMode.RELEASE_TIME, SortDirection.REVERSE)

        val current = sortMenuOptions.single { it.isCurrent(setting) }
        assertEquals("发布时间 旧→新", current.label)
        // 顶栏按钮读的就是这个式子（BrowserScreen 的接线）
        assertEquals(current.label, sortLabel(setting.mode, setting.directionOf()))
    }

    private fun currentLabels(setting: SortSetting): List<String> =
        sortMenuOptions.filter { it.isCurrent(setting) }.map { it.label }
}
