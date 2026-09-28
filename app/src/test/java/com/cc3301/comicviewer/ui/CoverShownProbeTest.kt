package com.cc3301.comicviewer.ui

import androidx.compose.ui.unit.dp
import com.cc3301.comicviewer.core.source.PerfTiming
import com.cc3301.comicviewer.core.source.field
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * 封面「首次上屏」打点的接线（票 #145 帧级打点轮）：**位图一到位就记一次**，落进下一个帧回调。
 *
 * 锁的是真件本身（[CoverThumb] 的三处出口：真取解解出位图、组合期命中内存缓存、以及位图始终没到位），
 * 计数口径在 `core/view/ScrollProbe`（`coverShownFrames` / `coverShownDrawMaxMs` / `coverShownMaxPerFrame`）。
 * 它锁不住的部分：真机上「就绪 → 帧回调」的投递顺序（主线程帧绘制之后投递，最多差一帧）需真机取数才判。
 *
 * 帧量测本用例手动投（不注册平台的 `Window.OnFrameMetricsAvailableListener`）：这里要钉的是**封面侧那一刻
 * 有没有记数**，不是平台的帧数据本身。开关用 [PerfTiming.forcedForTest] **显式**打开（不靠 `log.tag`：
 * 平台值是进程级懒值，整批用例里谁先读到就定死，与 `BrowseItemCountTest` 同一套接法）。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class CoverShownProbeTest {

    private val plan = CoverPlan.of(CoverSizing.OwnAspect(56.dp), density = 2.75f, reloadKey = 0)

    @Before
    fun 打开量测开关() {
        PerfTiming.forcedForTest = true
    }

    @After
    fun 收口窗口() {
        PerfTiming.forcedForTest = null
        BrowseScroll.probe.onScrollSessionEnd()
    }

    /**
     * 组合一格封面，然后反复「重新登记活动 + 跑一轮 UI + 投一帧」，直到摘要满足 [stopWhen]（或到 [maxRounds]），
     * 返回最后那一行的摘要。
     *
     * 为什么要反复、而不是「等条件 + 投一帧」：位图到位在 IO 线程，而赋值与「记一次」在主线程的 effect 续体里——
     * Robolectric 一轮 `measure + layout + idle` **不保证**那个续体已经跑过（同 `layoutUntil` 记的那个坑），
     * 单投一帧会偶发读到 `coverShownFrames=0`（已真实发生：同类用例单跑绿、整批跑红）。
     * 多投几帧不会多记：没有新封面的帧不进那三个数；每轮重新登记活动只是保活窗口（不触发静止落行，
     * 帧时间戳用「现在」，与真机上「本帧绘制之后才投递回调」同一先后）。
     */
    private fun composeFrames(
        entryId: String,
        loadBytes: suspend () -> ByteArray?,
        maxRounds: Int = 20,
        stopWhen: (String) -> Boolean = { false },
    ): String {
        BrowseScroll.probe.onScrollSessionEnd()
        val view = composeViewInActivity {
            CoverThumb(coverUri = null, cacheKey = entryId, loadBytes = loadBytes, plan = plan)
        }
        var line = ""
        repeat(maxRounds) {
            BrowseScroll.probe.markScrollActivity(System.nanoTime())
            view.layoutOnce(widthPx = 400, heightPx = 800)
            val flushed = BrowseScroll.probe.onFrame(
                totalNanos = 20_000_000,
                layoutNanos = 2_000_000,
                drawNanos = 40_000_000,
                frameNanos = System.nanoTime(),
            )
            line = flushed ?: BrowseScroll.probe.summaryLine()
            if (stopWhen(line)) return line
        }
        return line
    }

    /** 封面侧记一次之后，下一个帧回调那一帧才会出现在摘要里 */
    private fun coverShownFrames(line: String): String = field(line, "coverShownFrames")

    @Test
    fun `真取解到位后记一次首次上屏`() {
        val line = composeFrames(
            entryId = "cover-shown-cold",
            loadBytes = { SyntheticPng.of(600, 800) },
            stopWhen = { coverShownFrames(it) == "1" },
        )
        assertEquals("有 1 帧有新封面上屏（位图到位后的第一个帧回调）", "1", coverShownFrames(line))
        assertEquals("单帧最多 1 张", "1", field(line, "coverShownMaxPerFrame"))
        assertEquals("记的是那一帧的 drawMs", "40", field(line, "coverShownDrawMaxMs"))
    }

    @Test
    fun `组合期命中内存缓存也算一次首次上屏`() {
        val entryId = "cover-shown-hit"
        // 先真解一张进封面分区（键 = 这条路的内存缓存键），模拟「返回浏览页时封面全是内存命中」
        assertTrue(
            "前置：缓存已就位",
            PageDecoder.decodeCoverBytes(
                plan.keyOf(entryId),
                SyntheticPng.of(600, 800),
                plan.widthPx,
                plan.cropTarget,
            ) != null,
        )
        var loads = 0
        val line = composeFrames(
            entryId = entryId,
            loadBytes = { loads++; null },
            stopWhen = { coverShownFrames(it) == "1" },
        )
        assertEquals("命中就不向来源要字节", 0, loads)
        assertEquals("命中即首帧有图：这一帧照样算一次首次上屏", "1", coverShownFrames(line))
    }

    @Test
    fun `位图始终没到位就不记首次上屏`() {
        var loads = 0
        // 跑到轮数上限（不提前停）：取字节真的被调过（真走进了那条路），而三个数始终是 0
        val line = composeFrames(
            entryId = "cover-shown-miss",
            loadBytes = { loads++; null },
        )
        assertTrue("前置：真取解那条路被走过了", loads > 0)
        assertEquals("0", coverShownFrames(line))
        assertEquals("0", field(line, "coverShownMaxPerFrame"))
        assertEquals("0", field(line, "coverShownDrawMaxMs"))
    }
}
