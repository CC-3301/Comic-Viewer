package com.cc3301.comicviewer.ui

import com.cc3301.comicviewer.core.view.CoverDecode
import kotlin.math.ceil
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 封面取图方案（票 #135，取代票 #108 r7 的「两条路径对齐」用例）。
 *
 * 改动前：可见行与预取**各算一份**解码参数，「两边逐字相等」只由本用例钉着；漂移的失败模式是**静默**的
 * （预取解好的那张可见行命不中 ⇒ 可见行又自己取一遍字节、解一遍码，预取白干还白占一份封面分区预算）。
 * 票 #135 把两条合成一处：[CoverPlan.of] 是唯一推导，可见行与预取拿的是**同一个方案实例**，相等不再是约定。
 * 本用例因此改为钉住**这一处推导的输出**——宽度桶、裁剪目标、重取键参与的方式，以及票 #53 的 uri 例外；
 * 断言口径与改动前同强（桶值直写、桶的上取整与过冲逐条验，不再靠两条路径互相比）。
 */
class CoverDecodeKeysTest {

    private val density = 2.75f

    @Test
    fun `网格档：格宽决定宽度桶与裁剪目标`() {
        // 156dp × 2.75 = 429px → 向上取到 32 的倍数 = 448
        val plan = CoverPlan.of(coverWidthDp = 156f, grid = true, density = density, reloadKey = 3)

        assertEquals("格宽决定解码宽度（向上分桶）", 448, plan.widthPx)
        assertEquals("网格档裁到格子", CoverDecode.CropTarget.GridCell, plan.cropTarget)
        assertEquals(
            "键 = 条目 id + 重取键 + 目标宽度 + 裁剪目标（口径不变）",
            CoverDecode.key("root/a", 3, 448, CoverDecode.CropTarget.GridCell),
            plan.keyOf("root/a"),
        )
    }

    @Test
    fun `列表档：行内封面列宽决定宽度桶与裁剪目标`() {
        // 56dp × 2.75 = 154px → 160
        val plan = CoverPlan.of(coverWidthDp = 56f, grid = false, density = density, reloadKey = 0)

        assertEquals("行内封面列宽决定解码宽度", 160, plan.widthPx)
        assertEquals("列表档按源比例夹兜底区间", CoverDecode.CropTarget.OwnAspect, plan.cropTarget)
        assertEquals(
            CoverDecode.key("root/a", 0, 160, CoverDecode.CropTarget.OwnAspect),
            plan.keyOf("root/a"),
        )
    }

    @Test
    fun `宽度与密度各种取值下 桶都是向上取整的 32 倍数`() {
        // 分桶边界附近最容易漂：原稿在这里比「两条路径是否相等」，现在直写桶的算式（同强、更贴身）
        val widths = listOf(40f, 55f, 56f, 57f, 120f, 156f, 200f, 233f)
        val densities = listOf(1f, 1.5f, 2f, 2.75f, 3f)
        for (width in widths) {
            for (d in densities) {
                val displayPx = width * d
                val bucket = ceil(displayPx / CoverDecode.BUCKET_PX.toDouble()).toInt() * CoverDecode.BUCKET_PX
                val grid = CoverPlan.of(width, grid = true, density = d, reloadKey = null)
                val list = CoverPlan.of(width, grid = false, density = d, reloadKey = null)

                assertEquals("格宽 $width dp / 密度 $d：桶", bucket, grid.widthPx)
                assertTrue("解码宽度不小于显示宽度", grid.widthPx >= displayPx)
                assertTrue("过冲小于一个桶", grid.widthPx - displayPx < CoverDecode.BUCKET_PX)
                assertEquals("列表档同一条分桶函数", bucket, list.widthPx)
            }
        }
    }

    @Test
    fun `档位不同则键不同（两档不串图）`() {
        val grid = CoverPlan.of(156f, grid = true, density = density, reloadKey = 0)
        val list = CoverPlan.of(156f, grid = false, density = density, reloadKey = 0)

        assertNotEquals("裁剪目标不同 ⇒ 键必须不同", grid.keyOf("root/a"), list.keyOf("root/a"))
    }

    @Test
    fun `重取键参与键（下拉更新要重解）`() {
        val before = CoverPlan.of(156f, grid = true, density = density, reloadKey = 0)
        val after = CoverPlan.of(156f, grid = true, density = density, reloadKey = 1)

        assertNotEquals(
            "重取键变 ⇒ 键必须变（否则下拉更新不会重取封面）",
            before.keyOf("root/a"),
            after.keyOf("root/a"),
        )
    }

    @Test
    fun `走 uri 的封面不吃重取键（票 #53）`() {
        val uri = "content://media/external/images/1"
        val before = CoverPlan.of(56f, grid = false, density = density, reloadKey = 0)
        val after = CoverPlan.of(56f, grid = false, density = density, reloadKey = 1)
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
        val plan = CoverPlan.of(156f, grid = true, density = density, reloadKey = 7)

        for (coverUri in listOf(null, "", "smb://host/share/a.jpg", "webdav-http://host/dav/a.jpg")) {
            val route = plan.route("root/a", coverUri)
            assertTrue("来源标识串 / 空 uri ⇒ 只能走来源字节（coverUri=$coverUri）", route.viaSourceBytes)
            assertNull("没有可直解的 uri", route.uri)
            assertEquals("字节那条键 = 带重取键的解码键", plan.keyOf("root/a"), route.bytesKey)
        }
    }
}
