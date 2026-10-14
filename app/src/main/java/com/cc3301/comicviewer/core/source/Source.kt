package com.cc3301.comicviewer.core.source

/** 四类内容来源 */
enum class SourceType { LOCAL, SMB, WEBDAV, KOMGA }

/**
 * 排序方式（spec：名称 / 修改时间 / 发布时间，全部来源可用）。
 * 方向不在这个接口里：正/反向是展示层概念（`core/sort/SortSetting.kt` 的全局排序设置），
 * 来源只按方式返回「正向」序——名称升序、修改时间/发布时间 新→旧。
 */
enum class SortMode { NAME, MODIFIED_TIME, RELEASE_TIME }

/**
 * 浏览条目：书或容器（文件夹/系列）。
 * id 在来源内不透明唯一；上层只能从 [Source.listEntries] 的返回值中获取后回传。
 */
data class BrowseEntry(
    val id: String,
    val name: String,
    /** true=可直接打开阅读的书；false=需继续浏览的容器 */
    val isBook: Boolean,
    /**
     * 封面：书=第一页；本层有图的容器（「图片+子文件夹/压缩包」这类目录是容器）= 本层首图；
     * 其余容器 = 逐级下取（每一层都是本层图 → 本层首个压缩包的首帧 → 再下探子目录）；null=暂无。
     *
     * 文件源（本地/SAF、SMB、WebDAV 共用 [DocumentTreeSource]）的枚举期不为封面做额外往返：
     * 只给「本层首图」与「图片本身」这类零开销的 uri（含本层有图的容器），其余容器封面与压缩包封面一律为 null，
     * 由界面在可见行走 [Source.coverBytes] 按需取。
     */
    val coverUri: String?,
    /**
     * 书=总页数；容器=null。
     *
     * 文件源（本地/SAF、SMB、WebDAV 共用 [DocumentTreeSource]）的枚举期不统计页数：
     * 不为页数读压缩包中央目录、也不为页数列子目录，因此列表里一律为 null；
     * 页数只在打开书后由 [BookHandle.pageCount] 给出。
     * Komga 的页数来自服务器返回的 payload（零额外成本）；列表条目照常带上该字段（契约保留，
     * 当前无 UI/业务消费者），但界面不显示。
     */
    val pageCount: Int? = null,
)

/**
 * 路径菜单要列的一级目录：显示用的 [entry] + 它**是不是起点层本身**（[isStartLayer]）。
 *
 * [isStartLayer] = true 只在一种情形下成立：连接的起始浏览路径正好落在这一层的内容上
 *（Komga 把路径设成 `/series` 这类）——它跟起点层是同一屏，菜单据此把它指回起点层、
 * 不另压一层相同的（spec 故事 55）。文件源永远为 false（顶层条目不可能是根容器自己）。
 */
data class TopLevelEntry(val entry: BrowseEntry, val isStartLayer: Boolean = false)

/**
 * 路径菜单的「顶层」：列出 [entries] 的那一屏——[containerId] 是它的容器 id（`null` = 根容器），
 * 菜单里的 `/` 就指向它。
 * 默认（文件源与 Komga 默认起始路径）恒为根容器；Komga 把起始路径设到别处时，根容器成了那一处的列表，
 * 四入口那一屏改用分类根地址（`.../cat`）——见 [Source.topLevelEntries]。
 */
data class TopLevelListing(val containerId: String?, val entries: List<TopLevelEntry>)

/** 阅读进度（页码 0-based） */
data class ReadingProgress(
    val pageIndex: Int,
    val totalPages: Int,
    val updatedAtMs: Long,
)

/** 进度展示纯函数：部分填充绿=进行中，满格红=读完（读到最后一页） */
val ReadingProgress.isCompleted: Boolean
    get() = pageIndex + 1 >= totalPages.coerceAtLeast(1)

val ReadingProgress.displayFraction: Float
    get() = ((pageIndex + 1).toFloat() / totalPages.coerceAtLeast(1)).coerceIn(0f, 1f)

/**
 * 条目该显示哪一条进度（spec 故事 16/45）：只有书条目、且已读过才显示——
 * 文件夹与系列不存在「读到第几页」，即使它们的 id 下碰巧有进度行也不画进度条。
 * 浏览列表与书柜柜内的进度条门控共用这一处，两处只能同时显示或同时不显示。
 */
fun progressForEntry(entry: BrowseEntry, progress: ReadingProgress?): ReadingProgress? =
    progress?.takeIf { entry.isBook }

/** 单页图片数据 */
class PageData(val bytes: ByteArray, val mimeType: String)

/** 打开书之后的句柄：随机访问页面 */
interface BookHandle {
    /** 与打开时传入的 bookId 同源（缓存键/进度写入的权威来源） */
    val id: String
    val pageCount: Int

    /** 越界抛 IndexOutOfBoundsException */
    suspend fun loadPage(index: Int): PageData
}

/**
 * 浏览一页的结果：[hasNext] = 后面还有没取回的条目。
 * 与 [KomgaPageResult] 同一形状，但住在本层：界面只认 [Source]，不依赖具体来源的分页 DTO。
 */
data class BrowseEntryPage(val entries: List<BrowseEntry>, val hasNext: Boolean)

/**
 * 把一份**整层列表**切成一页：越界页与短末页都夹到合法区间，[BrowseEntryPage.hasNext] 看后面还有没有。
 *
 * **唯一一份算式**：[Source.listEntriesPage] 的默认实现与 `KomgaSource` 的回退档逐字相同地各写过一份，
 * 现在都调这里——两处手写同形算式会让「末页 `hasNext` 怎么算」各飘各的。
 * `internal`：消费方只有本模块的默认实现、`KomgaSource` 与用例（与 `core/source/AtomicFileMove.kt` 同一取舍）。
 */
internal fun sliceEntryPage(all: List<BrowseEntry>, page: Int, size: Int): BrowseEntryPage {
    val from = (page * size).coerceIn(0, all.size)
    val to = (from + size).coerceIn(from, all.size)
    return BrowseEntryPage(entries = all.subList(from, to).toList(), hasNext = to < all.size)
}

/**
 * 四来源统一接口（tracer-bullet seam）。
 * 同一套行为测试集（SourceBehaviorContract）盯的是 `FsBackend` seam 的文件树后端 adapter：
 * 本地 SAF、SMB、WebDAV 三者各有一份绑定，跑的是同一份浏览→打开→取图→进度代码；
 * `File` 后端在 `main` 里零调用点，不进契约绑定。
 */
interface Source {
    val type: SourceType

    /**
     * 列出容器下的条目（已按 [sort] 排序）。
     * containerId=null 表示来源根容器。
     */
    suspend fun listEntries(containerId: String?, sort: SortMode): List<BrowseEntry>

    /**
     * 分页列出容器下的条目：[page] 从 0 起、每页最多 [size] 条。
     *
     * 顺序契约：**同一 (containerId, sort) 下，逐页拼起来的结果与 [listEntries] 逐字同序**——
     * 界面因此能把增量加载拼成与「一次取完再上屏」等价的列表，排序语义不变。
     *
     * 默认实现：取 [listEntries] 全量后切片（文件源列目录没有服务端分页，行为与 [listEntries] 一致）。
     * 有服务端分页的来源（Komga）在「服务器排序即最终顺序」的档位上覆盖本方法按页直取，不拉全量；
     * 需要本地重排的档位（如名称档的 Windows 序）必须保留默认实现，否则局部重排会打乱全局顺序。
     *
     * **[size] 可以大于常规页长**：默认实现下 [size] 只决定切多宽——一次要 1578 条是**一次**
     * 全量重列，而不是 8 次；有服务端分页的来源按 [maxPageSize] 收口（调用方也按它夹）。
     *
     * 下一页是否有内容由 [BrowseEntryPage.hasNext] 给出（不看本页是否刚好满一页——
     * 服务端末页恰好满页时那会多要一次空页）。
     */
    suspend fun listEntriesPage(
        containerId: String?,
        sort: SortMode,
        page: Int,
        size: Int,
    ): BrowseEntryPage = sliceEntryPage(listEntries(containerId, sort), page, size)

    /**
     * 一次 [listEntriesPage] 最多能要多少条。
     *
     * 存在的唯一理由：首屏「取够」时调用方想把请求数**收成一次**（见 `BrowsePageLoader.loadFirstPages`）——
     * 默认实现里**每一页都是一次全量重列**（取全量再切片），1578 条的层按 200 一页问就是 8 次全量重列。
     * 调用方按「还差多少条」给 `size`、再夹到本上限，因此这里的值必须是**来源真能接受的一次请求条数**。
     *
     * **前置条件**：**本值不得小于调用方的一页长度**（`BrowsePageLoader` 的
     * `BROWSE_PAGE_SIZE`，现为 200）。调用方的夹法是「向下取整到页长的整数倍、且**不低于一页**」——
     * `maxPageSize < 一页长度` 时那个兜底会把 `size` 顶到一页长度（比本值大），与本属性「最多能要多少条」
     * 的语义相反（分页坐标本身不会错，`nextPage` 仍按 `page * pageSize` 推算，但请求会被服务器拒）。
     * 现网四个来源都不命中这条（Komga 500 > 200，其余 [Int.MAX_VALUE]），因此只立边界、不改算式。
     *
     * 默认 [Int.MAX_VALUE]：本地来源（SAF / SMB / WebDAV 的文件层）一次给多少都行；
     * 有服务端分页上限的来源覆盖它（`KomgaSource` 给服务器页上限）。
     */
    val maxPageSize: Int get() = Int.MAX_VALUE

    /**
     * 打开一本书。
     *
     * **空书口径（四来源一致）**：书存在但**一页都没有**（压缩包内没有图片、Komga 返回空页列表）时返回
     * `pageCount == 0` 的句柄，界面据此显示中文空态（`ReaderScreen` 的「此书没有可显示的页面」），而不是一直转圈；
     * 抛 [IllegalArgumentException] 只留给**不是一本书**的输入（id 形状不对、越界引用、目录本层没有图片
     * ——只含子目录 / 只含压缩包 / 空目录），判据见 [isNotABook]。
     */
    suspend fun openBook(bookId: String): BookHandle

    /** 未读过返回 null */
    suspend fun readProgress(bookId: String): ReadingProgress?

    suspend fun writeProgress(bookId: String, pageIndex: Int, totalPages: Int)

    /**
     * 相邻书：同一容器内 isBook 条目按名称自然序的前后邻位（与列表当前排序无关）。
     * 到头（第一本/最后一本）对应侧为 null。
     *
     * 文件源（[DocumentTreeSource]）只从**已有会话快照**里取：本层本次会话没被列过时给出
     * `(null, null)`，不为此去列目录、探测子目录（一次打开就是上百次网络往返）；浏览页枚举过的层
     * （即点开书的主路径）照常给出邻位。Komga 相邻书取的是服务器已排序的那一份，不受此限。
     */
    suspend fun neighbors(bookId: String): Neighbors

    /**
     * 后台补齐 [neighbors] 的判定依据：文件源（[DocumentTreeSource]）的 [neighbors] 只读
     * 已有会话快照，因此「这一层本次会话谁都没列过」时邻位未知（启动页/抽屉入口直接进阅读器就是这条）。
     * 界面在进入阅读器后**在后台**调一次本方法即可补齐（不得放在打开书/进阅读器的等待路径上）。
     *
     * 实现契约：**最多一次**整层枚举；已有判定依据时不再枚举（不额外列目录/探测）；失败（离线/传输故障）
     * **不重试、不轮询**（调用方每次进阅读器只调一次），邻位保持未知即退回 [neighbors] 的空结果。
     * 默认无操作（Komga 的 [neighbors] 每次自带请求，无需预热）。
     */
    suspend fun warmNeighbors(bookId: String) {}

    /**
     * 封面字节：给无系统可解码 uri 的来源（SMB/WebDAV/Komga）用。
     *
     * 文件源（本地/SAF、SMB、WebDAV）的容器封面与压缩包封面也走这里：
     * **枚举期不发这类请求**；调用时机有两处——**可见行**自己取，以及浏览页的**预取窗口**
     * （可见区 ±1 屏、并发 ≤ `CoverPrefetch.MAX_CONCURRENT_LOADS`、出屏不立即淘汰）。
     * 因此实现方必须让**同一 id 的字节可复用**（会话级字节缓存见 [CoverByteCache]），
     * 否则预取这一遍会被丢掉、变成每张封面多一轮往返。默认 null。
     *
     * 实现契约：**取消照常传播**——等待期间被取消时抛 `CancellationException`，不返回 null。
     * 取消不是来源的答复（本条只给得出「取到 / 这次取不到」两种答复），两者混成同一个值就分不开
     * 「拖列表时白花的取数」与「来源真失败」。
     */
    suspend fun coverBytes(entryId: String): ByteArray? = null

    /**
     * 会话级封面字节缓存里**已有**这一条的字节吗：浏览页的预取据此判断「这一条还要不要再发一次」。
     *
     * 为什么把判据放在来源而不是界面侧的记帐本：字节缓存的真相只有来源自己知道——它有上界、越界按插入序
     * **淘汰最旧**（`CoverByteCache`），界面侧若另记一个「已预取」集合，就与淘汰无联动（长列表滚远再滚回时
     * 界面以为已有、缓存里其实已经被淘汰 → 这一层预取彻底失效）。
     *
     * 实现契约：**只读内存、绝不做 IO**（不 resolve、不发请求，它在滚动路径上被逐条调用）；
     * 默认 false（无字节缓存的来源照旧不预取）。
     */
    fun hasCachedCoverBytes(entryId: String): Boolean = false

    /**
     * 下拉更新要失效的东西（**由来源自己定**：每个来源「列表怎么才算新鲜」不同）：
     *
     * - 文件源（本地/SAF/SMB/WebDAV）：**只清封面字节缓存**。列表的新鲜度由「每次进目录真列 1 次 +
     *   按子项 mtime 增量比对」保证（`docs/spec/browsing.md`「列表取数性能」），清掉列表快照反而会毁掉
     *   比对基线（下一次进这一层变成整层全探）；
     * - Komga：除封面外还要清它的会话内列表——它的列表没有 mtime、没有比对基线，只能重新向服务器枚举。
     *
     * 连接被编辑/删除走的是另一条路（实例释放 + 落盘清空），不经本方法。
     * containerId=null 表示来源根容器。默认无操作（无缓存的来源不需要）。
     */
    fun invalidateForRefresh(containerId: String?) {}

    /**
     * 上一次 [listEntries]（同一容器 + 同一排序）因来源分页取数上限被截断时的中文提示。
     *
     * 非 null = 「只显示了前 N 条」：界面据此在列表上方给一条可见提示，上限截断因此不静默。
     * 同一层这一档排序没被截断（或来源不分页）返回 null；换层/换排序/下拉更新后重新枚举即由实现方重算。
     * 默认 null——**只有 Komga 有服务端分页上限**，文件源列目录没有这一层。
     *
     * 实现契约：**只读内存、绝不做 IO**（在枚举落地后被调一次）。
     */
    fun listTruncationNotice(containerId: String?, sort: SortMode): String? = null

    /**
     * 同步读该容器**当前**的列表快照：不解析来源、不列目录、不探测、**不做任何 IO**
     * （实现方在组合期被调用：发布时间排序只查已算过的键，缺失用快照里的 mtime 兜底）。
     *
     * 消费者是**不需要新鲜度**的两处：路径菜单的顶层条目（[cachedTopLevelEntries] 的默认实现）
     * 与诊断导出（`cache.list.entries`）。界面首屏**不用**它——进目录要的是「列完目录之后的新列表」
     * （`docs/spec/browsing.md`「进目录的单段式取数」），先把旧列表上屏再原地替换就是把过期的内容给人看。
     *
     * 命中返回按 [sort] 排好的条目；**没有快照返回 null**（还没枚举过这一层 / 被腾掉 / 无快照的来源）。
     * 默认 null（无列表快照的来源不需要）。
     */
    fun cachedEntries(containerId: String?, sort: SortMode): List<BrowseEntry>? = null

    /**
     * **目录树顶层**那一层的条目（spec 故事 55 的路径菜单要列的一级目录）：与 [listEntries] 的
     * 「根容器」**不同义**——后者对 Komga 由连接的起始浏览路径决定（可能落在某系列或「阅读过」），
     * 本方法恒给**来源自己的顶层**。
     *
     * - 默认实现 = 根容器（`containerId = null`）那一层，按 [sort] 排；菜单固定传 [SortMode.NAME]
     *   （菜单顺序与全局排序设置无关）
     * - Komga 覆盖为固定的四入口（收藏 / 系列 / 书籍 / 阅读过），**不参与排序、不走网络**，
     *   并把「起始路径正好落在某一类」的那一项标 [TopLevelEntry.isStartLayer]，
     *   把 [TopLevelListing.containerId] 换成分类根（`.../cat`）——起始路径不默认时根容器不是四入口那一屏
     * - 菜单只取其中的**目录**（`isBook = false`），实现不必替调用方过滤
     *
     * 取数口径：先看 [cachedTopLevelEntries]（同步、零网络），没有才调本方法；
     * 实现方应让之后的调用不再走网络（默认实现靠 [listEntries] 自身的会话内缓存）。
     */
    suspend fun topLevelEntries(sort: SortMode): TopLevelListing =
        TopLevelListing(containerId = null, entries = listEntries(null, sort).map { TopLevelEntry(it) })

    /**
     * [topLevelEntries] 的**会话内**缓存（同步、零网络、可在组合期调用）：没取过返回 null。
     *
     * 默认读根容器的快照（[cachedEntries]；文件源的列表快照与排序方式无关，因此已看过的根层恒命中）；
     * Komga 覆盖为「四入口恒可给」——那是枚举构造的固定项，没有取数一说。
     */
    fun cachedTopLevelEntries(): TopLevelListing? =
        cachedEntries(null, SortMode.NAME)?.let { list ->
            TopLevelListing(containerId = null, entries = list.map { TopLevelEntry(it) })
        }

    /** 释放来源持有的会话资源（SMB/WebDAV 连接、HTTP 连接池）；默认无操作 */
    fun close() {}
}

/**
 * 「不是一本书」的判据（**只有这一处**）：[Source.openBook] 用 [IllegalArgumentException] 表达这个失败。
 *
 * 两个消费者共用它，不再各自写类型判断：界面侧据此脱敏成中文提示（`readerOpenErrorMessage`），
 * 启动还原侧据此回落到浏览层（`resolveStartupRead`）。将来若要区分「id 形状不对」与「不是书」
 * （[Source.openBook] 目前把两者并列），只改这里。
 *
 * 注意：**其余任何失败都不算「不是书」**——断链/超时（`TransportFailure`、`SourceReadTimeoutException`）是暂时性故障，
 * 各处的处理只能是重试/冒泡，不能当成「这本不存在了」。
 */
internal fun isNotABook(t: Throwable): Boolean = t is IllegalArgumentException

/** 相邻书引用 */
data class Neighbors(val prev: String?, val next: String?)

/** 阅读进度存储抽象：UI 侧由 Room 实现，测试由内存实现 */
interface ProgressStore {
    suspend fun read(bookId: String): ReadingProgress?
    suspend fun write(bookId: String, pageIndex: Int, totalPages: Int)
}

/**
 * 「始终从第一页打开」语义（spec）：开启后打开书定位第 1 页。
 *
 * 本函数只管**落点**：打开书不改写落盘进度（进度写点在阅读中与离开阅读页）。
 */
fun openStartIndex(progress: ReadingProgress?, alwaysFirstPage: Boolean, pageCount: Int): Int {
    return if (alwaysFirstPage) {
        0
    } else {
        progress?.pageIndex?.coerceIn(0, (pageCount - 1).coerceAtLeast(0)) ?: 0
    }
}

/** 打开一本书的结果：句柄 + 落点 */
data class BookOpening(val handle: BookHandle, val startIndex: Int)

/**
 * 打开一本书并定好落点，**不写进度**。落点只由**这本书自己**的进度与当前开关值决定——
 * 换书（菜单上/下一本、跨书确认条）时上一本读到第几页一律不参与，因此 A→B→A 各自回到自己的页位；
 * 开关开启 = 第 1 页。
 *
 * 前置路径（`ui/preloadReaderOpening`）与阅读页兜底路径（`ui.openAndLandReaderEntry`）都用它：
 * 先开书、再等首批解码的这段时间里用户可能改点另一本 / 返回 / 切走（= 取消，书没被打开），
 * 打开那一刻若写进度，「点了又取消」就会把这本书记成读过（浏览页上因此平白出现进度条）。
 */
suspend fun openBookAtLanding(source: Source, bookId: String, alwaysFirstPage: Boolean): BookOpening {
    val handle = source.openBook(bookId)
    return BookOpening(handle, openStartIndex(source.readProgress(bookId), alwaysFirstPage, handle.pageCount))
}
