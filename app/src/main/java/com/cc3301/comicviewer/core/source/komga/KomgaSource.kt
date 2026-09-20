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
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Komga 来源（票 13、#78）：四入口（收藏 / 系列 / 书籍 / 阅读过）→ 收藏或系列 → 书 的浏览，
 * 书列表按服务器端排序取回，阅读走 Komga 的按页取图 API。
 *
 * 根容器（`listEntries(null)`）的语义自票 #78 起由**连接配置的起始路径**决定（默认 `/` = 四入口），
 * 不再固定是全部系列列表；路径与容器 id 的对应见 [KomgaBrowsePaths] 与 [KomgaIds]。
 *
 * 与文件源的区别：这里不经过 DocumentTreeSource（Komga 没有文件树），
 * 但浏览/阅读的上层（BrowserScreen、ReaderScreen、进度条、阅读菜单）完全复用同一套 [Source] 契约。
 *
 * 进度（票 14，SPEC 故事 42）：打开书时从服务器拉取定位（服务器进度权威），
 * 阅读中节流回传到 `PATCH /books/{id}/read-progress`；回传失败不阻塞阅读，记入待补传队列，
 * 下次保存或下次打开时重试（进程被杀会丢失待补传，但本地进度仍在，不会丢阅读位置）。
 *
 * 排序约定（SPEC 故事 10-14）：
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

    /** 已知页数（打开书时记录）：把服务器进度换算成「第几页 / 共几页」需要它 */
    private val pageCounts = ConcurrentHashMap<String, Int>()

    /** 待补传的进度（回传失败时记录，page 从 1 起）：bookId → 进度 */
    private val pendingSync = ConcurrentHashMap<String, KomgaReadProgress>()

    /** 同一本书的同步串行锁：翻页保存与打开时的补传会并发（慢网下单请求可能很久） */
    private val syncLocks = ConcurrentHashMap<String, Mutex>()

    /**
     * 会话内列表快照（票 #74）：键 = 容器 id + 排序方式（Komga 排序在服务端，因此与文件源不同、键必须带排序）。
     * 它同时是 [Source.cachedEntries] 的数据源：从阅读器返回浏览页时首帧同步取它，不等服务器往返。
     * [listEntries] 每次照常问服务器（服务器为权威源，不因缓存而变旧），本缓存只服务同步访问器；
     * 失效：下拉更新（[invalidateListCache]）与实例释放（[close]）。
     */
    private val listedEntries = ConcurrentHashMap<String, List<BrowseEntry>>()

    override val type: SourceType get() = SourceType.KOMGA

    override suspend fun listEntries(containerId: String?, sort: SortMode): List<BrowseEntry> {
        // containerId = null 不再固定是「全部系列」（票 #78）：它是**连接起始路径指定的那一层**
        //（默认 `/` = 四个入口）；显式传入的容器 id 按分类 / 收藏 / 系列三种命名空间分派
        val entries = if (containerId == null) entriesAtStart(sort) else entriesFor(containerId, sort)
        cacheListed(containerId, sort, entries)
        return entries
    }

    /** 同步读会话内列表快照（票 #74 / 承办 #73 AC3）：不发起任何服务器请求 */
    override fun cachedEntries(containerId: String?, sort: SortMode): List<BrowseEntry>? =
        listedEntries[listingCacheKey(containerId, sort)]

    /** 显式失效（下拉更新）：清该容器（null = 根）下各排序方式的快照 */
    override fun invalidateListCache(containerId: String?) {
        val prefix = keyPrefixOf(containerId)
        listedEntries.keys.removeIf { it.startsWith(prefix) }
    }

    private fun cacheListed(containerId: String?, sort: SortMode, entries: List<BrowseEntry>) {
        listedEntries[listingCacheKey(containerId, sort)] = entries
    }

    private fun listingCacheKey(containerId: String?, sort: SortMode): String =
        keyPrefixOf(containerId) + sort.name

    /** 缓存键前缀 = 容器 id + 分隔符：写入与失效共用这一处，改一处不会漏另一处 */
    private fun keyPrefixOf(containerId: String?): String =
        containerId.orEmpty() + LISTING_CACHE_KEY_SEPARATOR

    override suspend fun openBook(bookId: String): BookHandle {
        val rawBookId = KomgaIds.rawAnyBookId(prefix, bookId)
            ?: throw IllegalArgumentException("无效的 Komga 书：$bookId")
        val pages = api.bookPages(rawBookId)
        // 空书口径与文件源一致（票 #97，契约见 [Source.openBook]）：存在但一页都没有 → 0 页句柄，
        // 界面按 pageCount == 0 显示中文空态；抛 IllegalArgumentException 只留给「不是一本书」的输入
        // （上面已按前缀校验过 id 形状；真机上未知 id 是 404 → 传输层异常，不经这条兜底）
        pageCounts[bookId] = pages.size
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

    /**
     * 打开书时拉取服务器进度：
     * - 有未回传的本地进度时以它为准（它比服务器上的旧值新），并先尝试补传；
     * - 否则用服务器值定位（多端阅读进度一致），服务器值比本地更旧时不回退阅读位置；
     * - 合并结果写回本地（列表进度条与离线阅读都用本地）；失败一律退回本地，不打断阅读。
     */
    override suspend fun readProgress(bookId: String): ReadingProgress? = withSyncLock(bookId) {
        val local = progressStore.read(bookId)
        val rawBookId = KomgaIds.rawAnyBookId(prefix, bookId) ?: return@withSyncLock local
        val total = totalPagesOf(bookId, local)

        pendingSync[bookId]?.let { pending ->
            if (runCatching { api.writeProgress(rawBookId, pending.page, pending.completed) }.isSuccess) {
                pendingSync.remove(bookId)
            }
            return@withSyncLock mergeIntoLocal(bookId, pageIndex = localIndexOf(pending, total), local = local, total = total)
        }

        val remote = runCatching { api.readProgress(rawBookId) }.getOrNull() ?: return@withSyncLock local
        // 取更靠后的那个：上次回传失败后，服务器上的旧值不能把阅读位置拉回去
        val pageIndex = maxOf(localIndexOf(remote, total), local?.pageIndex ?: 0)
        mergeIntoLocal(bookId, pageIndex, local, total)
    }

    /** 服务器/待补传进度 → 本地页索引（Komga 的 page 从 1 起；completed 落在最后一页） */
    private fun localIndexOf(progress: KomgaReadProgress, total: Int): Int =
        if (progress.completed && total > 0) total - 1 else (progress.page - 1).coerceAtLeast(0)

    /** 合并结果写回本地；[total] 未知（0）时不写，避免进度条被误判成「读完」 */
    private suspend fun mergeIntoLocal(
        bookId: String,
        pageIndex: Int,
        local: ReadingProgress?,
        total: Int,
    ): ReadingProgress {
        if (total <= 0) return local ?: ReadingProgress(pageIndex, 0, System.currentTimeMillis())
        runCatching { progressStore.write(bookId, pageIndex, total) }
        return ReadingProgress(pageIndex, total, System.currentTimeMillis())
    }

    /**
     * 阅读中保存：本地先写（永不阻塞阅读），再回传服务器；
     * 回传失败记入待补传，下次保存/下次打开时重试（AC：网络失败不阻塞阅读，恢复后补传）。
     */
    override suspend fun writeProgress(bookId: String, pageIndex: Int, totalPages: Int) {
        progressStore.write(bookId, pageIndex, totalPages)
        val rawBookId = KomgaIds.rawAnyBookId(prefix, bookId) ?: return
        val target = KomgaReadProgress(
            page = pageIndex + 1,
            completed = totalPages > 0 && pageIndex + 1 >= totalPages,
        )
        // 与打开时的补传串行化：并发会用较旧的值覆盖较新的待补传值
        withSyncLock(bookId) {
            pendingSync.remove(bookId)?.let { pending ->
                val flushed = runCatching { api.writeProgress(rawBookId, pending.page, pending.completed) }.isSuccess
                if (!flushed) {
                    // 补传失败：保留「更新」的值，避免旧值覆盖新值
                    pendingSync[bookId] = target
                    return@withSyncLock
                }
            }
            if (!runCatching { api.writeProgress(rawBookId, target.page, target.completed) }.isSuccess) {
                pendingSync[bookId] = target
            }
        }
    }

    /** 同一本书的进度同步串行化（翻页保存与打开时补传可能并发，慢网下单请求很久） */
    private suspend fun <T> withSyncLock(bookId: String, block: suspend () -> T): T =
        syncLocks.computeIfAbsent(bookId) { Mutex() }.withLock { block() }

    override suspend fun neighbors(bookId: String): Neighbors {
        val seriesId = KomgaIds.seriesOfBook(prefix, bookId) ?: return Neighbors(null, null)
        // 相邻书只由名称自然序决定（与当前列表排序无关）：服务器按 titleSort 取回后本地再按 Windows 序排一次
        val ordered = loadAll { page -> api.listBooks(KomgaBookQuery.Series(seriesId), page, KOMGA_PAGE_SIZE, KomgaSort.FOR_NEIGHBORS) }
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
        // 无系列的书也走同一条封面通路（票 #78 修复轮：能列出就能取封面）
        KomgaIds.rawAnyBookId(prefix, entryId)?.let { return@runCatching api.bookThumbnail(it) }
        null
    }.getOrNull()

    /** 释放 HTTP 连接池（换来源时由 ServiceLocator 调用）；待补传是内存态，随会话结束丢弃 */
    override fun close() {
        api.close()
        pendingSync.clear()
        pageCounts.clear()
        syncLocks.clear()
        listedEntries.clear()
    }

    // ---------- 内部 ----------

    /**
     * 连接起始路径（票 #78）：进连接后落到的那一层。
     * 非法/缺失的路径在 [KomgaConnectionConfig.fromJson] 里已归一成 `/`，这里直接按解析结果分派。
     */
    private suspend fun entriesAtStart(sort: SortMode): List<BrowseEntry> =
        when (val start = KomgaBrowsePaths.parse(config.browsePath)) {
            KomgaBrowsePath.Root -> categoryEntries()
            KomgaBrowsePath.Collections -> collectionEntries()
            is KomgaBrowsePath.Collection -> collectionContentEntries(start.collectionId, sort)
            KomgaBrowsePath.Series -> listSeries(sort)
            is KomgaBrowsePath.SeriesBooks -> listBooks(start.seriesId, sort)
            KomgaBrowsePath.Books -> allBooks(sort)
            KomgaBrowsePath.Read -> readBooks()
        }

    /**
     * 显式容器 id → 条目（票 #78）：三种命名空间各自分派。
     * 不属于本连接的、或形状不认识的都抛 [IllegalArgumentException]（与票 13 同一口径：不静默返回空列表）。
     */
    private suspend fun entriesFor(containerId: String, sort: SortMode): List<BrowseEntry> {
        KomgaIds.rawCategory(prefix, containerId)
            ?.let { kind ->
                return when (KomgaCategory.ofKind(kind)) {
                    KomgaCategory.COLLECTIONS -> collectionEntries()
                    KomgaCategory.SERIES -> listSeries(sort)
                    KomgaCategory.BOOKS -> allBooks(sort)
                    KomgaCategory.READ -> readBooks()
                    null -> throw IllegalArgumentException("无效的 Komga 容器：$containerId")
                }
            }
        KomgaIds.rawCollectionId(prefix, containerId)
            ?.let { return collectionContentEntries(it, sort) }
        KomgaIds.rawSeriesId(prefix, containerId)
            ?.let { return listBooks(it, sort) }
        throw IllegalArgumentException("无效的 Komga 容器：$containerId")
    }

    /** 根层四个入口（票 #78）：固定顺序 = 收藏 / 系列 / 书籍 / 阅读过，不参与排序设置 */
    private fun categoryEntries(): List<BrowseEntry> = KomgaCategory.entries.map {
        BrowseEntry(
            id = KomgaIds.categoryId(prefix, it.kind),
            name = it.label,
            isBook = false,
            coverUri = null,
            // 容器不是可读单元：pageCount 约定为 null（书才有页数）
            pageCount = null,
        )
    }

    private suspend fun listSeries(sort: SortMode): List<BrowseEntry> {
        val series = loadAll { page -> api.listSeries(page, KOMGA_PAGE_SIZE, KomgaSort.forSeries(sort)) }
        return seriesEntries(series, sort)
    }

    /** 收藏列表（票 #78）：按名称（服务器端 `name,asc` 后再走一遍名称序，与系列同一套比较器） */
    private suspend fun collectionEntries(): List<BrowseEntry> {
        val collections = loadAll { page -> api.listCollections(page, KOMGA_PAGE_SIZE, KomgaSort.FOR_COLLECTION_NAMES) }
        return collections
            .map {
                BrowseEntry(
                    id = KomgaIds.collectionId(prefix, it.id),
                    name = it.name,
                    isBook = false,
                    coverUri = null,
                    pageCount = null,
                )
            }
            .sortedWith(compareBy(nameComparator) { it.name })
    }

    /**
     * 收藏内容（票 #78）：**按服务端返回什么就渲染什么**——系列→系列行、书→书行
     * （票面「若返回书则渲染为书行」，修复轮接上分派）。
     * 纯系列（Komga 原生结构）沿用系列列表那一套（含名称档的本地重排）；两类混排时按服务端顺序原样渲染。
     */
    private suspend fun collectionContentEntries(collectionId: String, sort: SortMode): List<BrowseEntry> {
        val items = loadAll { page ->
            api.collectionContent(collectionId, page, KOMGA_PAGE_SIZE, KomgaSort.forSeries(sort))
        }
        val seriesOnly = items.mapNotNull { (it as? KomgaCollectionItem.Series)?.series }
        if (seriesOnly.size == items.size) return seriesEntries(seriesOnly, sort)
        return items.map { item ->
            when (item) {
                is KomgaCollectionItem.Series -> seriesEntry(item.series)
                is KomgaCollectionItem.Book -> bookEntry(item.book)
            }
        }
    }

    private suspend fun listBooks(seriesId: String, sort: SortMode): List<BrowseEntry> {
        val books = loadAll { page -> api.listBooks(KomgaBookQuery.Series(seriesId), page, KOMGA_PAGE_SIZE, KomgaSort.forBooks(sort)) }
        return bookEntries(books, reorderByName = sort == SortMode.NAME)
    }

    /** 全部书（票 #78）：不带系列筛选，沿用全局排序设置 */
    private suspend fun allBooks(sort: SortMode): List<BrowseEntry> {
        val books = loadAll { page -> api.listBooks(KomgaBookQuery.All, page, KOMGA_PAGE_SIZE, KomgaSort.forBooks(sort)) }
        return bookEntries(books, reorderByName = sort == SortMode.NAME)
    }

    /**
     * 阅读过（票 #78）：在读 + 已读完（服务端筛）。
     * **固定按最近阅读倒序**（[KomgaSort.FOR_READ_BOOKS]，故事 14 的有意例外）——因此有意
     * 不在本地按名称重排，也不跟随排序菜单的类别档。
     */
    private suspend fun readBooks(): List<BrowseEntry> {
        val books = loadAll { page ->
            api.listBooks(KomgaBookQuery.Read, page, KOMGA_PAGE_SIZE, KomgaSort.FOR_READ_BOOKS)
        }
        return bookEntries(books, reorderByName = false)
    }

    /** 单个系列条目（系列列表与收藏内容共用） */
    private fun seriesEntry(series: KomgaSeries): BrowseEntry = BrowseEntry(
        id = KomgaIds.seriesId(prefix, series.id),
        name = series.title,
        isBook = false,
        // 系列封面按需取（见 coverBytes）：BrowseEntry.coverUri 只接受系统可解码 uri
        coverUri = null,
        pageCount = null,
    )

    /** 系列 → 条目；名称档下本地再排一遍（与文件源同一套比较器） */
    private fun seriesEntries(series: List<KomgaSeries>, sort: SortMode): List<BrowseEntry> {
        val entries = series.map { seriesEntry(it) }
        return if (sort == SortMode.NAME) entries.sortedWith(compareBy(nameComparator) { it.name }) else entries
    }

    /**
     * 书 → 条目（系列内 / 全部 / 阅读过 / 收藏内容共用）：带系列的书 id 仍是
     * `.../series/<seriesId>/book/<bookId>`（票 #78 不动它的形状——它是存量进度键）；
     * 服务器没回 `seriesId` 的书走独立命名空间 `.../book/<bookId>`（票 #78 修复轮：
     * 维护者裁决「要列出来」，不再静默丢掉）。
     */
    private suspend fun bookEntries(books: List<KomgaBook>, reorderByName: Boolean): List<BrowseEntry> {
        val entries = books.map { bookEntry(it) }
        return if (reorderByName) entries.sortedWith(compareBy(nameComparator) { it.name }) else entries
    }

    /** 单个书条目：选 id 命名空间（有/无系列）并把服务器进度并入本地 */
    private suspend fun bookEntry(book: KomgaBook): BrowseEntry {
        val id = bookIdOf(book)
        persistServerProgressIfNew(id, book)
        return BrowseEntry(
            id = id,
            name = displayNameOf(book),
            isBook = true,
            coverUri = null,
            pageCount = book.pageCount.takeIf { count -> count > 0 },
        )
    }

    /** 书条目 id（票 #78 修复轮）：有系列走 4 段（存量进度键形态），无系列走 `.../book/<bookId>` */
    private fun bookIdOf(book: KomgaBook): String =
        if (book.seriesId.isNotBlank()) KomgaIds.bookId(prefix, book.seriesId, book.id)
        else KomgaIds.standaloneBookId(prefix, book.id)

    /**
     * 列表里的服务器进度并入本地（仅当本地还没有记录）：别的设备读过的书，
     * 不必先在本机打开一次，浏览列表就能看到绿色/红色进度条。
     * [entryId] 就是该条目的进度键（有/无系列两种命名空间都适用）。
     */
    private suspend fun persistServerProgressIfNew(entryId: String, book: KomgaBook) {
        val remote = book.readProgress ?: return
        if (book.pageCount <= 0) return
        if (progressStore.read(entryId) != null) return
        runCatching { progressStore.write(entryId, localIndexOf(remote, book.pageCount), book.pageCount) }
    }

    /** 页数：优先打开书时记录的，其次本地进度里的，最后 0（未知） */
    private fun totalPagesOf(bookId: String, local: ReadingProgress?): Int =
        pageCounts[bookId]?.takeIf { it > 0 } ?: local?.totalPages?.takeIf { it > 0 } ?: 0

    /** 书名：优先标题，标题为空时退回册号；两者都没有时用 Komga 的 id（保证列表里每一项可辨认） */
    private fun displayNameOf(book: KomgaBook): String = when {
        book.title.isNotBlank() -> book.title
        book.number.isNotBlank() -> "第 " + book.number + " 册"
        else -> book.id
    }

    /** 服务器端分页：取到没有下一页为止（共用 [komgaLoadAll] 的页数上限，防服务器忽略分页导致死循环） */
    private fun <T> loadAll(load: (Int) -> KomgaPageResult<T>): List<T> = komgaLoadAll(load)

    private companion object {
        /** 会话内列表快照键里「容器 id」与「排序方式」的分隔符（票 #74） */
        const val LISTING_CACHE_KEY_SEPARATOR = "|"
    }
}
