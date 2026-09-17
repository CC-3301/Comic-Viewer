package com.cc3301.comicviewer.core.source

import com.cc3301.comicviewer.core.source.fs.FileBackend
import com.cc3301.comicviewer.core.source.fs.FsBackend
import com.cc3301.comicviewer.core.source.fs.FsNode
import com.cc3301.comicviewer.core.source.zip.RandomAccessBytes
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * 枚举期不统计页数（票 #36）：文件源（本地/SAF、SMB、WebDAV 共用 [DocumentTreeSource]）列目录时
 * 不得为页数读压缩包中央目录、不得为页数列子目录——列表条目的 pageCount 一律为 null，
 * 页数只在打开书后由 `BookHandle.pageCount` 给出。
 *
 * 用计数型 [FsBackend] 锁住「读了多少字节」：这是 SAF provider IPC / SMB / WebDAV 上真实往返的来源，
 * 只看返回值（pageCount 是否为 null）不足以证明没有发生读取。
 *
 * 边界：压缩包**封面**的解出仍会读一次包内条目（票 10 既有浏览列表行为，其按需化属 #30），
 * 因此断言写成「哪些包被读了」而不是「一次都不读」。
 */
class ListEntriesPageCountTest {

    // fixture：
    //   alpha/cover.jpg     子目录书：封面取自目录内图片 → 它内部的压缩包除了页数没有任何理由被读
    //   alpha/extra.cbz
    //   beta/pack.cbz       子目录书：封面 = 包内首页
    //   gamma/cover1.cbz    子目录书：封面 = 第一个压缩包；第二个压缩包旧实现只为页数读
    //   gamma/cover2.cbz
    //   deep/inner/only.jpg 纯目录容器（封面逐级下取，属封面通路）
    //   top.cbz             根下直接列出的压缩包
    private val root: File = Files.createTempDirectory("list-pagecount").toFile().also { dir ->
        File(dir, "alpha").mkdirs()
        File(dir, "alpha/cover.jpg").writeBytes("alpha-cover".toByteArray())
        writeCbz(File(dir, "alpha/extra.cbz"), listOf("p1.jpg", "p2.jpg"))
        File(dir, "beta").mkdirs()
        writeCbz(File(dir, "beta/pack.cbz"), listOf("p1.jpg", "p2.jpg"))
        File(dir, "gamma").mkdirs()
        writeCbz(File(dir, "gamma/cover1.cbz"), listOf("p1.jpg"))
        writeCbz(File(dir, "gamma/cover2.cbz"), listOf("p1.jpg"))
        File(dir, "deep/inner").mkdirs()
        File(dir, "deep/inner/only.jpg").writeBytes("deep-cover".toByteArray())
        writeCbz(File(dir, "top.cbz"), listOf("p1.jpg", "p2.jpg"))
    }

    private fun source(backend: CountingBackend, coverCacheDir: File?): DocumentTreeSource =
        DocumentTreeSource(
            backend = backend,
            progressStore = InMemoryProgressStore(),
            coverCacheDir = coverCacheDir,
        )

    private suspend fun entries(source: Source) = source.listEntries(null, SortMode.NAME)

    @Test
    fun `枚举含压缩包与子目录的目录不读任何包 列表不带页数`() = runTest {
        // 不生成 CBZ 封面（coverCacheDir = null）：枚举期连封面都不该读，更不该为页数读
        val backend = CountingBackend(root)
        val entries = entries(source(backend, coverCacheDir = null))

        assertEquals(
            "枚举期不读压缩包中央目录（票 #36）",
            emptyList<String>(),
            backend.readPaths,
        )
        assertEquals(
            listOf("alpha", "beta", "deep", "gamma", "top.cbz"),
            entries.map { it.name },
        )
        assertFalse("只含子目录的 deep 仍是容器", entries.first { it.name == "deep" }.isBook)
        // 书/容器的判定与名称不受影响；页数全部为 null（不再统计）
        assertTrue(entries.filter { it.name != "deep" }.all { it.isBook })
        assertTrue(entries.all { it.pageCount == null })
    }

    @Test
    fun `有封面缓存时只为封面读包 不为页数读`() = runTest {
        val backend = CountingBackend(root)
        val source = source(backend, coverCacheDir = Files.createTempDirectory("list-pagecount-covers").toFile())
        val entries = entries(source)

        // 每个压缩包封面解出各读一次；alpha 的封面取自 cover.jpg、gamma 的封面是 cover1.cbz，
        // 因此 alpha/extra.cbz 与 gamma/cover2.cbz 的包内条目一次都不该被碰（旧实现为页数读它们）。
        // 边界：本夹具只统计读字节（readBytes/openRandomAccess），「不为页数列子目录」那一半由枚举路径的代码审查守护；
        // 期望集合里的封面读读取属既有封面通路（其移除见 #30），#30 落地后需同步更新本期望值。
        assertEquals(
            setOf("beta/pack.cbz", "gamma/cover1.cbz", "top.cbz"),
            backend.readPaths.toSet(),
        )
        assertTrue(entries.all { it.pageCount == null })
        assertEquals(setOf("alpha", "beta", "deep", "gamma", "top.cbz"), entries.map { it.name }.toSet())
    }

    @Test
    fun `页数在打开书时才取 打开后仍准确`() = runTest {
        val backend = CountingBackend(root)
        val source = source(backend, coverCacheDir = null)
        val listed = entries(source)
        assertTrue("枚举期零读取", backend.readPaths.isEmpty())

        // 打开书才付出读包内条目的代价，且页数照旧准确
        assertEquals(3, source.openBook(listed.first { it.name == "alpha" }.id).pageCount)
        assertEquals(2, source.openBook(listed.first { it.name == "top.cbz" }.id).pageCount)
        assertTrue("页数由打开书时的包内条目得出", backend.readPaths.any { it == "alpha/extra.cbz" })
    }

    @Test
    fun `列表条目不统计页数 但封面位置照旧给出`() = runTest {
        val entries = entries(source(CountingBackend(root), coverCacheDir = null))

        assertNull(entries.first { it.name == "top.cbz" }.pageCount)
        assertTrue(entries.first { it.name == "alpha" }.coverUri!!.endsWith("cover.jpg"))
        assertTrue("容器封面仍逐级下取到第一张图", entries.first { it.name == "deep" }.coverUri!!.endsWith("only.jpg"))
    }

    private fun writeCbz(file: File, imageNames: List<String>) {
        file.parentFile?.mkdirs()
        ZipOutputStream(file.outputStream()).use { zip ->
            imageNames.forEach { name ->
                zip.putNextEntry(ZipEntry(name))
                zip.write(("cbz-" + name).toByteArray())
                zip.closeEntry()
            }
        }
    }

    /** 计数型后端：记录枚举/打开书期间哪些节点被读了字节（相对 root 的路径） */
    private class CountingBackend(rootDir: File) : FsBackend {
        private val delegate = FileBackend(rootDir)
        private val rootPath = rootDir.absoluteFile.path

        val readPaths = mutableListOf<String>()

        override val root: FsNode = CountingNode(delegate.root, this)

        override fun resolve(id: String): FsNode? = delegate.resolve(id)?.let { CountingNode(it, this) }

        fun noteRead(id: String) {
            readPaths += id.removePrefix(rootPath).trimStart(File.separatorChar).replace(File.separatorChar, '/')
        }
    }

    private class CountingNode(private val delegate: FsNode, private val counter: CountingBackend) : FsNode {
        override val id: String get() = delegate.id
        override val name: String get() = delegate.name
        override val isDirectory: Boolean get() = delegate.isDirectory
        override val lastModifiedMs: Long? get() = delegate.lastModifiedMs
        override val imageUri: String get() = delegate.imageUri

        override fun children(): List<FsNode> = delegate.children().map { CountingNode(it, counter) }
        override fun parent(): FsNode? = delegate.parent()?.let { CountingNode(it, counter) }

        override fun readBytes(): ByteArray {
            counter.noteRead(delegate.id)
            return delegate.readBytes()
        }

        override fun openRandomAccess(): RandomAccessBytes {
            counter.noteRead(delegate.id)
            return delegate.openRandomAccess()
        }
    }
}
