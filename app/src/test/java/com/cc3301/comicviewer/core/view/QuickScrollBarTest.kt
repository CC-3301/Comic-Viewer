package com.cc3301.comicviewer.core.view

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * 浏览页快速定位滑条（票 #60）的两条纯函数：滑条几何（长度 + 位置）与「拖动位移 → 目标条目索引」。
 *
 * 票面 AC 原文：「滑条几何与『拖动 → 索引』是纯函数且有单测」——本用例就是那条 AC 的落点。
 * 出现/隐藏、跟手定位、与下拉更新及条目点击的手势分层属 Compose 接线，按仓库口径（SPEC 的
 * Testing Decisions：Compose 交互不做大规模自动化）走真机验收，不在本用例内。
 *
 * 为什么两条必须**互为逆映射**：几何把「当前首条索引」映射成轨道位置，拖动把轨道位置映射回索引；
 * 只有同口径（[quickScrollBarProgress] 一处给进度、两处共用），松手后的滑条位置才与拖动时看到的一致
 * （见 [拖动与滑条位置互为逆映射]）。
 *
 * 算例参数的来路：1000 条 = 维护者反馈的目录规模，一屏 10 条 = 竖屏列表档的可见条目数，
 * 轨道 2000px / 最短 24px = 常见手机（2x 密度）的量级。
 */
class QuickScrollBarTest {

    /** 轨道长度（px，两档共用一条竖直轨道） */
    private val trackPx = 2000f

    /** 滑条最短长度（px）：条目极多时也要抓得住 */
    private val minThumbPx = 24f

    private fun geometry(index: Int, total: Int = 1000, visible: Int = 10) =
        quickScrollBarGeometry(
            totalItems = total,
            visibleItems = visible,
            firstVisibleItemIndex = index,
            trackLengthPx = trackPx,
            minThumbLengthPx = minThumbPx,
        )

    /** 上例几何的滑条长度（拖动的抓手点按「滑条中部」算，见 [quickScrollBarIndexForDrag]） */
    private val thumbPx: Float = requireNotNull(geometry(0)).thumbLengthPx

    // --- 不显示（返回 null）的三个前提 ---

    @Test
    fun `目录不足一屏时不给几何`() {
        // 100 条一屏正好装下 → 没有可快速定位的余量
        assertNull(geometry(0, total = 100, visible = 100))
        // 恰好一条不差
        assertNull(geometry(0, total = 100, visible = 101))
        // 探测期可见条目数尚未上报（0）但列表比一屏短
        assertNull(geometry(0, total = 3, visible = 3))
    }

    @Test
    fun `条目不足两条时不给几何`() {
        // 一条（或空列表）没有「移到别处」的语义
        assertNull(geometry(0, total = 1, visible = 1))
        assertNull(geometry(0, total = 0, visible = 0))
    }

    @Test
    fun `轨道还没量到长度时不给几何`() {
        // 首帧轨道尚未上报尺寸（0）：不画滑条，避免按 0 长度算出一根贴边的怪条
        assertNull(
            quickScrollBarGeometry(
                totalItems = 1000,
                visibleItems = 10,
                firstVisibleItemIndex = 0,
                trackLengthPx = 0f,
                minThumbLengthPx = minThumbPx,
            ),
        )
    }

    // --- 几何：长度与位置反映「视口 / 整份列表」 ---

    @Test
    fun `滑条长度与位置反映视口占整份列表的比例`() {
        // 100 条、一屏 25 条 → 滑条长 1/4 轨道；首条时贴在顶端
        val top = requireNotNull(geometry(0, total = 100, visible = 25))
        assertEquals(trackPx * 0.25f, top.thumbLengthPx, 0.01f)
        assertEquals(0f, top.thumbOffsetPx, 0.01f)
        // 末条时贴在底端：偏移 = 轨道 − 滑条长（行程跑满）
        val bottom = requireNotNull(geometry(99, total = 100, visible = 25))
        assertEquals(trackPx - bottom.thumbLengthPx, bottom.thumbOffsetPx, 0.01f)
    }

    @Test
    fun `滑条位置随首条索引线性推进`() {
        // 中期位置：索引 49/99 → 行程过半
        val middle = requireNotNull(geometry(49, total = 100, visible = 25))
        val travel = trackPx - middle.thumbLengthPx
        assertEquals(travel * 49f / 99f, middle.thumbOffsetPx, 0.01f)
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
                visibleItems = 10,
                firstVisibleItemIndex = 500,
                trackLengthPx = 10f,
                minThumbLengthPx = minThumbPx,
            ),
        )
        // 滑条不超出轨道、偏移不越界（行程为 0）
        assertEquals(10f, short.thumbLengthPx, 0.01f)
        assertEquals(0f, short.thumbOffsetPx, 0.01f)
    }

    // --- 拖动 → 目标条目索引 ---

    @Test
    fun `拖到轨道顶端 中点 底端 分别定位到首条 约第500条 末条`() {
        assertEquals(0, quickScrollBarIndexForDrag(0f, 1000, trackPx, thumbPx))
        // AC：1000+ 条目目录里拖到中点应落在「约第 500 条」附近（±2 条）
        val middle = quickScrollBarIndexForDrag(trackPx / 2f, 1000, trackPx, thumbPx)
        assertTrue("中点应约第 500 条（0-based 500 = 第 501 条），实得 $middle", abs(middle - 500) <= 2)
        // AC：拖到底端应落在约第 1000 条（末条）
        assertEquals(999, quickScrollBarIndexForDrag(trackPx, 1000, trackPx, thumbPx))
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
                quickScrollBarIndexForDrag(grabbed, 1000, trackPx, bar.thumbLengthPx),
            )
        }
    }

    @Test
    fun `拖动超出轨道两端被夹在本份列表内`() {
        // 手指滑出轨道上端/下端（含负值）都停在首条/末条，不产生越界索引
        assertEquals(0, quickScrollBarIndexForDrag(-500f, 1000, trackPx, thumbPx))
        assertEquals(0, quickScrollBarIndexForDrag(0f, 1000, trackPx, thumbPx))
        assertEquals(999, quickScrollBarIndexForDrag(trackPx * 2f, 1000, trackPx, thumbPx))
    }

    @Test
    fun `条目极多时相邻索引仍能被区分`() {
        // 10000 条：滑条行程不变，一个条目 ≈ 0.2px 仍单调（拖动是连续的，不要求一格一索引）
        val first = quickScrollBarIndexForDrag(trackPx / 2f, 10_000, trackPx, thumbPx)
        val second = quickScrollBarIndexForDrag(trackPx / 2f + 1f, 10_000, trackPx, thumbPx)
        assertTrue("越往下拖索引不应回退：$first → $second", second >= first)
        assertEquals(5000, first)
    }

    @Test
    fun `拖动为零长度行程时不崩`() {
        // 轨道与滑条等长（行程 0）：没有可拖的余量，退回首条
        assertEquals(0, quickScrollBarIndexForDrag(500f, 1000, trackPx, trackPx))
        assertEquals(0, quickScrollBarIndexForDrag(500f, 1, trackPx, thumbPx))
    }
}
