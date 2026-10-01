package com.cc3301.comicviewer.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import com.cc3301.comicviewer.core.view.CrossBookBarLayout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 跨书确认条的**文字配色口径**（票 #100 r3）：中格位置标签 = 纯白，按钮格 = 强调橙，两种色各自达标。
 *
 * 真机反馈原文：「首末页的文案不要用橙色 和上/下一本按钮混色了」——位置标签只是提示、按钮才是动作，
 * 同色会让「哪一格能点」失去视觉线索，因此拆成两个来源。本条把「看得清 / 分得开」变成可算的数：
 * 条底是半透的，文字压在**条底压过底图之后**的色上，最坏情况是**最亮的底图**（白页）。
 *
 * 判别力（哪条断言会被哪种改错变红）：
 * ① 两个色值各钉一处（`0xFFFFFFFF` 与 `0xFFFF9800`）：把按钮也改白、或把位置标签改回橙 → 红；
 * ② 两者必须**不相等**：把两边一改到底（r2 那种「三格同一个色」）→ 红；
 * ③ 对比度下界 ≥ 4.5（AA 正文）：白字（≥ 7 的 AAA 级）与橙按钮都在其上；原来的灰在白页档上只有 2.96 → 红。
 *
 * 不覆盖的部分：**落在哪一格**与**像素上的实际颜色**由 `ui/CrossBookBarTest`（Robolectric 真渲染）把守；
 * 本文件只管口径本身（纯函数，不需要 Robolectric）。
 */
class CrossBookBarStyleTest {

    private val barAlpha = CrossBookBarLayout.BAR_ALPHA

    /** 对比度下界 = 条底压在最亮底图（白页）上时，与文字的 WCAG 对比度 */
    private fun worstCaseContrast(label: Color): Double =
        crossBookBarLabelContrast(label, barAlpha, pageColor = Color.White)

    @Test
    fun `中格位置标签是纯白 按钮格仍是强调橙 两个色各有各的来源且不相等`() {
        assertEquals(
            "中格位置标签（「第一页」/「最后一页」）必须是纯白 0xFFFFFFFF",
            0xFFFFFFFF.toInt(),
            CROSS_BOOK_LABEL_COLOR.toArgb(),
        )
        assertEquals(
            "按钮格（「上一本书」/「下一本书」）必须仍是仓库强调橙 0xFFFF9800（SPEC 故事 27 的橙色跳转按钮）",
            0xFFFF9800.toInt(),
            CROSS_BOOK_ACTION_COLOR.toArgb(),
        )
        assertEquals(
            "按钮色必须就是阅读器菜单橙字那一个常量（不靠复制值维持）",
            ACCENT_ORANGE,
            CROSS_BOOK_ACTION_COLOR,
        )
        assertNotEquals(
            "位置标签与按钮必须不同色：按钮被改成白（或标签被改回橙）都会让「哪一格能点」失去线索",
            CROSS_BOOK_LABEL_COLOR,
            CROSS_BOOK_ACTION_COLOR,
        )
        assertNotEquals("被换掉的灰色（原来位置格用的就是它）", Color.Gray, CROSS_BOOK_LABEL_COLOR)
        assertNotEquals("位置标签不该是强调橙（r3 就是要把两者分开）", ACCENT_ORANGE, CROSS_BOOK_LABEL_COLOR)
    }

    @Test
    fun `半透黑底压在最亮底图上 白字与橙按钮都达 AA 对比度`() {
        val white = worstCaseContrast(CROSS_BOOK_LABEL_COLOR)
        assertTrue(
            "白页（最亮底图）档上条底最亮、对比度最低：白字实测 $white 必须 ≥ 4.5（AA 正文）",
            white >= 4.5,
        )
        assertTrue(
            "白字应显著高于 AA（本档实测 $white，AAA 级 7 以上）——不是压线过关",
            white >= 7.0,
        )
        // 深色页那一档对比度更高（底图越暗、条底越黑）：两个方向都要达标
        assertTrue(
            "深色页档上也必须达标（全黑底图 = 文字 vs 纯黑）",
            crossBookBarLabelContrast(CROSS_BOOK_LABEL_COLOR, barAlpha, pageColor = Color.Black) >= 4.5,
        )
        // 按钮格仍是橙：AC「按钮文案与配色不变」的可读性口径一并保住
        val orange = worstCaseContrast(CROSS_BOOK_ACTION_COLOR)
        assertTrue(
            "橙按钮在白页档上实测 $orange 仍须 ≥ 4.5（r2 已达标，r3 不改按钮色就不该掉下来）",
            orange >= 4.5,
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
            "换成的纯白必须明显好于原来的灰",
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
