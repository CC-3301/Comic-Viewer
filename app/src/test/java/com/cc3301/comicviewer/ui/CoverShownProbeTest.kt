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
     * 等待上限取**真实时间**而不是轮数：一轮 `measure + layout + idle` 的耗时随机型 / JIT 差两个数量级
     * （实测 4ms ~ 280ms），按轮数等就会偶发「等不到」——r1 的 20 轮空转加起来只有 0.086s，
     * 等不到 IO 线程上真解完那张合成 PNG（`coverShownFrames` 停在 0，单跑/整批都可能红）。
     */
    private val waitTimeoutNanos = 30_000_000_000L

    /**
     * 组合一格封面，然后反复「重新登记活动 + 跑一轮 UI + 投一帧」，返回摘要行。
     *
     * [decodedKey] 非空 = 这条通路先**有界等到位图真进封面分区**（[PageDecoder.cachedCover] 用位图入缓存那把键，
     * 与位图入缓存是同一挂接点）再往下数帧：Robolectric 的 `idle()` 不驱动 `Dispatchers.IO` 的真线程池，
     * 光投帧是空转、等不到解码结果。**等不到就算失败**（报等待超时），不静默继续。
     * [stopWhen] 成立即提前返回，再空跑 [tailRounds] 轮坐实（摘要里已消费的帧不再变）；两处等待都按
     * [waitTimeoutNanos] 的真实时间封顶。
     * 多投几帧不会多记：没有新封面的帧不进那三个数；每轮重新登记活动只是保活窗口（不触发静止落行，
     * 帧时间戳用「现在」，与真机上「本帧绘制之后才投递回调」同一先后）。
     */
    private fun composeFrames(
        entryId: String,
        loadBytes: suspend () -> ByteArray?,
        decodedKey: String? = null,
        stopWhen: (String) -> Boolean = { false },
        tailRounds: Int = 0,
    ): String {
        BrowseScroll.probe.onScrollSessionEnd()
        val view = composeViewInActivity {
            CoverThumb(coverUri = null, cacheKey = entryId, loadBytes = loadBytes, plan = plan)
        }
        val deadlineNanos = System.nanoTime() + waitTimeoutNanos
        var line = ""

        /** 一轮：登记活动 → 跑一轮 UI（驱动组合与 effect）→ 投一帧（真机上帧回调在本帧绘制之后投递） */
        fun frameRound() {
            BrowseScroll.probe.markScrollActivity(System.nanoTime())
            view.layoutOnce(widthPx = 400, heightPx = 800)
            val flushed = BrowseScroll.probe.onFrame(
                totalNanos = 20_000_000,
                layoutNanos = 2_000_000,
                drawNanos = 40_000_000,
                frameNanos = System.nanoTime(),
            )
            line = flushed ?: BrowseScroll.probe.summaryLine()
        }

        // 先跑一轮：没有它 line 还是空串，stopWhen 里的字段查询会当场抛（摘要行还没投出来）
        frameRound()
        // ① 真取解那条通路：先等到位图真解出来（`Thread.sleep` 是给 IO 线程让出真实时间，不是等主线程 looper）
        if (decodedKey != null) {
            while (PageDecoder.cachedCover(decodedKey) == null) {
                assertTrue(
                    "等待超时（${waitTimeoutNanos / 1_000_000}ms）：$decodedKey 的位图没进封面分区（真解没跑完）；最后一行：$line",
                    System.nanoTime() < deadlineNanos,
                )
                frameRound()
                Thread.sleep(5)
            }
        }
        // ② 再投帧到摘要出现预期（位图到位之后的第一个帧回调那一帧）
        while (!stopWhen(line)) {
            assertTrue(
                "等待超时（${waitTimeoutNanos / 1_000_000}ms）：摘要里始终没出现预期，最后一行：$line",
                System.nanoTime() < deadlineNanos,
            )
            frameRound()
        }
        repeat(tailRounds) { frameRound() }
        return line
    }

    /** 封面侧记一次之后，下一个帧回调那一帧才会出现在摘要里 */
    private fun coverShownFrames(line: String): String = field(line, "coverShownFrames")

    @Test
    fun `真取解到位后记一次首次上屏`() {
        val entryId = "cover-shown-cold"
        val line = composeFrames(
            entryId = entryId,
            loadBytes = { SyntheticPng.of(600, 800) },
            // 先等到真解出来的那张进封面分区（等不到由 composeFrames 报等待超时）
            decodedKey = plan.keyOf(entryId),
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
        // 等到「取字节真被调过」（真走进了那条路）再空跑 3 轮坐实：`loaded == null` ⇒ 位图永远不记，
        // 三个数始终是 0（不空转到等待上限：这里要的不是等某个异步结果，而是确认它不会凭空冒出来）
        val line = composeFrames(
            entryId = "cover-shown-miss",
            loadBytes = { loads++; null },
            stopWhen = { loads > 0 },
            tailRounds = 3,
        )
        assertTrue("前置：真取解那条路被走过了", loads > 0)
        assertEquals("0", coverShownFrames(line))
        assertEquals("0", field(line, "coverShownMaxPerFrame"))
        assertEquals("0", field(line, "coverShownDrawMaxMs"))
    }
}
