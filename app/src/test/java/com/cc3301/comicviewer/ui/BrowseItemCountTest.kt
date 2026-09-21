package com.cc3301.comicviewer.ui

import android.os.Looper
import android.util.Log
import android.view.View
import androidx.activity.ComponentActivity
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.unit.dp
import com.cc3301.comicviewer.core.source.BrowseEntry
import com.cc3301.comicviewer.core.source.PerfTiming
import com.cc3301.comicviewer.core.source.SortMode
import com.cc3301.comicviewer.core.source.Source
import com.cc3301.comicviewer.core.source.SourceType
import com.cc3301.comicviewer.core.view.CoverByteRequests
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLog

/**
 * 条目体首的滚动量测计数接线（票 #109；r6 补的验证项）：**条目 composable 体执行一次就计一次**。
 *
 * 锁的是真件本身（`BrowseRow` / `BrowserGridCell`，r6 起为 `internal` 以便组合），因此把两处
 * `BrowseScroll.probe.onItemComposed()` 任一删掉/挪到体外的分支里，本用例就红——这是 r5 只锁「键口径」时
 * 漏掉的那一面：键对了但计数点断了，真机上仍会打出 `itemsComposed=0`。
 *
 * 计数只在**活动窗口内**计（`ScrollProbe` 的口径），因此每次组合前先收口上一窗口、再登记一次活动。
 * 开关是 `PerfTiming.isOn`（`log.tag.ComicViewerPerf`），Robolectric 下用 `ShadowLog` 打开。
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
        ShadowLog.setLoggable(PerfTiming.TAG, Log.DEBUG)
    }

    @After
    fun 收口窗口() {
        BrowseScroll.probe.onScrollSessionEnd()
    }

    /** 组合一次 [content]，返回这一个活动窗口里记到的条目组合次数 */
    private fun itemComposedCount(content: @Composable () -> Unit): Int {
        BrowseScroll.probe.onScrollSessionEnd()
        BrowseScroll.probe.markScrollActivity(System.nanoTime())
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup().get()
        val view = ComposeView(activity)
        activity.setContentView(view)
        view.setContent(content)
        view.measure(
            View.MeasureSpec.makeMeasureSpec(400, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(800, View.MeasureSpec.EXACTLY),
        )
        view.layout(0, 0, view.measuredWidth, view.measuredHeight)
        shadowOf(Looper.getMainLooper()).idle()
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
