package com.cc3301.comicviewer.core.source.komga

import com.cc3301.comicviewer.core.source.SortMode

/** Komga 系列（票 13）：浏览的第一级 */
data class KomgaSeries(
    val id: String,
    val title: String,
    val booksCount: Int,
)

/**
 * Komga 收藏（票 #78）：服务端名称的集合（原生结构里收藏组织系列，阅读列表才组织书）。
 * 路径选择器与「收藏」入口都用它。
 */
data class KomgaCollection(
    val id: String,
    val name: String,
)

/**
 * 收藏内容的一项（票 #78 修复轮）：按**服务端返回什么就渲染什么**——Komga 原生结构里收藏组织系列
 * （`GET /api/v1/collections/{id}/series`），但票面要求「若返回书则渲染为书行」，因此两种形状都表达得出来。
 * 判定形状的可见边界（见 `HttpKomgaApi.collectionContent`）：条目带 `media`/`seriesId` 视为书，
 * 其余视为系列（系列 DTO 带 `booksCount`、不带 `media`）。
 */
sealed interface KomgaCollectionItem {
    data class Series(val series: KomgaSeries) : KomgaCollectionItem

    data class Book(val book: KomgaBook) : KomgaCollectionItem
}

/** Komga 书（票 13）：浏览的第二级，也是可阅读单元 */
data class KomgaBook(
    val id: String,
    val seriesId: String,
    val title: String,
    val number: String,
    val pageCount: Int,
    /**
     * ISO 日期（Komga 的 metadata.releaseDate），仅用于展示/诊断，排序由服务器负责。
     * 服务器返回什么就保留什么（不解析成 Instant、不做 UTC/时区归一化）——票 #22 核验：
     * 上游 gotson/komga#818 的时区偏差只影响 webui 显示（0.153.0 修复，PR #875 标题限定 webui），
     * APP 侧排序完全交给服务器，因此不需要容差。
     */
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
 * 书列表查询（票 #78）：三种浏览入口各自的筛选条件收在一处，
 * 不给 [KomgaApi.listBooks] 长出一堆可空开关（那些开关的组合里有一半是非法的）。
 *
 * 请求体的形状与 #77 同一处：筛选条件全在 JSON 体的 `condition`（BookSearch 条件 DSL）里，
 * 查询串只放 `page`/`size`/`sort`。
 */
sealed interface KomgaBookQuery {
    /** 某系列下的书：`condition.seriesId = {operator: is, value: <seriesId>}` */
    data class Series(val seriesId: String) : KomgaBookQuery

    /** 全部书：无筛选条件（体里 `condition` 不出现） */
    data object All : KomgaBookQuery

    /**
     * 阅读过 = 有阅读记录的书（票 #78：`readStatus ∈ {IN_PROGRESS, READ}`）。
     * 用它的补集表达：`condition.readStatus = {operator: isNot, value: UNREAD}`。
     * **未真机验证**（本机无 Komga 实例）：若服务器不接受该算子，真机验收会当场暴露（票面 AC9）。
     */
    data object Read : KomgaBookQuery
}

/**
 * 分页取完所有页时的页大小（Komga 默认上限 2000，取 500 兼顾首屏速度与请求数）。
 * 浏览与路径选择器共用这一对常量与 [komgaLoadAll]（票 #78）。
 */
internal const val KOMGA_PAGE_SIZE: Int = 500

/** 分页上限（20 * 500 = 1 万条），防止服务器分页字段异常时无限循环 */
internal const val KOMGA_MAX_PAGES: Int = 20

/** 服务器端分页：取到没有下一页为止（带页数上限，防服务器忽略分页导致死循环） */
internal fun <T> komgaLoadAll(load: (Int) -> KomgaPageResult<T>): List<T> {
    val out = mutableListOf<T>()
    var page = 0
    while (page < KOMGA_MAX_PAGES) {
        val result = load(page)
        out += result.items
        if (!result.hasNext || result.items.isEmpty()) break
        page++
    }
    return out
}

/**
 * Komga REST 窄接口（票 13、#78）：把 HTTP/JSON 细节压在实现里，
 * 让 [KomgaSource] 只处理「四入口 → 收藏/系列 → 书 → 页」的语义，便于用假实现单测。
 *
 * 实现：[HttpKomgaApi]（OkHttp）；测试：[FakeKomgaApi] 与 HttpKomgaApiTest（MockWebServer + 固定 JSON）。
 */
interface KomgaApi : AutoCloseable {
    /** 系列列表（服务器端分页 + 排序） */
    fun listSeries(page: Int, size: Int, sort: String): KomgaPageResult<KomgaSeries>

    /** 收藏列表（票 #78）：服务器端分页 + 排序 */
    fun listCollections(page: Int, size: Int, sort: String): KomgaPageResult<KomgaCollection>

    /** 收藏内容（票 #78）：Komga 原生结构里是**系列列表**，服务端若返回书则按书渲染 */
    fun collectionContent(
        collectionId: String,
        page: Int,
        size: Int,
        sort: String,
    ): KomgaPageResult<KomgaCollectionItem>

    /**
     * 书列表（服务器端分页 + 排序）；[query] 给出三种入口各自的筛选（票 #78）。
     */
    fun listBooks(query: KomgaBookQuery, page: Int, size: Int, sort: String): KomgaPageResult<KomgaBook>

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
 * Komga 的排序字段映射（票 13）：SPEC 故事 14 要求三种排序在四种来源都可用，
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

    /** 系列列表按名称（票 #78）：路径选择器与收藏内容都用它 */
    const val FOR_SERIES_NAMES: String = "metadata.titleSort,asc"

    /** 相邻书判定固定用名称序（SPEC 故事 28：与当前列表排序无关） */
    const val FOR_NEIGHBORS: String = FOR_SERIES_NAMES

    /** 收藏列表按名称（票 #78：收藏列表与类别列表按名称） */
    const val FOR_COLLECTION_NAMES: String = "name,asc"

    /**
     * 阅读过（票 #78）：**固定按最近阅读倒序**。
     *
     * 该入口是「排序方式与方向是全局一份设置」（`docs/SPEC.md` 故事 14）的**有意例外**：
     * 不跟随排序菜单的类别档（方向仍由界面按全局设置对结果整份翻转）。
     * 维护者口径（2026-09-20 当面确认）：「按 komga 返回的排序走 或者 固定也行」→ 取「固定」；
     * 故事 14 / 15 与 Komga 集成段都已登记这条例外。
     */
    const val FOR_READ_BOOKS: String = "readProgress.lastModified,desc"
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

    override fun listCollections(page: Int, size: Int, sort: String): KomgaPageResult<KomgaCollection> =
        classify("收藏列表") { delegate.listCollections(page, size, sort) }

    override fun collectionContent(
        collectionId: String,
        page: Int,
        size: Int,
        sort: String,
    ): KomgaPageResult<KomgaCollectionItem> =
        classify("收藏 " + collectionId + " 的内容") { delegate.collectionContent(collectionId, page, size, sort) }

    override fun listBooks(
        query: KomgaBookQuery,
        page: Int,
        size: Int,
        sort: String,
    ): KomgaPageResult<KomgaBook> = classify(what = "书列表") { delegate.listBooks(query, page, size, sort) }

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
