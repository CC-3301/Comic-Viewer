package com.cc3301.comicviewer.ui.nav

import com.cc3301.comicviewer.core.source.PerfTiming
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * 黑帧取数的时刻线：一行一个时刻、`t` 以这次过渡的 t0 为基准、同一次过渡共用一个 `id`。
 *
 * 钉住四件在设备上读不出来的事（读错的话那五个时刻的差值就没有意义）：
 * 1. **t0 是「导航请求」还是「栈变化」**——[NavTransitionTimeline.request] 之后的第一次 `begin` 用请求时刻当 t0，
 *    请求过期（守卫为假 ⇒ 永远不会来 `begin`）时不得冒充下一次过渡的 t0；
 * 2. **只记一次**——[NavTransitionTimeline.mark] 的 `onceKey`（绘制块每帧都会被求值）；
 * 3. **迟到窗口**——超过 [NavTransitionTimeline.LATE_WINDOW_MILLIS] 的打点丢掉（宁可少一行也不认错过渡）；
 * 4. **开关关着时零副作用**——一行不产，`id` 也不推进。
 *
 * 行格式（[NavTransitionTimeline.timelineLine]）单独钉一次：设备上按它 grep。
 */
class NavTransitionTimelineTest {

    private lateinit var lines: MutableList<String>

    @Before
    fun setUp() {
        NavTransitionTimeline.resetForTest()
        lines = PerfTiming.newRecordedLinesForTest()
        PerfTiming.recordedLinesForTest = lines
        PerfTiming.forcedForTest = true
    }

    @After
    fun tearDown() {
        PerfTiming.forcedForTest = null
        PerfTiming.recordedLinesForTest = null
    }

    /** 时刻线在整套用例里是**同一个对象**（生产也是单例）⇒ 逐次递增的 `id` 不能写死，统一抹成 `id=N` */
    private fun normalized(): List<String> = lines.toList().map { it.replace(Regex(" id=\\d+ "), " id=N ") }

    @Test
    fun `行格式是 前缀 id phase t 加自由字段`() {
        assertEquals(
            "navTransitionDetail id=3 phase=firstDraw t=12ms entry=e1 offX=1080 travel=1080",
            NavTransitionTimeline.timelineLine(
                3,
                "firstDraw",
                12,
                "entry=e1 offX=1080 travel=1080",
            ),
        )
        assertEquals(
            "自由字段为空时不留尾随空格（真机上 grep 与机械比对都按这个形状）",
            "navTransitionDetail id=3 phase=animStart t=13ms",
            NavTransitionTimeline.timelineLine(3, "animStart", 13, ""),
        )
    }

    @Test
    fun `request 之后的第一次 begin 用请求时刻当 t0`() {
        NavTransitionTimeline.request("enterReader", nowNanos = 0L)
        NavTransitionTimeline.begin("enterReader", nowNanos = 5_000_000L) { "from=/browse to=/reader" }
        NavTransitionTimeline.mark("compose", nowNanos = 15_000_000L) { "entry=e1" }

        assertEquals(
            "begin 的 t 是「请求 → 栈变化」的差（5ms），mark 的 t 从请求那一刻算（15ms）",
            listOf(
                "navTransitionDetail id=N phase=begin t=5ms kind=enterReader from=/browse to=/reader",
                "navTransitionDetail id=N phase=compose t=15ms entry=e1",
            ),
            normalized(),
        )
    }

    @Test
    fun `没有 request 时 begin 自己当 t0`() {
        NavTransitionTimeline.begin("hierarchy", nowNanos = 1_000_000L) { "from=/a to=/a/b" }
        NavTransitionTimeline.mark("compose", nowNanos = 4_000_000L) { "entry=e1" }

        assertEquals(
            listOf(
                "navTransitionDetail id=N phase=begin t=0ms kind=hierarchy from=/a to=/a/b",
                "navTransitionDetail id=N phase=compose t=3ms entry=e1",
            ),
            normalized(),
        )
    }

    @Test
    fun `过期的 request 不当 t0 用`() {
        val late = NavTransitionTimeline.REQUEST_TTL_MILLIS * 1_000_000L + 1
        NavTransitionTimeline.request("enterReader", nowNanos = 0L)
        NavTransitionTimeline.begin("enterReader", nowNanos = late) { "from=/browse to=/reader" }

        assertEquals(
            "过期请求只作为 staleRequest= 留在行里（看得见它发生过），t0 改用 begin 那一刻",
            listOf(
                "navTransitionDetail id=N phase=begin t=0ms kind=enterReader from=/browse to=/reader " +
                    "staleRequest=enterReader",
            ),
            normalized(),
        )
    }

    @Test
    fun `不同类的年轻 request 也不当 t0 用`() {
        // 点了书（守卫为假 ⇒ 永远不会来同类的 begin），紧接着用户返回上级：那一次 request 是另一次导航的，
        // 不能当这次层级导航的 t0（否则 t 会整体偏移，看起来像「导航被挡住了 300ms」）
        NavTransitionTimeline.request("enterReader", nowNanos = 0L)
        NavTransitionTimeline.begin("hierarchy", nowNanos = 3_000_000L) { "from=/a to=/a/b" }

        assertEquals(
            listOf("navTransitionDetail id=N phase=begin t=0ms kind=hierarchy from=/a to=/a/b staleRequest=enterReader"),
            normalized(),
        )
    }

    @Test
    fun `同一个 onceKey 在一次过渡里只记一次`() {
        NavTransitionTimeline.begin("enterReader", nowNanos = 0L) { "from=/browse to=/reader" }
        NavTransitionTimeline.mark("firstDraw", onceKey = "draw:e1", nowNanos = 1_000_000L) {
            "entry=e1 offX=1080 travel=1080"
        }
        NavTransitionTimeline.mark("firstDraw", onceKey = "draw:e1", nowNanos = 2_000_000L) {
            "entry=e1 offX=900 travel=1080"
        }
        NavTransitionTimeline.mark("firstDraw", onceKey = "draw:e2", nowNanos = 3_000_000L) {
            "entry=e2 offX=0 travel=1080"
        }

        assertEquals(
            "同一屏只记首帧；另一屏各自记（旧屏也要看它首帧的 offX）",
            listOf(
                "navTransitionDetail id=N phase=begin t=0ms kind=enterReader from=/browse to=/reader",
                "navTransitionDetail id=N phase=firstDraw t=1ms entry=e1 offX=1080 travel=1080",
                "navTransitionDetail id=N phase=firstDraw t=3ms entry=e2 offX=0 travel=1080",
            ),
            normalized(),
        )
    }

    @Test
    fun `下一次 begin 会清掉上一轮的 onceKey`() {
        NavTransitionTimeline.begin("enterReader", nowNanos = 0L) { "from=/browse to=/reader" }
        NavTransitionTimeline.mark("animStart", onceKey = "anim:e1", nowNanos = 1_000_000L) { "entry=e1" }
        NavTransitionTimeline.begin("exitReader", nowNanos = 2_000_000L) { "from=/reader to=/browse" }
        NavTransitionTimeline.mark("animStart", onceKey = "anim:e1", nowNanos = 3_000_000L) { "entry=e1" }

        assertEquals(
            "新一次过渡是新的一轮：同一个 entry 的 key 可以再记一次（否则第二次过渡的时刻会缺）",
            listOf(
                "navTransitionDetail id=N phase=begin t=0ms kind=enterReader from=/browse to=/reader",
                "navTransitionDetail id=N phase=animStart t=1ms entry=e1",
                "navTransitionDetail id=N phase=begin t=0ms kind=exitReader from=/reader to=/browse",
                "navTransitionDetail id=N phase=animStart t=1ms entry=e1",
            ),
            normalized(),
        )
    }

    @Test
    fun `超出迟到窗口的打点丢掉`() {
        val late = NavTransitionTimeline.LATE_WINDOW_MILLIS * 1_000_000L + 1
        NavTransitionTimeline.begin("enterReader", nowNanos = 0L) { "from=/browse to=/reader" }
        NavTransitionTimeline.mark("contentReady", nowNanos = late) { "book=A" }

        assertEquals("超过 10s 的迟到打点不认（宁可少一行，也不把它算到另一次过渡头上）", 1, lines.size)
    }

    @Test
    fun `begin 之前的 mark 静默丢弃`() {
        NavTransitionTimeline.mark("contentReady", nowNanos = 1_000_000L) { "book=A" }

        assertTrue("没有开着的时刻线时 mark 一行都不产（阅读页的 contentReady 可能落在任何过渡之外）", lines.isEmpty())
    }

    @Test
    fun `开关关着时一行不产 id 也不推进`() {
        PerfTiming.forcedForTest = false
        NavTransitionTimeline.request("enterReader", nowNanos = 0L)
        NavTransitionTimeline.begin("enterReader", nowNanos = 0L) { "from=/browse to=/reader" }
        NavTransitionTimeline.mark("compose", nowNanos = 1_000_000L) { "entry=e1" }

        assertTrue("关着时零开销：一行都不产", lines.isEmpty())

        PerfTiming.forcedForTest = true
        NavTransitionTimeline.begin("hierarchy", nowNanos = 0L) { "from=/a to=/a/b" }
        assertEquals("关闭期间状态不推进（id 从 1 起、也不留下脏的 once 键）", 1, lines.size)
        assertTrue("第一行就是这一轮的第一条时刻线", lines[0].startsWith("navTransitionDetail id=1 phase=begin "))
    }

    @Test
    fun `关闭时 detail 不求值`() {
        PerfTiming.forcedForTest = false
        var built = 0
        NavTransitionTimeline.begin("enterReader", nowNanos = 0L) { built++; "from=/browse to=/reader" }
        NavTransitionTimeline.mark("compose", nowNanos = 1_000_000L) { built++; "entry=e1" }

        assertEquals("关着时连 detail 的字符串都不拼（与 PerfTiming.log 的惰性口径一致）", 0, built)
    }

    @Test
    fun `kind 常量是四种过渡的日志口径`() {
        assertEquals("enterReader", NavTransitionTimeline.KIND_ENTER_READER)
        assertEquals("exitReader", NavTransitionTimeline.KIND_EXIT_READER)
        assertEquals("swapReader", NavTransitionTimeline.KIND_SWAP_READER)
        assertEquals("hierarchy", NavTransitionTimeline.KIND_HIERARCHY)
    }
}
