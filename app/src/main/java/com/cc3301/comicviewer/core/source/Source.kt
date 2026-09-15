package com.cc3301.comicviewer.core.source

/** 五类内容来源 */
enum class SourceType { LOCAL, SMB, WEBDAV, KOMGA, OPDS }

/** 排序方式（spec：名称 / 修改时间 / 发布时间，全部来源可用） */
enum class SortMode { NAME, MODIFIED_TIME, RELEASE_TIME }

/**
 * 浏览条目：书或容器（文件夹/系列/feed 节点）。
 * id 在来源内不透明唯一；上层只能从 [Source.listEntries] 的返回值中获取后回传。
 */
data class BrowseEntry(
    val id: String,
    val name: String,
    /** true=可直接打开阅读的书；false=需继续浏览的容器 */
    val isBook: Boolean,
    /** 封面：书=第一页；多子文件夹容器=第一个子文件夹首页（逐级下取）；null=暂无 */
    val coverUri: String?,
    /** 书=总页数；容器=null */
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

/** 单页图片数据 */
class PageData(val bytes: ByteArray, val mimeType: String)

/** 打开书之后的句柄：随机访问页面 */
interface BookHandle {
    val pageCount: Int

    /** 越界抛 IndexOutOfBoundsException */
    suspend fun loadPage(index: Int): PageData
}

/**
 * 五来源统一接口（tracer-bullet seam）。
 * 同一套行为测试集（SourceBehaviorContract）将运行于全部五个实现之上。
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
}

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
