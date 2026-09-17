package com.cc3301.comicviewer.core.source.komga

import com.cc3301.comicviewer.core.order.WindowsNameOrder
import com.cc3301.comicviewer.core.source.BookHandle
import com.cc3301.comicviewer.core.source.BrowseEntry
import com.cc3301.comicviewer.core.source.Neighbors
import com.cc3301.comicviewer.core.source.PageData
import com.cc3301.comicviewer.core.source.ProgressStore
import com.cc3301.comicviewer.core.source.ReadingProgress
import com.cc3301.comicviewer.core.source.SortMode
import com.cc3301.comicviewer.core.source.Source
import com.cc3301.comicviewer.core.source.SourceType

/**
 * Komga 来源（票 13）：系列 → 书 两级浏览，书列表按服务器端排序取回，
 * 阅读走 Komga 的按页取图 API。
 *
 * 与文件源的区别：这里不经过 DocumentTreeSource（Komga 没有文件树），
 * 但浏览/阅读的上层（BrowserScreen、ReaderScreen、进度条、阅读菜单）完全复用同一套 [Source] 契约。
 *
 * 排序约定（SPEC 故事 14/15/44）：
 * - 名称：服务器端先按 titleSort 取，再用 Windows 名称序本地排一遍（与文件源同一套比较器）；
 * - 修改时间：服务器端 `lastModifiedDate,desc`；
 * - 发布时间：服务器端 `metadata.releaseDate,desc`（书）；系列没有发布时间，回退最后修改时间；
 * - 相邻书：固定用名称自然序（SPEC 故事 28），因此单独按名称取一次同系列的书。
 */
class KomgaSource(
    private val api: KomgaApi,
    private val config: KomgaConnectionConfig,
    private val progressStore: ProgressStore,
    private val nameComparator: Comparator<String> = WindowsNameOrder.COMPARATOR,
) : Source {

    private val prefix: String = KomgaIds.prefix(config.baseUrl)

    override val type: SourceType get() = SourceType.KOMGA

    override suspend fun listEntries(containerId: String?, sort: SortMode): List<BrowseEntry> {
        val seriesId = containerId?.let {
            KomgaIds.rawSeriesId(prefix, it) ?: throw IllegalArgumentException("无效的 Komga 容器：$it")
        }
        return if (seriesId == null) listSeries(sort) else listBooks(seriesId, sort)
    }

    override suspend fun openBook(bookId: String): BookHandle {
        val rawBookId = KomgaIds.rawBookId(prefix, bookId)
            ?: throw IllegalArgumentException("无效的 Komga 书：$bookId")
        val pages = api.bookPages(rawBookId)
        if (pages.isEmpty()) throw IllegalArgumentException("不是一本书：$bookId")
        return object : BookHandle {
            override val id: String = bookId
            override val pageCount: Int = pages.size
            override suspend fun loadPage(index: Int): PageData {
                val page = pages.getOrNull(index)
                    ?: throw IndexOutOfBoundsException("页码越界：$index / ${pages.size}")
                return PageData(api.pageBytes(rawBookId, page.number), page.mediaType)
            }
        }
    }

    override suspend fun readProgress(bookId: String): ReadingProgress? = progressStore.read(bookId)

    override suspend fun writeProgress(bookId: String, pageIndex: Int, totalPages: Int) =
        progressStore.write(bookId, pageIndex, totalPages)

    override suspend fun neighbors(bookId: String): Neighbors {
        val seriesId = KomgaIds.seriesOfBook(prefix, bookId) ?: return Neighbors(null, null)
        // 相邻书只由名称自然序决定（与当前列表排序无关）：服务器按 titleSort 取回后本地再按 Windows 序排一次
        val ordered = loadAll { page -> api.listBooks(seriesId, page, PAGE_SIZE, KomgaSort.FOR_NEIGHBORS) }
            .map { KomgaIds.bookId(prefix, seriesId, it.id) to displayNameOf(it) }
            .sortedWith(compareBy(nameComparator) { it.second })
            .map { it.first }
        val index = ordered.indexOf(bookId)
        if (index < 0) return Neighbors(null, null)
        return Neighbors(
            prev = ordered.getOrNull(index - 1),
            next = ordered.getOrNull(index + 1),
        )
    }

    /**
     * 封面（票 13）：Komga 需要认证头，系统解码器拿不到，因此走这条按需取字节的路径。
     * 失败一律吞成 null（与 DocumentTreeSource 一致）：缩略图没有就没有，不能让浏览列表崩。
     */
    override suspend fun coverBytes(entryId: String): ByteArray? = runCatching {
        KomgaIds.rawSeriesId(prefix, entryId)?.let { return@runCatching api.seriesThumbnail(it) }
        KomgaIds.rawBookId(prefix, entryId)?.let { return@runCatching api.bookThumbnail(it) }
        null
    }.getOrNull()

    /** 释放 HTTP 连接池（换来源时由 ServiceLocator 调用） */
    override fun close() {
        api.close()
    }

    // ---------- 内部 ----------

    private fun listSeries(sort: SortMode): List<BrowseEntry> {
        val series = loadAll { page -> api.listSeries(page, PAGE_SIZE, KomgaSort.forSeries(sort)) }
        val entries = series.map {
            BrowseEntry(
                id = KomgaIds.seriesId(prefix, it.id),
                name = it.title,
                isBook = false,
                // 系列封面按需取（见 coverBytes）：BrowseEntry.coverUri 只接受系统可解码 uri
                coverUri = null,
                // 容器不是可读单元：pageCount 约定为 null（书才有页数）
                pageCount = null,
            )
        }
        return if (sort == SortMode.NAME) entries.sortedWith(compareBy(nameComparator) { it.name }) else entries
    }

    private fun listBooks(seriesId: String, sort: SortMode): List<BrowseEntry> {
        val books = loadAll { page -> api.listBooks(seriesId, page, PAGE_SIZE, KomgaSort.forBooks(sort)) }
        val entries = books.map {
            BrowseEntry(
                id = KomgaIds.bookId(prefix, seriesId, it.id),
                name = displayNameOf(it),
                isBook = true,
                coverUri = null,
                pageCount = it.pageCount.takeIf { count -> count > 0 },
            )
        }
        return if (sort == SortMode.NAME) entries.sortedWith(compareBy(nameComparator) { it.name }) else entries
    }

    /** 书名：优先标题，标题为空时退回册号；两者都没有时用 Komga 的 id（保证列表里每一项可辨认） */
    private fun displayNameOf(book: KomgaBook): String = when {
        book.title.isNotBlank() -> book.title
        book.number.isNotBlank() -> "第 " + book.number + " 册"
        else -> book.id
    }

    /** 服务器端分页：取到没有下一页为止（带页数上限，防服务器忽略分页导致死循环） */
    private fun <T> loadAll(load: (Int) -> KomgaPageResult<T>): List<T> {
        val out = mutableListOf<T>()
        var page = 0
        while (page < MAX_PAGES) {
            val result = load(page)
            out += result.items
            if (!result.hasNext || result.items.isEmpty()) break
            page++
        }
        return out
    }

    private companion object {
        /** 单次请求条数（Komga 默认上限 2000，取 500 兼顾首屏速度与请求数） */
        const val PAGE_SIZE = 500

        /** 分页上限（20 * 500 = 1 万条），防止服务器分页字段异常时无限循环 */
        const val MAX_PAGES = 20
    }
}
