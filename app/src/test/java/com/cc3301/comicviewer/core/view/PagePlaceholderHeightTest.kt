package com.cc3301.comicviewer.core.view

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 条漫未解码页的占位高：按已知页比例（宽 / 高）从页容器宽度推出来，**唯一出处**。
 *
 * 判据（见 `docs/spec/shell.md` 的「等页期间的呈现」）：比例已知 ⇒ 页容器宽 ÷ 页比例；
 * 比例未知（null）、宽度非正数、比例非法或极端都退回固定占位高，且结果恒为有限正数。
 */
class PagePlaceholderHeightTest {

    @Test
    fun `比例已知时占位高 = 容器宽除以页比例`() {
        // 常见竖图 2:3（宽 / 高 = 0.6667）：1080dp 宽的页 ⇒ 1620dp 高
        assertEquals(1620f, pagePlaceholderHeightDp(1080f, 2f / 3f), 0.01f)
        // 横图 16:9：1080dp 宽的页 ⇒ 607.5dp 高
        assertEquals(607.5f, pagePlaceholderHeightDp(1080f, 16f / 9f), 0.01f)
        // 方图
        assertEquals(1080f, pagePlaceholderHeightDp(1080f, 1f), 0.01f)
    }

    @Test
    fun `比例未知时退回固定占位高`() {
        // 刚进书的第一屏：这本书还没有任何一页画出来可比照
        assertEquals(PAGE_PLACEHOLDER_HEIGHT_DP, pagePlaceholderHeightDp(1080f, null), 0.01f)
        assertEquals(PAGE_PLACEHOLDER_HEIGHT_DP, pagePlaceholderHeightDp(0f, null), 0.01f)
    }

    @Test
    fun `宽度非正数退回固定占位高`() {
        // 布局首帧可能给 0 宽；除出来不能是 0 高或无穷
        assertEquals(PAGE_PLACEHOLDER_HEIGHT_DP, pagePlaceholderHeightDp(0f, 2f / 3f), 0.01f)
        assertEquals(PAGE_PLACEHOLDER_HEIGHT_DP, pagePlaceholderHeightDp(-12f, 2f / 3f), 0.01f)
        assertEquals(PAGE_PLACEHOLDER_HEIGHT_DP, pagePlaceholderHeightDp(Float.NaN, 2f / 3f), 0.01f)
        assertEquals(PAGE_PLACEHOLDER_HEIGHT_DP, pagePlaceholderHeightDp(Float.POSITIVE_INFINITY, 2f / 3f), 0.01f)
    }

    @Test
    fun `极端比例不崩`() {
        // 长条漫一页可以到 1:20：仍是有限正数，高度按比例推出来
        val tall = pagePlaceholderHeightDp(1080f, 0.05f)
        assertEquals(21600f, tall, 1f)
        // 0 / 负数 / NaN / 无穷 / 除到溢出：一律退回固定占位高，绝不给 NaN、负数、无穷
        listOf(
            0f,
            -0.5f,
            Float.NaN,
            Float.POSITIVE_INFINITY,
            Float.NEGATIVE_INFINITY,
            Float.MIN_VALUE,
        ).forEach { aspect ->
            val height = pagePlaceholderHeightDp(1080f, aspect)
            assertTrue("aspect=$aspect 推出来的高度 $height 必须是有限正数", height.isFinite() && height > 0f)
        }
    }
}
