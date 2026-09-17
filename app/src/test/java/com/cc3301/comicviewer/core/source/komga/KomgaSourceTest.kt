package com.cc3301.comicviewer.core.source.komga

import com.cc3301.comicviewer.core.source.InMemoryProgressStore
import com.cc3301.comicviewer.core.source.SortMode
import com.cc3301.comicviewer.core.source.SourceType
import com.cc3301.comicviewer.core.source.isCompleted
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

    private fun book(
        id: String,
        title: String,
        number: String = "",
        pages: Int = 2,
        seriesId: String = "s1",
        releaseDate: String = "2020-01-01",
    ) = KomgaBook(id = id, seriesId = seriesId, title = title, number = number, pageCount = pages, releaseDate = releaseDate)

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

    private fun source(api: KomgaApi = api(), store: InMemoryProgressStore = InMemoryProgressStore()) =
        KomgaSource(api = api, config = config, progressStore = store)

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
    fun `发布时间排序不在本地按 releaseDate 重排 服务器顺序原样返回`() = runBlocking<Unit> {
        // 票 #22：APP 不解析也不换算 releaseDate（只把服务器给的字符串原样传递），
        // 所以上游 issue 的时区偏差若存在只会体现在 Komga 自己返回的顺序里，APP 不会二次排序放大。
        // 故意给出「既非日期升序也非降序」的服务器顺序：本地任何按日期的重排都会与它不同。
        val fake = FakeKomgaApi(
            series = listOf(seriesA),
            books = mapOf(
                "s1" to listOf(
                    book("b1", "Mid", releaseDate = "2019-06-01"),
                    book("b2", "Newest", releaseDate = "2021-03-01"),
                    book("b3", "Oldest", releaseDate = "2020-01-01"),
                ),
            ),
        )

        val entries = KomgaSource(api = fake, config = config, progressStore = InMemoryProgressStore())
            .listEntries(prefix + "/series/s1", SortMode.RELEASE_TIME)

        assertEquals(listOf("metadata.releaseDate,desc"), fake.bookSortRequests)
        assertEquals(listOf("Mid", "Newest", "Oldest"), entries.map { it.name })
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
    fun `打开书时以服务器进度定位 并写回本地`() = runBlocking<Unit> {
        val fake = api()
        val store = InMemoryProgressStore()
        val src = KomgaSource(api = fake, config = config, progressStore = store)
        val bookId = prefix + "/series/s1/book/b1"
        // 打开书先记录页数（2 页），服务器说读到第 2 页且未读完
        src.openBook(bookId)
        fake.setServerProgress("b1", page = 2)

        val progress = src.readProgress(bookId)!!

        assertEquals("Komga 的 page 从 1 起，本地从 0 起", 1, progress.pageIndex)
        assertEquals(2, progress.totalPages)
        assertEquals("要写回本地，列表进度条与离线续读才有值", 1, store.read(bookId)!!.pageIndex)
    }

    @Test
    fun `服务器标记读完时落在最后一页`() = runBlocking<Unit> {
        val fake = api()
        val src = source(fake)
        val bookId = prefix + "/series/s1/book/b1"
        src.openBook(bookId)   // 2 页
        fake.setServerProgress("b1", page = 2, completed = true)

        val progress = src.readProgress(bookId)!!

        assertTrue("读完后进度条要满格红", progress.isCompleted)
        assertEquals(1, progress.pageIndex)
    }

    @Test
    fun `服务器没有记录时用本地进度`() = runBlocking<Unit> {
        val store = InMemoryProgressStore()
        val src = KomgaSource(api = api(), config = config, progressStore = store)
        val bookId = prefix + "/series/s1/book/b1"
        store.write(bookId, pageIndex = 1, totalPages = 2)

        assertEquals("服务器没记录不能把本地进度清掉", 1, src.readProgress(bookId)!!.pageIndex)
    }

    @Test
    fun `服务器进度比本地旧时不回退阅读位置`() = runBlocking<Unit> {
        val fake = api()
        val store = InMemoryProgressStore()
        val src = KomgaSource(api = fake, config = config, progressStore = store)
        val bookId = prefix + "/series/s1/book/b1"
        store.write(bookId, pageIndex = 5, totalPages = 10)
        fake.setServerProgress("b1", page = 2)   // 服务器上还是旧值（例如上次回传失败过）

        assertEquals("要取更靠后的那个", 5, src.readProgress(bookId)!!.pageIndex)
    }

    @Test
    fun `有未回传的进度时以它定位并补传 不被服务器旧值覆盖`() = runBlocking<Unit> {
        val fake = api()
        val store = InMemoryProgressStore()
        val src = KomgaSource(api = fake, config = config, progressStore = store)
        val bookId = prefix + "/series/s1/book/b1"
        src.openBook(bookId)   // 10 页（fixture 里 b1 两页，这里用 b10 更明确）
        val tenPageBook = prefix + "/series/s1/book/b10"
        fake.setServerProgress("b10", page = 2)
        store.write(tenPageBook, pageIndex = 8, totalPages = 10)

        // 先制造一次回传失败（待补传 = 第 9 页）
        fake.failWrites = true
        src.writeProgress(tenPageBook, pageIndex = 8, totalPages = 10)
        // 网络恢复后打开这本书：应以「待补传的第 9 页」定位，并把服务器补齐
        fake.failWrites = false
        fake.setServerProgress("b10", page = 2)

        val progress = src.readProgress(tenPageBook)!!

        assertEquals("以本地待补传值为准，不被服务器旧值拉回", 8, progress.pageIndex)
        assertEquals("补传后服务器应看到第 9 页", 9, fake.serverProgressOf("b10")!!.page)
    }

    @Test
    fun `书列表里带服务器进度时并入本地 列表即可见跨端进度`() = runBlocking<Unit> {
        val fake = api()
        val store = InMemoryProgressStore()
        val src = KomgaSource(api = fake, config = config, progressStore = store)
        fake.setServerProgress("b2", page = 2, completed = true)   // b2 共 2 页 → 读完

        src.listEntries(prefix + "/series/s1", SortMode.NAME)

        val stored = store.read(prefix + "/series/s1/book/b2")!!
        assertEquals(2, stored.totalPages)
        assertTrue("服务器读完要显示成本地读完", stored.isCompleted)
    }

    @Test
    fun `阅读中回传页码 服务器按 1 起`() = runBlocking<Unit> {
        val fake = api()
        val src = source(fake)
        val bookId = prefix + "/series/s1/book/b1"

        src.writeProgress(bookId, pageIndex = 0, totalPages = 2)
        assertEquals("b1", fake.progressWrites.last().first)
        assertEquals("回传要按 1 起", 1, fake.progressWrites.last().second.page)
        assertTrue(!fake.progressWrites.last().second.completed)

        src.writeProgress(bookId, pageIndex = 1, totalPages = 2)
        assertEquals(2, fake.progressWrites.last().second.page)
        assertTrue("最后一页要标记读完", fake.progressWrites.last().second.completed)
    }

    @Test
    fun `回传失败不阻塞阅读 本地照常保存 下次保存补传`() = runBlocking<Unit> {
        val fake = api()
        val store = InMemoryProgressStore()
        val src = KomgaSource(api = fake, config = config, progressStore = store)
        val bookId = prefix + "/series/s1/book/b1"
        fake.failWrites = true

        // 回传失败：不抛异常（阅读不能被打断），本地已有进度
        src.writeProgress(bookId, pageIndex = 1, totalPages = 2)
        assertEquals(1, store.read(bookId)!!.pageIndex)
        assertTrue("失败过所以要补传", fake.progressWrites.isEmpty())

        // 网络恢复：下一次保存先补传旧值，并把当前进度推上去（页码序列能区分两种实现）
        fake.failWrites = false
        src.writeProgress(bookId, pageIndex = 0, totalPages = 2)

        assertEquals("先补传旧值（2）再推当前值（1）", listOf(2, 1), fake.progressWrites.map { it.second.page })
        assertEquals("服务器最终是当前值", 1, fake.serverProgressOf("b1")!!.page)
        assertTrue(!fake.serverProgressOf("b1")!!.completed)
    }

    @Test
    fun `读取服务器进度失败时退回本地进度`() = runBlocking<Unit> {
        val fake = api()
        val src = source(fake)
        val bookId = prefix + "/series/s1/book/b1"
        src.writeProgress(bookId, pageIndex = 1, totalPages = 2)

        fake.alwaysFailWith(SocketTimeoutException("timed out"))
        val progress = src.readProgress(bookId)!!

        assertEquals("断网也要能续读（用本地）", 1, progress.pageIndex)
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
