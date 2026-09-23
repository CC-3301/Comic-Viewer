package com.cc3301.comicviewer.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp
import com.cc3301.comicviewer.core.source.BrowseEntry
import com.cc3301.comicviewer.core.source.PerfTiming
import com.cc3301.comicviewer.core.source.SortMode
import com.cc3301.comicviewer.core.source.Source
import com.cc3301.comicviewer.core.source.SourceType
import com.cc3301.comicviewer.core.view.CoverByteRequests
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 条目体首的滚动量测计数接线（票 #109；r6 补的验证项）：**条目 composable 体执行一次就计一次**。
 *
 * 锁的是真件本身（`BrowseRow` / `BrowserGridCell`，r6 起为 `internal` 以便组合），因此把两处
 * `BrowseScroll.probe.onItemComposed()` 任一删掉/挪到体外的分支里，本用例就红——这是 r5 只锁「键口径」时
 * 漏掉的那一面：键对了但计数点断了，真机上仍会打出 `itemsComposed=0`。
 *
 * 计数只在**活动窗口内**计（`ScrollProbe` 的口径），因此每次组合前先收口上一窗口、再登记一次活动。
 * 开关用 [PerfTiming.forcedForTest] **显式**打开（不靠 `log.tag`）：平台值是进程级懒值，整批用例里谁先读到就定死——
 * 宿主门禁实测过：靠 `ShadowLog.setLoggable` 打开时，本用例在全量 suite 下 `expected:<1> but was:<0>`。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class BrowseItemCountTest {

    /** 合成条目：无 coverUri ⇒ 封面走来源字节那条路，本用例的假来源给 null（不碰解码） */
    private val entry = BrowseEntry(id = "synthetic-book", name = "合成条目", isBook = true, coverUri = null)

    /** 行为方法都不参与本用例：`CoverThumb` 要不到字节就停在骨架占位，不影响条目体执行 */
    private class FakeSource : Source {
        override val type = SourceType.SMB
        override suspend fun listEntries(containerId: String?, sort: SortMode) = emptyList<BrowseEntry>()
        override suspend fun openBook(bookId: String) = throw UnsupportedOperationException("不参与本用例")
        override suspend fun readProgress(bookId: String) = null
        override suspend fun writeProgress(bookId: String, pageIndex: Int, totalPages: Int) = Unit
        override suspend fun neighbors(bookId: String) = throw UnsupportedOperationException("不参与本用例")
    }

    @Before
    fun 打开量测开关() {
        // 先把平台值读一次固定下来（模拟「整批用例里别的用例已先读到」的那个条件），再打开注入开关：
        // 注入开关必须能覆盖已读到的平台值，否则本用例在全量 suite 下随执行顺序红。
        val platformOn = PerfTiming.isOn
        PerfTiming.forcedForTest = true
        assertTrue("注入开关要覆盖已读到的平台值（平台值本次=$platformOn）", PerfTiming.isOn)
    }

    @After
    fun 收口窗口() {
        PerfTiming.forcedForTest = null
        BrowseScroll.probe.onScrollSessionEnd()
    }

    /** 组合一次 [content]，返回这一个活动窗口里记到的条目组合次数 */
    private fun itemComposedCount(content: @Composable () -> Unit): Int {
        BrowseScroll.probe.onScrollSessionEnd()
        BrowseScroll.probe.markScrollActivity(System.nanoTime())
        val view = composeViewInActivity(content)
        view.layoutOnce(400, 800)
        val line = BrowseScroll.probe.summaryLine()
        return line.split(' ').first { it.startsWith("itemsComposed=") }.substringAfter('=').toInt()
    }

    @Test
    fun `列表行体执行一次就计一次`() {
        val count = itemComposedCount {
            BrowseRow(
                entry = entry,
                progress = null,
                source = FakeSource(),
                coverReloadKey = null,
                coverRequests = CoverByteRequests(),
                onOpen = {},
            )
        }
        assertEquals("列表行体执行了却没计数（接线断了）", 1, count)
    }

    @Test
    fun `网格格体执行一次就计一次`() {
        val count = itemComposedCount {
            BrowserGridCell(
                entry = entry,
                progress = null,
                source = FakeSource(),
                cellWidth = 120.dp,
                cellMaxHeight = 400.dp,
                coverReloadKey = null,
                coverRequests = CoverByteRequests(),
                onOpen = {},
            )
        }
        assertEquals("网格格体执行了却没计数（接线断了）", 1, count)
    }
}
