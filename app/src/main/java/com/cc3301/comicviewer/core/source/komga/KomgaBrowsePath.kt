package com.cc3301.comicviewer.core.source.komga

/**
 * Komga 根层的四个入口（票 #78）：收藏 / 系列 / 书籍 / 阅读过。
 *
 * [kind] 既是分类容器 id 的命名空间段（`.../cat/<kind>`），也是起始路径的段名（`/collections` …）——
 * 两者都是**稳定 token**（纯 ASCII、不随界面文案变，票 #78 修复轮：拿显示文案当落库段名会让改文案
 * 静默失效）。[label] 只用于列表里显示的名字（`收藏` …），也是 r1 落过库的旧段名的**唯一**来源
 * （两者逐字相同，因此不再另存一份 legacyLabel）：解析仍认它（存量连接的起始路径不能因为这次改名就不认了）。
 */
enum class KomgaCategory(val kind: String, val label: String) {
    COLLECTIONS("collections", "收藏"),
    SERIES("series", "系列"),
    BOOKS("books", "书籍"),
    READ("read", "阅读过"),
    ;

    /** 该类别本身的路径（票 #78）：`/collections` `/series` `/books` `/read` */
    val path: String get() = KomgaBrowsePaths.ROOT + kind

    companion object {
        fun ofKind(kind: String?): KomgaCategory? = entries.firstOrNull { it.kind == kind }

        /** 路径段 → 类别（票 #78 修复轮）：稳定 token 优先，r1 的中文段兼容（= 现 [label]，逐字相同） */
        fun ofSegment(segment: String?): KomgaCategory? = entries.firstOrNull {
            it.kind == segment || it.label == segment
        }
    }
}

/**
 * Komga 连接的**起始路径**（票 #78）：决定进连接后落到哪一层，也决定「逐级返回」的起点。
 *
 * 与容器 id 的分工：路径是**用户可配置的字符串**（落 `KomgaConnectionConfig`），容器 id 是**运行期**的
 * 导航参数（由 [KomgaSource.listEntries] 交出去、回传回来）。两者的层级一一对应，但形态不同——
 * [KomgaBrowsePaths] 把路径解析到这个 sealed 层级，[KomgaIds] 把 id 解析成同一批层级。
 */
sealed interface KomgaBrowsePath {
    /** `/`：四个入口（收藏 / 系列 / 书籍 / 阅读过） */
    data object Root : KomgaBrowsePath

    /** `/collections`：收藏列表 */
    data object Collections : KomgaBrowsePath

    /** `/collections/<collectionId>`：某收藏的内容（Komga 原生结构里是系列列表） */
    data class Collection(val collectionId: String) : KomgaBrowsePath

    /** `/series`：系列列表 */
    data object Series : KomgaBrowsePath

    /** `/series/<seriesId>`：某系列的书 */
    data class SeriesBooks(val seriesId: String) : KomgaBrowsePath

    /** `/books`：全部书平铺 */
    data object Books : KomgaBrowsePath

    /** `/read`：有阅读记录的书（在读 + 已读完） */
    data object Read : KomgaBrowsePath
}

/**
 * 起始路径的解析 / 格式化 / 上一级（票 #78，纯函数）。
 *
 * **段名是稳定 token**（`/collections`、`/series`、`/books`、`/read`，见 [KomgaCategory.kind]）：
 * 改界面文案（`收藏` 这类 [KomgaCategory.label]）不会让存量连接的起始路径失效。
 * 解析**同时兼容 r1 落过的中文段**（`/收藏/<id>` 这类）——存量连接是用户自己配过的，
 * 修复轮不能因为改名就不认；归一（[normalize]）后一律回到 token 形态。
 *
 * 其余形态（空串、缺前导斜杠后拼不出的段、不认识的段、多出来的段）一律回落 [KomgaBrowsePath.Root]——
 * 用户手改库、旧版本残留的坏值不能让连接进不去；回落是静默的（界面看到的就是四入口），
 * 不做「路径非法」提示，因为 `/` 本身就是合法默认值。
 */
object KomgaBrowsePaths {

    /** 根路径（票 #78）：四入口，也是新建连接的默认值 */
    const val ROOT: String = "/"

    fun parse(raw: String?): KomgaBrowsePath {
        val segments = raw.orEmpty().trim().trim('/').split('/').filter { it.isNotEmpty() }
        val head = segments.firstOrNull()?.let { KomgaCategory.ofSegment(it) }
        return when (segments.size) {
            0 -> KomgaBrowsePath.Root
            1 -> when (head) {
                KomgaCategory.COLLECTIONS -> KomgaBrowsePath.Collections
                KomgaCategory.SERIES -> KomgaBrowsePath.Series
                KomgaCategory.BOOKS -> KomgaBrowsePath.Books
                KomgaCategory.READ -> KomgaBrowsePath.Read
                null -> KomgaBrowsePath.Root
            }
            2 -> when (head) {
                KomgaCategory.COLLECTIONS -> KomgaBrowsePath.Collection(segments[1])
                KomgaCategory.SERIES -> KomgaBrowsePath.SeriesBooks(segments[1])
                else -> KomgaBrowsePath.Root
            }
            else -> KomgaBrowsePath.Root
        }
    }

    fun format(path: KomgaBrowsePath): String = when (path) {
        KomgaBrowsePath.Root -> ROOT
        KomgaBrowsePath.Collections -> KomgaCategory.COLLECTIONS.path
        is KomgaBrowsePath.Collection -> KomgaCategory.COLLECTIONS.path + "/" + path.collectionId
        KomgaBrowsePath.Series -> KomgaCategory.SERIES.path
        is KomgaBrowsePath.SeriesBooks -> KomgaCategory.SERIES.path + "/" + path.seriesId
        KomgaBrowsePath.Books -> KomgaCategory.BOOKS.path
        KomgaBrowsePath.Read -> KomgaCategory.READ.path
    }

    /** 落库 / 回填前归一：非法值（含空）回落 [ROOT] */
    fun normalize(raw: String?): String = format(parse(raw))

    /** 上一级（票 #78 路径选择器「上箭头」）：根路径的上一级是它自己 */
    fun parent(path: KomgaBrowsePath): KomgaBrowsePath = when (path) {
        is KomgaBrowsePath.Collection -> KomgaBrowsePath.Collections
        is KomgaBrowsePath.SeriesBooks -> KomgaBrowsePath.Series
        else -> KomgaBrowsePath.Root
    }
}
