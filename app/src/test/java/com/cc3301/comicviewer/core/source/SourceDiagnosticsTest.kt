package com.cc3301.comicviewer.core.source

import com.cc3301.comicviewer.core.source.Source
import com.cc3301.comicviewer.core.source.SortMode
import com.cc3301.comicviewer.core.source.SourceType
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * 偶发退化诊断打点（票 #113）：**行格式**与**接线**。
 *
 * 现象是「阅读中突然转圈 + 返回书柜封面变灰、十几秒后恢复」，因此本用例只锁打点本身（同轮的行为改动在 SMB 传输层，不在本用例的范围）：
 * ① 每类事件的行里必须带上真机取数要的字段（实例身份、触发原因、清掉的量）；
 * ② 打点必须落在**真正走的那条路**上（`invalidateListCache` 与 `close` 各报一次、原因不同）——
 * ②是本用例的重点：字段口径对了但接线断了，真机上照样什么都看不到（票 #109 r5 的教训）。
 * ③ 开关关着时一个字段都不拼、一行都不记（默认关、零开销）。
 *
 * 取数协议（真机上怎么开开关、抓哪些行、怎么读成时间线）见 [SourceDiagnostics] 与 `PerfTiming`。
 * 未覆盖：真机上的时间线本身（没有真实 SMB 与设备），由维护者按 #113 的协议取。
 */
class SourceDiagnosticsTest {

    /** 只做身份载体的最小来源：本用例的行为都打在别的对象上 */
    private class StubSource : Source {
        override val type = SourceType.SMB
        override suspend fun listEntries(containerId: String?, sort: SortMode) =
            throw UnsupportedOperationException("不参与本用例")

        override suspend fun openBook(bookId: String) = throw UnsupportedOperationException("不参与本用例")
        override suspend fun readProgress(bookId: String) = null
        override suspend fun writeProgress(bookId: String, pageIndex: Int, totalPages: Int) = Unit
        override suspend fun neighbors(bookId: String) = throw UnsupportedOperationException("不参与本用例")
    }

    private val lines = PerfTiming.newRecordedLinesForTest()

    @Before
    fun 打开量测开关() {
        // 与 #109 同一口径：显式覆盖平台值（`log.tag` 那套按进程缓存，整批 suite 下按执行顺序红）
        PerfTiming.forcedForTest = true
        PerfTiming.recordedLinesForTest = lines
    }

    @After
    fun 收口() {
        PerfTiming.forcedForTest = null
        PerfTiming.recordedLinesForTest = null
    }

    private fun lineWith(prefix: String): String {
        // 断言侧先取快照：打点来自任意线程，直接迭代会边写边读
        val snapshot = lines.toList()
        val hit = snapshot.filter { it.startsWith(prefix) }
        assertTrue("本段应至少有产出一行 $prefix：$snapshot", hit.isNotEmpty())
        return hit.first()
    }

    /** `key=value` 取值（日志行的字段口径就是空格分隔，这里按同一个分词读） */

    @Test
    fun `来源实例的建立与释放行带实例身份与槽位`() {
        val source = StubSource()
        val other = StubSource()

        val open = SourceDiagnostics.sourceOpenLine(source, connId = 7, slot = "browse")
        val release = SourceDiagnostics.sourceReleaseLine(
            source,
            SourceDiagnostics.RELEASE_BROWSE_REPLACED,
            closed = true,
        )

        assertEquals("browse", field(open, "slot"))
        assertEquals("7", field(open, "conn"))
        assertEquals("SMB", field(open, "source"))
        assertEquals("browseReplaced", field(release, "reason"))
        assertEquals("true", field(release, "closed"))
        assertEquals("同一实例两行必须是同一把身份", field(open, "instance"), field(release, "instance"))
        assertNotEquals("实例换了身份必须换（否则真机读不出「重建」）", field(open, "instance"), SourceDiagnostics.instanceTag(other))
    }

    @Test
    fun `封面缓存清空行带触发原因与清掉的量`() {
        val source = StubSource()
        val line = SourceDiagnostics.coverCacheClearLine(source, SourceDiagnostics.CLEAR_ON_REFRESH, entries = 12, bytes = 4096)

        assertEquals("refresh", field(line, "reason"))
        assertEquals("12", field(line, "entries"))
        assertEquals("4096", field(line, "bytes"))
        assertEquals(SourceDiagnostics.instanceTag(source), field(line, "instance"))
    }

    @Test
    fun `SMB 会话行分得清新建与重建`() {
        val line = SourceDiagnostics.smbSessionOpenLine("nas.local", 445, "comics", rebuilt = true)

        assertEquals("nas.local", field(line, "host"))
        assertEquals("445", field(line, "port"))
        assertEquals("comics", field(line, "share"))
        assertEquals("true", field(line, "rebuilt"))
    }

    @Test
    fun `SMB 读失败行带操作 等待时长 类型与异常类名`() {
        val line = SourceDiagnostics.smbReadFailLine(op = "bytes", ms = 1_049L, kind = "peerClose", ex = "TransportException")

        assertEquals("bytes", field(line, "op"))
        assertEquals("1049", field(line, "ms"))
        assertEquals("peerClose", field(line, "kind"))
        assertEquals("TransportException", field(line, "ex"))
    }

    @Test
    fun `SMB 重建行带尝试号 四段耗时与失败段`() {
        val failed = SourceDiagnostics.smbRebuildLine(
            attempt = 2,
            closeMs = 3,
            connectMs = 10_012,
            authMs = 0,
            shareMs = 0,
            ms = 10_018,
            failedSegment = "connect",
            ex = "TransportException",
        )
        val ok = SourceDiagnostics.smbRebuildLine(
            attempt = 3,
            closeMs = 1,
            connectMs = 220,
            authMs = 40,
            shareMs = 12,
            ms = 275,
            failedSegment = null,
            ex = null,
        )

        assertEquals("2", field(failed, "attempt"))
        assertEquals("10012", field(failed, "connectMs"))
        assertEquals("10018", field(failed, "ms"))
        assertEquals("没跑到的那一段就是 0，所以必须看 failed=", "connect", field(failed, "failed"))
        assertEquals("3", field(failed, "closeMs"))
        assertEquals("失败在连接段：认证与进共享都没跑到", "0", field(failed, "authMs"))
        assertEquals("0", field(failed, "shareMs"))
        assertEquals("none", field(ok, "failed"))
        assertEquals("none", field(ok, "ex"))
    }

    @Test
    fun `SMB 探活行分得清真探与跳过`() {
        val probed = SourceDiagnostics.smbProbeLine(probed = true, ok = false, ms = 31)
        val skipped = SourceDiagnostics.smbProbeLine(probed = false, ok = true, ms = 0)

        assertEquals("probe", field(probed, "tick"))
        assertEquals("false", field(probed, "ok"))
        assertEquals("31", field(probed, "ms"))
        assertEquals("跳过的一拍不能报成探活成功（否则判读会被带反）", "skip", field(skipped, "tick"))
    }

    @Test
    fun `整体清空返回清掉的条目数与字节数`() {
        val cache = CoverByteCache(maxEntries = 8, maxBytes = 1024)
        cache.put("a", ByteArray(10))
        cache.put("b", ByteArray(20))

        val cleared = cache.clear()

        assertEquals(2, cleared.entries)
        assertEquals(30L, cleared.bytes)
        assertEquals("清空后查不到", null, cache.get("a"))
        assertEquals("已经空了：再清一次是 0", 0, cache.clear().entries)
    }

    @Test
    fun `刷新与释放两条路各报一次 原因不同`() {
        val source = DocumentTreeSource(FakeTreeBackend(fakeDir("root")), InMemoryProgressStore())

        source.invalidateListCache(null)
        source.close()

        val clears = lines.toList().filter { it.startsWith("coverCacheClear") }
        assertEquals("刷新与释放各报一次：$clears", 2, clears.size)
        assertEquals("refresh", field(clears[0], "reason"))
        assertEquals("close", field(clears[1], "reason"))
        assertEquals(
            "同一次会话里清两次：实例身份必须相同",
            field(clears[0], "instance"),
            field(clears[1], "instance"),
        )
        assertEquals(SourceDiagnostics.instanceTag(source), field(clears[0], "instance"))
    }

    @Test
    fun `开关关着时一行都不记`() {
        PerfTiming.forcedForTest = false
        val source = DocumentTreeSource(FakeTreeBackend(fakeDir("root")), InMemoryProgressStore())

        source.invalidateListCache(null)
        source.close()

        assertTrue("默认关：一个字段都不拼、一行都不记", lines.toList().isEmpty())
    }
}
