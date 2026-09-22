package com.cc3301.comicviewer.core.source.komga

import com.cc3301.comicviewer.core.order.WindowsNameOrder
import com.cc3301.comicviewer.core.source.BookHandle
import com.cc3301.comicviewer.core.source.BrowseEntry
import com.cc3301.comicviewer.core.source.BrowseEntryPage
import com.cc3301.comicviewer.core.source.CoverByteCache
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

    /**
     * 撞到 [KOMGA_MAX_PAGES] 取数上限的那一层列表的提示（票 #119）：键 = 与 [listedEntries] 同一份缓存键。
     * 为什么按（容器 id + 排序）存而不是一个全局标志：界面在下拉更新/切排序后会重新枚举，
     * 提示必须跟着**当前这一层这一档排序**的枚举结果走，否则会把上一层的截断提示留在屏幕上。
     */
    private val truncationNotices = ConcurrentHashMap<String, String>()

    /**
     * 封面字节的会话级缓存（票 #108 r2）：键 = [coverBytes] 收到的条目 id，与文件源共用 [CoverByteCache]。
     *
     * 为什么必须有（评审 P1-2）：浏览页按可见区 ±1 屏预取封面字节，预取的收益全在「拿到的那份被可见行复用」。
     * Komga 在 r1 没有这层缓存，预取结果直接被丢掉——用户滚到那一行时仍要再拉一次，净效果是每张封面
     * 多一轮网络请求，而「滚动到之前已开始加载」一次都没发生。有了它，预取与可见行各调一次 [coverBytes]，
     * 服务器只被问一次（判据见 `KomgaSourceTest` 的「同一 id 的封面字节只拉一次」）。
     *
     * 只缓存**成功取到的字节**：失败/无封面不缓存（容器行兜底链取不到时下次仍会重试，与既有失败口径一致）。
     * 失效：[invalidateListCache]（下拉更新要真刷封面）与 [close] 整体清空。
     */
    private val coverBytesCache = CoverByteCache()

    /** 缓存里已有这一条的字节吗（票 #108 r4）：本源的缓存键就是条目 id，查一次 map 即可（不做 IO） */
    override fun hasCachedCoverBytes(entryId: String): Boolean = coverBytesCache.get(entryId) != null

    override val type: SourceType get() = SourceType.KOMGA

    override suspend fun listEntries(containerId: String?, sort: SortMode): List<BrowseEntry> {
        // containerId = null 不再固定是「全部系列」（票 #78）：它是**连接起始路径指定的那一层**
        //（默认 `/` = 四个入口）；显式传入的容器 id 按分类 / 收藏 / 系列三种命名空间分派
        val listing = if (containerId == null) entriesAtStart(sort) else entriesFor(containerId, sort)
        cacheListed(containerId, sort, listing.entries)
        recordTruncation(containerId, sort, listing)
        return listing.entries
    }

    /** 同步读会话内列表快照（票 #74 / 承办 #73 AC3）：不发起任何服务器请求 */
    override fun cachedEntries(containerId: String?, sort: SortMode): List<BrowseEntry>? =
        listedEntries[listingCacheKey(containerId, sort)]

    /**
     * 上一次 [listEntries] 撞到取数上限时的中文提示（票 #119）
     *（未截断 / 这一层还没枚举过 → null）。
     */
    override fun listTruncationNotice(containerId: String?, sort: SortMode): String? =
        truncationNotices[listingCacheKey(containerId, sort)]

    /** 显式失效（下拉更新）：清该容器（null = 根）下各排序方式的快照与截断提示，并清封面字节缓存 */
    override fun invalidateListCache(containerId: String?) {
        val prefix = keyPrefixOf(containerId)
        listedEntries.keys.removeIf { it.startsWith(prefix) }
        truncationNotices.keys.removeIf { it.startsWith(prefix) }
        // 刷新要真刷封面（与 DocumentTreeSource 同一口径）：不清就会把同一条目的旧封面还回去
        coverBytesCache.clear()
    }

    private fun cacheListed(containerId: String?, sort: SortMode, entries: List<BrowseEntry>) {
        listedEntries[listingCacheKey(containerId, sort)] = entries
    }

    /** 记下/清掉这一层这一档排序的截断提示（票 #119）：提示文案只在这里拼一次 */
    private fun recordTruncation(containerId: String?, sort: SortMode, listing: Listing) {
        val key = listingCacheKey(containerId, sort)
        if (listing.truncated) {
            truncationNotices[key] =
                "只显示了前 " + listing.entries.size + " 条（已达到 " + KOMGA_MAX_PAGES + " 页取数上限，之后的条目未显示）"
        } else {
            truncationNotices.remove(key)
        }
    }

    /**
     * 按页列出（票 #119 步骤 3）：只在「服务器排序就是最终顺序」的层按页直取服务器，不拉全量。
     *
     * 覆盖面（本轮交的是**取数接缝**，界面的增量加载在下一轮接）：
     * - 「阅读过」入口（固定最近阅读倒序、本地不重排）：任意排序档都能按页直取；
     * - 「全部书」与某系列的书：只在服务器排序即最终顺序的修改时间/发布时间档按页直取。
     *
     * 其余一律回退 [Source.listEntriesPage] 的默认实现（先全量、再切片）：名称档要按 Windows 名称序
     * 本地重排，收藏/系列列表与收藏内容也走本地名称序，逐页直取会让局部重排打乱全局顺序。
     */
    override suspend fun listEntriesPage(
        containerId: String?,
        sort: SortMode,
        page: Int,
        size: Int,
    ): BrowseEntryPage {
        // 起始路径（containerId=null）分派到哪一层取决于连接配置，一律走默认实现（全量后切片）
        if (containerId == null) return super.listEntriesPage(containerId, sort, page, size)
        KomgaIds.rawCategory(prefix, containerId)?.let { kind ->
            return when (KomgaCategory.ofKind(kind)) {
                KomgaCategory.READ -> booksPage(KomgaBookQuery.Read, KomgaSort.FOR_READ_BOOKS, page, size)
                KomgaCategory.BOOKS -> if (sort == SortMode.NAME) {
                    super.listEntriesPage(containerId, sort, page, size)
                } else {
                    booksPage(KomgaBookQuery.All, KomgaSort.forBooks(sort), page, size)
                }
                else -> super.listEntriesPage(containerId, sort, page, size)
            }
        }
        KomgaIds.rawSeriesId(prefix, containerId)?.let { seriesId ->
            return if (sort == SortMode.NAME) {
                super.listEntriesPage(containerId, sort, page, size)
            } else {
                booksPage(KomgaBookQuery.Series(seriesId), KomgaSort.forBooks(sort), page, size)
            }
        }
        return super.listEntriesPage(containerId, sort, page, size)
    }

    /**
     * 书列表的一页（票 #119 步骤 3）：服务器按 [sort] 排好、本地不重排——
     * 只有「服务器排序即最终顺序」的档位会调到这里（见 [listEntriesPage]）。
     */
    private suspend fun booksPage(query: KomgaBookQuery, sort: String, page: Int, size: Int): BrowseEntryPage {
        val result = api.listBooks(query, page, size, sort)
        return BrowseEntryPage(bookEntries(result.items, reorderByName = false), result.hasNext)
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
        val ordered = komgaLoadAll { page -> api.listBooks(KomgaBookQuery.Series(seriesId), page, KOMGA_PAGE_SIZE, KomgaSort.FOR_NEIGHBORS) }
            .items
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
     *
     * 字节按条目 id 进会话缓存（票 #108 r2）：预取与可见行各调一次这里，服务器只被问一次。
     *
     * 票 #78 追加口径（维护者真机反馈）：**容器行自身没有封面时，回退显示第一个子项的封面**
     * （与文件源 #102 的「父容器逐级下取」同一口径）——系列行在服务端缩略图缺失时回退该系列第一本书；
     * 收藏行回退该收藏第一个子项；根层四个入口行回退该入口第一个子项。
     * 「第一个子项」一律取**名称序**第一个：本接口不带排序设置（列表排序由 [listEntries] 的 sort 决定），
     * 名称序是唯一与调用方无关的确定口径。兜底最多 4 次请求（入口 → 收藏 → 系列 → 书），
     * 取不到就静默返回 null —— 不重试、不报错：可见行的封面加载是并行的（同一行只会出现一个占位图）。
     */
    override suspend fun coverBytes(entryId: String): ByteArray? {
        coverBytesCache.get(entryId)?.let { return it }
        val bytes = runCatching {
            KomgaIds.rawSeriesId(prefix, entryId)?.let { return@runCatching seriesCover(it) }
            // 无系列的书也走同一条封面通路（票 #78 修复轮：能列出就能取封面）
            KomgaIds.rawAnyBookId(prefix, entryId)?.let { return@runCatching api.bookThumbnail(it) }
            KomgaIds.rawCollectionId(prefix, entryId)?.let { return@runCatching firstChildCoverOfCollection(it) }
            KomgaIds.rawCategory(prefix, entryId)?.let { kind ->
                KomgaCategory.ofKind(kind)?.let { return@runCatching firstChildCoverOfCategory(it) }
            }
            null
        }.getOrNull() ?: return null
        coverBytesCache.put(entryId, bytes)
        return bytes
    }

    /** 系列封面（票 #78 追加口径）：服务端缩略图优先，缺失时回退该系列名称序第一本书 */
    private suspend fun seriesCover(seriesId: String): ByteArray? = api.seriesThumbnail(seriesId)
        ?: firstBookCover(KomgaBookQuery.Series(seriesId), KomgaSort.forBooks(SortMode.NAME))

    /** 收藏行封面（票 #78 追加口径）：该收藏名称序第一个子项的封面（子项可能是系列，也可能是书） */
    private suspend fun firstChildCoverOfCollection(collectionId: String): ByteArray? =
        api.collectionContent(collectionId, 0, COVER_CANDIDATE_SIZE, KomgaSort.forSeries(SortMode.NAME))
            .items.firstOrNull()
            ?.let { item ->
                when (item) {
                    is KomgaCollectionItem.Series -> seriesCover(item.series.id)
                    is KomgaCollectionItem.Book -> api.bookThumbnail(item.book.id)
                }
            }

    /**
     * 类别行封面（票 #78 追加口径）：该入口名称序第一个子项的封面。
     * 分派与 [entriesForCategory] 一一对应（同一个入口 → 同一批子项）；
     * 阅读过按它自己的固定排序（最近阅读倒序）取第一本，与入口列表看到的第一行一致。
     */
    private suspend fun firstChildCoverOfCategory(category: KomgaCategory): ByteArray? = when (category) {
        KomgaCategory.COLLECTIONS ->
            api.listCollections(0, COVER_CANDIDATE_SIZE, KomgaSort.FOR_COLLECTION_NAMES)
                .items.firstOrNull()
                ?.let { firstChildCoverOfCollection(it.id) }
        KomgaCategory.SERIES ->
            api.listSeries(0, COVER_CANDIDATE_SIZE, KomgaSort.forSeries(SortMode.NAME))
                .items.firstOrNull()
                ?.let { seriesCover(it.id) }
        KomgaCategory.BOOKS -> firstBookCover(KomgaBookQuery.All, KomgaSort.forBooks(SortMode.NAME))
        KomgaCategory.READ -> firstBookCover(KomgaBookQuery.Read, KomgaSort.FOR_READ_BOOKS)
    }

    /** 书列表里名称序第一本的封面（票 #78 追加口径：只取一本，不为一张兜底封面拉整页列表） */
    private suspend fun firstBookCover(query: KomgaBookQuery, sort: String): ByteArray? =
        api.listBooks(query, 0, COVER_CANDIDATE_SIZE, sort).items.firstOrNull()?.let { api.bookThumbnail(it.id) }

    /** 释放 HTTP 连接池（换来源时由 ServiceLocator 调用）；待补传是内存态，随会话结束丢弃 */
    override fun close() {
        api.close()
        pendingSync.clear()
        pageCounts.clear()
        syncLocks.clear()
        listedEntries.clear()
        truncationNotices.clear()
        coverBytesCache.clear()
    }

    // ---------- 内部 ----------

    /**
     * 一层列表的枚举结果 + 是否撞到取数上限（票 #119）。
     * [truncated] 从最内层的 [komgaLoadAll] 一路带到 [listEntries]，在那里转成可见提示。
     */
    private data class Listing(val entries: List<BrowseEntry>, val truncated: Boolean = false)

    /**
     * 连接起始路径（票 #78）：进连接后落到的那一层。
     * 非法/缺失的路径在 [KomgaConnectionConfig.fromJson] 里已归一成 `/`，这里直接按解析结果分派。
     */
    private suspend fun entriesAtStart(sort: SortMode): Listing =
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
    private suspend fun entriesFor(containerId: String, sort: SortMode): Listing {
        KomgaIds.rawCategory(prefix, containerId)
            ?.let { kind ->
                val category = KomgaCategory.ofKind(kind)
                    ?: throw IllegalArgumentException("无效的 Komga 容器：$containerId")
                return entriesForCategory(category, sort)
            }
        KomgaIds.rawCollectionId(prefix, containerId)
            ?.let { return collectionContentEntries(it, sort) }
        KomgaIds.rawSeriesId(prefix, containerId)
            ?.let { return listBooks(it, sort) }
        throw IllegalArgumentException("无效的 Komga 容器：$containerId")
    }

    /** 类别档 → 条目（票 #78）：类别内容的四路分派只有这一处 */
    private suspend fun entriesForCategory(kind: KomgaCategory, sort: SortMode): Listing =
        when (kind) {
            KomgaCategory.COLLECTIONS -> collectionEntries()
            KomgaCategory.SERIES -> listSeries(sort)
            KomgaCategory.BOOKS -> allBooks(sort)
            KomgaCategory.READ -> readBooks()
        }

    /** 根层四个入口（票 #78）：固定顺序 = 收藏 / 系列 / 书籍 / 阅读过，不参与排序设置 */
    private fun categoryEntries(): Listing = Listing(KomgaCategory.entries.map {
        BrowseEntry(
            id = KomgaIds.categoryId(prefix, it.kind),
            name = it.label,
            isBook = false,
            coverUri = null,
            // 容器不是可读单元：pageCount 约定为 null（书才有页数）
            pageCount = null,
        )
    })

    private suspend fun listSeries(sort: SortMode): Listing {
        val loaded = komgaLoadAll { page -> api.listSeries(page, KOMGA_PAGE_SIZE, KomgaSort.forSeries(sort)) }
        return Listing(seriesEntries(loaded.items, sort), loaded.truncated)
    }

    /** 收藏列表（票 #78）：按名称（服务器端 `name,asc` 后再走一遍名称序，与系列同一套比较器） */
    private suspend fun collectionEntries(): Listing {
        val loaded = komgaLoadAll { page -> api.listCollections(page, KOMGA_PAGE_SIZE, KomgaSort.FOR_COLLECTION_NAMES) }
        val entries = loaded.items
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
        return Listing(entries, loaded.truncated)
    }

    /**
     * 收藏内容（票 #78）：**按服务端返回什么就渲染什么**——系列→系列行、书→书行
     * （票面「若返回书则渲染为书行」，修复轮接上分派）。
     * 纯系列（Komga 原生结构）沿用系列列表那一套（含名称档的本地重排）；两类混排时按服务端顺序原样渲染。
     */
    private suspend fun collectionContentEntries(collectionId: String, sort: SortMode): Listing {
        val loaded = komgaLoadAll { page ->
            api.collectionContent(collectionId, page, KOMGA_PAGE_SIZE, KomgaSort.forSeries(sort))
        }
        val items = loaded.items
        val seriesOnly = items.mapNotNull { (it as? KomgaCollectionItem.Series)?.series }
        if (seriesOnly.size == items.size) return Listing(seriesEntries(seriesOnly, sort), loaded.truncated)
        val entries = items.map { item ->
            when (item) {
                is KomgaCollectionItem.Series -> seriesEntry(item.series)
                is KomgaCollectionItem.Book -> bookEntry(item.book)
            }
        }
        return Listing(entries, loaded.truncated)
    }

    private suspend fun listBooks(seriesId: String, sort: SortMode): Listing =
        listedBooks(KomgaBookQuery.Series(seriesId), KomgaSort.forBooks(sort), reorderByName = sort == SortMode.NAME)

    /** 全部书（票 #78）：不带系列筛选，沿用全局排序设置 */
    private suspend fun allBooks(sort: SortMode): Listing =
        listedBooks(KomgaBookQuery.All, KomgaSort.forBooks(sort), reorderByName = sort == SortMode.NAME)

    /**
     * 阅读过（票 #78）：在读 + 已读完（服务端筛）。
     * **固定按最近阅读倒序**（[KomgaSort.FOR_READ_BOOKS]，故事 14 的有意例外）——因此有意
     * 不在本地按名称重排，也不跟随排序菜单的类别档。
     */
    private suspend fun readBooks(): Listing =
        listedBooks(KomgaBookQuery.Read, KomgaSort.FOR_READ_BOOKS, reorderByName = false)

    /**
     * 书列表（票 #78）：系列内 / 全部 / 阅读过三处同一形状——按 [query] 取（服务端按 [sort] 排序），
     * [reorderByName] 决定是否再按名称自然序本地排一遍（阅读过固定最近阅读倒序，不重排）。
     */
    private suspend fun listedBooks(
        query: KomgaBookQuery,
        sort: String,
        reorderByName: Boolean,
    ): Listing {
        val loaded = komgaLoadAll { page -> api.listBooks(query, page, KOMGA_PAGE_SIZE, sort) }
        return Listing(bookEntries(loaded.items, reorderByName = reorderByName), loaded.truncated)
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

    private companion object {
        /** 会话内列表快照键里「容器 id」与「排序方式」的分隔符（票 #74） */
        const val LISTING_CACHE_KEY_SEPARATOR = "|"

        /** 容器行封面兜底只看第一个子项（票 #78 追加口径）：一张封面不需要整页列表 */
        const val COVER_CANDIDATE_SIZE = 1
    }
}
