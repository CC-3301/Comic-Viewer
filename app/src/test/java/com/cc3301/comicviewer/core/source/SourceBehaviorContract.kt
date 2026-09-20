package com.cc3301.comicviewer.core.source

import com.cc3301.comicviewer.core.sort.SortDirection
import com.cc3301.comicviewer.core.sort.applySortDirection
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Source 行为契约（tracer-bullet seam）：文件源三实现（本地/SMB/WebDAV）跑同一套用例，
 * 子类只需提供基于临时目录的来源实例；Komga 的 REST 语义另有独立契约测试（KomgaSourceTest/HttpKomgaApiTest）。
 */
abstract class SourceBehaviorContract {

    /** 由子类创建被测来源；root 内已按 [buildFixture] 布好数据 */
    abstract fun createSource(root: File, progressStore: ProgressStore): Source

    // ---------- fixture 结构 ----------
    // root/
    //   series-a/            书（3 图：page1/2/3.jpg）
    //   folder-only/         容器（只有子目录 inner/x.png）
    //   mixed/               容器（票 #97：2 图 + 书子目录 book-b + 纯文件夹 plain）
    //   ep 2/ ep 10/         数值排序守护（名称排序须 2 < 10）
    //   cbz/                 容器（票 #97：只含压缩包 a/b 与图片文件夹 plain-images）
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
        // 子目录名排在图片名之后（c < z）：暴露“分区拼接”与“全局名称序”的分歧
        val zBook = File(mixed, "z-book").apply { mkdirs() }
        writeBytes(File(zBook, "z1.jpg"), "zb-1".toByteArray())

        val ep2 = File(root, "ep 2").apply { mkdirs() }
        writeBytes(File(ep2, "g.jpg"), "ep-2".toByteArray())
        val ep10 = File(root, "ep 10").apply { mkdirs() }
        writeBytes(File(ep10, "g.jpg"), "ep-10".toByteArray())

        // 压缩包（票 10）：a 与 b 的 mtime 相同但 ComicInfo 年份不同 → 能区分“真读了元数据”与“退化成 mtime”
        val cbzDir = File(root, "cbz").apply { mkdirs() }
        val t = 1_600_000_000_000L
        writeCbz(
            File(cbzDir, "a.cbz"),
            listOf(
                "page2.jpg" to "a-2".toByteArray(),
                "page10.jpg" to "a-10".toByteArray(),
                "ComicInfo.xml" to
                    "<ComicInfo><Year>1999</Year><Month>1</Month><Day>1</Day></ComicInfo>".toByteArray(),
            ),
            modifiedMs = t,
        )
        writeCbz(
            File(cbzDir, "b.cbz"),
            listOf(
                "page1.jpg" to "b-1".toByteArray(),
                "ComicInfo.xml" to
                    "<ComicInfo><Year>2020</Year><Month>6</Month><Day>1</Day></ComicInfo>".toByteArray(),
            ),
            modifiedMs = t,
        )
        // 纯图片文件夹（无元数据）→ 发布时间排序回退 mtime（spec 故事 13）
        val plainImages = File(cbzDir, "plain-images").apply { mkdirs() }
        writeBytes(File(plainImages, "x.jpg"), "p-x".toByteArray())
        writeBytes(File(plainImages, "y.jpg"), "p-y".toByteArray())
        plainImages.setLastModified(1_000_000_000_000L)   // 2001-09：介于 a(1999) 与 b(2020) 之间
    }

    private fun writeCbz(file: File, entries: List<Pair<String, ByteArray>>, modifiedMs: Long) {
        file.parentFile?.mkdirs()
        java.util.zip.ZipOutputStream(file.outputStream()).use { zos ->
            entries.forEach { (name, bytes) ->
                zos.putNextEntry(java.util.zip.ZipEntry(name))
                zos.write(bytes)
                zos.closeEntry()
            }
        }
        file.setLastModified(modifiedMs)
    }

    private fun writeBytes(f: File, bytes: ByteArray) {
        f.parentFile?.mkdirs()
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
    fun `根容器列出全部条目且类型正确`() = runTest {
        val root = tempRoot()
        val source = newSource(root)
        val entries = source.listEntries(null, SortMode.NAME)
        assertEquals(
            listOf("cbz", "ep 2", "ep 10", "folder-only", "mixed", "series-a"),
            entries.map { it.name },
        )
        // 票 #97 口径（[com.cc3301.comicviewer.core.source.DirContents.isBook]）：目录**本层只有图片**才是书，
        // 本层有子目录或压缩包 ⇒ 容器（子目录与压缩包在它下一层各自是条目、各自是一本）。
        // 因此 cbz（只含压缩包与子目录）与 mixed（本层既有图片又有子目录）由书改判为容器；
        // series-a / ep 2 / ep 10（本层只有图片）与 folder-only（没图片）的判定不变。
        assertEquals(
            mapOf(
                "cbz" to false,
                "ep 2" to true,
                "ep 10" to true,
                "folder-only" to false,
                "mixed" to false,
                "series-a" to true,
            ),
            entries.associate { it.name to it.isBook },
        )
    }

    @Test
    fun `名称排序数字按数值比较`() = runTest {
        val source = newSource(tempRoot())
        val names = source.listEntries(null, SortMode.NAME).map { it.name }
        assertTrue("ep 2 应在 ep 10 之前（数值序）", names.indexOf("ep 2") < names.indexOf("ep 10"))
    }

    @Test
    fun `修改时间排序降序`() = runTest {
        val root = tempRoot()
        val source = newSource(root)
        java.io.File(root, "ep 2").setLastModified(2000L)
        java.io.File(root, "ep 10").setLastModified(1000L)
        val names = source.listEntries(null, SortMode.MODIFIED_TIME).map { it.name }
        assertTrue("修改时间新的 ep 2 应在前", names.indexOf("ep 2") < names.indexOf("ep 10"))
    }

    @Test
    fun `书的条目带封面 列表不统计页数`() = runTest {
        val source = newSource(tempRoot())
        val seriesA = rootEntry(source, "series-a")
        assertNull("枚举期不统计页数（票 #36）", seriesA.pageCount)
        assertNotNull(seriesA.coverUri)
        assertTrue(seriesA.coverUri!!.endsWith("page1.jpg"))
    }

    @Test
    fun `纯文件夹容器封面逐级下取 但只在按需通路发生`() = runTest {
        val source = newSource(tempRoot())
        val folderOnly = rootEntry(source, "folder-only")
        assertNull(folderOnly.pageCount)
        // 枚举期不再逐级下取容器封面位置（票 #30）：封面字节只为可见行走 coverBytes，规则不变
        assertNull("枚举期不给容器封面位置（票 #30）", folderOnly.coverUri)
        assertEquals("png-bytes", String(source.coverBytes(folderOnly.id)!!))
    }

    @Test
    fun `混合列表同时给出图片条目与子目录条目`() = runTest {
        val source = newSource(tempRoot())
        val mixed = rootEntry(source, "mixed")
        assertFalse("混合目录（本层图片 + 子目录）是容器：点它进浏览列表，图片与子目录都各自可选（票 #97）", mixed.isBook)
        val inner = source.listEntries(mixed.id, SortMode.NAME)
        assertEquals(
            listOf("book-b", "cover1.png", "cover2.png", "plain", "z-book"),
            inner.map { it.name },
        )
        // 图片条目：从该图连读（页数在打开书时才得出，见下一条断言）
        val cover1 = inner.first { it.name == "cover1.png" }
        assertNull("枚举期不统计页数（票 #36）", cover1.pageCount)
        assertEquals("从该图连读到末图共 2 页", 2, source.openBook(cover1.id).pageCount)
        // 含图子文件夹是书；纯文件夹是容器
        assertEquals(true, inner.first { it.name == "book-b" }.isBook)
        assertEquals(false, inner.first { it.name == "plain" }.isBook)
    }

    // ---------- 打开书与取页 ----------

    @Test
    fun `目录书按序取页且字节一致`() = runTest {
        val source = newSource(tempRoot())
        val seriesAId = rootEntry(source, "series-a").id
        val book = source.openBook(seriesAId)
        assertEquals(seriesAId, book.id)
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

    // ---------- 相邻书（票 07） ----------

    @Test
    fun `相邻书只按名称序且到头为空`() = runTest {
        val source = newSource(tempRoot())
        // 根列表 isBook 序（票 #97 口径：目录本层只有图片才是书）：ep 2, ep 10, series-a；
        // cbz（只含压缩包与子目录）、folder-only（只含子目录）、mixed（图片+子目录）是容器，不进书序列
        val ep2 = rootEntry(source, "ep 2").id
        val ep10 = rootEntry(source, "ep 10").id
        val seriesA = rootEntry(source, "series-a").id

        assertEquals(Neighbors(prev = null, next = ep10), source.neighbors(ep2))
        assertEquals(Neighbors(prev = ep2, next = seriesA), source.neighbors(ep10))
        assertEquals(Neighbors(prev = ep10, next = null), source.neighbors(seriesA))
        assertEquals(
            "容器不在书序列里：票 #97 起 cbz 是容器（它里面的压缩包在下一层各自为书）",
            Neighbors(prev = null, next = null),
            source.neighbors(rootEntry(source, "cbz").id),
        )
        assertEquals(
            "只含子目录的容器同理（既有口径不变）",
            Neighbors(prev = null, next = null),
            source.neighbors(rootEntry(source, "folder-only").id),
        )
    }

    @Test
    fun `混合列表内图片条目与子目录书同列相邻`() = runTest {
        val source = newSource(tempRoot())
        val mixed = rootEntry(source, "mixed").id
        val inner = source.listEntries(mixed, SortMode.NAME)
        val bookB = inner.first { it.name == "book-b" }.id
        val cover1 = inner.first { it.name == "cover1.png" }.id
        val cover2 = inner.first { it.name == "cover2.png" }.id

        val zBook = inner.first { it.name == "z-book" }.id
        assertEquals(Neighbors(null, cover1), source.neighbors(bookB))
        assertEquals(Neighbors(bookB, cover2), source.neighbors(cover1))
        // 名称序 c < z：cover2 的下一本必须是 z-book（分区拼接实现会错）
        assertEquals(Neighbors(cover1, zBook), source.neighbors(cover2))
        assertEquals(Neighbors(cover2, null), source.neighbors(zBook))
    }

    /**
     * 方向属展示层（票 #29 裁决 7）：界面把 `applySortDirection` 作用在来源已排序结果上。
     * 本用例用**固定黄金序列**锁住「反向作用后顺序整份倒过来、内容不变」，并锁住
     * 「列表反向 ≠ 相邻书反向」——相邻书仍只认名称自然序（裁决 6）。
     *
     * 不覆盖：方向本身如何接线到界面（来源接口没有方向参数，界面侧的翻转由 `SortSettingTest` 的纯函数用例
     * 与真机清单守护）。
     */
    @Test
    fun `列表反向时 相邻书仍按名称自然序`() = runTest {
        val source = newSource(tempRoot())
        // 根列表书条目的名称序（票 #97：目录本层只有图片才是书，容器不进书序列）：固定黄金序列，正好钉住内容与顺序
        assertEquals(
            listOf("ep 2", "ep 10", "series-a"),
            source.listEntries(null, SortMode.NAME).filter { it.isBook }.map { it.name },
        )

        // 展示层施加反向（票 #29 裁决 7）：顺序整份倒过来，条目一个不少
        val shown = source.listEntries(null, SortMode.NAME).filter { it.isBook }
            .applySortDirection(SortDirection.REVERSE).map { it.name }
        assertEquals(listOf("series-a", "ep 10", "ep 2"), shown)

        // 相邻书判定与列表当前排序方式及方向都无关（票 #29 裁决 6）：ep 2 的下一本仍是名称序里的 ep 10
        val ep2 = source.listEntries(null, SortMode.NAME).first { it.name == "ep 2" }.id
        val ep10 = source.listEntries(null, SortMode.NAME).first { it.name == "ep 10" }.id
        assertEquals(Neighbors(prev = null, next = ep10), source.neighbors(ep2))
    }

    // ---------- 压缩包（CBZ/ZIP，票 10）----------

    private fun cbzContainer(source: Source): String = runBlocking {
        source.listEntries(null, SortMode.NAME).first { it.name == "cbz" }.id
    }

    @Test
    fun `CBZ 作为书列出且枚举期不读包内条目`() = runTest {
        val source = newSource(tempRoot())
        val entries = source.listEntries(cbzContainer(source), SortMode.NAME)

        val a = entries.first { it.name == "a.cbz" }
        assertTrue("CBZ 应当作书", a.isBook)
        assertNull("ComicInfo.xml 不算页，且枚举期不读包内条目（票 #36）", a.pageCount)
        // 压缩包封面也不在枚举期解出（票 #30）：可见行才走按需通路，封面仍是包内首页
        assertNull("枚举期不给压缩包封面（票 #30）", a.coverUri)
        assertEquals("a-2", String(source.coverBytes(a.id)!!))

        val b = entries.first { it.name == "b.cbz" }
        assertNull(b.pageCount)
        // 页数改为打开书时才给（a 的 2 页由「CBZ 页序」用例守护，b 的 1 页只能靠这里）
        assertEquals(1, source.openBook(b.id).pageCount)
    }

    @Test
    fun `CBZ 页序按包内文件名自然排序`() = runTest {
        val source = newSource(tempRoot())
        val a = source.listEntries(cbzContainer(source), SortMode.NAME).first { it.name == "a.cbz" }
        val handle = source.openBook(a.id)

        assertEquals(2, handle.pageCount)
        // page2 在 page10 之前：Windows 自然序按数值比较，而非字典序
        assertEquals("a-2", String(handle.loadPage(0).bytes))
        assertEquals("a-10", String(handle.loadPage(1).bytes))
        assertEquals("image/jpeg", handle.loadPage(0).mimeType)
    }

    @Test
    fun `压缩包取页越界抛 IndexOutOfBounds`() = runTest {
        val source = newSource(tempRoot())
        val b = source.listEntries(cbzContainer(source), SortMode.NAME).first { it.name == "b.cbz" }
        val handle = source.openBook(b.id)
        assertThrows(IndexOutOfBoundsException::class.java) { runBlocking { handle.loadPage(5) } }
    }

    /**
     * 空书口径（票 #97 统一到四来源，契约见 [Source.openBook]）：书存在但一页都没有（包内没有图片）
     * 返回 0 页句柄，界面据此显示中文空态；抛 [IllegalArgumentException] 只留给「不是一本书」的输入
     * （同文件里 `非书 id 打开抛 IllegalArgument` 守着那一条）。
     *
     * 包在**建好来源之后**才放进去：既不动 fixture 的根层条目黄金序列，也不需要改其他用例的预期。
     */
    @Test
    fun `包里没有图片的压缩包是 0 页的书 不是打不开`() = runTest {
        val root = tempRoot()
        val source = newSource(root)
        writeCbz(File(root, "empty.cbz"), listOf("readme.txt" to "not-image".toByteArray()), modifiedMs = 1_600_000_000_000L)

        val entry = source.listEntries(null, SortMode.NAME).first { it.name == "empty.cbz" }

        assertTrue("压缩包不看内容就算书（枚举期不读包内条目，票 #36）", entry.isBook)
        assertEquals("0 页句柄：界面显示中文空态，不是一直转圈", 0, source.openBook(entry.id).pageCount)
    }

    @Test
    fun `发布时间排序优先 ComicInfo 缺失回退修改时间`() = runTest {
        val source = newSource(tempRoot())
        val names = source.listEntries(cbzContainer(source), SortMode.RELEASE_TIME).map { it.name }

        // a/b 的 mtime 相同（且都很新），但 ComicInfo 分别是 1999 / 2020；
        // plain-images 无元数据 → 回退目录 mtime（2001-09）。
        // 若实现忽略了 ComicInfo（退化成 mtime），顺序会变成 a/b 在前，本用例即红。
        assertEquals(listOf("b.cbz", "plain-images", "a.cbz"), names)
    }

    // ---------- helper ----------

    protected fun tempRoot(): File =
        java.nio.file.Files.createTempDirectory("source-contract").toFile()
}
