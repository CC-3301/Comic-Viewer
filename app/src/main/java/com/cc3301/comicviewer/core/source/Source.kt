package com.cc3301.comicviewer.core.source

/** 四类内容来源 */
enum class SourceType { LOCAL, SMB, WEBDAV, KOMGA }

/**
 * 排序方式（spec：名称 / 修改时间 / 发布时间，全部来源可用）。
 * 方向不在这个接口里：正/反向是展示层概念（`core/sort/SortSetting.kt` 的全局排序设置），
 * 来源只按方式返回「正向」序——名称 A→Z、修改时间/发布时间 新→旧。
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
     * 其余容器 = 第一个子文件夹首页（逐级下取）；null=暂无。
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
     * **空书口径（票 #97，四来源一致）**：书存在但**一页都没有**（压缩包内没有图片、Komga 返回 0 页）时返回
     * `pageCount == 0` 的句柄，界面据此显示中文空态（「找不到图片」），而不是一直转圈；
     * 抛 [IllegalArgumentException] 只留给**不是一本书**的输入（id 形状不对、越界引用、目录本层没有图片）。
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
     * 文件源（本地/SAF、SMB、WebDAV）的容器封面与压缩包封面也走这里（票 #30），
     * 且只在可见行被调（不预取）：枚举期不发这类请求。默认 null。
     */
    suspend fun coverBytes(entryId: String): ByteArray? = null

    /**
     * 会话级列表缓存的显式失效/刷新入口（票 #30）：文件源列目录是逐层网络往返/provider IPC，
     * 同一目录会话内二次进入命中缓存；文件改动由容器 mtime 自动失效，其余情况（手动刷新）走这里。
     * containerId=null 表示来源根容器。默认无操作（无缓存的来源不需要）。
     */
    fun invalidateListCache(containerId: String?) {}

    /** 释放来源持有的会话资源（票 11：SMB/WebDAV 连接、HTTP 连接池）；默认无操作 */
    fun close() {}
}

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
 * 打开一本书并定好落点（票 #68）：落点只由**这本书自己**的进度与当前开关值决定——
 * 换书（菜单上/下一本、跨书确认条）时上一本读到第几页一律不参与，因此 A→B→A 各自回到自己的页位。
 * 开关开启 = 第 1 页，且打开瞬间即把该书进度覆盖成第 1 页（spec 故事 40：进入马上退出也只算读了 1 页）。
 */
suspend fun openForReading(source: Source, bookId: String, alwaysFirstPage: Boolean): BookOpening {
    val handle = source.openBook(bookId)
    val startIndex = openStartIndex(source.readProgress(bookId), alwaysFirstPage, handle.pageCount)
    if (alwaysFirstPage && handle.pageCount > 0) {
        // 不变式：alwaysFirstPage ⇒ startIndex == 0（见 openStartIndex 的返回契约），即 KDoc 说的「覆盖为第 1 页」；
        // 写入值刻意与落点共用同一个 startIndex，落点公式一变不会出现「定位到第 1 页但落盘写成另1页」的漂移。
        // 0 页的书（空/坏压缩包，票 #97）不写：0/0 会被进度条读成「读完」（满格红），而它根本没有页可读
        source.writeProgress(bookId, startIndex, handle.pageCount)
    }
    return BookOpening(handle, startIndex)
}
