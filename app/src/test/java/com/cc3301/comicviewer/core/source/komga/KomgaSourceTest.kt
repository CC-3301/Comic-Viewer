package com.cc3301.comicviewer.core.source.komga

import com.cc3301.comicviewer.core.source.InMemoryProgressStore
import com.cc3301.comicviewer.core.source.SortMode
import com.cc3301.comicviewer.core.source.SourceType
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.SocketTimeoutException

/**
 * Komga 来源行为（票 13）：系列 → 书 两级浏览、服务器端排序参数、分页循环、
 * 相邻书（名称序）、按页取图与封面、进度读写、失败冒泡。
 */
class KomgaSourceTest {

    private val config = KomgaConnectionConfig(baseUrl = "http://komga:25600", apiKey = "k")
    private val prefix = KomgaIds.prefix(config.baseUrl)

    private val seriesA = KomgaSeries(id = "s1", title = "Series A", booksCount = 3)
    private val seriesB = KomgaSeries(id = "s2", title = "Series B", booksCount = 1)

    private fun book(id: String, title: String, number: String = "", pages: Int = 2, seriesId: String = "s1") =
        KomgaBook(id = id, seriesId = seriesId, title = title, number = number, pageCount = pages, releaseDate = "2020-01-01")

    private fun api(pageSize: Int = 0) = FakeKomgaApi(
        series = listOf(seriesA, seriesB),
        books = mapOf(
            // 故意给出与 Windows 名称序不同的服务器顺序（服务器按 titleSort，本地再排一次）
            "s1" to listOf(book("b10", "Vol 10"), book("b2", "Vol 2"), book("b1", "Vol 1")),
            "s2" to listOf(book("b9", "Only", seriesId = "s2")),
        ),
        pages = mapOf(
            "b1" to listOf(
                KomgaPage(1, "image/jpeg"),
                KomgaPage(2, "image/png"),
            ),
        ),
        pageSize = pageSize,
    )

    private fun source(api: KomgaApi = api()) =
        KomgaSource(api = api, config = config, progressStore = InMemoryProgressStore())

    @Test
    fun `根列表给出系列 名称序用 Windows 序`() = runBlocking<Unit> {
        val entries = source().listEntries(null, SortMode.NAME)

        assertEquals(listOf("Series A", "Series B"), entries.map { it.name })
        assertEquals(listOf(prefix + "/series/s1", prefix + "/series/s2"), entries.map { it.id })
        assertTrue("系列不是可读单元", entries.none { it.isBook })
        // 容器约定：pageCount 只对书有意义（书 = 页数；容器 = null）
        assertTrue("系列是容器，不该带页数", entries.all { it.pageCount == null })
    }

    @Test
    fun `发布时间排序向服务器请求 releaseDate 相关排序字段`() = runBlocking<Unit> {
        val fake = api()
        source(fake).listEntries(null, SortMode.RELEASE_TIME)
        source(fake).listEntries(null, SortMode.MODIFIED_TIME)
        // 系列没有发布时间 → 回退最后修改时间（与文件源缺少 ComicInfo 时回退 mtime 一致）
        assertEquals(listOf("lastModifiedDate,desc", "lastModifiedDate,desc"), fake.seriesSortRequests)
    }

    @Test
    fun `书列表按发布时间排序走服务器端 metadata_releaseDate`() = runBlocking<Unit> {
        val fake = api()
        source(fake).listEntries(prefix + "/series/s1", SortMode.RELEASE_TIME)

        assertEquals(listOf("metadata.releaseDate,desc"), fake.bookSortRequests)
        // 服务器顺序原样保留（不在本地重排），否则服务器端排序就白做了
        assertEquals(listOf("Vol 10", "Vol 2", "Vol 1"), source(api()).listEntries(prefix + "/series/s1", SortMode.RELEASE_TIME).map { it.name })
    }

    @Test
    fun `书列表名称序用 Windows 序 编号数字序正确`() = runBlocking<Unit> {
        val entries = source().listEntries(prefix + "/series/s1", SortMode.NAME)
        // Vol 1 < Vol 2 < Vol 10（数值比较，不是字典序）
        assertEquals(listOf("Vol 1", "Vol 2", "Vol 10"), entries.map { it.name })
        assertTrue(entries.all { it.isBook })
        assertEquals(listOf(2, 2, 2), entries.map { it.pageCount })
    }

    @Test
    fun `服务器分页时循环取完所有条目`() = runBlocking<Unit> {
        val entries = source(api(pageSize = 1)).listEntries(prefix + "/series/s1", SortMode.NAME)
        assertEquals(listOf("Vol 1", "Vol 2", "Vol 10"), entries.map { it.name })
    }

    @Test
    fun `打开书按页取图 页码越界报错 MIME 来自页信息`() = runBlocking<Unit> {
        val handle = source().openBook(prefix + "/series/s1/book/b1")

        assertEquals(2, handle.pageCount)
        assertEquals(prefix + "/series/s1/book/b1", handle.id)
        assertEquals("image/jpeg", handle.loadPage(0).mimeType)
        assertEquals("page-b1-1", String(handle.loadPage(0).bytes))
        assertEquals("image/png", handle.loadPage(1).mimeType)
        assertThrows(IndexOutOfBoundsException::class.java) { runBlocking { handle.loadPage(2) } }
    }

    @Test
    fun `没有页的书不是一本书`() = runBlocking<Unit> {
        assertThrows(IllegalArgumentException::class.java) {
            runBlocking { source().openBook(prefix + "/series/s1/book/unknown") }
        }
    }

    @Test
    fun `相邻书按名称自然序 与当前列表排序无关`() = runBlocking<Unit> {
        val src = source()
        val prev = src.neighbors(prefix + "/series/s1/book/b2")
        assertEquals(prefix + "/series/s1/book/b1", prev.prev)
        assertEquals(prefix + "/series/s1/book/b10", prev.next)

        assertNull("第一本没有上一本", src.neighbors(prefix + "/series/s1/book/b1").prev)
        assertNull("最后一本没有下一本", src.neighbors(prefix + "/series/s1/book/b10").next)
        // 相邻书只查同系列：别的系列的书不在其中
        assertNull(src.neighbors(prefix + "/series/s2/book/b9").prev)
    }

    @Test
    fun `封面按需取字节 系列与书分开`() = runBlocking<Unit> {
        val src = source()
        assertEquals("cover-series-s1", String(src.coverBytes(prefix + "/series/s1")!!))
        assertEquals("cover-book-b1", String(src.coverBytes(prefix + "/series/s1/book/b1")!!))
        assertNull("不属于本连接的 id 不给封面", src.coverBytes("http://other/series/s1"))
    }

    @Test
    fun `封面取不到时返回 null 而不是抛异常`() = runBlocking<Unit> {
        // 缩略图在浏览列表里并行加载：这里抛异常会直接把浏览页打崩（review P1）
        val fake = api()
        val src = source(fake)
        fake.alwaysFailWith(SocketTimeoutException("timed out"))

        assertNull(src.coverBytes(prefix + "/series/s1"))
        assertNull(src.coverBytes(prefix + "/series/s1/book/b1"))
    }

    @Test
    fun `进度读写走本地存储 且类型为 KOMGA`() = runBlocking<Unit> {
        val src = source()
        assertEquals(SourceType.KOMGA, src.type)
        assertNull(src.readProgress(prefix + "/series/s1/book/b1"))
        src.writeProgress(prefix + "/series/s1/book/b1", 1, 2)
        assertEquals(1, src.readProgress(prefix + "/series/s1/book/b1")!!.pageIndex)
        assertEquals(2, src.readProgress(prefix + "/series/s1/book/b1")!!.totalPages)
    }

    @Test
    fun `无效容器或书 id 明确报错 不静默`() = runBlocking<Unit> {
        val src = source()
        assertThrows(IllegalArgumentException::class.java) {
            runBlocking { src.listEntries("http://other/series/s1", SortMode.NAME) }
        }
        assertThrows(IllegalArgumentException::class.java) {
            runBlocking { src.openBook("http://other/series/s1/book/b1") }
        }
    }

    @Test
    fun `传输失败冒泡为带原因的异常 不返回空列表`() = runBlocking<Unit> {
        val fake = api()
        val src = source(fake)
        fake.alwaysFailWith(SocketTimeoutException("timed out"))

        assertThrows(SocketTimeoutException::class.java) { runBlocking { src.listEntries(null, SortMode.NAME) } }
    }
}
