package com.cc3301.comicviewer.core.view

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * 浏览页快速定位滑条（票 #60）的纯函数：滑条几何（长度 + 位置）、两条连续化读数与「拖动位移 → 目标落点」。
 *
 * 票面 AC 原文：「滑条几何与『拖动 → 索引』是纯函数且有单测」——本用例就是那条 AC 的落点。
 * 出现/隐藏、跟手定位、与下拉更新及条目点击的手势分层属 Compose 接线，按仓库口径（SPEC 的
 * Testing Decisions：Compose 交互不做大规模自动化）走真机验收，不在本用例内。
 *
 * 为什么两条必须**互为逆映射**：几何把「当前首条索引」映射成轨道位置，拖动把轨道位置映射回**行内位置**；
 * 只有同口径（[quickScrollBarProgress] 一处给进度、两处共用），松手后的滑条位置才与拖动时看到的一致
 * （见 [拖动与滑条位置互为逆映射] 与 [拖动与滑条位置互为逆映射 含行内比例]）。
 *
 * 进度以**行**为单位（票 #60 批次 9 r2）：网格档一档 [gridGeometry] 的列数个格子，按条目算会让行内速度只有真实的
 * 一半、每跨一行边界补跳 1/总格数（真机「网格档滚动时滑条一格一格跳」）；列表档一行 = 一条（`itemsPerRow = 1`）
 * ⇒ 下面这些列表档用例的算式与改动前逐像素一致。
 *
 * **票 #148 ① 起分母换成「可滚动行数」**（= 总行数 − 可见行数，下限 1）：旧式拿**总行数**当分母时到底进度恒 < 1
 * （维护者 2026-09-30：「最底部滑动条没在最下方」，十几条的目录里停在轨道约 77%）。到底时首个可见行恰是
 * 「总行数 − 可见行数」那一行 ⇒ 分子上界就是可滚动行数，分母取它才能贴住轨道两端；拖动反解用**同一个分母函数**
 * （`core/view/QuickScrollBar.kt` 的 `scrollableRowCount`）⇒ 两条路仍互逆。
 * 可见行数是浮点（按露出比例求和 ÷ 每行条目数）⇒ 每个用到分母的算例都显式喂同一个可见条目数
 * （[listVisibleItems] / [gridGeometry]）。
 *
 * **票 #149 起拖动反解保留行内偏移**（`行内位置 = 行索引 + 行内比例 × 行距`）：旧式 `roundToInt` 取整到整行时
 * 网格档一次跳一整行（真机「一排排封面滚」），而几何给出的位置本来就是连续的。行内偏移的同一量是 [rowExtentPx]
 *（与 [quickScrollBarItemScrollFraction] 的条目高度同一份）——两处各算一次会在比例尺上错开。
 *
 * 算例参数的来路：1000 条 = 维护者反馈的目录规模，一屏 10 条 = 竖屏列表档的可见条目数，
 * 轨道 2000px / 最短 24px = 常见手机（2x 密度）的量级。
 */
class QuickScrollBarTest {

    /** 轨道长度（px，两档共用一条竖直轨道） */
    private val trackPx = 2000f

    /** 滑条最短长度（px）：条目极多时也要抓得住 */
    private val minThumbPx = 24f

    /**
     * 一屏可见条目数（列表档算例：1000 条一屏 10 条）。票 #148 ① 起它同时是进度分母的减数
     * ⇒ 几何、进度、拖动反解三处都喂**同一个值**。
     */
    private val listVisibleItems = 10f

    private fun geometry(
        index: Int,
        total: Int = 1000,
        visible: Float = listVisibleItems,
        scrollFraction: Float = 0f,
    ) = quickScrollBarGeometry(
        totalItems = total,
        visibleItems = visible,
        firstVisibleItemIndex = index,
        firstVisibleItemScrollFraction = scrollFraction,
        trackLengthPx = trackPx,
        minThumbLengthPx = minThumbPx,
        itemsPerRow = 1,
    )

    /** 上例几何的滑条长度（拖动的抓手点按「滑条中部」算，见 [quickScrollBarTargetForDrag]） */
    private val thumbPx: Float = requireNotNull(geometry(0)).thumbLengthPx

    /**
     * 行距（px）：拖动落点的行内偏移按它折算，与 [quickScrollBarItemScrollFraction] 那个比例的分母、
     * 几何侧的条内高度是**同一个量**（票 #149）。100px = 真机一行的量级。
     */
    private val rowExtentPx = 100

    // --- 不显示（返回 null）的三个前提 ---

    @Test
    fun `目录不足一屏时不给几何`() {
        // 100 条一屏正好装下 → 没有可快速定位的余量
        assertNull(geometry(0, total = 100, visible = 100f))
        // 恰好一条不差
        assertNull(geometry(0, total = 100, visible = 101f))
        // 探测期可见条目数尚未上报（0）但列表比一屏短
        assertNull(geometry(0, total = 3, visible = 3f))
    }

    @Test
    fun `条目不足两条时不给几何`() {
        // 一条（或空列表）没有「移到别处」的语义
        assertNull(geometry(0, total = 1, visible = 1f))
        assertNull(geometry(0, total = 0, visible = 0f))
    }

    @Test
    fun `轨道还没量到长度时不给几何`() {
        // 首帧轨道尚未上报尺寸（0）：不画滑条，避免按 0 长度算出一根贴边的怪条
        assertNull(
            quickScrollBarGeometry(
                totalItems = 1000,
                visibleItems = 10f,
                firstVisibleItemIndex = 0,
                firstVisibleItemScrollFraction = 0f,
                trackLengthPx = 0f,
                minThumbLengthPx = minThumbPx,
                itemsPerRow = 1,
            ),
        )
    }

    // --- 几何：长度与位置反映「视口 / 整份列表」 ---

    @Test
    fun `滑条长度与位置反映视口占整份列表的比例`() {
        // 100 条、一屏 25 条 → 滑条长 1/4 轨道；首条时贴在顶端
        val top = requireNotNull(geometry(0, total = 100, visible = 25f))
        assertEquals(trackPx * 0.25f, top.thumbLengthPx, 0.01f)
        assertEquals(0f, top.thumbOffsetPx, 0.01f)
        // 末条时贴在底端：偏移 = 轨道 − 滑条长（行程跑满）
        val bottom = requireNotNull(geometry(99, total = 100, visible = 25f, scrollFraction = 1f))
        assertEquals(trackPx - bottom.thumbLengthPx, bottom.thumbOffsetPx, 0.01f)
    }

    @Test
    fun `滑条位置随首条索引线性推进`() {
        // 中期位置：进度口径（票 #148 ① 起）= 索引 ÷ **可滚动行数**；100 条一屏 25 条 ⇒ 分母 100 − 25 = 75
        val middle = requireNotNull(geometry(49, total = 100, visible = 25f))
        val travel = trackPx - middle.thumbLengthPx
        assertEquals(travel * 49f / 75f, middle.thumbOffsetPx, 0.01f)
    }

    // --- 票 #148 ①：到底贴轨道底（旧式分母是总行数 ⇒ 永远差一截，见文件头） ---

    /** 到底（首个可见条目 = 最后一行）时滑条底部必须落在轨道底部：偏移跑满整个行程 */
    private fun assertThumbAtTrackBottom(bar: QuickScrollBarGeometry, case: String) {
        assertEquals(
            "$case：到底时滑条底部应贴轨道底部",
            trackPx - bar.thumbLengthPx,
            bar.thumbOffsetPx,
            0.01f,
        )
    }

    /**
     * 票 #148 ① 列表档：**滚到底时滑条底部 == 轨道底部**。
     *
     * 到底时首个可见条目就是「总条目数 − 可见条目数」那一条、屏内比例为 0 ⇒ 分子 = 可滚动行数、分母同值
     * ⇒ 进度 = 1。**短列表（刚过一屏）也要成立**——维护者看的正是十几条的目录。
     *
     * 判别力（**旧式分母下本用例先红**）：分母取总行数时 12 条那档停在行程的 2/12（底部只到 1/6）、
     * 1000 条那档停在 990/1000。
     */
    @Test
    fun `列表档滚到底时滑条底部贴轨道底部`() {
        for (total in listOf(12, 100, 1000)) {
            val lastReachable = total - listVisibleItems.toInt()
            assertThumbAtTrackBottom(
                bar = requireNotNull(geometry(index = lastReachable, total = total)),
                case = "$total 条、一屏 $listVisibleItems 条（首个可见条目 $lastReachable）",
            )
        }
    }

    /**
     * 票 #148 ① 网格档：同上，两档都要贴底（网格档的可见行数 = 可见格子数 ÷ 列数，票面点名的「同一个量」）。
     *
     * 短列表那档就是维护者反馈的量级：2 列、只有十几行、一屏看到五行 ⇒ 旧式停在 3/8 = 37.5% 行程
     * （这里取 30 条 / 一屏 5 行，几何能给出滑条的下限条件：可见条目数 < 总数）。
     */
    @Test
    fun `网格档滚到底时滑条底部贴轨道底部`() {
        // 短列表：2 列 30 条（15 行）、一屏 5 行（可见 10 格）⇒ 到底 = 第 10 行（首个可见条目 20）
        assertThumbAtTrackBottom(
            bar = requireNotNull(
                quickScrollBarGeometry(
                    totalItems = 30,
                    visibleItems = 10f,
                    firstVisibleItemIndex = 20,
                    firstVisibleItemScrollFraction = 0f,
                    trackLengthPx = trackPx,
                    minThumbLengthPx = minThumbPx,
                    itemsPerRow = 2,
                ),
            ),
            case = "30 条 2 列、一屏 5 行（首个可见条目 20）",
        )
        // 1000 条：2 列 500 行、一屏 10 行 ⇒ 到底 = 第 490 行（首个可见条目 980）
        val lastRow = gridTotalRows - gridVisibleRows.toInt()
        assertThumbAtTrackBottom(
            bar = gridGeometry(index = lastRow * 2, total = 2 * gridTotalRows, columns = 2),
            case = "1000 条 2 列、一屏 $gridVisibleRows 行（首个可见条目 ${lastRow * 2}）",
        )
    }

    @Test
    fun `滑条长度有下限 条目再多也抓得住`() {
        // 1000 条、一屏 10 条 → 比例算出 20px < 下限 24px → 取 24px
        assertEquals(minThumbPx, thumbPx, 0.01f)
    }

    @Test
    fun `轨道短于滑条下限时不越界`() {
        val short = requireNotNull(
            quickScrollBarGeometry(
                totalItems = 1000,
                visibleItems = 10f,
                firstVisibleItemIndex = 500,
                firstVisibleItemScrollFraction = 0f,
                trackLengthPx = 10f,
                minThumbLengthPx = minThumbPx,
                itemsPerRow = 1,
            ),
        )
        // 滑条不超出轨道、偏移不越界（行程为 0）
        assertEquals(10f, short.thumbLengthPx, 0.01f)
        assertEquals(0f, short.thumbOffsetPx, 0.01f)
    }

    // --- r6 位置连续化：同一索引内随屏内比例推进（真机「移动不连贯」的修法） ---

    /**
     * r6 返工口径①：进度 = `(首条索引 + 屏内已滚过比例) / 可滚动行数`（票 #148 ① 换的分母）。
     *
     * 判别力：退回「只吃整数索引」（屏内比例不参与）时，
     * 取样 0 → 半高 → 满高 三次算出的进度会**相等**，本条与下一条都变红。
     */
    @Test
    fun `同一索引内屏内比例推进时进度严格单调递增`() {
        val total = 1000
        val scrollableRows = total - listVisibleItems
        val halfItem = 0.5f
        val starts = quickScrollBarProgress(
            index = 500,
            scrollFraction = 0f,
            totalItems = total,
            itemsPerRow = 1,
            visibleItems = listVisibleItems,
        )
        val middle = quickScrollBarProgress(
            index = 500,
            scrollFraction = halfItem,
            totalItems = total,
            itemsPerRow = 1,
            visibleItems = listVisibleItems,
        )
        val ends = quickScrollBarProgress(
            index = 500,
            scrollFraction = 1f,
            totalItems = total,
            itemsPerRow = 1,
            visibleItems = listVisibleItems,
        )

        assertTrue("0 → 半高 应递增：$starts → $middle", middle > starts)
        assertTrue("半高 → 满高 应递增：$middle → $ends", ends > middle)
        // 相邻差值 = 半高 ÷ 可滚动行数（浮点容差）
        assertEquals(halfItem / scrollableRows, middle - starts, 1e-6f)
        assertEquals(halfItem / scrollableRows, ends - middle, 1e-6f)
        // 进度 × 可滚动行数 = 索引 + 屏内比例（拖动的逆映射与几何同口径）
        assertEquals(500.5f, middle * scrollableRows, 1e-3f)
    }

    /**
     * r6 证据口径：同一次滚动里两次相邻取样的 `progress` **不再相等**（整数索引时它们必然相等）。
     * 取样步长取 1/16 个条目高——真机上一帧的位移量级。
     */
    @Test
    fun `相邻两帧取样在同一个首条索引内也得到不同的进度`() {
        val total = 1000
        val samples = listOf(0f, 1f / 16f, 2f / 16f, 3f / 16f)
            .map {
                quickScrollBarProgress(
                    index = 640,
                    scrollFraction = it,
                    totalItems = total,
                    itemsPerRow = 1,
                    visibleItems = listVisibleItems,
                )
            }
        samples.zipWithNext { previous, next ->
            assertTrue("相邻两帧应给出不同进度：$previous → $next", next > previous)
        }
        assertEquals("四次取样应互不相等", 4, samples.toSet().size)
    }

    /** 几何的偏移也随屏内比例连续推进（不只是纯函数层面的进度） */
    @Test
    fun `滑条偏移随屏内比例连续推进 相邻取样不再相等`() {
        val offsets = listOf(0f, 0.5f, 1f).map { fraction ->
            requireNotNull(geometry(500, scrollFraction = fraction)).thumbOffsetPx
        }
        assertTrue("半高应比贴顶更靠下：${offsets[0]} → ${offsets[1]}", offsets[1] > offsets[0])
        assertTrue("满高应比半高更靠下：${offsets[1]} → ${offsets[2]}", offsets[2] > offsets[1])
        val travel = trackPx - requireNotNull(geometry(500)).thumbLengthPx
        // 票 #148 ①：分母 = 可滚动行数（1000 − 10），不是总条目数
        assertEquals(travel * 0.5f / (1000f - listVisibleItems), offsets[1] - offsets[0], 0.01f)
    }

    @Test
    fun `屏内比例与索引越界都被夹住 不产生越界进度`() {
        val total = 1000
        assertProgress(0f, index = -5, fraction = 0f, total = total)
        assertProgress(0f, index = 0, fraction = -1f, total = total)
        assertProgress(1f, index = 999, fraction = 2f, total = total)
        // 条目不足两条：没有可推进的区间
        assertProgress(0f, index = 0, fraction = 0.5f, total = 1)
        assertProgress(0f, index = 0, fraction = 0.5f, total = 0)
    }

    /** 列表档算例下的进度断言（[listVisibleItems] 一屏） */
    private fun assertProgress(expected: Float, index: Int, fraction: Float, total: Int) {
        assertEquals(
            expected,
            quickScrollBarProgress(
                index = index,
                scrollFraction = fraction,
                totalItems = total,
                itemsPerRow = 1,
                visibleItems = listVisibleItems,
            ),
            1e-6f,
        )
    }

    // --- r6 长度钉稳：连续可见条目数（真机「胶囊长度在滚动时抖」的修法） ---

    /** 构造「视口高 1000px、条目高 100px、无间距、已滚过 [scrollOffsetPx]」时的各条目露出比例 */
    private fun visibleFractionsAt(scrollOffsetPx: Int, viewportPx: Int = 1000, itemPx: Int = 100): List<Float> {
        val firstIndex = scrollOffsetPx / itemPx
        val lastIndex = (scrollOffsetPx + viewportPx) / itemPx
        return (firstIndex..lastIndex).map { index ->
            quickScrollBarItemVisibleFraction(
                itemOffsetPx = (index * itemPx - scrollOffsetPx).toFloat(),
                itemExtentPx = itemPx.toFloat(),
                viewportStartPx = 0f,
                viewportEndPx = viewportPx.toFloat(),
            )
        }
    }

    /**
     * r6 返工口径②：连续可见条目数 = 各条目露出比例之和。**判别力**：整数计数（`visibleItemsInfo.size`）
     * 在滚动中会在 10 与 11 之间跳（露头的那一格算一整个），长度因此每格抖；本函数对同一屏恒为 10。
     */
    @Test
    fun `连续可见条目数在滚动中恒定 不随条目边界跨越而跳`() {
        val expected = 10f // 视口 1000px ÷ 条目高 100px
        var sawIntegerCountJump = false
        for (scrollOffsetPx in 0 until 100 step 1) {
            val fractions = visibleFractionsAt(scrollOffsetPx)
            assertEquals(
                "已滚 $scrollOffsetPx px 时的连续可见条目数",
                expected,
                quickScrollBarVisibleItems(fractions),
                1e-4f,
            )
            if (fractions.count { it > 0f } != expected.toInt()) sawIntegerCountJump = true
        }
        assertTrue("整数计数在滚动中确实会 ±1（这条用例的存在理由）", sawIntegerCountJump)
    }

    @Test
    fun `单个条目的露出比例按视口裁切`() {
        // 完整可见
        assertEquals(1f, quickScrollBarItemVisibleFraction(0f, 100f, 0f, 1000f), 1e-6f)
        // 上半被滚出视口：露一半
        assertEquals(0.5f, quickScrollBarItemVisibleFraction(-50f, 100f, 0f, 1000f), 1e-6f)
        // 只露头：底部裁切
        assertEquals(0.25f, quickScrollBarItemVisibleFraction(975f, 100f, 0f, 1000f), 1e-6f)
        // 完全在视口外
        assertEquals(0f, quickScrollBarItemVisibleFraction(1200f, 100f, 0f, 1000f), 1e-6f)
        assertEquals(0f, quickScrollBarItemVisibleFraction(-200f, 100f, 0f, 1000f), 1e-6f)
        // 首帧未布局（条目高 0）：不除零
        assertEquals(0f, quickScrollBarItemVisibleFraction(0f, 0f, 0f, 1000f), 1e-6f)
    }

    @Test
    fun `条目高为零或取不到时屏内比例退回 0`() {
        assertEquals(0.5f, quickScrollBarItemScrollFraction(50, 100), 1e-6f)
        assertEquals(0f, quickScrollBarItemScrollFraction(50, 0), 1e-6f)
        assertEquals(0f, quickScrollBarItemScrollFraction(50, -100), 1e-6f)
        // 越界夹在 [0, 1]
        assertEquals(1f, quickScrollBarItemScrollFraction(200, 100), 1e-6f)
    }

    // --- 拖动 → 目标落点（行首条目索引 + 行内偏移） ---

    @Test
    fun `拖到轨道顶端 中点 底端 分别定位到首行 中段行 末行`() {
        assertEquals(0, dragIndex(0f))
        // AC：1000+ 条目目录里拖到轨道中点应落在「可滚动范围的中点」附近（±2 行；票 #148 ① 起 = 990 行的一半 495）
        val middle = dragIndex(trackPx / 2f)
        assertTrue("中点应约第 495 行（0-based），实得 $middle", abs(middle - 495) <= 2)
        // AC：拖到底端应落在列表底——一屏 10 条时首个可见条目最多是 990（其后 10 条仍在屏内），不是 999
        assertEquals(990, dragIndex(trackPx))
    }

    /** 拖动反解（列表档算例：1000 条一屏 [listVisibleItems] 条、滑条 [thumbPx]、行距 [rowExtentPx]） */
    private fun dragTarget(
        positionPx: Float,
        total: Int = 1000,
        thumbLengthPx: Float = thumbPx,
        itemsPerRow: Int = 1,
        visibleItems: Float = listVisibleItems,
        rowExtentPx: Int = this.rowExtentPx,
    ) = quickScrollBarTargetForDrag(
        positionPx = positionPx,
        totalItems = total,
        trackLengthPx = trackPx,
        thumbLengthPx = thumbLengthPx,
        itemsPerRow = itemsPerRow,
        visibleItems = visibleItems,
        rowExtentPx = rowExtentPx,
    )

    private fun dragIndex(
        positionPx: Float,
        total: Int = 1000,
        thumbLengthPx: Float = thumbPx,
        itemsPerRow: Int = 1,
        visibleItems: Float = listVisibleItems,
        rowExtentPx: Int = this.rowExtentPx,
    ) = dragTarget(positionPx, total, thumbLengthPx, itemsPerRow, visibleItems, rowExtentPx).index

    /** 拖动落点的**行内偏移**（px，票 #149） */
    private fun dragOffset(
        positionPx: Float,
        total: Int = 1000,
        thumbLengthPx: Float = thumbPx,
        itemsPerRow: Int = 1,
        visibleItems: Float = listVisibleItems,
        rowExtentPx: Int = this.rowExtentPx,
    ) = dragTarget(positionPx, total, thumbLengthPx, itemsPerRow, visibleItems, rowExtentPx).rowOffsetPx

    /**
     * 「行内位置 → 抓手点」的反查（只供用例构造取样点）：把反解的算式倒过来解出抓手点的 y。
     * 一个具体 y 对应「第几行、行内多少」在脑内算不动，因此行内偏移的用例都从 [rowPosition]
     *（= 行索引 + 行内比例，与 [quickScrollBarProgress] 的分子同义）出发。
     */
    private fun dragPosition(
        rowPosition: Float,
        total: Int = 1000,
        thumbLengthPx: Float = thumbPx,
        itemsPerRow: Int = 1,
        visibleItems: Float = listVisibleItems,
    ): Float {
        // 分母调生产的同一个函数（不自抄一份算式）：口径改一处时本文件跟着变
        val scrollableRows = scrollableRowCount(total, itemsPerRow, visibleItems)
        return thumbLengthPx / 2f + rowPosition / scrollableRows * (trackPx - thumbLengthPx)
    }

    /**
     * 互逆的使用范围是**可达范围**（票 #148 ①）：首个可见条目只能是 `[0, 总条目数 − 可见条目数]` 里的一个，
     * 超出那一段（如 999）的滑条位置与 990 重合（进度都夹到 1）⇒ 反解回到 990。
     *
     * 票 #149 起反解**保留行内比例**：几何的分子是「行索引 + 行内比例」，拖回同一位置也解回同一个值。
     */
    @Test
    fun `拖动与滑条位置互为逆映射`() {
        // 拿起滑条中部拖到某个索引的位置，应正好定位回那个索引（行内）
        for (index in listOf(0, 1, 250, 499, 500, 750, 989, 990)) {
            val bar = requireNotNull(geometry(index))
            val grabbed = bar.thumbOffsetPx + bar.thumbLengthPx / 2f
            assertEquals(
                "索引 $index 的往返定位",
                index,
                dragIndex(grabbed, thumbLengthPx = bar.thumbLengthPx),
            )
        }
        // 越出可达范围：位置与 990 重合（同行程末端），反解也回到 990
        val bottom = requireNotNull(geometry(990))
        assertEquals(
            990,
            dragIndex(bottom.thumbOffsetPx + bottom.thumbLengthPx / 2f, thumbLengthPx = bottom.thumbLengthPx),
        )
    }

    /**
     * 票 #149 的另一半：**行内比例也解回来**（旧口径只对到整行，行内比例被丢掉）。
     *
     * 判别力：把反解退回 `roundToInt`（只给行首、偏移恒 0）时，下面每条的行内偏移断言都期望非 0 ⇒ 本条红。
     */
    @Test
    fun `拖动与滑条位置互为逆映射 含行内比例`() {
        // 索引 1 是**进位那一支**的客户：几何回来时行内位置是 0.9999996 行，不进位就给出「第 0 行 + 偏移 = 整行」
        for ((index, fraction) in listOf(0 to 0f, 1 to 0f, 250 to 0.25f, 989 to 0.75f)) {
            val bar = requireNotNull(geometry(index, scrollFraction = fraction))
            val target = dragTarget(bar.thumbOffsetPx + bar.thumbLengthPx / 2f, thumbLengthPx = bar.thumbLengthPx)
            assertEquals("索引 $index 的往返定行", index, target.index)
            assertEquals(
                "索引 $index、行内比例 $fraction 的往返偏移",
                (fraction * rowExtentPx).roundToInt(),
                target.rowOffsetPx,
            )
            assertTrue(
                "行内偏移恒小于行距（取整后正好凑满一行时进位到下一行的行首）",
                target.rowOffsetPx < rowExtentPx,
            )
        }
    }

    /**
     * 票 #149 拍板 A1：落点 = **行 + 行内偏移**（不再取整到整行）。
     *
     * 判别力：把落点算式退回旧口径（`roundToInt` 取整到整行、偏移恒 0）时，同一行内三次取样给出的偏移
     * 都是 0 ⇒ 本条红（票 #149 证据）。
     */
    @Test
    fun `同一行内拖动 落点带行内偏移且随位移递增`() {
        // 行内位置 300.25 / 300.50 / 300.75：同一行（第 300 行）内的三档
        val samples = listOf(0.25f, 0.5f, 0.75f).map { fraction ->
            dragTarget(dragPosition(rowPosition = 300f + fraction))
        }
        assertEquals("三次取样都落在第 300 行", listOf(300, 300, 300), samples.map { it.index })
        assertEquals(
            "行内偏移随行内比例推进（行距 $rowExtentPx px）",
            listOf(25, 50, 75),
            samples.map { it.rowOffsetPx },
        )
        // 真机一帧的位移量级：1px 的拖动也要改变落点（旧口径下跳过半行才动）
        assertNotEquals(
            "1px 的拖动要给出不同的落点",
            dragOffset(dragPosition(rowPosition = 500f)),
            dragOffset(dragPosition(rowPosition = 500f) + 1f),
        )
    }

    /**
     * 票 #149 验收①点的是**网格档（≥2 列）**：2 列与 4 列上各两次小于一行的取样都要给出不同的行内偏移
     *（旧口径两次都是 0）。
     */
    @Test
    fun `网格档同一行内拖动 两次取样给出不同的行内偏移`() {
        for (columns in listOf(2, 4)) {
            val total = columns * gridTotalRows
            val row = 100
            val index = row * columns
            val thumb = gridGeometry(index = index, total = total, columns = columns).thumbLengthPx
            val target = { rowPosition: Float ->
                dragTarget(
                    dragPosition(
                        rowPosition = rowPosition,
                        total = total,
                        thumbLengthPx = thumb,
                        itemsPerRow = columns,
                        visibleItems = gridVisibleRows * columns,
                    ),
                    total = total,
                    thumbLengthPx = thumb,
                    itemsPerRow = columns,
                    visibleItems = gridVisibleRows * columns,
                )
            }
            val first = target(row + 0.25f)
            val second = target(row + 0.5f)
            assertEquals("$columns 列：两次取样都在第 $row 行的首条", listOf(index, index), listOf(first.index, second.index))
            assertEquals("$columns 列：行内偏移不同", listOf(25, 50), listOf(first.rowOffsetPx, second.rowOffsetPx))
        }
    }

    /** 整行（行内比例 0）落点不再多滚：偏移 0，该行上沿停在落位处 */
    @Test
    fun `整行落点不带行内偏移`() {
        assertEquals(300, dragIndex(dragPosition(rowPosition = 300f)))
        assertEquals(0, dragOffset(dragPosition(rowPosition = 300f)))
    }

    /** 行距取不到（0 = 首帧未布局）时行内偏移退回 0：不除零、也不产生怪偏移 */
    @Test
    fun `行距为零时行内偏移退回 0`() {
        assertEquals(300, dragIndex(dragPosition(rowPosition = 300.5f), rowExtentPx = 0))
        assertEquals(0, dragOffset(dragPosition(rowPosition = 300.5f), rowExtentPx = 0))
    }

    @Test
    fun `拖动超出轨道两端被夹在本份列表的可达范围内`() {
        // 手指滑出轨道上端/下端（含负值）都停在首行/末行，不产生越界索引；下端 = 列表底（990）
        assertEquals(0, dragIndex(-500f))
        assertEquals(0, dragIndex(0f))
        assertEquals(990, dragIndex(trackPx * 2f))
        // 两端都是整行落点（进度被夹到 0 / 1 ⇒ 行内位置正好落在行边界）
        assertEquals(0, dragOffset(-500f))
        assertEquals(0, dragOffset(trackPx * 2f))
    }

    @Test
    fun `条目极多时相邻索引仍能被区分`() {
        // 10000 条：滑条行程不变，一个条目 ≈ 0.2px 仍单调（拖动是连续的，不要求一格一索引）；
        // 中点 = 可滚动行数（9990）的一半
        val first = dragIndex(trackPx / 2f, total = 10_000)
        val second = dragIndex(trackPx / 2f + 1f, total = 10_000)
        assertTrue("越往下拖索引不应回退：$first → $second", second >= first)
        assertEquals(4995, first)
    }

    @Test
    fun `拖动为零长度行程时不崩`() {
        // 轨道与滑条等长（行程 0）：没有可拖的余量，退回首行
        assertEquals(0, dragIndex(500f, thumbLengthPx = trackPx))
        assertEquals(0, dragOffset(500f, thumbLengthPx = trackPx))
        assertEquals(0, dragIndex(500f, total = 1))
    }

    /**
     * 短列表（十几条的目录，票 #149 反馈「一目了然能看出没到底」那种规模）也要成立：可滚动行数只有几行时
     * 行内偏移照旧、拖到底落在**末行**（不是总行数 − 1 那一行之外）。
     */
    @Test
    fun `短列表里拖动落点也带行内偏移 且拖到底落在末行`() {
        // 13 条 2 列 = 7 行、一屏 5 条 = 2.5 行 ⇒ 可滚动 4.5 行；滑条长度按同一份几何算
        val total = 13
        val visible = 5f
        val columns = 2
        val shortThumb = requireNotNull(
            quickScrollBarGeometry(
                totalItems = total,
                visibleItems = visible,
                firstVisibleItemIndex = 0,
                firstVisibleItemScrollFraction = 0f,
                trackLengthPx = trackPx,
                minThumbLengthPx = minThumbPx,
                itemsPerRow = columns,
            ),
        ).thumbLengthPx
        val position = { rowPosition: Float ->
            dragPosition(
                rowPosition = rowPosition,
                total = total,
                thumbLengthPx = shortThumb,
                itemsPerRow = columns,
                visibleItems = visible,
            )
        }
        val target = { rowPosition: Float ->
            dragTarget(
                position(rowPosition),
                total = total,
                thumbLengthPx = shortThumb,
                itemsPerRow = columns,
                visibleItems = visible,
            )
        }
        assertEquals("第 4 行的首条（2 列）", 8, target(4.25f).index)
        assertEquals("行内比例 0.25 ⇒ 行距的 1/4", 25, target(4.25f).rowOffsetPx)
        assertEquals("拖到底（可滚动 4.5 行）= 第 4 行 + 半行，不再往后的第 6 行", 8, target(4.5f).index)
        assertEquals("行内比例 0.5 ⇒ 行距的一半", 50, target(4.5f).rowOffsetPx)
    }

    // --- 生产下限 64dp（票 #60 追加口径 AC8/AC9；2026-09-21 真机反馈「滑条太短、不容易碰到」） ---

    /** 生产下限 64dp 换算到本用例的 2x 密度口径 = 128px（`ui/QuickScrollBar.kt` 的 `QUICK_SCROLL_BAR_MIN_LENGTH`） */
    private val productionMinThumbPx = 128f

    @Test
    fun `1000 条目的目录里长度就取下限 不再随条目数变短`() {
        // AC8：1000 条 / 一屏 10 条 → 比例算出 20px < 下限 128px（64dp）→ 取 128px；
        // 旧下限 24px（12dp）正是「太短、不容易碰到」的根因；条目再多也是同一根长度。
        for (total in listOf(1000, 5000, 10_000)) {
            val bar = requireNotNull(
                quickScrollBarGeometry(
                    totalItems = total,
                    visibleItems = 10f,
                    firstVisibleItemIndex = 0,
                    firstVisibleItemScrollFraction = 0f,
                    trackLengthPx = trackPx,
                    minThumbLengthPx = productionMinThumbPx,
                    itemsPerRow = 1,
                ),
            )
            assertEquals("$total 条的滑条长度", productionMinThumbPx, bar.thumbLengthPx, 0.01f)
        }
    }

    @Test
    fun `比例长度大于下限时仍按比例 不被抬高`() {
        // AC9：100 条 / 一屏 25 条 / 轨道 2000px → 比例长度 500px > 下限 128px → 仍是 500px（与改动前一致）
        val bar = requireNotNull(
            quickScrollBarGeometry(
                totalItems = 100,
                visibleItems = 25f,
                firstVisibleItemIndex = 40,
                firstVisibleItemScrollFraction = 0f,
                trackLengthPx = trackPx,
                minThumbLengthPx = productionMinThumbPx,
                itemsPerRow = 1,
            ),
        )
        assertEquals(trackPx * 0.25f, bar.thumbLengthPx, 0.01f)
    }

    // --- 批次 9 r2：进度按**行**算（真机「网格档滚动时滑条一格一格跳」的修法） ---

    /** 抽样网格的行数（滚动序列覆盖 490 行 × 每行 16 步） */
    private val gridTotalRows = 500

    /** 一屏 10 行（与上面 1000 条 / 一屏 10 条的算例同量级） */
    private val gridVisibleRows = 10f

    /** 每行推进 16 步：真机一帧的位移量级 */
    private val gridStepsPerRow = 16

    /**
     * 网格档的几何：一档 [columns] 个格子、一屏 [gridVisibleRows] 行（可见格子数 = 行数 × 列数）。
     * 生产里 `LazyGridState.firstVisibleItemIndex` 给的是**首个可见行的首个格子**索引（2 列时 0 → 2 → 4…），
     * 行内比例由 `firstVisibleItemScrollOffset / 格子高` 得到，本函数按同一口径喂进来。
     */
    private fun gridGeometry(
        index: Int,
        total: Int,
        columns: Int,
        scrollFraction: Float = 0f,
    ) = requireNotNull(
        quickScrollBarGeometry(
            totalItems = total,
            visibleItems = gridVisibleRows * columns,
            firstVisibleItemIndex = index,
            firstVisibleItemScrollFraction = scrollFraction,
            trackLengthPx = trackPx,
            minThumbLengthPx = minThumbPx,
            itemsPerRow = columns,
        ),
    )

    /** 网格档滚动一遍（[gridTotalRows] 行、每行 [gridStepsPerRow] 步）时每步的滑条偏移 */
    private fun gridScanOffsets(columns: Int): List<Float> {
        val lastRow = gridTotalRows - gridVisibleRows.toInt()
        return (0..lastRow * gridStepsPerRow).map { step ->
            val row = step / gridStepsPerRow
            gridGeometry(
                index = row * columns,
                total = columns * gridTotalRows,
                columns = columns,
                scrollFraction = (step % gridStepsPerRow).toFloat() / gridStepsPerRow,
            ).thumbOffsetPx
        }
    }

    /**
     * 真机反馈（2026-09-22）「网格档滚动时滑条一格一格跳」的**判据**：把整段滚动序列喂进几何，
     * 每步位移都应与「行程 ÷ 可滚动行数 ÷ 每行步数」相等（票 #148 ① 起分母是**可滚动行数**
     * = 500 − 10 = 490，不是总行数）——行内与跨行**同一个速度**、跨行边界不额外跳。
     *
     * 判别力：按条目算（`(首条索引 + 行内比例) / 总条目数`）时，2 列下一行的两条只贡献 1 格的比例，
     * 行内速度只有真实的一半、每跨一行边界补跳 1/总格数 ⇒ 本条在跨行那一步变红。
     */
    @Test
    fun `网格档滚动一遍 每步位移平滑 跨行不跳格`() {
        val offsets = gridScanOffsets(columns = 2)
        val steps = offsets.zipWithNext { previous, next -> next - previous }
        assertTrue("滑条只应向前推进", steps.all { it > 0f })
        val travel = trackPx - gridGeometry(index = 0, total = 2 * gridTotalRows, columns = 2).thumbLengthPx
        val perStep = travel / (gridTotalRows - gridVisibleRows) / gridStepsPerRow
        steps.forEachIndexed { step, delta ->
            assertEquals("第 $step 步的位移", perStep, delta, 0.01f)
        }
    }

    /**
     * 同一条序列换成「行」口径：2 列 1000 条（500 行）× 一屏 10 行，应与列表档 500 条 × 一屏 10 条
     * **逐值相等**（票面 AC：行内位移速度 = 列表档同条目数下的速度，两档一屏都是 10 行）。
     */
    @Test
    fun `网格档两列的行内速度与同条目数的列表档一致`() {
        val grid = gridScanOffsets(columns = 2)
        val list = gridScanOffsets(columns = 1)
        grid.zip(list).forEachIndexed { step, (gridOffset, listOffset) ->
            assertEquals("第 $step 步", listOffset, gridOffset, 0.01f)
        }
    }

    /** 每行条目数非法（0 / 负数，比如布局未就绪）时当列表档算：不除零、不产生越界行号 */
    @Test
    fun `每行条目数非正时当列表档算 不除零`() {
        val list = quickScrollBarProgress(
            index = 50,
            scrollFraction = 0.5f,
            totalItems = 100,
            itemsPerRow = 1,
            visibleItems = listVisibleItems,
        )
        assertEquals(
            list,
            quickScrollBarProgress(50, 0.5f, 100, itemsPerRow = 0, visibleItems = listVisibleItems),
            1e-6f,
        )
        assertEquals(
            list,
            quickScrollBarProgress(50, 0.5f, 100, itemsPerRow = -3, visibleItems = listVisibleItems),
            1e-6f,
        )
        assertEquals(
            dragIndex(trackPx / 2f, total = 100, itemsPerRow = 1),
            dragIndex(trackPx / 2f, total = 100, itemsPerRow = 0),
        )
    }

    /**
     * 网格档拖动同样与滑条位置互为逆映射，且落点在该行的**首条**。票 #149 验收点名的四档覆盖里
     * 网格档有 2 列与 4 列两档，因此两者各跑一遍。
     */
    @Test
    fun `网格档拖动落在行的首条 且与滑条位置互为逆映射`() {
        for (columns in listOf(2, 4)) {
            val total = columns * gridTotalRows
            // 可达的行上限 = 总行数 − 可见行数（票 #148 ①）：499 行已超出（拖到底也到不了，见下方断言）
            for (row in listOf(0, 1, 250, gridTotalRows - gridVisibleRows.toInt())) {
                val bar = gridGeometry(index = row * columns, total = total, columns = columns)
                val grabbed = bar.thumbOffsetPx + bar.thumbLengthPx / 2f
                assertEquals(
                    "$columns 列第 $row 行的首条",
                    row * columns,
                    dragIndex(
                        grabbed,
                        total = total,
                        thumbLengthPx = bar.thumbLengthPx,
                        itemsPerRow = columns,
                        visibleItems = gridVisibleRows * columns,
                    ),
                )
            }
            // 超出可达行的拖到底端停在末行（490 行）的首条，不是总行数 − 1 那行的首条
            assertEquals(
                "$columns 列：拖到底停在末行",
                (gridTotalRows - gridVisibleRows.toInt()) * columns,
                dragIndex(
                    trackPx,
                    total = total,
                    itemsPerRow = columns,
                    visibleItems = gridVisibleRows * columns,
                ),
            )
            // 行内比例也解回来（票 #149）：半行的几何位置拖回去 ⇒ 同一行 + 半个行距
            val halfRow = gridGeometry(index = 200 * columns, total = total, columns = columns, scrollFraction = 0.5f)
            val halfRowTarget = dragTarget(
                halfRow.thumbOffsetPx + halfRow.thumbLengthPx / 2f,
                total = total,
                thumbLengthPx = halfRow.thumbLengthPx,
                itemsPerRow = columns,
                visibleItems = gridVisibleRows * columns,
            )
            assertEquals("$columns 列第 200 行的首条", 200 * columns, halfRowTarget.index)
            assertEquals("$columns 列：半个行距", rowExtentPx / 2, halfRowTarget.rowOffsetPx)
        }
    }
}
