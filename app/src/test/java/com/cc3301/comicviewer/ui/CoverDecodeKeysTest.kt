package com.cc3301.comicviewer.ui

import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * 预取与可见行必须取到**同一把封面解码键**（票 #108 r7）。
 *
 * 为什么这条用例是「预取不会静默白干」的守卫：r6 起预取连解码一起做，可见行靠
 * `PageDecoder.cachedCover(键)` 命中预取解好的那张；两条路径**各自独立算**（可见行从 [CoverSizing]、
 * 预取从「档位 + 格宽」两个标量），任一路径的换算漂移（忘了分桶、裁剪目标映射反了、宽度取了别的量）
 * 都会让键不相等——那时预取解出来的位图没人命中，可见行又自己取一遍字节、解一遍码，而**没有任何地方会报错**。
 * 因此这里逐项钉住相等，并在宽度/档位/密度变化时也钉住相等。
 */
class CoverDecodeKeysTest {

    private val density = 2.75f

    @Test
    fun `网格档：预取与可见行同一把键`() {
        val cellWidth = 156.dp
        val row = CoverDecodeKeys.forRow(CoverSizing.GridCell(cellWidth, 280.dp), density)
        val prefetch = CoverDecodeKeys.forPrefetch(cellWidth.value, grid = true, density = density)

        assertEquals("宽度 + 裁剪目标都相同", row, prefetch)
        assertEquals("键也逐字相等", row.keyOf("root/a", 3), prefetch.keyOf("root/a", 3))
    }

    @Test
    fun `列表档：预取与可见行同一把键`() {
        val row = CoverDecodeKeys.forRow(CoverSizing.OwnAspect(56.dp), density)
        val prefetch = CoverDecodeKeys.forPrefetch(56f, grid = false, density = density)

        assertEquals(row, prefetch)
        assertEquals(row.keyOf("root/a", 0), prefetch.keyOf("root/a", 0))
    }

    @Test
    fun `宽度与密度各种取值下都相等`() {
        // 分桶边界附近最容易漂（目标宽度向上取到 32 的整数倍）：整屏格宽 × 常见密度都过一遍
        val widths = listOf(40f, 55f, 56f, 57f, 120f, 156f, 200f, 233f)
        val densities = listOf(1f, 1.5f, 2f, 2.75f, 3f)
        for (width in widths) {
            for (d in densities) {
                assertEquals(
                    "格宽 $width dp / 密度 $d",
                    CoverDecodeKeys.forRow(CoverSizing.GridCell(width.dp, 280.dp), d),
                    CoverDecodeKeys.forPrefetch(width, grid = true, density = d),
                )
                assertEquals(
                    "列表列宽 $width dp / 密度 $d",
                    CoverDecodeKeys.forRow(CoverSizing.OwnAspect(width.dp), d),
                    CoverDecodeKeys.forPrefetch(width, grid = false, density = d),
                )
            }
        }
    }

    @Test
    fun `档位不同则键不同（两档不串图）`() {
        val grid = CoverDecodeKeys.forPrefetch(156f, grid = true, density = density)
        val list = CoverDecodeKeys.forPrefetch(156f, grid = false, density = density)

        assertNotEquals("裁剪目标不同 ⇒ 键必须不同", grid.keyOf("root/a", 0), list.keyOf("root/a", 0))
    }

    @Test
    fun `重取键参与键（下拉更新要重解）`() {
        val params = CoverDecodeKeys.forPrefetch(156f, grid = true, density = density)

        assertNotEquals(
            "reloadTick 变 ⇒ 键必须变（否则下拉更新不会重取封面）",
            params.keyOf("root/a", 0),
            params.keyOf("root/a", 1),
        )
    }
}
