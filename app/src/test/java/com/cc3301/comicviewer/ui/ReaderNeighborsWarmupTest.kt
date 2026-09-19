package com.cc3301.comicviewer.ui

import com.cc3301.comicviewer.core.source.BookHandle
import com.cc3301.comicviewer.core.source.BrowseEntry
import com.cc3301.comicviewer.core.source.Neighbors
import com.cc3301.comicviewer.core.source.ReadingProgress
import com.cc3301.comicviewer.core.source.SortMode
import com.cc3301.comicviewer.core.source.Source
import com.cc3301.comicviewer.core.source.SourceType
import com.cc3301.comicviewer.core.source.smb.SmbException
import com.cc3301.comicviewer.core.source.smb.SmbFailureKind
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 阅读器进场后的邻位后台补齐（票 #93 修复轮）：[warmNeighborsQuietly] 的契约。
 *
 * 为什么单独钉这一层：进阅读器后必须调一次 `Source.warmNeighbors` 把邻位层补上（否则启动页/抽屉直接进阅读器时
 * 邻位永久为空），但**只能调这一次**：失败不重试、不轮询（离线/传输故障时邻位保持未知，与「确实到头」
 * 同一条提示），且不得回头去碰 `neighbors`/`listEntries`（否则就变成同步探测，本票的提速口径回退）。
 * 界面组合本身在仓库里没有 Compose UI 测试基建（先例：`ReaderSwapNavTest` 的同类声明），
 * 因此把这段行为收成一个可单测的 seam，调用点只留一句 `LaunchedEffect(bookId) { warmNeighborsQuietly(...) }`。
 */
class ReaderNeighborsWarmupTest {

    @Test
    fun `补齐只调一次 warmNeighbors 不碰任何同步探测`() = runTest {
        val source = RecordingSource()

        warmNeighborsQuietly(source, "root/B.cbz")

        assertEquals("只走 warmNeighbors 这一条补齐通路", listOf("warmNeighbors(root/B.cbz)"), source.calls)
        assertEquals("补齐只调一次（不重试、不轮询）", 1, source.warmCalls)
    }

    @Test
    fun `来源不可用时吞掉失败 不冒泡也不重试`() = runTest {
        val source = RecordingSource(failWarm = SmbException(SmbFailureKind.TIMEOUT, "连接超时"))

        val thrown = runCatching { warmNeighborsQuietly(source, "root/B.cbz") }.exceptionOrNull()

        assertTrue("补齐失败只吞掉（不抛给组合，也不变成错误界面）：$thrown", thrown == null)
        assertEquals("失败不重试：仍然只调一次", 1, source.warmCalls)
    }

    @Test
    fun `协程取消照常传播 不当作补齐失败`() = runTest {
        val source = RecordingSource(failWarm = CancellationException("换书/离开阅读页"))

        val thrown = runCatching { warmNeighborsQuietly(source, "root/B.cbz") }.exceptionOrNull()

        assertTrue("取消必须原样抛出（票 #26 登记项：裸 runCatching 会把取消当失败）：$thrown", thrown is CancellationException)
    }

    /** 只记录「谁被调用」的 Source 假体：补齐通路之外的方法被调到这里就会在断言里露出来 */
    private class RecordingSource(private val failWarm: Throwable? = null) : Source {
        override val type: SourceType = SourceType.SMB

        val calls = mutableListOf<String>()

        val warmCalls: Int get() = calls.count { it.startsWith("warmNeighbors") }

        override suspend fun warmNeighbors(bookId: String) {
            calls += "warmNeighbors($bookId)"
            failWarm?.let { throw it }
        }

        override suspend fun listEntries(containerId: String?, sort: SortMode): List<BrowseEntry> {
            calls += "listEntries($containerId)"
            return emptyList()
        }

        override suspend fun openBook(bookId: String): BookHandle {
            calls += "openBook($bookId)"
            throw UnsupportedOperationException("本用例不开书")
        }

        override suspend fun readProgress(bookId: String): ReadingProgress? {
            calls += "readProgress($bookId)"
            return null
        }

        override suspend fun writeProgress(bookId: String, pageIndex: Int, totalPages: Int) {
            calls += "writeProgress($bookId)"
        }

        override suspend fun neighbors(bookId: String): Neighbors {
            calls += "neighbors($bookId)"
            return Neighbors(null, null)
        }
    }
}
