package com.cc3301.comicviewer.core.source

/** 四类内容来源 */
enum class SourceType { LOCAL, SMB, WEBDAV, KOMGA }

/** 排序方式（spec：名称 / 修改时间 / 发布时间，全部来源可用） */
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
    /** 封面：书=第一页；多子文件夹容器=第一个子文件夹首页（逐级下取）；null=暂无 */
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

    suspend fun openBook(bookId: String): BookHandle

    /** 未读过返回 null */
    suspend fun readProgress(bookId: String): ReadingProgress?

    suspend fun writeProgress(bookId: String, pageIndex: Int, totalPages: Int)

    /**
     * 相邻书（票 07）：同一容器内 isBook 条目按名称自然序的前后邻位（与列表当前排序无关）。
     * 到头（第一本/最后一本）对应侧为 null。
     */
    suspend fun neighbors(bookId: String): Neighbors

    /**
     * 封面字节（票 11）：给无系统可解码 uri 的来源（SMB/WebDAV/Komga）用。
     * 默认 null——本地/SAF 走 [BrowseEntry.coverUri]，无需此口。
     */
    suspend fun coverBytes(entryId: String): ByteArray? = null

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
