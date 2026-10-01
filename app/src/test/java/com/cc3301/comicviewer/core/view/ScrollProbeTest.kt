package com.cc3301.comicviewer.core.view

import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 浏览页滚动量测的聚合口径（「先量再改」）：掉帧判定、掉帧/秒、**窗口边界**（静止帧与空闲期事件
 * 都不进统计）、封面取字节+解码统计、**并发写入不丢数**、摘要行形状。设备上比对数字读的就是这一行，
 * 因此键名与折算口径在本用例里锁死——改了先红。
 */
class ScrollProbeTest {

    /** 纳秒 / 毫秒（三段折算的算例用真单位，避免与实现的 1_000_000 各写一份） */
    private companion object {
        const val NANOS_PER_MILLI = 1_000_000L
    }

    /** 当前一帧的原始量测（设备上来自 `FrameMetrics` 的三个时长 + 帧自己的时间戳 `INTENDED_VSYNC_TIMESTAMP`） */
    private fun ScrollProbe.frame(
        frameMs: Long,
        totalMs: Double = 8.0,
        layoutMs: Double = 1.0,
        drawMs: Double = 3.0,
    ): String? = onFrame(
        totalNanos = (totalMs * 1_000_000).toLong(),
        layoutNanos = (layoutMs * 1_000_000).toLong(),
        drawNanos = (drawMs * 1_000_000).toLong(),
        frameNanos = frameMs * 1_000_000,
    )

    /** 从摘要行里取某个键的值（行是空格分隔的 key=value） */
    private fun key(line: String, name: String): String =
        line.split(' ').first { it.startsWith("$name=") }.substringAfter('=')

    /** 一次滚动活动（毫秒刻度，与 [ScrollProbeTest.frame] 同一套） */
    private fun ScrollProbe.scrollAt(nowMs: Long) = markScrollActivity(nowNanos = nowMs * 1_000_000)

    /** 只关心计数、不关心何时落行的算例：把静止阈值拉到不会触发，直接读摘要 */
    private fun countingProbe() = ScrollProbe(idleFlushNanos = Long.MAX_VALUE)

    /**
     * 只关心**整段**的算例：三段里把整段放进取字节段、解码段留 0——
     * 它们的断言只看 `coverLoadTotalMs` / `coverLoadMaxMs` / `coverLoadThreads`，与三段怎么分无关；
     * 三段各自的折算与占比另有专门的用例。
     */
    private fun ScrollProbe.coverLoad(millis: Long, thread: String) =
        onCoverLoad(CoverLoadSegments(fetchMs = millis, decodeMs = 0, waitMs = 0), thread)

    @Test
    fun `预算内的帧不计掉帧 静止帧不进窗口`() {
        val probe = ScrollProbe()
        probe.scrollAt(0)
        // 10 帧 16ms：最后一帧在 160ms，距最后一次活动只过 160ms，还没到静止阈值
        for (i in 1..10) assertNull("滚动中不落行", probe.frame(frameMs = i * 16L))
        // 700ms 那一帧按定义已在静止期（>500ms 没有活动）：它只负责落行，不进统计
        val line = probe.frame(frameMs = 700)
        assertNotNull("静止超过阈值后落行", line)
        assertTrue("统一前缀", line!!.startsWith(ScrollProbe.SUMMARY_PREFIX))
        assertEquals("只有滚动期的 10 帧", "10", key(line, "frames"))
        assertEquals("都短于 60Hz 预算", "0", key(line, "janky"))
        assertEquals("0 掉帧 → 0%", "0.0", key(line, "jankPct"))
        assertEquals("0 掉帧 → 0 掉帧每秒", "0.0", key(line, "jankPerSec"))
        assertEquals("窗口 = 首次活动 → 最后一帧计入的帧（不含静止尾巴）", "160", key(line, "windowMs"))
        assertEquals("一次可见区变化", "1", key(line, "steps"))
    }

    @Test
    fun `超过帧预算的帧计入掉帧 并折算百分点与每秒`() {
        val probe = countingProbe()
        probe.scrollAt(0)
        // 10 帧：2 帧超预算（33ms / 50ms），帧与帧的时钟间隔都是 100ms ⇒ 窗口 1 秒
        val totals = listOf(8.0, 8.0, 33.0, 8.0, 8.0, 8.0, 50.0, 8.0, 8.0, 8.0)
        totals.forEachIndexed { i, total ->
            assertNull("阈值拉长后不中途落行", probe.frame(frameMs = (i + 1) * 100L, totalMs = total))
        }
        val line = probe.summaryLine()
        assertEquals("2 帧超预算", "2", key(line, "janky"))
        assertEquals("2 / 10 帧", "20.0", key(line, "jankPct"))
        assertEquals("2 掉帧 ÷ 1.0 秒", "2.0", key(line, "jankPerSec"))
        assertEquals("窗口毫秒", "1000", key(line, "windowMs"))
        assertEquals("最长帧 = 50ms", "50", key(line, "frameMaxMs"))
        assertEquals("超预算那一帧的合成耗时", "3", key(line, "drawMaxMs"))
    }

    @Test
    fun `窗口时长按帧时间戳算 不受活动登记与回调投递延迟影响`() {
        val probe = ScrollProbe()
        // 设备上的坏场景：主线程忙 ⇒ ①滚动活动登记被压后到滚动开始 200ms 之后，
        // ②帧回调被突发投递、投递时刻挤在一起（同一批 19 帧的间隔被压到 16ms 内、整体后移 100ms）。
        // 第四个入参是**帧自己的时间戳**（设备取 FrameMetrics.INTENDED_VSYNC_TIMESTAMP，与 System.nanoTime 同一时钟）：
        // 窗口时长必须等于这 19 帧的时间戳跨度（288ms），不能是「活动登记时刻 → 最后一次投递」那一段（88ms）——
        // 后者正是 jankPerSec 分母不可信（设备推出 200+ fps）的原因。
        val base = 1_000L
        probe.scrollAt(base + 200)
        for (i in 0 until 19) assertNull("滚动中不落行", probe.frame(frameMs = base + i * 16L))
        val line = probe.frame(frameMs = base + 800)!!
        assertEquals("投递被压后也不丢帧", "19", key(line, "frames"))
        assertEquals("窗口 = 18 × 16ms 的帧时间戳跨度", "288", key(line, "windowMs"))
    }

    @Test
    fun `连续活动期间只落一行 窗口内的条目与封面组合都计进这一行`() {
        val probe = ScrollProbe()
        // 本例锁的是**窗口内计数**：活动每帧登记时，连续活动只落一行且窗口内的条目/封面重组都计进这一行。
        // **它锁不住**「接线侧登记得够密」（活动登记走主线程 `snapshotFlow` collector，发射率受调度约束）——
        // 「一段连续滚动只落一行」是设备判据。
        val base = 5_000L
        repeat(60) { i ->
            val ts = base + i * 16L
            probe.scrollAt(ts)
            probe.onItemComposed()
            probe.onCoverComposed()
            assertNull("连续滚动期间不落行", probe.frame(frameMs = ts))
        }
        val line = probe.frame(frameMs = base + 960L + 600L)!!
        assertEquals("一段连续滚动 = 一行", "60", key(line, "frames"))
        assertEquals("每帧一次活动登记", "60", key(line, "steps"))
        assertEquals("每帧一次条目重组都计到", "60", key(line, "itemsComposed"))
        assertEquals("封面同理", "60", key(line, "coversComposed"))
        assertEquals("窗口 = 首帧 → 末帧（59 × 16ms）", "944", key(line, "windowMs"))
    }

    @Test
    fun `机型不给帧时间戳时回落到投递时刻 静止判据仍成立`() {
        // 机型上 FrameMetrics.INTENDED_VSYNC_TIMESTAMP 恒为 0 时：不回落的后果是窗口起点被 minOf 拉到 0
        // （windowMs 变成设备开机时长量级）且 `0 - 最后一次活动` 恒为负 ⇒ 永不自动落行，只剩离开浏览层那一行。
        // 这里注入一个可控的回落时钟（单调时钟，值就是回调投递时刻）来锁这条回落。
        val deliveredAt = java.util.concurrent.atomic.AtomicLong(0L)
        val probe = ScrollProbe(monotonicNanos = { deliveredAt.get() })
        probe.scrollAt(1_000)
        deliveredAt.set(1_016_000_000L)
        assertNull(
            "滚动中不落行",
            probe.onFrame(totalNanos = 8_000_000, layoutNanos = 1_000_000, drawNanos = 1_000_000, frameNanos = 0L),
        )
        deliveredAt.set(1_600_000_000L) // 距最后一次活动 600ms：该落行了
        val line = probe.onFrame(totalNanos = 8_000_000, layoutNanos = 1_000_000, drawNanos = 1_000_000, frameNanos = 0L)
        assertNotNull("时间戳缺失也要能自动落行", line)
        assertEquals("回落后窗口 = 投递时刻跨度（活动 1000 → 帧 1016）", "16", key(line!!, "windowMs"))
    }

    @Test
    fun `亚毫秒窗口的掉帧率不因整毫秒截断而自相矛盾`() {
        val probe = countingProbe()
        probe.markScrollActivity(1_000_000_000L)
        // 同一毫秒内到的一帧且超预算：windowMs 的人读读数就是 0，但分母必须是真实的纳秒跨度
        probe.onFrame(totalNanos = 20_000_000, layoutNanos = 0, drawNanos = 0, frameNanos = 1_000_500_000L)
        val line = probe.summaryLine()
        assertEquals("整毫秒读数是 0", "0", key(line, "windowMs"))
        assertEquals("1 帧 1 掉帧", "100.0", key(line, "jankPct"))
        assertNotEquals("分母截断成 0 会打出 janky=1 却 jankPerSec=0.0", "0.0", key(line, "jankPerSec"))
        assertEquals("1 ÷ 0.0005s", "2000.0", key(line, "jankPerSec"))
    }

    @Test
    fun `静止期的帧不进统计 下一次滚动另开窗口`() {
        val probe = ScrollProbe()
        probe.scrollAt(0)
        assertNull("滚动中不落行", probe.frame(frameMs = 100))
        val first = probe.frame(frameMs = 700)!!
        assertEquals("首窗口只算滚动期那一帧", "1", key(first, "frames"))
        assertEquals("首窗口时长 = 0 → 100", "100", key(first, "windowMs"))
        // 落行后复位：没有新的滚动活动，来的帧一律不算
        assertNull("复位后第一帧", probe.frame(frameMs = 800))
        assertNull("复位后第二帧", probe.frame(frameMs = 900))
        // 再次滚动 = 新窗口，计数从头开始
        probe.scrollAt(1000)
        assertNull("新窗口滚动中不落行", probe.frame(frameMs = 1100))
        val second = probe.frame(frameMs = 1600)!!
        assertEquals("新窗口只算新窗口的帧", "1", key(second, "frames"))
        assertEquals("新窗口的步进数", "1", key(second, "steps"))
        assertEquals("新窗口时长 = 1000 → 1100", "100", key(second, "windowMs"))
    }

    @Test
    fun `p95 取最近秩 与最大值分开`() {
        val probe = countingProbe()
        probe.scrollAt(0)
        // 20 帧：18 帧 10ms + 2 帧 100ms ⇒ 最近秩 p95 = 排序后第 19 个 = 100ms
        val totals = List(18) { 10.0 } + listOf(100.0, 100.0)
        totals.forEachIndexed { i, total -> probe.frame(frameMs = (i + 1) * 50L, totalMs = total) }
        val line = probe.summaryLine()
        assertEquals("p95 命中长帧", "100", key(line, "frameP95Ms"))
        assertEquals("平均 = (18×10 + 2×100) ÷ 20", "19.0", key(line, "frameAvgMs"))
        assertEquals("最大值", "100", key(line, "frameMaxMs"))
    }

    @Test
    fun `条目与封面组合次数 以及封面取字节加重解码统计都进摘要`() {
        val probe = countingProbe()
        probe.scrollAt(0)
        repeat(3) { probe.onItemComposed() }
        repeat(3) { probe.onCoverComposed() }
        // 三段逐段给数：整段 = 取字节 + 解码，等待段单列
        probe.onCoverLoad(CoverLoadSegments(fetchMs = 10, decodeMs = 2, waitMs = 5), "DefaultDispatcher-worker-2")
        probe.onCoverLoad(CoverLoadSegments(fetchMs = 30, decodeMs = 8, waitMs = 1), "DefaultDispatcher-worker-1")
        probe.onCoverLoad(CoverLoadSegments(fetchMs = 4, decodeMs = 1, waitMs = 2), "DefaultDispatcher-worker-1")
        // 恰好等于预算的帧不算掉帧（判定是严格大于）
        assertNull(
            "阈值拉长后不中途落行",
            probe.onFrame(
                totalNanos = ScrollProbe.FRAME_BUDGET_NANOS,
                layoutNanos = 1_000_000,
                drawNanos = 2_000_000,
                frameNanos = 600_000_000,
            ),
        )
        val line = probe.summaryLine()
        assertEquals("0", key(line, "janky"))
        assertEquals("条目 composable 体执行 3 次", "3", key(line, "itemsComposed"))
        assertEquals("封面 composable 体执行 3 次", "3", key(line, "coversComposed"))
        assertEquals("3 次封面加载", "3", key(line, "coverLoads"))
        assertEquals("整段 = (10+2) + (30+8) + (4+1)", "55", key(line, "coverLoadTotalMs"))
        assertEquals("最慢那次", "38", key(line, "coverLoadMaxMs"))
        assertEquals("取字节段独立累加 10 + 30 + 4", "44", key(line, "coverLoadFetchMs"))
        assertEquals("解码段独立累加 2 + 8 + 1", "11", key(line, "coverLoadDecodeMs"))
        assertEquals("等待段独立累加 5 + 1 + 2（不进整段）", "8", key(line, "coverLoadWaitMs"))
        assertEquals(
            "线程分布按名排序、便于前后对齐",
            "DefaultDispatcher-worker-1:2,DefaultDispatcher-worker-2:1",
            key(line, "coverLoadThreads"),
        )
    }

    @Test
    fun `离开浏览层时收口 不把上一段的帧并进新行`() {
        val probe = ScrollProbe()
        probe.scrollAt(0)
        assertNull("滚动中不落行", probe.frame(frameMs = 100))
        // 尚未静止 500ms 就离开这一层浏览页（返回上级 / 进子目录 / 点书切阅读页）：不会有那一帧静止帧来落行，
        // 因此离开时必须主动收口（否则窗口跳屏存活，下一屏的事件并进同一行）
        probe.onItemComposed()
        val left = probe.onScrollSessionEnd()
        assertNotNull("离开浏览层时落行", left)
        assertEquals("落的是本屏这一段", "1", key(left!!, "frames"))
        assertEquals("1", key(left, "steps"))
        assertEquals("1", key(left, "itemsComposed"))
        assertNull("已收口：再调一次不产空行", probe.onScrollSessionEnd())

        // 回来了，再滚一段：新窗口，旧帧/旧事件不进新行
        assertNull("收口后没有滚动活动就不计帧", probe.frame(frameMs = 1500))
        probe.scrollAt(2000)
        assertNull("滚动中不落行", probe.frame(frameMs = 2100))
        val line = probe.summaryLine()
        assertEquals("回来后的窗口只算本屏这一帧", "1", key(line, "frames"))
        assertEquals("只算本屏这一次活动", "1", key(line, "steps"))
        assertEquals("窗口不含离开的那段空档", "100", key(line, "windowMs"))
        assertEquals("上一屏的条目组合不计进来", "0", key(line, "itemsComposed"))
    }

    @Test
    fun `窗口外的组合与封面事件不进统计`() {
        val probe = ScrollProbe()
        // 还没滚动就先来的事件（上一段落行之后、下一次滚动开始之前）：一律不进任何窗口
        probe.onItemComposed()
        probe.onCoverComposed()
        probe.coverLoad(millis = 7, thread = "DefaultDispatcher-worker-1")
        val idle = probe.summaryLine()
        assertEquals("空闲期的条目组合不计", "0", key(idle, "itemsComposed"))
        assertEquals("空闲期的封面组合不计", "0", key(idle, "coversComposed"))
        assertEquals("空闲期的封面加载不计", "0", key(idle, "coverLoads"))

        // 窗口内的事件才计
        probe.scrollAt(100)
        probe.onItemComposed()
        probe.onCoverComposed()
        probe.coverLoad(millis = 3, thread = "DefaultDispatcher-worker-1")
        assertNull("滚动中不落行", probe.frame(frameMs = 200))
        val during = probe.summaryLine()
        assertEquals("窗口内的条目组合计入", "1", key(during, "itemsComposed"))
        assertEquals("窗口内的封面组合计入", "1", key(during, "coversComposed"))
        assertEquals("窗口内的封面加载计入", "1", key(during, "coverLoads"))

        val flushed = probe.frame(frameMs = 900)!!
        assertEquals("落行的静止帧不算一帧", "1", key(flushed, "frames"))
        assertEquals("窗口 = 活动 100 → 最后一帧 200", "100", key(flushed, "windowMs"))

        // 落行之后、下一次滚动之前的事件进不了下一个窗口
        probe.onItemComposed()
        probe.coverLoad(millis = 9, thread = "DefaultDispatcher-worker-2")
        probe.scrollAt(1000)
        val next = probe.frame(frameMs = 1600)!!
        assertEquals("空闲期的事件不算进下一个窗口", "0", key(next, "itemsComposed"))
        assertEquals("0", key(next, "coverLoads"))
        assertEquals("0", key(next, "frames"))
    }

    @Test
    fun `封面首次上屏只算有封面的帧 与窗口级最大值分开`() {
        // 帧级打点：把「有新封面第一次上屏」的那几帧从窗口里挑出来——分工是
        // `coverShownDrawMaxMs ≈ drawMaxMs` ⇒ 那几帧贵在封面图上屏；远小于 ⇒ 贵的帧与封面首次上屏无关。
        val probe = countingProbe()
        probe.scrollAt(0)
        assertNull("滚动中不落行", probe.frame(frameMs = 16, drawMs = 5.0))
        assertNull("滚动中不落行", probe.frame(frameMs = 32, drawMs = 6.0))
        // 3 张就绪：记到**下一个帧回调**那一帧（设备上帧回调在本帧绘制之后才投递）
        repeat(3) { probe.onCoverShown() }
        assertNull("滚动中不落行", probe.frame(frameMs = 48, drawMs = 40.0))
        // 又 1 张就绪：另一帧
        probe.onCoverShown()
        assertNull("滚动中不落行", probe.frame(frameMs = 64, drawMs = 12.0))
        // 更贵的一帧、但这一帧没有新封面上屏：不许把它的绘制耗时算成「封面首次上屏」的代价
        assertNull("滚动中不落行", probe.frame(frameMs = 80, drawMs = 90.0))
        val line = probe.summaryLine()
        assertEquals("只有两帧有新封面首次上屏", "2", key(line, "coverShownFrames"))
        assertEquals("这两帧里最大的 drawMs（不是窗口最大值 90）", "40", key(line, "coverShownDrawMaxMs"))
        assertEquals("单帧最多同时上屏 3 张", "3", key(line, "coverShownMaxPerFrame"))
        assertEquals("窗口级最大值照旧单列", "90", key(line, "drawMaxMs"))
    }

    @Test
    fun `没有封面就绪时三个数都是 0`() {
        val probe = countingProbe()
        probe.scrollAt(0)
        assertNull("滚动中不落行", probe.frame(frameMs = 16, drawMs = 33.0))
        val line = probe.summaryLine()
        assertEquals("0", key(line, "coverShownFrames"))
        assertEquals("0", key(line, "coverShownDrawMaxMs"))
        assertEquals("0", key(line, "coverShownMaxPerFrame"))
        assertEquals("但窗口级最大值照记", "33", key(line, "drawMaxMs"))
    }

    @Test
    fun `窗口外就绪的封面不进统计 落行时余量不并进下一段`() {
        val probe = ScrollProbe()
        // 还没滚动（没有开着的窗口）：就绪的封面不许记
        probe.onCoverShown()
        probe.scrollAt(0)
        assertNull("滚动中不落行", probe.frame(frameMs = 100, drawMs = 7.0))
        assertEquals("窗口外那张不算", "0", key(probe.summaryLine(), "coverShownFrames"))
        // 窗口内就绪、但在碰上帧回调之前就落行（静止阈值）：余量丢掉，不并进下一段
        probe.onCoverShown()
        val flushed = probe.frame(frameMs = 700)!!
        assertEquals("落行那一帧不进统计", "0", key(flushed, "coverShownFrames"))
        probe.scrollAt(1000)
        assertNull("滚动中不落行", probe.frame(frameMs = 1100, drawMs = 6.0))
        assertEquals("上一段落行前的余量不跨段", "0", key(probe.summaryLine(), "coverShownFrames"))
    }

    @Test
    fun `多线程并发写封面加载计数不丢数 也不抛并发修改`() {
        val probe = countingProbe()
        probe.scrollAt(0)
        val writers = 8
        val perWriter = 2_000
        val pool = Executors.newFixedThreadPool(writers + 1)
        val start = CountDownLatch(1)
        val writersDone = CountDownLatch(writers)
        val readerFailure = AtomicReference<Throwable?>()
        try {
            repeat(writers) { worker ->
                pool.execute {
                    start.await()
                    repeat(perWriter) { probe.coverLoad(millis = 1, thread = "worker-$worker") }
                    writersDone.countDown()
                }
            }
            // 主线程落行 / 读摘要与 IO 线程写入重叠：迭代线程分布时不得抛 ConcurrentModificationException
            pool.execute {
                start.await()
                repeat(2_000) {
                    try {
                        probe.summaryLine()
                    } catch (t: Throwable) {
                        readerFailure.compareAndSet(null, t)
                    }
                }
            }
            start.countDown()
            assertTrue("写入应在 10s 内跑完", writersDone.await(10, TimeUnit.SECONDS))
        } finally {
            pool.shutdownNow()
        }
        assertNull("并发读摘要不得抛异常", readerFailure.get())
        val line = probe.summaryLine()
        assertEquals("$writers × $perWriter 次一次都不能丢", (writers * perWriter).toString(), key(line, "coverLoads"))
        assertEquals("耗时累加同样不能丢", (writers * perWriter).toString(), key(line, "coverLoadTotalMs"))
        assertEquals("每个写线程都留下一条分布", writers, key(line, "coverLoadThreads").split(",").size)
    }

    @Test
    fun `还没滚动时落行不除零`() {
        val line = ScrollProbe().summaryLine()
        assertEquals("0", key(line, "frames"))
        assertEquals("0.0", key(line, "jankPct"))
        assertEquals("0.0", key(line, "jankPerSec"))
        assertEquals("0.0", key(line, "frameAvgMs"))
        assertEquals("0", key(line, "windowMs"))
    }

    @Test
    fun `封面加载三段折算 整段与改动前同口径`() {
        // 上屏需求 → 10ms 后 IO 段真正开始（协程派发/主线程拥塞）→ 250ms 后字节到手 → 67ms 后位图就绪。
        // 整段必须是「IO 段起点 → 位图就绪」（区间与改动前相同，但该区间含取字节闸的等牌时间，
        // 因此与闸前的样本（基线的 317ms 那一批）不能逐字比），
        // 而不是「上屏需求 → 位图就绪」——否则改动前后的数会差出一个等待段、没法对比。
        val segments = CoverLoadSegments.of(
            askedNanos = 0L,
            ioStartNanos = 10 * NANOS_PER_MILLI,
            fetchDoneNanos = 260 * NANOS_PER_MILLI,
            doneNanos = 327 * NANOS_PER_MILLI,
        )
        assertEquals("整段 = 取字节 + 解码（不含等待段）", 317L, segments.totalMs)
        assertEquals("取字节段（250ms）", 250L, segments.fetchMs)
        assertEquals("解码段 = 整段 − 取字节", 67L, segments.decodeMs)
        assertEquals("位图就绪前的等待段单列、不进整段", 10L, segments.waitMs)
    }

    @Test
    fun `uri 通路的取字节不单列 整段计入解码`() {
        // 走系统解码器那条路（本地/SAF 的 content:// / file://）：取字节与解码在解码器内一步完成，
        // 调用方传同一个时刻 ⇒ fetchMs=0、整段落在 decodeMs（读日志时按 route=uri 认出这批）
        val segments = CoverLoadSegments.of(
            askedNanos = 0L,
            ioStartNanos = 5 * NANOS_PER_MILLI,
            fetchDoneNanos = 5 * NANOS_PER_MILLI,
            doneNanos = 90 * NANOS_PER_MILLI,
        )
        assertEquals("整段 = 取字节 + 解码", 85L, segments.totalMs)
        assertEquals("取字节不单列", 0L, segments.fetchMs)
        assertEquals("整段计入解码", 85L, segments.decodeMs)
        assertEquals("等待段照量", 5L, segments.waitMs)
    }

    @Test
    fun `三段折算把负差夹到 0 不打出负数`() {
        // 时钟异常（读数倒退）也不许出现负数段
        val segments = CoverLoadSegments.of(
            askedNanos = 9 * NANOS_PER_MILLI,
            ioStartNanos = 0L,
            fetchDoneNanos = 0L,
            doneNanos = 0L,
        )
        assertEquals("整段夹到 0", 0L, segments.totalMs)
        assertEquals("取字节夹到 0", 0L, segments.fetchMs)
        assertEquals("解码夹到 0", 0L, segments.decodeMs)
        assertEquals("等待夹到 0", 0L, segments.waitMs)
    }

    @Test
    fun `字节到手而解码失败 仍算取数成功 两段都是真值`() {
        // 通路口径只看「字节有没有到手」，**不看解码成没成立**。
        // 这一行改动前报的就是 route=source + 真实 fetchMs/decodeMs；把它改成 source-miss
        // （并把 fetchMs 抬成整段、decodeMs 清 0）就是数值回归，本条用例就是为此存在的。
        val measurement = CoverLoadMeasurement.of(
            askedNanos = 0L,
            ioStartNanos = 10 * NANOS_PER_MILLI,
            uriDecoded = false,
            bytesArrivedNanos = 260 * NANOS_PER_MILLI,
            doneNanos = 327 * NANOS_PER_MILLI,
        )
        assertEquals("字节到手就是来源字节通路", CoverLoadRoute.SourceBytes, measurement.route)
        assertEquals("取字节段照真值（250ms）", 250L, measurement.segments.fetchMs)
        assertEquals("解码段非 0：解码真跑了，只是没解出位图", 67L, measurement.segments.decodeMs)
    }

    @Test
    fun `字节没到手才算取数失败 整段进取字节段`() {
        val measurement = CoverLoadMeasurement.of(
            askedNanos = 0L,
            ioStartNanos = 10 * NANOS_PER_MILLI,
            uriDecoded = false,
            bytesArrivedNanos = null,
            doneNanos = 95 * NANOS_PER_MILLI,
        )
        assertEquals("没字节到手 ⇒ 标 source-miss", CoverLoadRoute.SourceMiss, measurement.route)
        assertEquals("这一整段等的就是字节", 85L, measurement.segments.fetchMs)
        assertEquals("一步解码都没发生", 0L, measurement.segments.decodeMs)
    }

    @Test
    fun `uri 解出来时取字节不单列`() {
        val measurement = CoverLoadMeasurement.of(
            askedNanos = 0L,
            ioStartNanos = 5 * NANOS_PER_MILLI,
            uriDecoded = true,
            bytesArrivedNanos = null,
            doneNanos = 90 * NANOS_PER_MILLI,
        )
        assertEquals(CoverLoadRoute.Uri, measurement.route)
        assertEquals("结构性不拆：取字节段记 0", 0L, measurement.segments.fetchMs)
        assertEquals("整段计入解码段", 85L, measurement.segments.decodeMs)
        assertEquals("等待段照量", 5L, measurement.segments.waitMs)
    }

    @Test
    fun `三个通路 token 互不相同 失败行与成功行不同形`() {
        assertEquals(
            "重名 = 失败行又变成与成功行同形，读日志的人分不出来",
            3,
            CoverLoadRoute.entries.map { it.token }.toSet().size,
        )
        // 同一个整段（IO 段起点 10ms → 位图就绪 327ms）：成功行（字节到手、解码失败）与失败行的形状必须不同——
        // 既在 token 上（source / source-miss），也在数字上（成功行的 250/67 vs 失败行的 317/0）
        val ok = CoverLoadMeasurement.of(0L, 10 * NANOS_PER_MILLI, false, 260 * NANOS_PER_MILLI, 327 * NANOS_PER_MILLI)
        val miss = CoverLoadMeasurement.of(0L, 10 * NANOS_PER_MILLI, false, null, 327 * NANOS_PER_MILLI)
        assertNotEquals(
            "同一条 ms= 下两类行必须分得开",
            ScrollProbe.coverLoadLine(ok.segments, "io-1", ok.route),
            ScrollProbe.coverLoadLine(miss.segments, "io-1", miss.route),
        )
        assertEquals(
            "browseCoverLoad ms=317 fetchMs=250 decodeMs=67 waitMs=10 route=source thread=io-1",
            ScrollProbe.coverLoadLine(ok.segments, "io-1", ok.route),
        )
        assertEquals(
            "browseCoverLoad ms=317 fetchMs=317 decodeMs=0 waitMs=10 route=source-miss thread=io-1",
            ScrollProbe.coverLoadLine(miss.segments, "io-1", miss.route),
        )
    }

    @Test
    fun `封面加载明细行带三段 通路与线程名`() {
        val line = ScrollProbe.coverLoadLine(
            segments = CoverLoadSegments(fetchMs = 250, decodeMs = 67, waitMs = 10),
            thread = "DefaultDispatcher-worker-2",
            route = CoverLoadRoute.SourceBytes,
        )
        assertTrue("统一前缀", line.startsWith(ScrollProbe.COVER_LOAD_PREFIX))
        assertEquals(
            "三段 + 通路 + 线程名，整行仍是空格分隔的 key=value",
            "ms=317 fetchMs=250 decodeMs=67 waitMs=10 route=source thread=DefaultDispatcher-worker-2",
            line.substringAfter(' '),
        )
        assertEquals(
            "uri 通路的取字节段恒为 0（口径见 CoverLoadSegments）",
            "browseCoverLoad ms=85 fetchMs=0 decodeMs=85 waitMs=5 route=uri thread=main",
            ScrollProbe.coverLoadLine(
                segments = CoverLoadSegments(fetchMs = 0, decodeMs = 85, waitMs = 5),
                thread = "main",
                route = CoverLoadRoute.Uri,
            ),
        )
    }

    @Test
    fun `线程名里的空白折成下划线 不破坏 key=value 切分`() {
        val line = ScrollProbe.coverLoadLine(
            segments = CoverLoadSegments(fetchMs = 1, decodeMs = 0, waitMs = 0),
            thread = "my thread\t2",
            route = CoverLoadRoute.SourceBytes,
        )
        assertEquals("browseCoverLoad ms=1 fetchMs=1 decodeMs=0 waitMs=0 route=source thread=my_thread_2", line)
    }

    @Test
    fun `一位小数格式化不依赖默认 Locale`() {
        val before = Locale.getDefault()
        try {
            // 德语区小数点是逗号：直接用 String.format 会让行里出现 "12,3"，前后数字没法机械比对
            Locale.setDefault(Locale.GERMANY)
            assertEquals("12.3", oneDecimal(12.34))
            assertEquals("0.0", oneDecimal(0.0))
            assertEquals("四舍五入到一位", "2.0", oneDecimal(1.96))
            assertEquals("负数", "-1.5", oneDecimal(-1.46))
        } finally {
            Locale.setDefault(before)
        }
    }
}
