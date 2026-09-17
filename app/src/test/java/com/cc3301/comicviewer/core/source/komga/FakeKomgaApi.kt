package com.cc3301.comicviewer.core.source.komga

/**
 * Komga 假实现（票 13 测试用）：在内存里保存系列/书/页，用于验证 [KomgaSource] 的浏览、
 * 排序、分页、相邻书与取页逻辑——不需要 Docker/真实 Komga 实例。
 *
 * 真实 HTTP 语义（路径、查询串、JSON 解析、状态码）由 HttpKomgaApiTest（MockWebServer）覆盖。
 */
class FakeKomgaApi(
    private val series: List<KomgaSeries> = emptyList(),
    private val books: Map<String, List<KomgaBook>> = emptyMap(),
    private val pages: Map<String, List<KomgaPage>> = emptyMap(),
    /** 每页条数：>0 时模拟服务器端分页（验证分页循环） */
    private val pageSize: Int = 0,
) : KomgaApi {

    /** 记录收到的排序参数（断言「发布时间走服务器端 sort」用） */
    val seriesSortRequests = mutableListOf<String>()
    val bookSortRequests = mutableListOf<String>()

    /** 非 null 时所有调用都抛它（验证失败冒泡） */
    private var failure: Throwable? = null

    fun alwaysFailWith(t: Throwable) {
        failure = t
    }

    override fun listSeries(page: Int, size: Int, sort: String): KomgaPageResult<KomgaSeries> {
        failIfNeeded()
        seriesSortRequests += sort
        return slice(series, page, size)
    }

    override fun listBooks(seriesId: String, page: Int, size: Int, sort: String): KomgaPageResult<KomgaBook> {
        failIfNeeded()
        bookSortRequests += sort
        return slice(books[seriesId].orEmpty(), page, size)
    }

    override fun seriesThumbnail(seriesId: String): ByteArray? {
        failIfNeeded()
        return "cover-series-$seriesId".toByteArray()
    }

    override fun bookThumbnail(bookId: String): ByteArray? {
        failIfNeeded()
        return "cover-book-$bookId".toByteArray()
    }

    override fun bookPages(bookId: String): List<KomgaPage> {
        failIfNeeded()
        return pages[bookId].orEmpty()
    }

    override fun pageBytes(bookId: String, pageNumber: Int): ByteArray {
        failIfNeeded()
        return ("page-$bookId-$pageNumber").toByteArray()
    }

    override fun close() {
        // 内存实现无资源
    }

    private fun <T> slice(all: List<T>, page: Int, size: Int): KomgaPageResult<T> {
        if (pageSize <= 0) return KomgaPageResult(all, hasNext = false)
        val from = page * pageSize
        val items = all.drop(from).take(pageSize)
        return KomgaPageResult(items, hasNext = from + items.size < all.size)
    }

    private fun failIfNeeded() {
        failure?.let { throw it }
    }
}
