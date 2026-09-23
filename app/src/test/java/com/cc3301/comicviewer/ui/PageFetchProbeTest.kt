package com.cc3301.comicviewer.ui

import com.cc3301.comicviewer.core.source.BookHandle
import com.cc3301.comicviewer.core.source.PageData
import com.cc3301.comicviewer.core.source.PerfTiming
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Test

/**
 * 取页打点 `pageBytes` 的字段口径（票 #113；r3 按评审去掉名实不符的 `net=`）。
 *
 * 真机上「阅读器突然转圈」要对得上一行「这一页**没命中页磁盘缓存**、当场向来源取」：
 * - `disk=true` ⇒ 页磁盘缓存命中，这次取页**没碰来源**（远端来源上也就没有网络）；
 * - `disk=false` ⇒ 未命中，字节是当场向来源取的（本用例锁这一支：接缝上给一个会返回字节的假句柄，
 *   磁盘缓存里没有它）。
 *
 * **`net=` 为什么被去掉**：它只是 `disk=` 的取反，名字却暗示「走了网络」——被 `BlockCachedRandomAccess`
 * 的进程内块缓存接住的那次也会是 `net=true`（`remoteRead` 根本不发），维护者会按字段名把根因读反。
 * 要判「慢在不在网络」，看同一时间段有没有 `remoteRead` 行（`PerfTiming` 的既有打点）——**图片书那支
 * 根本不发它**（`SourceDiagnostics` 的 `from=image`），它的缺席不代表没走网络；
 * 因此这里顺带锁住「不再发这个字段」，防止它被加回来继续误导取数。
 */
class PageFetchProbeTest {

    private class FakeHandle(private val bytes: ByteArray) : BookHandle {
        override val id = "page-probe-book"
        override val pageCount = 1
        override suspend fun loadPage(index: Int) = PageData(bytes, "image/jpeg")
    }

    private val lines = PerfTiming.newRecordedLinesForTest()

    @Before
    fun 打开量测开关() {
        PerfTiming.forcedForTest = true
        PerfTiming.recordedLinesForTest = lines
    }

    @After
    fun 收口() {
        PerfTiming.forcedForTest = null
        PerfTiming.recordedLinesForTest = null
        lines.clear()
    }

    private fun field(line: String, key: String): String =
        line.split(' ').first { it.startsWith(key + "=") }.substringAfter('=')

    @Test
    fun `未命中页磁盘缓存时 disk=false`() = runBlocking {
        val bytes = ByteArray(64) { 7 }

        val loaded = PageDecoder.loadPageBytes(FakeHandle(bytes), 3)

        assertEquals("取页拿到的就是句柄给的字节", bytes.toList(), loaded.toList())
        // 先取快照再断言：打点来自任意线程（打点线程 / IO 调度线程）
        val line = lines.toList().single { it.startsWith("pageBytes") }
        assertEquals(3.toString(), field(line, "index"))
        assertEquals("磁盘未命中 ⇒ 本次字节当场向来源取（远端来源上即一次可能的网络往返）", "false", field(line, "disk"))
        assertEquals("64", field(line, "bytes"))
        assertFalse(
            "不再发 net=：它只是 disk= 的取反却暗示「走了网络」，真假网络往返要看 remoteRead 行",
            line.contains("net="),
        )
    }
}
