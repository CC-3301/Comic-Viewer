package com.cc3301.comicviewer.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import com.cc3301.comicviewer.core.view.CrossBookBarLayout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 跨书确认条的**文字配色口径**（票 #100 r2）：三格文案同一个强调橙，且在半透黑底上达到 WCAG AA。
 *
 * 真机反馈原文：「第一页/最后一页的文字不应该用白色，配合透明背景看不清」——本条把「看不清」变成
 * 可算的数：条底是半透的，文字压在**条底压过底图之后**的色上，最坏情况是**最亮的底图**（白页）。
 *
 * 判别力（把 [CROSS_BOOK_LABEL_COLOR] 改回 `Color.Gray` / `Color.White` 会变红的是哪几条）：
 * ① 色值钉死 = 0xFFFF9800（仓库强调橙）：改成白/灰立刻红；
 * ② 对比度下界 ≥ 4.5（AA 正文）：原来的灰在白页档上是 2.96 → 红，正是维护者看到的「看不清」；
 * ③ `CROSS_BOOK_LABEL_COLOR` 必须就是 [ACCENT_ORANGE]（阅读器菜单橙字同一个橙，不靠复制值维持）。
 *
 * 不覆盖的部分：**落在哪一格**与**像素上的实际色**由 `ui/CrossBookBarTest`（Robolectric 真渲染）把守；
 * 本文件只管口径本身（纯函数，不需要 Robolectric）。
 */
class CrossBookBarStyleTest {

    private val barAlpha = CrossBookBarLayout.BAR_ALPHA

    /** 对比度下界 = 条底压在最亮底图（白页）上时，与文字的 WCAG 对比度 */
    private fun worstCaseContrast(label: Color): Double =
        crossBookBarLabelContrast(label, barAlpha, pageColor = Color.White)

    @Test
    fun `条上文案就是仓库强调橙 不是白也不是灰`() {
        assertEquals(
            "三格文案（含「第一页」/「最后一页」）必须用仓库强调橙 0xFFFF9800",
            0xFFFF9800.toInt(),
            CROSS_BOOK_LABEL_COLOR.toArgb(),
        )
        assertEquals("必须与阅读器菜单的橙字同一个色（口径唯一一处的来源）", ACCENT_ORANGE, CROSS_BOOK_LABEL_COLOR)
        assertNotEquals("维护者否掉的白色", Color.White, CROSS_BOOK_LABEL_COLOR)
        assertNotEquals("被换掉的灰色（原来位置格用的就是它）", Color.Gray, CROSS_BOOK_LABEL_COLOR)
    }

    @Test
    fun `半透黑底压在最亮底图上 强调橙仍达 AA 对比度`() {
        val contrast = worstCaseContrast(CROSS_BOOK_LABEL_COLOR)
        assertTrue(
            "白页（最亮底图）档上条底最亮、对比度最低：实测 $contrast 必须 ≥ 4.5（AA 正文）",
            contrast >= 4.5,
        )
        // 深色页那一档对比度更高（底图越暗、条底越黑）：两个方向都要达标
        assertTrue(
            "深色页档上也必须达标（全黑底图 = 文字 vs 纯黑）",
            crossBookBarLabelContrast(CROSS_BOOK_LABEL_COLOR, barAlpha, pageColor = Color.Black) >= 4.5,
        )
    }

    @Test
    fun `判别力 原来的灰色在白页档上不达 AA`() {
        val gray = worstCaseContrast(Color.Gray)
        assertTrue(
            "原位置格的灰在白页档上只有 $gray —— 低于 AA 的 4.5，正是维护者看到的「看不清」；" +
                "本测试因此对「换回灰」有判别力",
            gray < 4.5,
        )
        assertTrue(
            "换成的强调橙必须明显好于原来的灰",
            worstCaseContrast(CROSS_BOOK_LABEL_COLOR) > gray,
        )
    }

    @Test
    fun `条底越黑对比度越高 加深条底与提可读性同向`() {
        val r2 = worstCaseContrastAt(CrossBookBarLayout.BAR_ALPHA)
        val r1 = worstCaseContrastAt(0.6f)
        assertTrue(
            "r2 加深条底（$r1 → $r2）后白页档上的对比度必须上升：加深底色的口径与「文字看得清」同向",
            r2 > r1,
        )
        assertTrue("加深到 0.78 后必须过 AA", r2 >= 4.5)
        assertTrue("不能是纯黑（条底仍要透出画面）", CrossBookBarLayout.BAR_ALPHA < 1f)
    }

    private fun worstCaseContrastAt(alpha: Float): Double =
        crossBookBarLabelContrast(CROSS_BOOK_LABEL_COLOR, alpha, pageColor = Color.White)
}
