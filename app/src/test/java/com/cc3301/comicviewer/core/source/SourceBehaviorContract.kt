package com.cc3301.comicviewer.core.source

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Source 行为契约（tracer-bullet seam）：全部五个来源实现（本地/SMB/WebDAV/Komga/OPDS）
 * 必须通过同一套用例。子类只需提供基于临时目录的来源实例。
 */
abstract class SourceBehaviorContract {

    /** 由子类创建被测来源；root 内已按 [buildFixture] 布好数据 */
    abstract fun createSource(root: File, progressStore: ProgressStore): Source

    // ---------- fixture 结构 ----------
    // root/
    //   series-a/            书（3 图：page1/2/3.jpg）
    //   folder-only/         容器（只有子目录 inner/x.png）
    //   mixed/               混合（2 图 + 书子目录 book-b + 纯文件夹 plain）
    protected fun buildFixture(root: File) {
        val seriesA = File(root, "series-a").apply { mkdirs() }
        writeBytes(File(seriesA, "page1.jpg"), "img-1".toByteArray())
        writeBytes(File(seriesA, "page2.jpg"), "img-2".toByteArray())
        writeBytes(File(seriesA, "page3.jpg"), "img-3".toByteArray())

        val folderOnly = File(root, "folder-only/inner").apply { mkdirs() }
        writeBytes(File(folderOnly, "x.png"), "png-bytes".toByteArray())

        val mixed = File(root, "mixed").apply { mkdirs() }
        writeBytes(File(mixed, "cover1.png"), "mixed-c1".toByteArray())
        writeBytes(File(mixed, "cover2.png"), "mixed-c2".toByteArray())
        val bookB = File(mixed, "book-b").apply { mkdirs() }
        writeBytes(File(bookB, "p1.jpg"), "bb-1".toByteArray())
        writeBytes(File(bookB, "p2.jpg"), "bb-2".toByteArray())
        File(mixed, "plain").mkdirs()
        writeBytes(File(mixed, "plain/note.txt"), "not-image".toByteArray())
    }

    private fun writeBytes(f: File, bytes: ByteArray) {
        f.parentFile.mkdirs()
        f.writeBytes(bytes)
    }

    private fun newSource(root: File): Source {
        buildFixture(root)
        return createSource(root, InMemoryProgressStore())
    }

    private fun rootEntry(source: Source, name: String): BrowseEntry = runBlocking {
        source.listEntries(null, SortMode.NAME).first { it.name == name }
    }

    // ---------- 浏览 ----------

    @Test
    fun `根容器列出三个条目且类型正确`() = runTest {
        val root = tempRoot()
        val source = newSource(root)
        val entries = source.listEntries(null, SortMode.NAME)
        assertEquals(listOf("folder-only", "mixed", "series-a"), entries.map { it.name })
        assertEquals(false, entries[0].isBook)
        assertEquals(true, entries[1].isBook)
        assertEquals(true, entries[2].isBook)
    }

    @Test
    fun `书的条目带页数与封面`() = runTest {
        val source = newSource(tempRoot())
        val seriesA = rootEntry(source, "series-a")
        assertEquals(3, seriesA.pageCount)
        assertNotNull(seriesA.coverUri)
        assertTrue(seriesA.coverUri!!.endsWith("page1.jpg"))
    }

    @Test
    fun `纯文件夹容器逐级下取封面`() = runTest {
        val source = newSource(tempRoot())
        val folderOnly = rootEntry(source, "folder-only")
        assertNull(folderOnly.pageCount)
        assertNotNull(folderOnly.coverUri)
        assertTrue(folderOnly.coverUri!!.endsWith("x.png"))
    }

    @Test
    fun `混合列表同时给出图片条目与子目录条目`() = runTest {
        val source = newSource(tempRoot())
        val mixed = rootEntry(source, "mixed")
        val inner = source.listEntries(mixed.id, SortMode.NAME)
        assertEquals(listOf("book-b", "cover1.png", "cover2.png", "plain"), inner.map { it.name })
        // 图片条目：从该图连读
        val cover1 = inner.first { it.name == "cover1.png" }
        assertEquals(2, cover1.pageCount)
        // 含图子文件夹是书；纯文件夹是容器
        assertEquals(true, inner.first { it.name == "book-b" }.isBook)
        assertEquals(false, inner.first { it.name == "plain" }.isBook)
    }

    // ---------- 打开书与取页 ----------

    @Test
    fun `目录书按序取页且字节一致`() = runTest {
        val source = newSource(tempRoot())
        val book = source.openBook(rootEntry(source, "series-a").id)
        assertEquals(3, book.pageCount)
        assertEquals("img-1", String(book.loadPage(0).bytes))
        assertEquals("img-2", String(book.loadPage(1).bytes))
        assertEquals("image/jpeg", book.loadPage(0).mimeType)
    }

    @Test
    fun `混合列表点图片条目从该图连读`() = runTest {
        val source = newSource(tempRoot())
        val mixed = rootEntry(source, "mixed")
        val cover2 = source.listEntries(mixed.id, SortMode.NAME).first { it.name == "cover2.png" }
        val book = source.openBook(cover2.id)
        assertEquals(1, book.pageCount)
        assertEquals("mixed-c2", String(book.loadPage(0).bytes))
        assertEquals("image/png", book.loadPage(0).mimeType)
    }

    @Test
    fun `取页越界抛 IndexOutOfBounds`() = runTest {
        val source = newSource(tempRoot())
        val book = source.openBook(rootEntry(source, "series-a").id)
        assertThrows(IndexOutOfBoundsException::class.java) { runBlocking { book.loadPage(3) } }
    }

    @Test
    fun `非书 id 打开抛 IllegalArgument`() = runTest {
        val source = newSource(tempRoot())
        val plain = source.listEntries(rootEntry(source, "mixed").id, SortMode.NAME)
            .first { it.name == "plain" }
        assertThrows(IllegalArgumentException::class.java) { runBlocking { source.openBook(plain.id) } }
    }

    // ---------- 进度 ----------

    @Test
    fun `进度未读返回 null`() = runTest {
        val source = newSource(tempRoot())
        assertNull(source.readProgress(rootEntry(source, "series-a").id))
    }

    @Test
    fun `进度写后读一致`() = runTest {
        val source = newSource(tempRoot())
        val bookId = rootEntry(source, "series-a").id
        source.writeProgress(bookId, pageIndex = 2, totalPages = 3)
        val progress = source.readProgress(bookId)
        assertNotNull(progress)
        assertEquals(2, progress!!.pageIndex)
        assertEquals(3, progress.totalPages)
    }

    // ---------- helper ----------

    protected fun tempRoot(): File =
        java.nio.file.Files.createTempDirectory("source-contract").toFile()
}
