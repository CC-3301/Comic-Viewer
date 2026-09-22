package com.cc3301.comicviewer.ui

import com.cc3301.comicviewer.core.source.BookHandle
import com.cc3301.comicviewer.core.source.PageData
import com.cc3301.comicviewer.core.source.PerfTiming
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

/**
 * 取页的 `net=` 打点（票 #113）：真机上「阅读器突然转圈」要对得上一行「这一页**当场向来源取数**」。
 *
 * 口径：[loadPageBytes] 的两条路——页磁盘缓存命中（`disk=true net=false`，不去来源、也没有网络）与
 * 未命中（`disk=false net=true`，字节当场向来源取；远端来源上这就是走网络）。
 * 本用例锁的是后者（也是本票的原始现象那一支）：接缝上只给一个「取页会返回字节」的假句柄，
 * 磁盘缓存里没有它，因此这一行必须报 `net=true`。
 *
 * 未覆盖：真机上的网络往返本身——`net=` 只说明没命中**磁盘**缓存，被进程内块缓存接住的那次仍算 `net=true`；
 * 真要确认发出了往返，得看同期的 `remoteRead` 行（`PerfTiming` 的既有打点）。
 */
class PageFetchNetProbeTest {

    private class FakeHandle(private val bytes: ByteArray) : BookHandle {
        override val id = "net-probe-book"
        override val pageCount = 1
        override suspend fun loadPage(index: Int) = PageData(bytes, "image/jpeg")
    }

    private val lines = mutableListOf<String>()

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
    fun `未命中页磁盘缓存时报 net=true`() = runBlocking {
        val bytes = ByteArray(64) { 7 }

        val loaded = PageDecoder.loadPageBytes(FakeHandle(bytes), 3)

        assertEquals("取页拿到的就是句柄给的字节", bytes.toList(), loaded.toList())
        val line = lines.single { it.startsWith("pageBytes") }
        assertEquals(3.toString(), field(line, "index"))
        assertEquals("磁盘未命中", "false", field(line, "disk"))
        assertEquals("未命中页磁盘缓存 ⇒ 本次字节当场向来源取（远端来源上即走网络）", "true", field(line, "net"))
        assertEquals("64", field(line, "bytes"))
    }
}
