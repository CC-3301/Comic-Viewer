package com.cc3301.comicviewer.core.view

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 封面尺寸规则（票 #46 AC 的纯函数落点）：按比例铺满可用宽度、完整显示；
 * 极端比例夹在兜底区间内并转成填充裁剪；比例未知时用占位比例。
 */
class CoverLayoutTest {

    // ---------- 正常封面：完整显示、不裁剪 ----------

    @Test
    fun `竖版封面按自身比例算出高度`() {
        // 2:3 封面（高宽比 1.5）：宽 100 → 高 150
        assertEquals(1.5f, CoverLayout.displayAspect(1.5f), 0.0001f)
        assertEquals(150f, CoverLayout.displayHeight(100f, 1.5f), 0.0001f)
        assertFalse("正常竖版封面不得裁剪", CoverLayout.needsCrop(1.5f))
    }

    @Test
    fun `横版封面按自身比例算出高度`() {
        // 4:3 跨页（高宽比 0.75）：宽 100 → 高 75
        assertEquals(0.75f, CoverLayout.displayAspect(0.75f), 0.0001f)
        assertEquals(75f, CoverLayout.displayHeight(100f, 0.75f), 0.0001f)
        assertFalse("正常横版封面不得裁剪", CoverLayout.needsCrop(0.75f))
    }

    @Test
    fun `比例未知时用占位比例 高度不为零`() {
        assertEquals(CoverLayout.PLACEHOLDER_ASPECT, CoverLayout.displayAspect(null), 0.0001f)
        assertTrue("占位阶段必须有高度，否则列表会先塌陷再撑开", CoverLayout.displayHeight(100f, null) > 0f)
        assertFalse("占位阶段不裁剪", CoverLayout.needsCrop(null))
    }

    // ---------- 边界与异常值 ----------

    @Test
    fun `区间边界值本身不裁剪`() {
        assertEquals(CoverLayout.MIN_ASPECT, CoverLayout.displayAspect(CoverLayout.MIN_ASPECT), 0.0001f)
        assertEquals(CoverLayout.MAX_ASPECT, CoverLayout.displayAspect(CoverLayout.MAX_ASPECT), 0.0001f)
        assertFalse(CoverLayout.needsCrop(CoverLayout.MIN_ASPECT))
        assertFalse(CoverLayout.needsCrop(CoverLayout.MAX_ASPECT))
    }

    @Test
    fun `超长条漫页夹到上限并裁剪`() {
        // 1:5 的条漫页（高宽比 5）：夹到 1.6
        assertEquals(CoverLayout.MAX_ASPECT, CoverLayout.displayAspect(5f), 0.0001f)
        assertEquals(160f, CoverLayout.displayHeight(100f, 5f), 0.0001f)
        assertTrue("超限比例走填充裁剪分支", CoverLayout.needsCrop(5f))
    }

    @Test
    fun `超宽跨页夹到下限并裁剪`() {
        // 5:1 的跨页（高宽比 0.2）：夹到 0.6
        assertEquals(CoverLayout.MIN_ASPECT, CoverLayout.displayAspect(0.2f), 0.0001f)
        assertEquals(60f, CoverLayout.displayHeight(100f, 0.2f), 0.0001f)
        assertTrue("超限比例走填充裁剪分支", CoverLayout.needsCrop(0.2f))
    }

    @Test
    fun `非法比例按占位处理`() {
        listOf(0f, -1f, Float.NaN, Float.POSITIVE_INFINITY).forEach { bad ->
            assertEquals("非法比例 $bad 应按占位处理", CoverLayout.PLACEHOLDER_ASPECT, CoverLayout.displayAspect(bad), 0.0001f)
            assertFalse("非法比例不裁剪", CoverLayout.needsCrop(bad))
        }
    }

    // ---------- 位图尺寸 → 比例 ----------

    @Test
    fun `位图宽高换算比例`() {
        assertEquals(1.5f, CoverLayout.aspectOf(200, 300)!!, 0.0001f)
        assertNull("尺寸不可用时没有比例", CoverLayout.aspectOf(0, 300))
        assertNull(CoverLayout.aspectOf(200, 0))
    }

    @Test
    fun `子采样后的位图比例与原图一致`() {
        // BitmapFactory 的 inSampleSize 是等比缩放：比例不因解码宽度变化（票 #46「比例信息要保留」）
        assertEquals(CoverLayout.aspectOf(1800, 2700), CoverLayout.aspectOf(128, 192))
    }
}
