package com.cc3301.comicviewer.core.view

import com.cc3301.comicviewer.core.touch.TouchZone
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 跨书确认条的版式口径（票 #100）：**命中格 → 动作**的判定与条高/字号。
 *
 * 票 #100 的版式是「整宽纯黑条 + 三等分三列」：左格 / 中格 / 右格，中格恒是位置提示
 * （「第一页」/「最后一页」），**按钮格按方向选**——首页方向（换上一本）在左格、末页方向（换下一本）在右格。
 *
 * 判别力：
 * ① 三列划分必须与触摸区**逐像素同源**：本测试的采样点就是触摸区的左缘/正中/右缘，
 *    断言它们落在动作格里；边界取 `w/3` 与 `2w/3` 两侧 0.5px，越界立刻不命中——
 *    任何「按钮列与触摸区列不对齐」的实现（例：整列内缩了系统栏/挖孔 inset、
 *    或按钮只占文字宽度）都会在这里变红；
 * ② 中格与反向那一侧的空白格**一律不动作**（首页方向点右格不跳下一本，反之亦然）；
 * ③ 条高 64dp / 字号 20sp（改动前 48dp / 16sp，票面方案 2）；
 * ④ 批次 6 + r2 的两条口径：条面底色 = **黑七成八**（[CrossBookBarLayout.BAR_ALPHA]，不是纯黑、不是全透明）、
 *    文案在**整块条面**（[CrossBookBarLayout.bandHeightDp]）里垂直居中（[CrossBookBarLayout.labelCenterFromBottomDp]）
 *    ——后者与「居中在 64dp 内容带里」差 6dp（88dp 条面）或 12dp（64dp 条面），把这条量出来才不会被退回；
 * ⑤ r2 的命中口径：**命中格 = 视觉格整格**（含底部避让那一截），不是只截到 64dp 内容带——
 *    后者会在按钮格内部留出一截死区（r2 真机「点击左右选区有时候无效」的根因，见 brief 补记 2）。
 *    这条是几何关系（命中层高 = [CrossBookBarLayout.bandHeightDp]），由 `ui/CrossBookBarTest` 的
 *    逐像素纵向扫描量出来；本文件只管口径本身，因此只钉住「条面高」这一个量。
 *
 * 不覆盖的部分（写明，避免读成全覆盖）：真实列的落位几何、命中区的像素边界、条的黑底与圆角
 * 由 `ui/CrossBookBarTest`（Robolectric 实测）与真机目视把守；本文件只管口径本身。
 */
class CrossBookBarLayoutTest {

    private val w = 1080f

    @Test
    fun `动作格按方向选取 首页方向在左格 末页方向在右格`() {
        assertEquals("首页方向（第一页）按钮应落在左格", TouchZone.LEFT, CrossBookBarLayout.actionZone(forward = false))
        assertEquals("末页方向（最后一页）按钮应落在右格", TouchZone.RIGHT, CrossBookBarLayout.actionZone(forward = true))
    }

    @Test
    fun `命中判定与触摸区共用同一份三等分 边界逐像素重合`() {
        // 触摸区左区的三点（区内左缘 / 正中 / 右缘）都落在首页方向的动作格里
        for (x in listOf(1f, w / 6f, w / 3f - 1f)) {
            assertTrue("首页方向 x=$x（左触摸区内）应命中左格按钮", CrossBookBarLayout.confirmsAt(x, w, forward = false))
        }
        // 触摸区右区的三点都落在末页方向的动作格里
        for (x in listOf(2f * w / 3f, 5f * w / 6f, w - 1f)) {
            assertTrue("末页方向 x=$x（右触摸区内）应命中右格按钮", CrossBookBarLayout.confirmsAt(x, w, forward = true))
        }
        // 边界 = 触摸区的分区边界（w/3 与 2w/3）：越界 0.5px 就换格
        assertTrue("左格右缘内侧 0.5px 仍属左格", CrossBookBarLayout.confirmsAt(w / 3f - 0.5f, w, forward = false))
        assertFalse("左格右缘外侧 0.5px 已属中格", CrossBookBarLayout.confirmsAt(w / 3f, w, forward = false))
        assertFalse("右格左缘内侧 0.5px 仍属中格", CrossBookBarLayout.confirmsAt(2f * w / 3f - 0.5f, w, forward = true))
        assertTrue("右格左缘外侧 0.5px 已属右格", CrossBookBarLayout.confirmsAt(2f * w / 3f, w, forward = true))
    }

    @Test
    fun `中格提示格三点一律不命中`() {
        for (forward in listOf(false, true)) {
            for (x in listOf(w / 3f, w / 2f, 2f * w / 3f - 1f)) {
                assertFalse(
                    "中格 x=$x（forward=$forward）不可点：它只用来提示第一页/最后一页",
                    CrossBookBarLayout.confirmsAt(x, w, forward = forward),
                )
            }
        }
    }

    @Test
    fun `反向空白格不命中 首页方向点右格与末页方向点左格都不动作`() {
        for (x in listOf(2f * w / 3f, 5f * w / 6f, w - 1f)) {
            assertFalse("首页方向点右格 x=$x 不应跳到下一本", CrossBookBarLayout.confirmsAt(x, w, forward = false))
        }
        for (x in listOf(1f, w / 6f, w / 3f - 1f)) {
            assertFalse("末页方向点左格 x=$x 不应跳到上一本", CrossBookBarLayout.confirmsAt(x, w, forward = true))
        }
    }

    @Test
    fun `非法宽度防御 不命中`() {
        // 视口宽为 0 或负数时分区判定回中格（touchZoneAt 的既有防御）→ 两个方向都不命中
        assertFalse(CrossBookBarLayout.confirmsAt(0f, 0f, forward = false))
        assertFalse(CrossBookBarLayout.confirmsAt(100f, -5f, forward = true))
    }

    @Test
    fun `条面底色为黑七成八 不是纯黑也不是全透明`() {
        assertEquals("r2 口径：条底再加深到黑 0.78", 0.78f, CrossBookBarLayout.BAR_ALPHA, 0.0001f)
        assertTrue("必须比批次 6 的黑 0.6 更深（真机：「还是有点透，不够黑」）", CrossBookBarLayout.BAR_ALPHA > 0.6f)
        assertTrue("不能是纯黑（黑 100%）：画面要透出来一档", CrossBookBarLayout.BAR_ALPHA < 1f)
        assertTrue("不能全透明：那条上的文字就没有底了", CrossBookBarLayout.BAR_ALPHA > 0f)
    }

    @Test
    fun `条面高 = 64dp 内容带 + 底部避让 文案中心落在条面正中而非内容带正中`() {
        assertEquals("无底部避让时条面就是内容带 64dp", 64f, CrossBookBarLayout.bandHeightDp(0f), 0.01f)
        assertEquals(
            "沉浸态底部兜底 24dp（ReaderOverlayLayout.MIN_BOTTOM_DP）时条面 = 88dp",
            88f,
            CrossBookBarLayout.bandHeightDp(ReaderOverlayLayout.MIN_BOTTOM_DP),
            0.01f,
        )
        // 判别点：文案中心是条面正中（44dp），不是 64dp 内容带正中（32dp）——
        // 「文案居中在内容带里」的实现在这条上变红，正是维护者报的「偏上、下方空隙大」
        assertEquals(32f, CrossBookBarLayout.BAR_HEIGHT_DP / 2f, 0.01f)
        assertEquals(
            "文案中心距条面底边 = 条面正中",
            44f,
            CrossBookBarLayout.labelCenterFromBottomDp(ReaderOverlayLayout.MIN_BOTTOM_DP),
            0.01f,
        )
        val band = CrossBookBarLayout.bandHeightDp(ReaderOverlayLayout.MIN_BOTTOM_DP)
        val center = CrossBookBarLayout.labelCenterFromBottomDp(ReaderOverlayLayout.MIN_BOTTOM_DP)
        assertEquals("上下留白一致（到条面顶 = 到条面底）", center, band - center, 0.01f)
        assertTrue(
            "文案下缘仍要避开手势带上滑带（≥ MIN_BOTTOM_DP）",
            center - CrossBookBarLayout.LABEL_SP / 2f >= ReaderOverlayLayout.MIN_BOTTOM_DP,
        )
    }

    @Test
    fun `条高 64dp 与字号 20sp`() {
        assertEquals("条高按票面方案 = 64dp（改动前 48dp）", 64f, CrossBookBarLayout.BAR_HEIGHT_DP, 0.01f)
        assertEquals("条内字号按票面方案 = 20sp（改动前 16sp）", 20f, CrossBookBarLayout.LABEL_SP, 0.01f)
        assertTrue("条高必须比改动前的 48dp 大", CrossBookBarLayout.BAR_HEIGHT_DP > 48f)
        assertTrue("字号必须比改动前的 16sp 大", CrossBookBarLayout.LABEL_SP > 16f)
    }
}
