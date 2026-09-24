package com.cc3301.comicviewer.ui

import androidx.compose.ui.unit.dp
import com.cc3301.comicviewer.core.view.CoverDecode
import com.cc3301.comicviewer.core.view.CoverLayout
import com.cc3301.comicviewer.core.view.ViewMode
import kotlin.math.ceil
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
 * 1. **盒宽与解码同源**：同一 [CoverSizing] 推出的盒宽、宽度桶与裁剪目标必须成一套
 *    ——「盒子按列表档画、解码按网格档解」这种改坏在这里红；
 * 2. **档位映射只一处**：[coverSizingFor] 逐档钉住（把 `view.isGrid` 判反即红）；
 * 3. **盒子几何**：两档的盒宽/盒高/是否裁剪是字面量；网格档缺可用高度**立刻报错**（不拿兜底高度画歪）；
 * 4. **位图状态的键**（[coverBitmapKey]）：只跟解码口径（uri + `widthPx` + `cropTarget` + `reloadKey`），
 *    同一桶内原始 dp 宽/密度变化**不换键**（否则重置位图、闪一帧骨架），跳桶/换档/换重取键/换 uri 必换键；
 * 5. 键的算式、重取键参与方式与票 #53 的 uri 例外。
 *
 * **期望值不自比较**（r2 b2）：键串写**字面量**、桶用**独立重算**（**Double 算术**，见 `expectedBucket`；
 * 不调生产的分桶函数）、通路判据写**字面量布尔**——拿被测的生产函数当期望值等于用例永不失败。桶的口径
 * （向上取整到 [CoverDecode.BUCKET_PX] 的倍数）在出货几何下的两个字面量锚点是 448（156dp × 2.75）与
 * 160（56dp × 2.75）。
 */
class CoverPlanTest {

    private val density = 2.75f

    /**
     * 独立重算的桶（向上取整到 [CoverDecode.BUCKET_PX] 的倍数）：**Double 算术**，不调生产的分桶函数
     * （`CoverDecode.targetWidthPx` 那一路用 Float；拿它当期望值等于用例永不可能失败）。
     * 本文件的显示宽度都为正，因此不需要生产那支非正数兜底。
     */
    private fun expectedBucket(displayPx: Float): Int =
        ceil(displayPx.toDouble() / CoverDecode.BUCKET_PX).toInt() * CoverDecode.BUCKET_PX

    /**
     * 盒子断言：宽/高按字面量比（容差 1dp 的千分之一——浮点乘法的末位与字面量可能差一位，比的是口径不是位模式）、
     * 裁剪按字面量比。期望值全是数字，不调 [CoverLayout] 的盒子函数（那等于用例永不可能失败）。
     */
    private fun assertBox(expectedWidth: Float, expectedHeight: Float, crop: Boolean, box: CoverLayout.CoverBox, message: String) {
        assertEquals("$message：盒宽", expectedWidth, box.width, 0.001f)
        assertEquals("$message：盒高", expectedHeight, box.height, 0.001f)
        assertEquals("$message：是否裁剪", crop, box.crop)
    }

    @Test
    fun `网格档：口径定出盒宽 宽度桶与裁剪目标`() {
        val plan = CoverPlan.of(CoverSizing.GridCell(156.dp), density = density, reloadKey = 3)

        assertEquals("盒宽就是口径给的那个值", 156.dp, plan.widthDp)
        // 156dp × 2.75 = 429px → 向上取到 32 的倍数 = 448（字面量锚点：钉住出货几何下的桶）
        assertEquals("解码宽度 = 显示宽度向上分桶", 448, plan.widthPx)
        assertEquals("网格档裁到格子", CoverDecode.CropTarget.GridCell, plan.cropTarget)
        assertEquals("键串（字面量：条目 id + 重取键 + 目标宽度 + 裁剪目标）", "cover@root/a@3@448@GridCell", plan.keyOf("root/a"))
    }

    @Test
    fun `列表档：行内封面列宽定出盒宽 宽度桶与裁剪目标`() {
        val plan = CoverPlan.of(CoverSizing.OwnAspect(56.dp), density = density, reloadKey = 0)

        assertEquals("盒宽 = 行内封面列宽", 56.dp, plan.widthDp)
        // 56dp × 2.75 = 154px → 160
        assertEquals("解码宽度（字面量锚点）", 160, plan.widthPx)
        assertEquals("列表档按源比例夹兜底区间", CoverDecode.CropTarget.OwnAspect, plan.cropTarget)
        assertEquals("键串（字面量）", "cover@root/a@0@160@OwnAspect", plan.keyOf("root/a"))
    }

    @Test
    fun `盒宽与解码宽度同源（同一口径推两边）`() {
        // 盒宽（[CoverPlan.widthDp]）与解码宽度（[CoverPlan.widthPx]）必须由**同一个** [CoverSizing] 推出、
        // 「过冲 < 一个桶」仍成立；档位也只由这个口径的子型定（裁剪目标与盒宽不可能是两套档）。
        // 桶的期望值独立重算（[expectedBucket]），不调生产的分桶函数
        val widths = listOf(40f, 55f, 56f, 57f, 120f, 156f, 200f, 233f)
        val densities = listOf(1f, 1.5f, 2f, 2.75f, 3f)
        for (width in widths) {
            for (d in densities) {
                val grid = CoverPlan.of(CoverSizing.GridCell(width.dp), density = d, reloadKey = null)
                val list = CoverPlan.of(CoverSizing.OwnAspect(width.dp), density = d, reloadKey = null)
                val bucket = expectedBucket(width * d)

                assertEquals("格宽 $width dp：盒宽取自口径", width.dp, grid.widthDp)
                assertEquals("格宽 $width dp / 密度 $d：桶", bucket, grid.widthPx)
                assertEquals("网格档的裁剪目标与口径子型一致", CoverDecode.CropTarget.GridCell, grid.cropTarget)
                assertTrue("解码宽度不小于显示宽度", grid.widthPx >= width * d)
                assertTrue("过冲小于一个桶", grid.widthPx - width * d < CoverDecode.BUCKET_PX)

                assertEquals("列表档同一个盒宽来源", width.dp, list.widthDp)
                assertEquals("列表档同一条分桶口径", bucket, list.widthPx)
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
    fun `盒子几何：两档的盒宽 盒高与裁剪是字面量`() {
        val listPlan = CoverPlan.of(CoverSizing.OwnAspect(56.dp), density = density, reloadKey = 0)
        val gridPlan = CoverPlan.of(CoverSizing.GridCell(156.dp), density = density, reloadKey = 0)

        // 列表档：高 = 宽 × 封面自身比例（比例无量纲），只有越界比例才裁剪
        assertBox(56f, 78.4f, crop = false, box = coverBoxOf(listPlan, 1.4f, null), message = "列表档 1.4")
        // 极端比例（0.5 < 下限 0.6）按限值盒子 + 填充裁剪
        assertBox(56f, 33.6f, crop = true, box = coverBoxOf(listPlan, 0.5f, null), message = "列表档 0.5")
        // 尚未解码 ⇒ 占位比例 1.4
        assertBox(56f, 78.4f, crop = false, box = coverBoxOf(listPlan, null, null), message = "列表档 占位")

        // 网格档：盒高只由格宽（156 × 4/3 = 208）与可用高度定，**与封面比例无关**（比例只决定裁不裁）
        assertBox(156f, 208f, crop = true, box = coverBoxOf(gridPlan, 1.4f, 400.dp), message = "网格档 1.4")
        assertBox(156f, 208f, crop = true, box = coverBoxOf(gridPlan, 0.7f, 400.dp), message = "网格档 0.7 同盒")
        // 可用高度不够 ⇒ 盒子等比收缩、宽按格比例反算（两侧留白），仍不拉伸
        assertBox(112.5f, 150f, crop = true, box = coverBoxOf(gridPlan, 1.4f, 150.dp), message = "网格档 收缩")
    }

    @Test
    fun `网格档缺可用高度会报错（不拿兜底高度画歪）`() {
        // 票 #135：可用高度是网格档盒子的**必需**布局输入，预取那一侧只推方案、不渲染（见 `CoverThumb`）。
        // 缺了就是接线错了：必须立刻抛，静默拿 0/兜底高度画出来的盒子看不出来是错的
        val gridPlan = CoverPlan.of(CoverSizing.GridCell(156.dp), density = density, reloadKey = 0)

        val thrown = runCatching { coverBoxOf(gridPlan, 1.4f, null) }.exceptionOrNull()

        assertTrue("缺可用高度必须抛（实际：$thrown）", thrown is IllegalStateException)
        assertTrue("报错要说清是网格档缺可用高度：${thrown?.message}", thrown?.message?.contains("网格档") == true)

        // 列表档不需要可用高度，同一个 null 必须照常给出盒子（不是「有高度才敢算」）
        val listPlan = CoverPlan.of(CoverSizing.OwnAspect(56.dp), density = density, reloadKey = 0)
        assertBox(56f, 78.4f, crop = false, box = coverBoxOf(listPlan, 1.4f, null), message = "列表档 无可用高度")
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

        assertEquals("重取键 0 的键串", "cover@root/a@0@448@GridCell", before.keyOf("root/a"))
        assertEquals("重取键 1 的键串", "cover@root/a@1@448@GridCell", after.keyOf("root/a"))
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
        assertEquals("uri 那条路的键不带重取键（字面量）", "cover@root/a@null@160@OwnAspect", routeBefore.uriKey)
        assertEquals("下拉更新（重取键 +1）不换 uri 那条路的键", routeBefore.uriKey, routeAfter.uriKey)
        assertEquals("来源字节那条路仍是带重取键的键（字面量）", "cover@root/a@0@160@OwnAspect", routeBefore.bytesKey)
        assertEquals("来源字节那条路换了重取键就是新键", "cover@root/a@1@160@OwnAspect", routeAfter.bytesKey)
    }

    @Test
    fun `走来源字节的封面没有 uri 只有字节那条键`() {
        val plan = CoverPlan.of(CoverSizing.GridCell(156.dp), density = density, reloadKey = 7)

        for (coverUri in listOf(null, "", "smb://host/share/a.jpg", "webdav-http://host/dav/a.jpg")) {
            val route = plan.route("root/a", coverUri)
            assertTrue("来源标识串 / 空 uri ⇒ 只能走来源字节（coverUri=$coverUri）", route.viaSourceBytes)
            assertNull("没有可直解的 uri", route.uri)
            assertEquals("字节那条键（字面量）", "cover@root/a@7@448@GridCell", route.bytesKey)
        }
    }

    @Test
    fun `通路判据两个入口同一个答案（预取筛候选只问布尔）`() {
        // 预取筛候选走 [CoverPlan.viaSourceBytes]（不构造键）、渲染走 [CoverPlan.route]：两个入口必须给出
        // **同一个**答案（否则会分叉成「预取取了可见行不会用的那份字节」，票 #108 r3 评审 P1 的那条）。
        // 期望值是**字面量布尔**，不是另一处生产调用的返回值——自比较的断言今天不可能失败
        val plan = CoverPlan.of(CoverSizing.GridCell(156.dp), density = density, reloadKey = 0)
        val expectations = listOf(
            "content://media/1" to false,
            "file:///sdcard/a.jpg" to false,
            "smb://h/a" to true,
            "webdav-http://h/a" to true,
            "" to true,
            null to true,
        )

        for ((coverUri, viaSourceBytes) in expectations) {
            assertEquals("uri=$coverUri：预取筛候选（只算布尔）", viaSourceBytes, plan.viaSourceBytes(coverUri))
            assertEquals("uri=$coverUri：渲染用的通路判据", viaSourceBytes, plan.route("root/a", coverUri).viaSourceBytes)
        }
    }

    @Test
    fun `位图键只跟解码口径：同一桶内换原始宽或密度不换键`() {
        // r1 曾把整份方案当键：同一解码桶内 sizing 的原始 dp 宽（多窗口/折叠/inset 变动）或密度一变，
        // 位图就被重置成 null、闪一帧骨架（票面要求表现零变化）。键只取**位图与解码键实际依赖的量**
        val uri = "content://media/external/images/1"
        val base = CoverPlan.of(CoverSizing.GridCell(100.dp), density = 2.75f, reloadKey = 0)
        val widerSameBucket = CoverPlan.of(CoverSizing.GridCell(101.dp), density = 2.75f, reloadKey = 0)
        val denserSameBucket = CoverPlan.of(CoverSizing.GridCell(100.dp), density = 2.8f, reloadKey = 0)

        // 先决条件：三个方案的桶确实相同（100×2.75=275、101×2.75=277.75、100×2.8=280 → 都进 288）
        assertEquals("base 桶", 288, base.widthPx)
        assertEquals("原始宽变但同桶", 288, widerSameBucket.widthPx)
        assertEquals("密度变但同桶", 288, denserSameBucket.widthPx)

        val key = coverBitmapKey(uri, base)
        assertEquals("同一桶内原始 dp 宽变（100dp → 101dp）不换键", key, coverBitmapKey(uri, widerSameBucket))
        assertEquals("同一桶内密度变（2.75 → 2.8）不换键", key, coverBitmapKey(uri, denserSameBucket))
        assertNotEquals("换了 uri 就换键", key, coverBitmapKey("content://media/external/images/2", base))
        assertEquals("键就是解码口径那四项（字面量）", listOf<Any?>(uri, 288, CoverDecode.CropTarget.GridCell, 0), key)
    }

    @Test
    fun `位图键跟解码口径：跳桶 换档 换重取键都换键`() {
        // 另一半：解码口径真变了就必须换键（否则会拿上一档/上一桶的位图当这一档用）
        val uri = "content://media/external/images/1"
        val base = CoverPlan.of(CoverSizing.GridCell(156.dp), density = 2.75f, reloadKey = 0)
        val key = coverBitmapKey(uri, base)

        assertNotEquals(
            "跳桶（156dp → 200dp：448 → 576）必换键",
            key,
            coverBitmapKey(uri, CoverPlan.of(CoverSizing.GridCell(200.dp), density = 2.75f, reloadKey = 0)),
        )
        assertNotEquals(
            "换档（同宽同桶，改成列表档）必换键",
            key,
            coverBitmapKey(uri, CoverPlan.of(CoverSizing.OwnAspect(156.dp), density = 2.75f, reloadKey = 0)),
        )
        assertNotEquals(
            "下拉更新（重取键 +1）必换键",
            key,
            coverBitmapKey(uri, CoverPlan.of(CoverSizing.GridCell(156.dp), density = 2.75f, reloadKey = 1)),
        )
    }
}
