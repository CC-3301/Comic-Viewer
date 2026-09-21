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
     * 封面：书=第一页；本层有图的容器（票 #97 起「图片+子文件夹/压缩包」这类目录是容器）= 本层首图；
     * 其余容器 = 逐级下取（票 #102：每一层都是本层图 → 本层首个压缩包的首帧 → 再下探子目录）；null=暂无。
     *
     * 文件源（本地/SAF、SMB、WebDAV 共用 [DocumentTreeSource]）的枚举期不为封面做额外往返（票 #30）：
     * 只给「本层首图」与「图片本身」这类零开销的 uri（含本层有图的容器），其余容器封面与压缩包封面一律为 null，
     * 由界面在可见行走 [Source.coverBytes] 按需取。
     */
    val coverUri: String?,
    /**
     * 书=总页数；容器=null。
     *
     * 文件源（本地/SAF、SMB、WebDAV 共用 [DocumentTreeSource]）的枚举期不统计页数（票 #36）：
     * 不为页数读压缩包中央目录、也不为页数列子目录，因此列表里一律为 null；
     * 页数只在打开书后由 [BookHandle.pageCount] 给出。
     * Komga 的页数来自服务器返回的 payload（零额外成本）；列表条目照常带上该字段（票面契约保留，
     * 当前无 UI/业务消费者），但界面不显示。
     */
    val pageCount: Int? = null,
)

/** 阅读进度（页码 0-based） */
data class ReadingProgress(
    val pageIndex: Int,
    val totalPages: Int,
    val updatedAtMs: Long,
)

/** 进度展示纯函数（票 05）：部分填充绿=进行中，满格红=读完（读到最后一页） */
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
 * 四来源统一接口（tracer-bullet seam）。
 * 同一套行为测试集（SourceBehaviorContract）将运行于全部四个实现之上。
 */
interface Source {
    val type: SourceType

    /**
     * 列出容器下的条目（已按 [sort] 排序）。
     * containerId=null 表示来源根容器。
     */
    suspend fun listEntries(containerId: String?, sort: SortMode): List<BrowseEntry>

    /**
     * 打开一本书。
     *
     * **空书口径（票 #97，四来源一致）**：书存在但**一页都没有**（压缩包内没有图片、Komga 返回空页列表）时返回
     * `pageCount == 0` 的句柄，界面据此显示中文空态（`ReaderScreen` 的「此书没有可显示的页面」），而不是一直转圈；
     * 抛 [IllegalArgumentException] 只留给**不是一本书**的输入（id 形状不对、越界引用、目录本层没有图片
     * ——只含子目录 / 只含压缩包 / 空目录），判据见 [isNotABook]。
     */
    suspend fun openBook(bookId: String): BookHandle

    /** 未读过返回 null */
    suspend fun readProgress(bookId: String): ReadingProgress?

    suspend fun writeProgress(bookId: String, pageIndex: Int, totalPages: Int)

    /**
     * 相邻书（票 07）：同一容器内 isBook 条目按名称自然序的前后邻位（与列表当前排序无关）。
     * 到头（第一本/最后一本）对应侧为 null。
     *
     * 文件源（[DocumentTreeSource]，票 #93）只从**已有会话快照**里取：本层本次会话没被列过时给出
     * `(null, null)`，不为此去列目录、探测子目录（一次打开就是上百次网络往返）；浏览页枚举过的层
     * （即点开书的主路径）照常给出邻位。Komga 相邻书取的是服务器已排序的那一份，不受此限。
     */
    suspend fun neighbors(bookId: String): Neighbors

    /**
     * 后台补齐 [neighbors] 的判定依据（票 #93 修复轮）：文件源（[DocumentTreeSource]）的 [neighbors] 只读
     * 已有会话快照，因此「这一层本次会话谁都没列过」时邻位未知（启动页/抽屉入口直接进阅读器就是这条）。
     * 界面在进入阅读器后**在后台**调一次本方法即可补齐（不得放在打开书/进阅读器的等待路径上）。
     *
     * 实现契约：**最多一次**整层枚举；已有判定依据时不再枚举（不额外列目录/探测）；失败（离线/传输故障）
     * **不重试、不轮询**（调用方每次进阅读器只调一次），邻位保持未知即退回 [neighbors] 的空结果。
     * 默认无操作（Komga 的 [neighbors] 每次自带请求，无需预热）。
     */
    suspend fun warmNeighbors(bookId: String) {}

    /**
     * 封面字节（票 11）：给无系统可解码 uri 的来源（SMB/WebDAV/Komga）用。
     *
     * 文件源（本地/SAF、SMB、WebDAV）的容器封面与压缩包封面也走这里（票 #30）：
     * **枚举期不发这类请求**；调用时机有两处——**可见行**自己取，以及浏览页的**预取窗口**
     * （票 #108 E2-B：可见区 ±1 屏、并发 ≤ `CoverPrefetch.MAX_CONCURRENT_LOADS`、出屏不立即淘汰）。
     * 因此实现方必须让**同一 id 的字节可复用**（会话级字节缓存见 [CoverByteCache]），
     * 否则预取这一遍会被丢掉、变成每张封面多一轮往返（票 #108 r2 评审 P1-2）。默认 null。
     */
    suspend fun coverBytes(entryId: String): ByteArray? = null

    /**
     * 会话级封面字节缓存里**已有**这一条的字节吗（票 #108 r4）：浏览页的预取据此判断「这一条还要不要再发一次」。
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
     * 会话级列表快照的显式失效/刷新入口（票 #30）：文件源列目录是逐层网络往返/provider IPC，
     * 同一目录会话内二次进入命中缓存；文件改动由容器 mtime 自动失效，其余情况（手动刷新）走这里。
     * containerId=null 表示来源根容器。默认无操作（无缓存的来源不需要）。
     * **票 #74 起必须同时清落盘快照**（下拉更新与连接编辑都要真失效）。
     */
    fun invalidateListCache(containerId: String?) {}

    /**
     * 同步读该容器**已有**的列表快照（票 #74 承办 #73 AC3）：不解析来源、不比对 mtime、不列目录、
     * **不做任何 IO**（实现方在组合期被调用：发布时间排序只查已算过的键，缺失用快照里的 mtime 兜底）——
     * 界面从阅读器返回浏览页时用它拿首帧，列表因此**立即可见**、不闪「加载中…」。
     *
     * 命中返回按 [sort] 排好的条目；**没有快照返回 null**（冷启动首帧 / 已被腾掉 / 无快照的来源），
     * 调用方照常走 [listEntries] 的异步路径。四个来源口径一致：文件源（本地/SAF、SMB、WebDAV）
     * 读会话内存快照（**冷启动（进程重启）首帧**内存为空，因此首帧仍是异步的：要等会话来源解析完——与 #74 同一句——
     * 才跑第一段（[snapshotEntries]），随后先落快照帧；「加载中…」期间不再发生列目录/探测），
     * Komga 读会话内列表快照。默认 null（无列表快照的来源不需要）。
     */
    fun cachedEntries(containerId: String?, sort: SortMode): List<BrowseEntry>? = null

    /**
     * 取该容器**已有快照**的条目（票 #75 的两段式读取第一段）：会话内存快照优先，内存没有就读**落盘快照**
     * （票 #74）；两者都没有返回 null。**不发任何列目录与探测**（0 请求，只读本地文件）。
     *
     * 与 [cachedEntries] 的分工：那个是**同步**读、只看会话内存（组合期首帧，给不了一丝 IO）；本方法是**挂起**读，
     * 多补上「内存没有、落盘有」那一半——跨重启/新实例进入时界面据此**先把上次那一层显示出来**，
     * 再等 [listEntries] 的新枚举结果原地替换（静默刷新，不闪空白、不跳滚动）。
     * 界面侧走 `listEntriesTwoPhaseRememberingNames`（先快照后新鲜，两段都回填条目名）。
     *
     * 默认 null（Komga 的列表以服务端为权威，其会话快照已由 [cachedEntries] 承担，没有可先显示的旧内容）。
     */
    suspend fun snapshotEntries(containerId: String?, sort: SortMode): List<BrowseEntry>? = null

    /** 释放来源持有的会话资源（票 11：SMB/WebDAV 连接、HTTP 连接池）；默认无操作 */
    fun close() {}
}

/**
 * 「不是一本书」的判据（票 #97，**只有这一处**）：[Source.openBook] 用 [IllegalArgumentException] 表达这个失败。
 *
 * 两个消费者共用它，不再各自写类型判断：界面侧据此脱敏成中文提示（`readerOpenErrorMessage`），
 * 启动还原侧据此回落到浏览层（`resolveStartupRead`）。将来若要区分「id 形状不对」与「不是书」
 * （[Source.openBook] 目前把两者并列），只改这里。
 *
 * 注意：**其余任何失败都不算「不是书」**——断链/超时（`TransportFailure`、`SourceReadTimeoutException`）是暂时性故障，
 * 各处的处理只能是重试/冒泡，不能当成「这本不存在了」。
 */
internal fun isNotABook(t: Throwable): Boolean = t is IllegalArgumentException

/** 相邻书引用（票 07） */
data class Neighbors(val prev: String?, val next: String?)

/** 阅读进度存储抽象：UI 侧由 Room 实现，测试由内存实现 */
interface ProgressStore {
    suspend fun read(bookId: String): ReadingProgress?
    suspend fun write(bookId: String, pageIndex: Int, totalPages: Int)
}

/**
 * 「始终从第一页打开」语义（spec）：开启后打开书定位第 1 页，
 * 且打开瞬间进度即覆盖为第 1 页（进入马上退出也只算读了 1 页）。
 */
fun openStartIndex(progress: ReadingProgress?, alwaysFirstPage: Boolean, pageCount: Int): Int {
    return if (alwaysFirstPage) {
        0
    } else {
        progress?.pageIndex?.coerceIn(0, (pageCount - 1).coerceAtLeast(0)) ?: 0
    }
}

/** 打开一本书的结果：句柄 + 落点（票 #68） */
data class BookOpening(val handle: BookHandle, val startIndex: Int)

/**
 * 打开一本书并定好落点，但**不落地进度**（票 #110）：给「先开书、后决定是否真的切进阅读页」的前置路径用。
 *
 * 落点与 [openForReading] 共用同一处计算（[openStartIndex]），因此两条路的落点语义恒等；
 * 差别只在「开启即覆盖进度」这一步什么时候发生——由 [commitOpeningProgress] 在调用方选定的时刻落地。
 */
suspend fun openBookAtLanding(source: Source, bookId: String, alwaysFirstPage: Boolean): BookOpening {
    val handle = source.openBook(bookId)
    return BookOpening(handle, openStartIndex(source.readProgress(bookId), alwaysFirstPage, handle.pageCount))
}

/**
 * 落地「开启即覆盖进度」的写（票 #110）：判据与写入值与 [openForReading] 里那一步同一份。
 *
 * 为什么单独拆出来：[openForReading] 在**打开瞬间**写，但「打开前置」（`ui/preloadReaderOpening`）是
 * 先开书、再等首批解码，等到的这段时间里用户可能改点另一本 / 返回 / 切走（= 取消，书没被打开）。
 * 写在那一步的话，「点了又取消」也会把这本书记成读了第 1 页（书柜上因此平白出现「在读」与进度）。
 * 前置路径于是改用 [openBookAtLanding]，由阅读页**取走前置那一刻**调本函数，把写推迟到真正切进阅读页。
 *
 * 不变式与 [openForReading] 相同：写入值刻意取自落点（[BookOpening.startIndex]），落点公式一变不会漂移；
 * 0 页的书不写（0/0 会被进度条读成「读完」，而它根本没有页可读）。
 */
suspend fun commitOpeningProgress(
    source: Source,
    bookId: String,
    alwaysFirstPage: Boolean,
    opening: BookOpening,
) {
    if (alwaysFirstPage && opening.handle.pageCount > 0) {
        source.writeProgress(bookId, opening.startIndex, opening.handle.pageCount)
    }
}

/**
 * 打开一本书并定好落点（票 #68）：落点只由**这本书自己**的进度与当前开关值决定——
 * 换书（菜单上/下一本、跨书确认条）时上一本读到第几页一律不参与，因此 A→B→A 各自回到自己的页位。
 * 开关开启 = 第 1 页，且打开瞬间即把该书进度覆盖成第 1 页（spec 故事 40：进入马上退出也只算读了 1 页）。
 */
suspend fun openForReading(source: Source, bookId: String, alwaysFirstPage: Boolean): BookOpening {
    // 不变式：alwaysFirstPage ⇒ startIndex == 0（见 openStartIndex 的返回契约），即 KDoc 说的「覆盖为第 1 页」。
    val opening = openBookAtLanding(source, bookId, alwaysFirstPage)
    commitOpeningProgress(source, bookId, alwaysFirstPage, opening)
    return opening
}
