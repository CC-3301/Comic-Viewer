package com.cc3301.comicviewer.core.view

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * 浏览页快速定位滑条（票 #60）的纯函数：滑条几何（长度 + 位置）、两条连续化读数与「拖动位移 → 目标条目索引」。
 *
 * 票面 AC 原文：「滑条几何与『拖动 → 索引』是纯函数且有单测」——本用例就是那条 AC 的落点。
 * 出现/隐藏、跟手定位、与下拉更新及条目点击的手势分层属 Compose 接线，按仓库口径（SPEC 的
 * Testing Decisions：Compose 交互不做大规模自动化）走真机验收，不在本用例内。
 *
 * 为什么两条必须**互为逆映射**：几何把「当前首条索引」映射成轨道位置，拖动把轨道位置映射回索引；
 * 只有同口径（[quickScrollBarProgress] 一处给进度、两处共用），松手后的滑条位置才与拖动时看到的一致
 * （见 [拖动与滑条位置互为逆映射]）。
 *
 * 进度以**行**为单位（票 #60 批次 9 r2）：网格档一档 [gridGeometry] 的列数个格子，按条目算会让行内速度只有真实的
 * 一半、每跨一行边界补跳 1/总格数（真机「网格档滚动时滑条一格一格跳」）；列表档一行 = 一条（`itemsPerRow = 1`）
 * ⇒ 下面这些列表档用例的算式与改动前逐像素一致。
 *
 * 算例参数的来路：1000 条 = 维护者反馈的目录规模，一屏 10 条 = 竖屏列表档的可见条目数，
 * 轨道 2000px / 最短 24px = 常见手机（2x 密度）的量级。
 */
class QuickScrollBarTest {

    /** 轨道长度（px，两档共用一条竖直轨道） */
    private val trackPx = 2000f

    /** 滑条最短长度（px）：条目极多时也要抓得住 */
    private val minThumbPx = 24f

    private fun geometry(
        index: Int,
        total: Int = 1000,
        visible: Float = 10f,
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

    /** 上例几何的滑条长度（拖动的抓手点按「滑条中部」算，见 [quickScrollBarIndexForDrag]） */
    private val thumbPx: Float = requireNotNull(geometry(0)).thumbLengthPx

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
        // 中期位置：r6 的进度口径 = 索引 / 总条目数 ⇒ 49/100 行程
        val middle = requireNotNull(geometry(49, total = 100, visible = 25f))
        val travel = trackPx - middle.thumbLengthPx
        assertEquals(travel * 49f / 100f, middle.thumbOffsetPx, 0.01f)
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
     * r6 返工口径①：进度 = `(首条索引 + 屏内已滚过比例) / 总条目数`。
     *
     * 判别力：退回「只吃整数索引」（屏内比例不参与）时，
     * 取样 0 → 半高 → 满高 三次算出的进度会**相等**，本条与下一条都变红。
     */
    @Test
    fun `同一索引内屏内比例推进时进度严格单调递增`() {
        val total = 1000
        val halfItem = 0.5f
        val starts = quickScrollBarProgress(index = 500, scrollFraction = 0f, totalItems = total, itemsPerRow = 1)
        val middle = quickScrollBarProgress(index = 500, scrollFraction = halfItem, totalItems = total, itemsPerRow = 1)
        val ends = quickScrollBarProgress(index = 500, scrollFraction = 1f, totalItems = total, itemsPerRow = 1)

        assertTrue("0 → 半高 应递增：$starts → $middle", middle > starts)
        assertTrue("半高 → 满高 应递增：$middle → $ends", ends > middle)
        // 相邻差值 = 半高 ÷ 总条目数（浮点容差）
        assertEquals(halfItem / total, middle - starts, 1e-6f)
        assertEquals(halfItem / total, ends - middle, 1e-6f)
        // 进度 × 总条目数 = 索引 + 屏内比例（拖动的逆映射与几何同口径）
        assertEquals(500.5f, middle * total, 1e-3f)
    }

    /**
     * r6 证据口径：同一次滚动里两次相邻取样的 `progress` **不再相等**（整数索引时它们必然相等）。
     * 取样步长取 1/16 个条目高——真机上一帧的位移量级。
     */
    @Test
    fun `相邻两帧取样在同一个首条索引内也得到不同的进度`() {
        val total = 1000
        val samples = listOf(0f, 1f / 16f, 2f / 16f, 3f / 16f)
            .map { quickScrollBarProgress(index = 640, scrollFraction = it, totalItems = total, itemsPerRow = 1) }
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
        assertEquals(travel * 0.5f / 1000f, offsets[1] - offsets[0], 0.01f)
    }

    @Test
    fun `屏内比例与索引越界都被夹住 不产生越界进度`() {
        val total = 1000
        assertEquals(0f, quickScrollBarProgress(-5, 0f, total, itemsPerRow = 1), 1e-6f)
        assertEquals(0f, quickScrollBarProgress(0, -1f, total, itemsPerRow = 1), 1e-6f)
        assertEquals(1f, quickScrollBarProgress(999, 2f, total, itemsPerRow = 1), 1e-6f)
        // 条目不足两条：没有可推进的区间
        assertEquals(0f, quickScrollBarProgress(0, 0.5f, 1, itemsPerRow = 1), 1e-6f)
        assertEquals(0f, quickScrollBarProgress(0, 0.5f, 0, itemsPerRow = 1), 1e-6f)
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

    // --- 拖动 → 目标条目索引 ---

    @Test
    fun `拖到轨道顶端 中点 底端 分别定位到首条 约第500条 末条`() {
        assertEquals(0, quickScrollBarIndexForDrag(0f, 1000, trackPx, thumbPx, itemsPerRow = 1))
        // AC：1000+ 条目目录里拖到中点应落在「约第 500 条」附近（±2 条）
        val middle = quickScrollBarIndexForDrag(trackPx / 2f, 1000, trackPx, thumbPx, itemsPerRow = 1)
        assertTrue("中点应约第 500 条（0-based 500 = 第 501 条），实得 $middle", abs(middle - 500) <= 2)
        // AC：拖到底端应落在约第 1000 条（末条）
        assertEquals(999, quickScrollBarIndexForDrag(trackPx, 1000, trackPx, thumbPx, itemsPerRow = 1))
    }

    @Test
    fun `拖动与滑条位置互为逆映射`() {
        // 拿起滑条中部拖到某个索引的位置，应正好定位回那个索引（±1 取整误差内恰好相等）
        for (index in listOf(0, 1, 250, 499, 500, 750, 999)) {
            val bar = requireNotNull(geometry(index))
            val grabbed = bar.thumbOffsetPx + bar.thumbLengthPx / 2f
            assertEquals(
                "索引 $index 的往返定位",
                index,
                quickScrollBarIndexForDrag(grabbed, 1000, trackPx, bar.thumbLengthPx, itemsPerRow = 1),
            )
        }
    }

    @Test
    fun `拖动超出轨道两端被夹在本份列表内`() {
        // 手指滑出轨道上端/下端（含负值）都停在首条/末条，不产生越界索引
        assertEquals(0, quickScrollBarIndexForDrag(-500f, 1000, trackPx, thumbPx, itemsPerRow = 1))
        assertEquals(0, quickScrollBarIndexForDrag(0f, 1000, trackPx, thumbPx, itemsPerRow = 1))
        assertEquals(999, quickScrollBarIndexForDrag(trackPx * 2f, 1000, trackPx, thumbPx, itemsPerRow = 1))
    }

    @Test
    fun `条目极多时相邻索引仍能被区分`() {
        // 10000 条：滑条行程不变，一个条目 ≈ 0.2px 仍单调（拖动是连续的，不要求一格一索引）
        val first = quickScrollBarIndexForDrag(trackPx / 2f, 10_000, trackPx, thumbPx, itemsPerRow = 1)
        val second = quickScrollBarIndexForDrag(trackPx / 2f + 1f, 10_000, trackPx, thumbPx, itemsPerRow = 1)
        assertTrue("越往下拖索引不应回退：$first → $second", second >= first)
        assertEquals(5000, first)
    }

    @Test
    fun `拖动为零长度行程时不崩`() {
        // 轨道与滑条等长（行程 0）：没有可拖的余量，退回首条
        assertEquals(0, quickScrollBarIndexForDrag(500f, 1000, trackPx, trackPx, itemsPerRow = 1))
        assertEquals(0, quickScrollBarIndexForDrag(500f, 1, trackPx, thumbPx, itemsPerRow = 1))
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
     * 每步位移都应与「行程 ÷ 总行数 ÷ 每行步数」相等——行内与跨行**同一个速度**、跨行边界不额外跳。
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
        val perStep = travel / gridTotalRows / gridStepsPerRow
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
        val list = quickScrollBarProgress(index = 50, scrollFraction = 0.5f, totalItems = 100, itemsPerRow = 1)
        assertEquals(list, quickScrollBarProgress(50, 0.5f, 100, itemsPerRow = 0), 1e-6f)
        assertEquals(list, quickScrollBarProgress(50, 0.5f, 100, itemsPerRow = -3), 1e-6f)
        assertEquals(
            quickScrollBarIndexForDrag(trackPx / 2f, 100, trackPx, thumbPx, itemsPerRow = 1),
            quickScrollBarIndexForDrag(trackPx / 2f, 100, trackPx, thumbPx, itemsPerRow = 0),
        )
    }
    /** 网格档拖动同样与滑条位置互为逆映射，且落点在该行的**首条**（2 列下是偶数索引） */
    @Test
    fun `网格档拖动落在行的首条 且与滑条位置互为逆映射`() {
        val columns = 2
        val total = columns * gridTotalRows
        for (row in listOf(0, 1, 250, 499)) {
            val bar = gridGeometry(index = row * columns, total = total, columns = columns)
            val grabbed = bar.thumbOffsetPx + bar.thumbLengthPx / 2f
            assertEquals(
                "第 $row 行的首条",
                row * columns,
                quickScrollBarIndexForDrag(grabbed, total, trackPx, bar.thumbLengthPx, itemsPerRow = columns),
            )
        }
    }
}
