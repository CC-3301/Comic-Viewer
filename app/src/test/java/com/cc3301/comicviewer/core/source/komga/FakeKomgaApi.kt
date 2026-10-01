package com.cc3301.comicviewer.core.source.komga

/**
 * Komga 假实现：在内存里保存系列/收藏/书/页，用于验证 [KomgaSource] 的浏览、
 * 排序、分页、相邻书与取页逻辑——不需要 Docker/真实 Komga 实例。
 *
 * 真实 HTTP 语义（路径、查询串、JSON 解析、状态码）由 HttpKomgaApiTest（MockWebServer）覆盖。
 *
 * 书列表按 [KomgaBookQuery] 筛选（某系列 / 全部 / 阅读过），收藏与收藏内容各有独立清单。
 * 封面 = 书的**第 1 页原图**，因此「这本书有没有封面」就由 [pages] 里有没有第 1 页决定
 *（空页列表 / 未登记 = 服务器取不到第 1 页，真实现是 404/204 → null）。
 * 各列表请求的 `size` 也记下来（[bookListSizes] 等）——封面的候选页大小是修复前提，
 * 要有护栏钉住（本夹具的 [pageSize] 仍是它自己的分页模拟旋钮，与调用方传的 `size` 无关，两者不要混）。
 */
class FakeKomgaApi(
    private val series: List<KomgaSeries> = emptyList(),
    private val books: Map<String, List<KomgaBook>> = emptyMap(),
    private val pages: Map<String, List<KomgaPage>> = emptyMap(),
    /** 每页条数：>0 时模拟服务器端分页（验证分页循环） */
    private val pageSize: Int = 0,
    /** true 时模拟「服务器永远说还有下一页」（验证取满上限后给出截断提示） */
    private val alwaysHasNext: Boolean = false,
    /** 收藏列表 */
    private val collections: List<KomgaCollection> = emptyList(),
    /** 收藏 id → 该收藏的内容（Komga 原生结构里是系列；也表达得了书） */
    private val collectionContents: Map<String, List<KomgaCollectionItem>> = emptyMap(),
    /**
     * 预置某本书第 1 页的**真实图片字节**（成本核算用）；没预置的书走玩具字节。
     * 只影响 [bookFirstPage] 的返回值，不改变「这本书有没有第 1 页」的判定（那由 [pages] 决定）。
     */
    private val firstPageBytes: Map<String, ByteArray> = emptyMap(),
) : KomgaApi {

    /** 封面取数记录（`page1/<bookId>`）：断言「兜底链每跳只问一次、不重试」 */
    val coverRequests = mutableListOf<String>()

    /** 记录收到的系列排序参数（断言「发布时间走服务器端 sort」用） */
    val seriesSortRequests = mutableListOf<String>()

    /** 记录书列表的（筛选条件, sort）请求（断言「筛选只查同系列」与「发布时间走服务器端 sort」用） */
    val bookListQueries = mutableListOf<Pair<KomgaBookQuery, String>>()

    /**
     * 各列表请求收到的**候选页大小**（护栏）：封面的候选从 1 提到一页是这处改动的前提，
     * 夹具不记 size 的话这个前提被改回 1 也没有用例会红（护栏就是假的）。
     * 四个字段各自对应一种列表请求，与上面几个**请求记录**字段同一形状。
     */
    val bookListSizes = mutableListOf<Int>()
    val seriesListSizes = mutableListOf<Int>()
    val collectionListSizes = mutableListOf<Int>()
    val collectionContentSizes = mutableListOf<Int>()

    /** 记录收藏列表的排序参数 */
    val collectionSortRequests = mutableListOf<String>()

    /** 记录收藏内容的（collectionId, sort）请求 */
    val collectionContentRequests = mutableListOf<Pair<String, String>>()

    /** 非 null 时所有调用都抛它（验证失败冒泡） */
    private var failure: Throwable? = null

    /** 服务器上的阅读进度（模拟 Komga 的 read-progress） */
    private val serverProgress = mutableMapOf<String, KomgaReadProgress>()

    /** 回传记录（断言「阅读中回传页码」） */
    val progressWrites = mutableListOf<Pair<String, KomgaReadProgress>>()

    /** 只让写进度失败（验证「网络失败不阻塞阅读 + 恢复后补传」） */
    var failWrites: Boolean = false

    fun alwaysFailWith(t: Throwable) {
        failure = t
    }

    /** 预置服务器进度（模拟「在别的设备上读过」） */
    fun setServerProgress(bookId: String, page: Int, completed: Boolean = false) {
        serverProgress[bookId] = KomgaReadProgress(page, completed)
    }

    fun serverProgressOf(bookId: String): KomgaReadProgress? = serverProgress[bookId]

    override fun listSeries(page: Int, size: Int, sort: String): KomgaPageResult<KomgaSeries> {
        failIfNeeded()
        seriesSortRequests += sort
        seriesListSizes += size
        return slice(series, page, size)
    }

    override fun listCollections(page: Int, size: Int, sort: String): KomgaPageResult<KomgaCollection> {
        failIfNeeded()
        collectionSortRequests += sort
        collectionListSizes += size
        return slice(collections, page, size)
    }

    override fun collectionContent(
        collectionId: String,
        page: Int,
        size: Int,
        sort: String,
    ): KomgaPageResult<KomgaCollectionItem> {
        failIfNeeded()
        collectionContentRequests += collectionId to sort
        collectionContentSizes += size
        return slice(collectionContents[collectionId].orEmpty(), page, size)
    }

    override fun listBooks(query: KomgaBookQuery, page: Int, size: Int, sort: String): KomgaPageResult<KomgaBook> {
        failIfNeeded()
        bookListQueries += query to sort
        bookListSizes += size
        val all = when (query) {
            is KomgaBookQuery.Series -> books[query.seriesId].orEmpty()
            // 「全部」与「阅读过」都跨系列；阅读过按服务器上的阅读记录筛
            KomgaBookQuery.All -> books.values.flatten()
            KomgaBookQuery.Read -> books.values.flatten().filter { serverProgress.containsKey(it.id) }
        }
        // 真实 Komga 在书列表里就带 readProgress：一起带上，便于验证「列表即可见跨端进度」
        return slice(all.map { it.copy(readProgress = serverProgress[it.id]) }, page, size)
    }

    /**
     * 书封面 = 该书**第 1 页原图**：页列表为空或没登记这本书 ⇒ 取不到第 1 页 ⇒ null。
     * 返回的字节带上页号，好让用例验「拿的是第 1 页」而不是随便某一页。
     */
    override fun bookFirstPage(bookId: String): ByteArray? {
        failIfNeeded()
        coverRequests += "page1/$bookId"
        firstPageBytes[bookId]?.let { return it }
        val first = pages[bookId]?.firstOrNull() ?: return null
        return ("cover-page-" + bookId + "-" + first.number).toByteArray()
    }

    override fun bookPages(bookId: String): List<KomgaPage> {
        failIfNeeded()
        return pages[bookId].orEmpty()
    }

    override fun pageBytes(bookId: String, pageNumber: Int): ByteArray {
        failIfNeeded()
        return ("page-$bookId-$pageNumber").toByteArray()
    }

    override fun readProgress(bookId: String): KomgaReadProgress? {
        failIfNeeded()
        return serverProgress[bookId]
    }

    override fun writeProgress(bookId: String, page: Int, completed: Boolean): KomgaReadProgress? {
        failIfNeeded()
        if (failWrites) throw KomgaException(KomgaFailureKind.TIMEOUT, "回传超时", null)
        val progress = KomgaReadProgress(page, completed)
        serverProgress[bookId] = progress
        progressWrites += bookId to progress
        return progress
    }

    override fun close() {
        // 内存实现无资源
    }

    private fun <T> slice(all: List<T>, page: Int, size: Int): KomgaPageResult<T> {
        if (pageSize <= 0) return KomgaPageResult(all, hasNext = false)
        val from = page * pageSize
        val items = all.drop(from).take(pageSize)
        return KomgaPageResult(items, hasNext = alwaysHasNext || from + items.size < all.size)
    }

    private fun failIfNeeded() {
        failure?.let { throw it }
    }
}
