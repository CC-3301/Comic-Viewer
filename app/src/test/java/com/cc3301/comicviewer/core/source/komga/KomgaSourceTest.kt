package com.cc3301.comicviewer.core.source.komga

import com.cc3301.comicviewer.core.source.InMemoryProgressStore
import com.cc3301.comicviewer.core.source.SortMode
import com.cc3301.comicviewer.core.source.SourceType
import com.cc3301.comicviewer.core.source.isCompleted
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
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

    /** 「系列」入口的容器 id（票 #78）：根层不再是全系列平铺，取系列列表得从这个入口进 */
    private val seriesCategory = KomgaIds.categoryId(prefix, KomgaCategory.SERIES.kind)
    private val booksCategory = KomgaIds.categoryId(prefix, KomgaCategory.BOOKS.kind)
    private val readCategory = KomgaIds.categoryId(prefix, KomgaCategory.READ.kind)
    private val collectionsCategory = KomgaIds.categoryId(prefix, KomgaCategory.COLLECTIONS.kind)

    /** 收藏行的容器 id（票 #78 追加口径：收藏行封面回落用） */
    private val collectionC1 = KomgaIds.collectionId(prefix, "c1")

    private val seriesA = KomgaSeries(id = "s1", title = "Series A", booksCount = 3)
    private val seriesB = KomgaSeries(id = "s2", title = "Series B", booksCount = 2)

    private fun book(
        id: String,
        title: String,
        number: String = "",
        pages: Int = 2,
        seriesId: String = "s1",
        releaseDate: String = "2020-01-01",
    ) = KomgaBook(id = id, seriesId = seriesId, title = title, number = number, pageCount = pages, releaseDate = releaseDate)

    private fun api(
        pageSize: Int = 0,
        seriesWithoutThumbnail: Set<String> = emptySet(),
        booksWithoutThumbnail: Set<String> = emptySet(),
    ) = FakeKomgaApi(
        series = listOf(seriesA, seriesB),
        books = mapOf(
            // 故意给出与 Windows 名称序不同的服务器顺序（服务器按 titleSort，本地再排一次）
            "s1" to listOf(book("b10", "Vol 10"), book("b2", "Vol 2"), book("b1", "Vol 1")),
            "s2" to listOf(
                book("b9", "Only", seriesId = "s2"),
                // 登记在册但页列表为空的书（票 #97 空书口径）——区别于「未登记 id」：真机上那是 404（HttpKomgaApi）
                book("b-empty", "Vol Empty", pages = 0, seriesId = "s2"),
            ),
        ),
        pages = mapOf(
            "b1" to listOf(
                KomgaPage(1, "image/jpeg"),
                KomgaPage(2, "image/png"),
            ),
            // 服务器确实给了这本书的页列表，只是它是空的
            "b-empty" to emptyList(),
        ),
        pageSize = pageSize,
        // 票 #78：收藏与收藏内容（收藏组织系列）
        collections = listOf(KomgaCollection(id = "c1", name = "Collection One")),
        collectionContents = mapOf(
            "c1" to listOf(KomgaCollectionItem.Series(seriesA), KomgaCollectionItem.Series(seriesB)),
        ),
        seriesWithoutThumbnail = seriesWithoutThumbnail,
        booksWithoutThumbnail = booksWithoutThumbnail,
    )

    private fun source(api: KomgaApi = api(), store: InMemoryProgressStore = InMemoryProgressStore()) =
        KomgaSource(api = api, config = config, progressStore = store)

    @Test
    fun `根列表给出四个入口 不再是全系列平铺`() = runBlocking<Unit> {
        val entries = source().listEntries(null, SortMode.NAME)

        assertEquals(listOf("收藏", "系列", "书籍", "阅读过"), entries.map { it.name })
        assertEquals(
            listOf(
                KomgaIds.categoryId(prefix, KomgaCategory.COLLECTIONS.kind),
                KomgaIds.categoryId(prefix, KomgaCategory.SERIES.kind),
                KomgaIds.categoryId(prefix, KomgaCategory.BOOKS.kind),
                KomgaIds.categoryId(prefix, KomgaCategory.READ.kind),
            ),
            entries.map { it.id },
        )
        assertTrue("入口都是容器", entries.none { it.isBook })
        assertTrue("容器不带页数", entries.all { it.pageCount == null })
    }

    @Test
    fun `系列入口给出系列列表 名称序用 Windows 序`() = runBlocking<Unit> {
        val entries = source().listEntries(seriesCategory, SortMode.NAME)

        assertEquals(listOf("Series A", "Series B"), entries.map { it.name })
        assertEquals(listOf(prefix + "/series/s1", prefix + "/series/s2"), entries.map { it.id })
        assertTrue("系列不是可读单元", entries.none { it.isBook })
        // 容器约定：pageCount 只对书有意义（书 = 页数；容器 = null）
        assertTrue("系列是容器，不该带页数", entries.all { it.pageCount == null })
    }

    @Test
    fun `发布时间排序向服务器请求 releaseDate 相关排序字段`() = runBlocking<Unit> {
        val fake = api()
        source(fake).listEntries(seriesCategory, SortMode.RELEASE_TIME)
        source(fake).listEntries(seriesCategory, SortMode.MODIFIED_TIME)
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

        assertEquals(listOf(KomgaBookQuery.Series("s1") to "metadata.releaseDate,desc"), fake.bookListQueries)
        assertEquals(listOf("Mid", "Newest", "Oldest"), entries.map { it.name })
    }

    @Test
    fun `书列表按发布时间排序走服务器端 metadata_releaseDate`() = runBlocking<Unit> {
        val fake = api()
        source(fake).listEntries(prefix + "/series/s1", SortMode.RELEASE_TIME)

        assertEquals(listOf(KomgaBookQuery.Series("s1") to "metadata.releaseDate,desc"), fake.bookListQueries)
        // 服务器顺序原样保留（不在本地重排），否则服务器端排序就白做了
        assertEquals(listOf("Vol 10", "Vol 2", "Vol 1"), source(api()).listEntries(prefix + "/series/s1", SortMode.RELEASE_TIME).map { it.name })
    }

    @Test
    fun `书列表名称序用 Windows 序 编号数字序正确`() = runBlocking<Unit> {
        val entries = source().listEntries(prefix + "/series/s1", SortMode.NAME)
        // Vol 1 < Vol 2 < Vol 10（数值比较，不是字典序）
        assertEquals(listOf("Vol 1", "Vol 2", "Vol 10"), entries.map { it.name })
        assertTrue(entries.all { it.isBook })
        // 票 #36：文件源枚举期不再统计页数，Komga 的页数来自服务器 payload（零额外成本，进度同步要用）——
        // 列表照常带上，只是 UI 不再显示
        assertEquals(listOf(2, 2, 2), entries.map { it.pageCount })
    }

    @Test
    fun `服务器分页时循环取完所有条目`() = runBlocking<Unit> {
        val entries = source(api(pageSize = 1)).listEntries(prefix + "/series/s1", SortMode.NAME)
        assertEquals(listOf("Vol 1", "Vol 2", "Vol 10"), entries.map { it.name })
    }

    @Test
    fun `会话内列表快照可同步读 失效路径清掉各排序方式`() = runBlocking<Unit> {
        // 票 #74：实例复用带来跨页面同步命中（与文件源 cachedEntries 同一口径）；
        // listEntries 本身不因缓存而跳过刷新（服务器是权威源），缓存只服务同步访问器。
        val fake = api()
        val src = source(fake)

        val first = src.listEntries(prefix + "/series/s1", SortMode.NAME)
        src.listEntries(prefix + "/series/s1", SortMode.RELEASE_TIME)
        assertEquals(
            "同步访问器命中刚列出的那一份（不等解析与服务器往返）",
            first,
            src.cachedEntries(prefix + "/series/s1", SortMode.NAME),
        )
        assertNotNull("不同排序方式各有一份", src.cachedEntries(prefix + "/series/s1", SortMode.RELEASE_TIME))

        src.invalidateListCache(prefix + "/series/s1")

        assertNull("下拉更新后不再命中（缓存确实被清了）", src.cachedEntries(prefix + "/series/s1", SortMode.NAME))
        assertNull(
            "同一容器的其它排序方式也一起失效（键前缀共用一处）",
            src.cachedEntries(prefix + "/series/s1", SortMode.RELEASE_TIME),
        )
        assertEquals(
            "重新列出的结果回到同步访问器",
            src.listEntries(prefix + "/series/s1", SortMode.NAME),
            src.cachedEntries(prefix + "/series/s1", SortMode.NAME),
        )
        assertEquals("三次 listEntries（两种排序 + 失效后重列）各问了一次服务器", 3, fake.bookListQueries.size)
    }

    @Test
    fun `根层的列表快照同样可失效`() = runBlocking<Unit> {
        val fake = api()
        val src = source(fake)

        val series = src.listEntries(null, SortMode.NAME)
        assertEquals("同步访问器命中根层快照", series, src.cachedEntries(null, SortMode.NAME))

        src.invalidateListCache(null)

        assertNull("根层（containerId = null）也要清掉", src.cachedEntries(null, SortMode.NAME))
    }

    @Test
    fun `收藏入口给出收藏列表 收藏是容器`() = runBlocking<Unit> {
        val fake = api()
        val entries = source(fake).listEntries(collectionsCategory, SortMode.NAME)

        assertEquals(listOf("Collection One"), entries.map { it.name })
        assertEquals(listOf(prefix + "/collection/c1"), entries.map { it.id })
        assertTrue(entries.none { it.isBook })
        assertEquals("收藏列表按名称", listOf("name,asc"), fake.collectionSortRequests)
    }

    @Test
    fun `收藏容器给出它的系列 系列仍能继续下钻到书`() = runBlocking<Unit> {
        val fake = api()
        val src = source(fake)

        val collection = src.listEntries(collectionsCategory, SortMode.NAME).single()
        val series = src.listEntries(collection.id, SortMode.NAME)

        assertEquals(listOf("Series A", "Series B"), series.map { it.name })
        assertEquals(
            listOf(prefix + "/series/s1", prefix + "/series/s2"),
            series.map { it.id },
        )
        assertTrue("收藏内容是系列（Komga 原生结构），不是书行", series.none { it.isBook })
        assertEquals(listOf("c1" to "metadata.titleSort,asc"), fake.collectionContentRequests)

        // 从收藏进系列后仍是同一套书条目（书 id 形状不变）
        val books = src.listEntries(series.first().id, SortMode.NAME)
        assertEquals(listOf("Vol 1", "Vol 2", "Vol 10"), books.map { it.name })
        assertTrue(books.all { it.isBook })
    }

    @Test
    fun `书籍入口给出全部书平铺 书 id 仍是系列与书的两段结构`() = runBlocking<Unit> {
        val fake = api()
        val entries = source(fake).listEntries(booksCategory, SortMode.NAME)

        assertEquals(
            setOf("Vol 1", "Vol 2", "Vol 10", "Only", "Vol Empty"),
            entries.map { it.name }.toSet(),
        )
        assertTrue("全部书都是书法条目", entries.all { it.isBook })
        // 存量进度键不得变（票 #78）：书 id 仍是 `.../series/<seriesId>/book/<bookId>`
        val vol1 = entries.first { it.name == "Vol 1" }
        assertEquals(prefix + "/series/s1/book/b1", vol1.id)
        assertEquals("s1", KomgaIds.seriesOfBook(prefix, vol1.id))
        assertEquals("b1", KomgaIds.rawBookId(prefix, vol1.id))
        assertEquals(listOf(KomgaBookQuery.All to "metadata.titleSort,asc"), fake.bookListQueries)
    }

    @Test
    fun `阅读过入口只给有阅读记录的书 默认按最近阅读倒序`() = runBlocking<Unit> {
        val fake = api()
        // 只有 b2 与 b9 在服务器上有阅读记录
        fake.setServerProgress("b2", page = 1)
        fake.setServerProgress("b9", page = 1)

        val entries = source(fake).listEntries(readCategory, SortMode.NAME)

        assertEquals(setOf("Vol 2", "Only"), entries.map { it.name }.toSet())
        assertTrue(entries.all { it.isBook })
        assertEquals(prefix + "/series/s1/book/b2", entries.first { it.name == "Vol 2" }.id)
        // 票面：默认（名称档 = 全局默认）按最近阅读倒序
        assertEquals(listOf(KomgaBookQuery.Read to "readProgress.lastModified,desc"), fake.bookListQueries)
    }

    @Test
    fun `阅读过固定最近阅读倒序 不跟随排序菜单的类别档`() = runBlocking<Unit> {
        // 票 #78 修复轮口径裁决（维护者当面确认）：该入口是「排序是全局一份设置」（故事 14）的
        // **有意例外**，因此三个类别档都发同一个服务端排序串
        val fake = api()

        source(fake).listEntries(readCategory, SortMode.NAME)
        source(fake).listEntries(readCategory, SortMode.RELEASE_TIME)
        source(fake).listEntries(readCategory, SortMode.MODIFIED_TIME)

        assertEquals(
            listOf(
                KomgaBookQuery.Read to KomgaSort.FOR_READ_BOOKS,
                KomgaBookQuery.Read to KomgaSort.FOR_READ_BOOKS,
                KomgaBookQuery.Read to KomgaSort.FOR_READ_BOOKS,
            ),
            fake.bookListQueries,
        )
        assertEquals("readProgress.lastModified,desc", KomgaSort.FOR_READ_BOOKS)
    }

    @Test
    fun `收藏内容里返回书时渲染为书行`() = runBlocking<Unit> {
        // 票 #78 修复轮：票面「若返回书则渲染为书行」——按服务端返回的形状分派
        val fake = FakeKomgaApi(
            collections = listOf(KomgaCollection(id = "c1", name = "Collection One")),
            collectionContents = mapOf(
                "c1" to listOf(
                    KomgaCollectionItem.Series(seriesA),
                    KomgaCollectionItem.Book(book("b1", "Vol 1", seriesId = "s1")),
                ),
            ),
        )

        val entries = source(fake).listEntries(prefix + "/collection/c1", SortMode.NAME)

        assertEquals(listOf("Series A", "Vol 1"), entries.map { it.name })
        assertEquals(listOf(false, true), entries.map { it.isBook })
        assertEquals(prefix + "/series/s1", entries[0].id)
        assertEquals(prefix + "/series/s1/book/b1", entries[1].id)
    }

    @Test
    fun `起始路径决定根层落点 系列与收藏容器都能当起点`() = runBlocking<Unit> {
        val fake = api()
        val seriesStart = KomgaSource(
            api = fake,
            config = config.copy(browsePath = "/series/s1"),
            progressStore = InMemoryProgressStore(),
        )
        val collectionStart = KomgaSource(
            api = fake,
            config = config.copy(browsePath = "/collections/c1"),
            progressStore = InMemoryProgressStore(),
        )

        // `/series/s1` → 根层直接是该系列的书（书 id 仍带系列）
        val books = seriesStart.listEntries(null, SortMode.NAME)
        assertEquals(listOf("Vol 1", "Vol 2", "Vol 10"), books.map { it.name })
        assertEquals(prefix + "/series/s1/book/b1", books.first().id)
        // `/collections/c1` → 根层直接是该收藏的系列
        val series = collectionStart.listEntries(null, SortMode.NAME)
        assertEquals(listOf("Series A", "Series B"), series.map { it.name })
    }

    @Test
    fun `r1 的中文段起始路径仍认 起点不丢`() = runBlocking<Unit> {
        // 票 #78 修复轮：段名改稳定 token，但 r1 落过库的中文段必须照旧认（存量连接不丢起点）
        val src = KomgaSource(
            api = api(),
            config = config.copy(browsePath = "/收藏/c1"),
            progressStore = InMemoryProgressStore(),
        )

        assertEquals(listOf("Series A", "Series B"), src.listEntries(null, SortMode.NAME).map { it.name })
    }

    @Test
    fun `起始路径是书籍或阅读过时 根层直接是对应列表`() = runBlocking<Unit> {
        val fake = api()
        fake.setServerProgress("b1", page = 1)

        val booksStart = KomgaSource(api = fake, config = config.copy(browsePath = "/books"), progressStore = InMemoryProgressStore())
        val readStart = KomgaSource(api = fake, config = config.copy(browsePath = "/read"), progressStore = InMemoryProgressStore())
        val collectionsStart = KomgaSource(
            api = fake,
            config = config.copy(browsePath = "/collections"),
            progressStore = InMemoryProgressStore(),
        )

        assertEquals(5, booksStart.listEntries(null, SortMode.NAME).size)
        assertEquals(listOf("Vol 1"), readStart.listEntries(null, SortMode.NAME).map { it.name })
        assertEquals(listOf("Collection One"), collectionsStart.listEntries(null, SortMode.NAME).map { it.name })
    }

    @Test
    fun `非法起始路径回落四入口`() = runBlocking<Unit> {
        val src = KomgaSource(
            api = api(),
            config = config.copy(browsePath = "/不认识的类别"),
            progressStore = InMemoryProgressStore(),
        )

        assertEquals(listOf("收藏", "系列", "书籍", "阅读过"), src.listEntries(null, SortMode.NAME).map { it.name })
    }

    @Test
    fun `缺 seriesId 的书也列出来 用独立命名空间且能打开与记进度`() = runBlocking<Unit> {
        // 票 #78 修复轮：维护者裁决「要列出来」——不再静默丢掉无系列的书
        val fake = FakeKomgaApi(
            books = mapOf("" to listOf(book("orphan", "Orphan", seriesId = ""))),
            pages = mapOf("orphan" to listOf(KomgaPage(1, "image/jpeg"))),
        )
        val store = InMemoryProgressStore()
        val src = KomgaSource(api = fake, config = config, progressStore = store)

        val entry = src.listEntries(booksCategory, SortMode.NAME).single()
        assertEquals("Orphan", entry.name)
        assertTrue(entry.isBook)
        // 独立命名空间（不带系列段）
        assertEquals(prefix + "/book/orphan", entry.id)
        assertNull("无系列书没有系列可解析", KomgaIds.seriesOfBook(prefix, entry.id))
        assertNull("既有 4 段解析不认它（存量进度键不回归）", KomgaIds.rawBookId(prefix, entry.id))
        assertEquals("orphan", KomgaIds.rawAnyBookId(prefix, entry.id))

        // 能进入（按 bookId 直取页列表，不依赖 seriesId）
        assertEquals(1, src.openBook(entry.id).pageCount)
        // 能记录进度：本地键 = 条目 id，回传按 1 起
        src.writeProgress(entry.id, 0, 1)
        assertEquals(0, src.readProgress(entry.id)!!.pageIndex)
        assertEquals(0, store.read(entry.id)!!.pageIndex)
        assertEquals("orphan", fake.progressWrites.last().first)
        assertEquals(1, fake.progressWrites.last().second.page)
    }

    @Test
    fun `阅读过入口也列缺 seriesId 的书`() = runBlocking<Unit> {
        val fake = FakeKomgaApi(books = mapOf("" to listOf(book("orphan", "Orphan", seriesId = ""))))
        fake.setServerProgress("orphan", page = 1)

        val entries = source(fake).listEntries(readCategory, SortMode.NAME)

        assertEquals(listOf(prefix + "/book/orphan"), entries.map { it.id })
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
    fun `登记在册但页列表为空的书给 0 页句柄 不是抛错`() = runBlocking<Unit> {
        // 票 #97 把空书口径统一到四来源（契约见 [com.cc3301.comicviewer.core.source.Source.openBook]）：
        // 书在册、服务器也回了页列表，但列表是空的 → 0 页句柄，界面据此显示中文空态；
        // 不用「未登记的 id」验这条：真机上那是 404 → 传输层异常（HttpKomgaApi.bookPages），只有 fake 才表现为空列表。
        assertEquals(0, source().openBook(prefix + "/series/s2/book/b-empty").pageCount)
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
    fun `相邻书只查本系列一次 不查全库`() = runBlocking<Unit> {
        // 票 #77：筛选条件必须在请求里（真实 HTTP 层见 HttpKomgaApiTest 的请求体断言）
        val fake = api()

        source(fake).neighbors(prefix + "/series/s1/book/b2")

        assertEquals(listOf(KomgaBookQuery.Series("s1") to "metadata.titleSort,asc"), fake.bookListQueries)
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
        // 容器行的兜底同样不许抛（票 #78 追加口径）：取不到就静默无封面
        assertNull(src.coverBytes(collectionsCategory))
        assertNull(src.coverBytes(collectionC1))
    }

    // ---------- 容器行封面兜底（票 #78 追加口径）----------

    @Test
    fun `收藏入口行的封面取第一个收藏的第一个子项`() = runBlocking<Unit> {
        val fake = api()

        assertEquals("cover-series-s1", String(source(fake).coverBytes(collectionsCategory)!!))
        assertEquals("收藏列表与收藏内容各只问一次", 1, fake.collectionSortRequests.size)
        assertEquals(listOf("c1"), fake.collectionContentRequests.map { it.first })
    }

    @Test
    fun `系列入口行的封面取第一个系列`() = runBlocking<Unit> {
        assertEquals("cover-series-s1", String(source().coverBytes(seriesCategory)!!))
    }

    @Test
    fun `书籍入口行的封面取第一本书`() = runBlocking<Unit> {
        assertEquals("cover-book-b10", String(source().coverBytes(booksCategory)!!))
    }

    @Test
    fun `阅读过入口行的封面取最近阅读倒序的第一本`() = runBlocking<Unit> {
        val fake = api()
        fake.setServerProgress("b2", page = 1)

        assertEquals("cover-book-b2", String(source(fake).coverBytes(readCategory)!!))
        assertEquals(
            "阅读过的第一本要按最近阅读倒序取（与入口列表同一排序）",
            "readProgress.lastModified,desc",
            fake.bookListQueries.last().second,
        )
    }

    @Test
    fun `收藏行的封面取该收藏第一个子项`() = runBlocking<Unit> {
        assertEquals("cover-series-s1", String(source().coverBytes(collectionC1)!!))
    }

    @Test
    fun `收藏内容为书时收藏行取的也是该书封面`() = runBlocking<Unit> {
        val fake = FakeKomgaApi(
            collections = listOf(KomgaCollection(id = "c1", name = "Collection One")),
            collectionContents = mapOf("c1" to listOf(KomgaCollectionItem.Book(book("b1", "Vol 1")))),
        )

        assertEquals("cover-book-b1", String(source(fake).coverBytes(collectionC1)!!))
    }

    @Test
    fun `容器为空或收藏内容为空时保持无封面`() = runBlocking<Unit> {
        val empty = source(FakeKomgaApi())
        assertNull(empty.coverBytes(collectionsCategory))
        assertNull(empty.coverBytes(seriesCategory))
        assertNull(empty.coverBytes(booksCategory))
        assertNull(empty.coverBytes(readCategory))

        // 收藏在册但内容为空：同样只有「无封面」一种表现
        val listedButEmpty = source(FakeKomgaApi(collections = listOf(KomgaCollection(id = "c1", name = "Collection One"))))
        assertNull(listedButEmpty.coverBytes(collectionC1))
    }

    @Test
    fun `子项自己也没有封面时保持无封面 且每个候选只问一次`() = runBlocking<Unit> {
        val fake = FakeKomgaApi(
            series = listOf(seriesA),
            books = mapOf("s1" to listOf(book("b1", "Vol 1"))),
            collections = listOf(KomgaCollection(id = "c1", name = "Collection One")),
            collectionContents = mapOf("c1" to listOf(KomgaCollectionItem.Series(seriesA))),
            seriesWithoutThumbnail = setOf("s1"),
            booksWithoutThumbnail = setOf("b1"),
        )
        val src = source(fake)

        assertNull(src.coverBytes(collectionsCategory))
        assertNull(src.coverBytes(collectionC1))
        assertEquals(
            "每个候选各问一次（无重试风暴）",
            listOf("series/s1", "book/b1", "series/s1", "book/b1"),
            fake.thumbnailRequests,
        )
    }

    @Test
    fun `系列缩略图缺失时回退该系列第一本书的封面`() = runBlocking<Unit> {
        val fake = api(seriesWithoutThumbnail = setOf("s1"))

        assertEquals("cover-book-b10", String(source(fake).coverBytes(prefix + "/series/s1")!!))
    }

    @Test
    fun `系列缩略图能取到时不问第一本书`() = runBlocking<Unit> {
        val fake = api()

        assertEquals("cover-series-s1", String(source(fake).coverBytes(prefix + "/series/s1")!!))
        assertEquals("能取到缩略图时行为不变", listOf("series/s1"), fake.thumbnailRequests)
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
        assertEquals("要写回本地，列表进度条与离线阅读才有值", 1, store.read(bookId)!!.pageIndex)
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

        assertEquals("断网也要能继续阅读（用本地）", 1, progress.pageIndex)
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

        assertThrows(SocketTimeoutException::class.java) {
            runBlocking { src.listEntries(seriesCategory, SortMode.NAME) }
        }
    }
}
