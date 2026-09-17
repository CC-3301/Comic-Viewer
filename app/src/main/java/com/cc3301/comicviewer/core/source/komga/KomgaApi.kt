package com.cc3301.comicviewer.core.source.komga

import com.cc3301.comicviewer.core.source.SortMode

/** Komga 系列（票 13）：浏览的第一级 */
data class KomgaSeries(
    val id: String,
    val title: String,
    val booksCount: Int,
)

/** Komga 书（票 13）：浏览的第二级，也是可阅读单元 */
data class KomgaBook(
    val id: String,
    val seriesId: String,
    val title: String,
    val number: String,
    val pageCount: Int,
    /** ISO 日期（Komga 的 metadata.releaseDate），仅用于展示/诊断，排序由服务器负责 */
    val releaseDate: String?,
    /** 服务器上的阅读进度（票 14：列表里就能显示跨端进度，不必先打开一次） */
    val readProgress: KomgaReadProgress? = null,
)

/**
 * Komga 页（票 13）：按页取图只需要编号与 MIME。
 * [number] 直接用服务器返回值（不假设 0 起还是 1 起），避免页码基准猜错。
 */
data class KomgaPage(
    val number: Int,
    val mediaType: String,
)

/** Komga 的阅读进度（票 14）：[page] 从 1 起，与 Komga 网页端一致 */
data class KomgaReadProgress(
    val page: Int,
    val completed: Boolean,
)

/** 分页结果（Komga 用 Spring Data 的 Page 结构：content + last/number） */
data class KomgaPageResult<T>(
    val items: List<T>,
    val hasNext: Boolean,
)

/**
 * Komga REST 窄接口（票 13）：把 HTTP/JSON 细节压在实现里，
 * 让 [KomgaSource] 只处理「系列 → 书 → 页」的语义，便于用假实现单测。
 *
 * 实现：[HttpKomgaApi]（OkHttp）；测试：[FakeKomgaApi] 与 HttpKomgaApiTest（MockWebServer + 固定 JSON）。
 */
interface KomgaApi : AutoCloseable {
    /** 系列列表（服务器端分页 + 排序） */
    fun listSeries(page: Int, size: Int, sort: String): KomgaPageResult<KomgaSeries>

    /** 某系列下的书（服务器端分页 + 排序）；[sort] 形如 `metadata.releaseDate,desc` */
    fun listBooks(seriesId: String, page: Int, size: Int, sort: String): KomgaPageResult<KomgaBook>

    /** 系列封面；无封面返回 null */
    fun seriesThumbnail(seriesId: String): ByteArray?

    /** 书封面；无封面返回 null */
    fun bookThumbnail(bookId: String): ByteArray?

    /** 书的页列表（编号 1 起） */
    fun bookPages(bookId: String): List<KomgaPage>

    /** 取某一页的图片字节（read 错误必须抛，不能吞成空数组） */
    fun pageBytes(bookId: String, pageNumber: Int): ByteArray

    /**
     * 服务器上的阅读进度；从未读过返回 null（票 14 双向同步的「拉取」方向）。
     * 实现走书详情接口（Komga 对 read-progress 只有 PATCH/DELETE，没有 GET）。
     */
    fun readProgress(bookId: String): KomgaReadProgress?

    /** 回传阅读进度（票 14 的「回传」方向）；返回服务器确认后的值 */
    fun writeProgress(bookId: String, page: Int, completed: Boolean): KomgaReadProgress?
}

/**
 * Komga 的排序字段映射（票 13）：SPEC 故事 14 要求三种排序在五种来源都可用，
 * 其中「发布时间」在 Komga 必须走服务器端 `sort=metadata.releaseDate`。
 */
object KomgaSort {
    /** 系列：名称用 titleSort；发布时间系列没有，回退最后修改时间（与文件源缺少 ComicInfo 时回退 mtime 一致） */
    fun forSeries(mode: SortMode): String = when (mode) {
        SortMode.NAME -> "metadata.titleSort,asc"
        SortMode.MODIFIED_TIME -> "lastModifiedDate,desc"
        SortMode.RELEASE_TIME -> "lastModifiedDate,desc"
    }

    /** 书：发布时间走服务器端 metadata.releaseDate（SPEC 明确要求） */
    fun forBooks(mode: SortMode): String = when (mode) {
        SortMode.NAME -> "metadata.titleSort,asc"
        SortMode.MODIFIED_TIME -> "lastModifiedDate,desc"
        SortMode.RELEASE_TIME -> "metadata.releaseDate,desc"
    }

    /** 相邻书判定固定用名称序（SPEC 故事 28：与当前列表排序无关） */
    const val FOR_NEIGHBORS: String = "metadata.titleSort,asc"
}

/**
 * 失败归类装饰器（票 13）：把底层 HTTP/IO 异常统一转成 [KomgaException]，
 * 使 UI 的错误提示能区分 地址不通 / 认证失败 / 超时 / 证书问题。
 */
class ClassifyingKomgaApi(
    private val delegate: KomgaApi,
    private val config: KomgaConnectionConfig,
) : KomgaApi {

    override fun listSeries(page: Int, size: Int, sort: String): KomgaPageResult<KomgaSeries> =
        classify("系列列表") { delegate.listSeries(page, size, sort) }

    override fun listBooks(seriesId: String, page: Int, size: Int, sort: String): KomgaPageResult<KomgaBook> =
        classify("系列 " + seriesId + " 的书列表") { delegate.listBooks(seriesId, page, size, sort) }

    override fun seriesThumbnail(seriesId: String): ByteArray? =
        classify("系列封面 " + seriesId) { delegate.seriesThumbnail(seriesId) }

    override fun bookThumbnail(bookId: String): ByteArray? =
        classify("书封面 " + bookId) { delegate.bookThumbnail(bookId) }

    override fun bookPages(bookId: String): List<KomgaPage> =
        classify("书 " + bookId + " 的页列表") { delegate.bookPages(bookId) }

    override fun pageBytes(bookId: String, pageNumber: Int): ByteArray =
        classify("第 " + pageNumber + " 页") { delegate.pageBytes(bookId, pageNumber) }

    override fun readProgress(bookId: String): KomgaReadProgress? =
        classify("书 " + bookId + " 的服务器进度") { delegate.readProgress(bookId) }

    override fun writeProgress(bookId: String, page: Int, completed: Boolean): KomgaReadProgress? =
        classify("回传书 " + bookId + " 的进度") { delegate.writeProgress(bookId, page, completed) }

    override fun close() {
        runCatching { delegate.close() }
    }

    private fun <T> classify(what: String, block: () -> T): T = try {
        block()
    } catch (t: Throwable) {
        throw asKomgaException(t, config.displayName + " 的 " + what)
    }
}
