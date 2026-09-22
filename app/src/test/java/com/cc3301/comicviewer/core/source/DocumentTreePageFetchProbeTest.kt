package com.cc3301.comicviewer.core.source

import com.cc3301.comicviewer.core.source.fs.FileBackend
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * 取页打点的 `from=`（图片书 / 压缩包书）与判读规则的前提（票 #113 r4 的 P1 修复）。
 *
 * r3 删掉 `net=` 后给出的判读规则是「`disk=false` 且没有 `remoteRead` ⇒ 被进程内块缓存接住、慢不在网络」。
 * **这条对图片书不成立**：图片书的页字节走 `FilePageRef.bytes()` → `node.readBytes()`，每一次都真读一次
 * 来源后端，而这条路上**根本不发 `remoteRead`**。维护者按旧规则会把自己正确的根因（网络慢）**反向排除**，
 * 整票取数白做。
 *
 * 修法 = 给来源侧 `loadPage` 加一个**每个页路径都会发**的字段 `from=`，判读规则按它分两段写；本用例锁两件：
 * ① 图片书取页发 `from=image`，且这条路上**确实不发** `remoteRead`（把旧规则的错误前提钉成可执行断言——
 *    哪天这条路径真开始发 `remoteRead`，本用例会红，规则文字必须同步改）；
 * ② 压缩包书取页发 `from=archive`（另一条判读分支的接线）。
 * 拿掉 ` from=…` 这个字段两条例都会红（不是只改注释）。
 */
class DocumentTreePageFetchProbeTest {

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

    private fun loadPageLines() = lines.filter { it.startsWith("loadPage") }

    /** 极小 CBZ（只装一页 jpg）：夹具的 `openRandomAccess` 吃这份字节 */
    private fun cbzBytes(): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            zip.putNextEntry(ZipEntry("001.jpg"))
            zip.write("page".toByteArray())
            zip.closeEntry()
        }
        return out.toByteArray()
    }

    @Test
    fun `图片书取页发 from=image 且这条路上不发 remoteRead`() = runBlocking {
        // 真文件系统后端：图片书的页字节走 node.readBytes()，夹具的 FakeTreeNode 有意不读字节
        val root = Files.createTempDirectory("page-fetch-probe").toFile()
        File(root, "001.jpg").writeBytes("page".toByteArray())
        val source = DocumentTreeSource(
            FileBackend(root),
            InMemoryProgressStore(),
            sourceType = SourceType.SMB,
        )
        // 本层图片各自成书：这一条就是「图片书」那条路（页字节 = 直接读来源）
        val book = source.listEntries(null, SortMode.NAME).single()
        val handle = source.openBook(book.id)
        handle.loadPage(0)

        val line = loadPageLines().last()
        assertEquals("图片书页（FilePageRef）", SourceDiagnostics.FROM_IMAGE, field(line, "from"))
        assertEquals("SMB", field(line, "source"))
        assertTrue("慢页要能归到实例：$line", field(line, "instance").startsWith("DocumentTreeSource#"))
        assertTrue("要带上取页耗时：$line", field(line, "ms").toLong() >= 0L)

        assertTrue(
            "图片书这条页路径本就不发 remoteRead——旧判读规则（拿 remoteRead 的缺席当「没走网络」）因此会把" +
                "网络慢反向排除（票 #113 r4）。若这条断言红了，说明该路径开始发 remoteRead，判读规则要同步改：" +
                "lines=" + lines,
            lines.none { it.startsWith("remoteRead") },
        )
    }

    @Test
    fun `压缩包书取页发 from=archive`() = runBlocking {
        val pack = FakeTreeNode("root/book.cbz", "book.cbz", isDirectory = false)
            .apply { packBytes = cbzBytes() }
        val source = DocumentTreeSource(
            FakeTreeBackend(fakeDir("root").add(pack)),
            InMemoryProgressStore(),
            sourceType = SourceType.SMB,
        )
        val book = source.listEntries(null, SortMode.NAME).single()
        val handle = source.openBook(book.id)
        handle.loadPage(0)

        assertEquals(
            "压缩包内页（ZipPageRef）——判读规则要看同期有没有 remoteRead",
            SourceDiagnostics.FROM_ARCHIVE,
            field(loadPageLines().last(), "from"),
        )
    }
}
