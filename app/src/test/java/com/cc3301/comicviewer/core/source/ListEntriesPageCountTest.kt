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
 * 边界（票 #30 落地后）：枚举期**一个包的字节都不读**，也不再为容器逐级下取封面位置；
 * 封面字节只在按需通路 `coverBytes`（可见行）发生，本文件也钉住该通路拿到的仍是正确封面。
 */
class ListEntriesPageCountTest {

    // fixture：
    //   alpha/cover.jpg     子目录书：含图 → 封面取目录内首图（零开销：图本来就要列出来）
    //   alpha/extra.cbz     同目录的压缩包：枚举期不为页数、也不为封面读它
    //   beta/pack.cbz       子目录书：只含压缩包 → 封面（首个包首帧）按需解出
    //   gamma/cover1.cbz    子目录书：封面 = 第一个压缩包（同样按需）
    //   gamma/cover2.cbz    同目录第二个压缩包：枚举期不为页数读它
    //   deep/inner/only.jpg 纯目录容器（封面逐级下取，只在按需通路发生）
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

    private fun source(backend: CountingBackend): DocumentTreeSource =
        DocumentTreeSource(
            backend = backend,
            progressStore = InMemoryProgressStore(),
        )

    private suspend fun entries(source: Source) = source.listEntries(null, SortMode.NAME)

    @Test
    fun `枚举含压缩包与子目录的目录不读任何字节 列表不带页数`() = runTest {
        val backend = CountingBackend(root)
        val entries = entries(source(backend))

        assertEquals(
            "枚举期不解包、不读图、也不逐级下取封面（票 #30）",
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
    fun `封面字节只在按需通路读 每个封面各解出一次 且结果照旧正确`() = runTest {
        val backend = CountingBackend(root)
        val source = source(backend)
        val entries = entries(source)
        assertEquals("枚举期零读取", emptyList<String>(), backend.readPaths)

        // 可见行按需取封面（spec 故事 9 的封面规则不变）：含图目录书取首图，只含压缩包的目录书取首个包首帧，
        // 纯容器逐级下取第一张图，压缩包取包内首页——都只在此时读字节
        val id = { name: String -> entries.first { it.name == name }.id }
        assertEquals("alpha-cover", String(source.coverBytes(id("alpha"))!!))
        assertEquals("cbz-p1.jpg", String(source.coverBytes(id("beta"))!!))
        assertEquals("deep-cover", String(source.coverBytes(id("deep"))!!))
        assertEquals("cbz-p1.jpg", String(source.coverBytes(id("top.cbz"))!!))
        assertEquals(
            setOf("alpha/cover.jpg", "beta/pack.cbz", "deep/inner/only.jpg", "top.cbz"),
            backend.readPaths.toSet(),
        )
    }

    @Test
    fun `页数在打开书时才取 打开后仍准确`() = runTest {
        val backend = CountingBackend(root)
        val source = source(backend)
        val listed = entries(source)
        assertTrue("枚举期零读取", backend.readPaths.isEmpty())

        // 打开书才付出读包内条目的代价，且页数照旧准确
        assertEquals(3, source.openBook(listed.first { it.name == "alpha" }.id).pageCount)
        assertEquals(2, source.openBook(listed.first { it.name == "top.cbz" }.id).pageCount)
        assertTrue("页数由打开书时的包内条目得出", backend.readPaths.any { it == "alpha/extra.cbz" })
    }

    @Test
    fun `列表条目页数一律为空 封面位置只给零开销的那一半`() = runTest {
        val source = source(CountingBackend(root))
        val entries = entries(source)

        assertTrue("页数不在枚举期统计（票 #36）", entries.all { it.pageCount == null })
        // 含图目录的书：封面 = 目录内首图。这一条 uri 是零开销的（图本来就要列出来）
        assertTrue(entries.first { it.name == "alpha" }.coverUri!!.endsWith("cover.jpg"))
        // 容器与压缩包：枚举期不给封面位置（旧实现会逐级下取 / 解包取首页，票 #30 移除），由按需通路承担
        assertNull(entries.first { it.name == "deep" }.coverUri)
        assertNull(entries.first { it.name == "top.cbz" }.coverUri)
    }


    @Test
    fun `封面字节会话内复用 二次取同一封面不再读字节`() = runTest {
        // 票 #51 F2：只缓存解码后的位图时，位图命中也要先向来源要一遍字节——
        // 「进子目录 → 返回上级」与「退出阅读器再回来」都会重下封面。现在字节层面命中。
        val backend = CountingBackend(root)
        val source = source(backend)
        val entries = entries(source)
        val deep = entries.first { it.name == "deep" }.id

        assertEquals("deep-cover", String(source.coverBytes(deep)!!))
        val afterFirst = backend.readPaths.toList()

        assertEquals("deep-cover", String(source.coverBytes(deep)!!))
        assertEquals("二次取封面命中会话字节缓存：一个字节都不再读", afterFirst, backend.readPaths)
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
