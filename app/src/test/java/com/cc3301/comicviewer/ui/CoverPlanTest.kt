package com.cc3301.comicviewer.ui

import androidx.compose.ui.unit.dp
import com.cc3301.comicviewer.core.view.CoverDecode
import com.cc3301.comicviewer.core.view.ViewMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 封面取图方案（票 #135，原名 `CoverDecodeKeysTest`：被删的 `CoverDecodeKeys` 那两条路径已合成此处）。
 *
 * 改动前：可见行与预取**各算一份**解码参数，「两边逐字相等」只由本文件钉着；漂移的失败模式是**静默**的
 * （预取解好的那张可见行命不中 ⇒ 可见行又自己取一遍字节、解一遍码，预取白干还白占一份封面分区预算）。
 * 票 #135 把两条合成一处：[CoverPlan] 的盒宽、解码宽度与裁剪目标**全是同一个口径的派生值**，
 * 可见行与预取拿的是同一个实例。本文件因此改为钉：
 *
 * 1. **盒宽与解码同源**（r2 b1 补的守卫）：同一 [CoverSizing] 推出的盒宽、宽度桶与裁剪目标必须成一套
 *    ——「盒子按列表档画、解码按网格档解」这种改坏在这里红；
 * 2. **档位映射只一处**：[coverSizingFor] 逐档钉住（把 `view.isGrid` 判反即红）；
 * 3. 键的算式、重取键参与方式与票 #53 的 uri 例外（断言口径与改动前同强：桶值直写 + 过冲 < 一个桶）。
 *
 * 桶算式**不手抄**：期望值取生产函数 [CoverDecode.targetWidthPx]，另用两个字面量锚点（448 / 160）钉住它
 * 在出货几何下的取值。
 */
class CoverPlanTest {

    private val density = 2.75f

    @Test
    fun `网格档：口径定出盒宽 宽度桶与裁剪目标`() {
        val sizing = CoverSizing.GridCell(156.dp)
        val plan = CoverPlan.of(sizing, density = density, reloadKey = 3)

        assertEquals("盒宽就是口径给的那个值", 156.dp, plan.widthDp)
        // 156dp × 2.75 = 429px → 向上取到 32 的倍数 = 448（字面量锚点：钉住出货几何下的桶）
        assertEquals("解码宽度 = 显示宽度向上分桶", 448, plan.widthPx)
        assertEquals("网格档裁到格子", CoverDecode.CropTarget.GridCell, plan.cropTarget)
        assertEquals(
            "键 = 条目 id + 重取键 + 目标宽度 + 裁剪目标（口径不变）",
            CoverDecode.key("root/a", 3, 448, CoverDecode.CropTarget.GridCell),
            plan.keyOf("root/a"),
        )
    }

    @Test
    fun `列表档：行内封面列宽定出盒宽 宽度桶与裁剪目标`() {
        val plan = CoverPlan.of(CoverSizing.OwnAspect(56.dp), density = density, reloadKey = 0)

        assertEquals("盒宽 = 行内封面列宽", 56.dp, plan.widthDp)
        // 56dp × 2.75 = 154px → 160
        assertEquals("解码宽度（字面量锚点）", 160, plan.widthPx)
        assertEquals("列表档按源比例夹兜底区间", CoverDecode.CropTarget.OwnAspect, plan.cropTarget)
        assertEquals(
            CoverDecode.key("root/a", 0, 160, CoverDecode.CropTarget.OwnAspect),
            plan.keyOf("root/a"),
        )
    }

    @Test
    fun `盒宽与解码宽度同源（同一口径推两边）`() {
        // r2 b1 的守卫：盒宽（[CoverPlan.widthDp]）与解码宽度（[CoverPlan.widthPx]）必须由**同一个** [CoverSizing]
        // 推出、「过冲 < 一个桶」仍成立；档位也只由这个口径的子型定（裁剪目标与盒宽不可能是两套档）
        val widths = listOf(40f, 55f, 56f, 57f, 120f, 156f, 200f, 233f)
        val densities = listOf(1f, 1.5f, 2f, 2.75f, 3f)
        for (width in widths) {
            for (d in densities) {
                val gridSizing = CoverSizing.GridCell(width.dp)
                val listSizing = CoverSizing.OwnAspect(width.dp)
                val grid = CoverPlan.of(gridSizing, density = d, reloadKey = null)
                val list = CoverPlan.of(listSizing, density = d, reloadKey = null)

                assertEquals("格宽 $width dp：盒宽取自口径", width.dp, grid.widthDp)
                assertEquals("格宽 $width dp / 密度 $d：桶就是口径宽 × 密度", CoverDecode.targetWidthPx(width * d), grid.widthPx)
                assertEquals("网格档的裁剪目标与口径子型一致", CoverDecode.CropTarget.GridCell, grid.cropTarget)
                assertTrue("解码宽度不小于显示宽度", grid.widthPx >= width * d)
                assertTrue("过冲小于一个桶", grid.widthPx - width * d < CoverDecode.BUCKET_PX)

                assertEquals("列表档同一条分桶函数与同一个盒宽来源", width.dp, list.widthDp)
                assertEquals(CoverDecode.targetWidthPx(width * d), list.widthPx)
                assertEquals("列表档的裁剪目标与口径子型一致", CoverDecode.CropTarget.OwnAspect, list.cropTarget)
            }
        }
    }

    @Test
    fun `档位映射只此一处（coverSizingFor）`() {
        // 把 view.isGrid 判反（或让某档少走一次映射）就会让一屏的盒子与键一起错档，这里逐档钉住：
        // 网格三档一律 GridCell、列表档一律 OwnAspect，宽度原样带过去
        val width = 157.dp
        assertEquals(CoverSizing.OwnAspect(width), coverSizingFor(ViewMode.LIST, width))
        assertEquals(CoverSizing.GridCell(width), coverSizingFor(ViewMode.GRID_2, width))
        assertEquals(CoverSizing.GridCell(width), coverSizingFor(ViewMode.GRID_3, width))
        assertEquals(CoverSizing.GridCell(width), coverSizingFor(ViewMode.GRID_4, width))

        // 映射出来的口径 → 方案：裁剪目标跟着档位走（判反即红）
        assertEquals(
            CoverDecode.CropTarget.OwnAspect,
            CoverPlan.of(coverSizingFor(ViewMode.LIST, width), density = density, reloadKey = null).cropTarget,
        )
        for (grid in listOf(ViewMode.GRID_2, ViewMode.GRID_3, ViewMode.GRID_4)) {
            assertEquals(
                "网格档 $grid 的裁剪目标",
                CoverDecode.CropTarget.GridCell,
                CoverPlan.of(coverSizingFor(grid, width), density = density, reloadKey = null).cropTarget,
            )
        }
    }

    @Test
    fun `档位不同则键不同（两档不串图）`() {
        val grid = CoverPlan.of(CoverSizing.GridCell(156.dp), density = density, reloadKey = 0)
        val list = CoverPlan.of(CoverSizing.OwnAspect(156.dp), density = density, reloadKey = 0)

        assertNotEquals("裁剪目标不同 ⇒ 键必须不同", grid.keyOf("root/a"), list.keyOf("root/a"))
    }

    @Test
    fun `重取键参与键（下拉更新要重解）`() {
        val before = CoverPlan.of(CoverSizing.GridCell(156.dp), density = density, reloadKey = 0)
        val after = CoverPlan.of(CoverSizing.GridCell(156.dp), density = density, reloadKey = 1)

        assertNotEquals(
            "重取键变 ⇒ 键必须变（否则下拉更新不会重取封面）",
            before.keyOf("root/a"),
            after.keyOf("root/a"),
        )
    }

    @Test
    fun `走 uri 的封面不吃重取键（票 #53）`() {
        val uri = "content://media/external/images/1"
        val before = CoverPlan.of(CoverSizing.OwnAspect(56.dp), density = density, reloadKey = 0)
        val after = CoverPlan.of(CoverSizing.OwnAspect(56.dp), density = density, reloadKey = 1)
        val routeBefore = before.route("root/a", uri)
        val routeAfter = after.route("root/a", uri)

        assertTrue("本地图片封面走 uri 通路", !routeBefore.viaSourceBytes)
        assertEquals("取到的是系统能解的那个 uri", uri, routeBefore.uri)
        assertEquals(
            "uri 那条路的键不带重取键",
            CoverDecode.key("root/a", null, before.widthPx, CoverDecode.CropTarget.OwnAspect),
            routeBefore.uriKey,
        )
        assertEquals("下拉更新（重取键 +1）不换 uri 那条路的键", routeBefore.uriKey, routeAfter.uriKey)
        assertNotEquals("来源字节那条路仍吃重取键", routeBefore.bytesKey, routeAfter.bytesKey)
    }

    @Test
    fun `走来源字节的封面没有 uri 只有字节那条键`() {
        val plan = CoverPlan.of(CoverSizing.GridCell(156.dp), density = density, reloadKey = 7)

        for (coverUri in listOf(null, "", "smb://host/share/a.jpg", "webdav-http://host/dav/a.jpg")) {
            val route = plan.route("root/a", coverUri)
            assertTrue("来源标识串 / 空 uri ⇒ 只能走来源字节（coverUri=$coverUri）", route.viaSourceBytes)
            assertNull("没有可直解的 uri", route.uri)
            assertEquals("字节那条键 = 带重取键的解码键", plan.keyOf("root/a"), route.bytesKey)
        }
    }

    @Test
    fun `通路判据两个入口同一个答案（预取筛候选只问布尔）`() {
        // 预取筛候选走 [CoverPlan.viaSourceBytes]（不构造键）、渲染走 [CoverPlan.route]：两处必须同源，
        // 否则会分叉成「预取取了可见行不会用的那份字节」（票 #108 r3 评审 P1 的那条）
        val plan = CoverPlan.of(CoverSizing.GridCell(156.dp), density = density, reloadKey = 0)

        for (coverUri in listOf("content://media/1", "file:///sdcard/a.jpg", "smb://h/a", "webdav-http://h/a", "", null)) {
            assertEquals(
                "uri=$coverUri：筛候选的布尔与渲染用的通路判据必须同源",
                plan.route("root/a", coverUri).viaSourceBytes,
                plan.viaSourceBytes(coverUri),
            )
        }
        assertEquals("本地图片行不预取", false, plan.viaSourceBytes("content://media/1"))
        assertEquals("容器行（无 uri）要预取", true, plan.viaSourceBytes(null))
    }
}
