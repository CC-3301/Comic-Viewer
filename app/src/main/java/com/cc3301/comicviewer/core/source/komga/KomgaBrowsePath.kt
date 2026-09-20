package com.cc3301.comicviewer.core.source.komga

/**
 * Komga 根层的四个入口（票 #78）：收藏 / 系列 / 书籍 / 阅读过。
 *
 * [kind] 是分类容器 id 的命名空间段（`.../cat/<kind>`，纯 ASCII、不随界面文案变）；
 * [label] 是列表里显示的名字，也是[KomgaBrowsePath] 路径里的段（票 #78 的路径写法 `/收藏/<id>`）。
 * 两处名字都由这一份枚举给出，因此 id 命名空间与显示文案不会各写一份而漂移。
 */
enum class KomgaCategory(val kind: String, val label: String) {
    COLLECTIONS("collections", "收藏"),
    SERIES("series", "系列"),
    BOOKS("books", "书籍"),
    READ("read", "阅读过"),
    ;

    /** 该类别本身的路径（票 #78）：`/收藏` `/系列` `/书籍` `/阅读过` */
    val path: String get() = KomgaBrowsePaths.ROOT + label

    companion object {
        fun ofKind(kind: String?): KomgaCategory? = entries.firstOrNull { it.kind == kind }
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

    /** `/收藏`：收藏列表 */
    data object Collections : KomgaBrowsePath

    /** `/收藏/<collectionId>`：某收藏的内容（Komga 原生结构里是系列列表） */
    data class Collection(val collectionId: String) : KomgaBrowsePath

    /** `/系列`：系列列表 */
    data object Series : KomgaBrowsePath

    /** `/系列/<seriesId>`：某系列的书 */
    data class SeriesBooks(val seriesId: String) : KomgaBrowsePath

    /** `/书籍`：全部书平铺 */
    data object Books : KomgaBrowsePath

    /** `/阅读过`：有阅读记录的书（在读 + 已读完） */
    data object Read : KomgaBrowsePath
}

/**
 * 起始路径的解析 / 格式化 / 上一级（票 #78，纯函数）。
 *
 * 解析**只认**上面这七种形态，其余（空串、缺前导斜杠后拼不出的段、不认识的段、多出来的段）
 * 一律回落 [KomgaBrowsePath.Root]——用户手改库、旧版本残留的坏值不能让连接进不去；
 * 回落是静默的（界面看到的就是四入口），不做「路径非法」提示，因为 `/` 本身就是合法默认值。
 */
object KomgaBrowsePaths {

    /** 根路径（票 #78）：四入口，也是新建连接的默认值 */
    const val ROOT: String = "/"

    fun parse(raw: String?): KomgaBrowsePath {
        val segments = raw.orEmpty().trim().trim('/').split('/').filter { it.isNotEmpty() }
        return when (segments.size) {
            0 -> KomgaBrowsePath.Root
            1 -> when (segments[0]) {
                KomgaCategory.COLLECTIONS.label -> KomgaBrowsePath.Collections
                KomgaCategory.SERIES.label -> KomgaBrowsePath.Series
                KomgaCategory.BOOKS.label -> KomgaBrowsePath.Books
                KomgaCategory.READ.label -> KomgaBrowsePath.Read
                else -> KomgaBrowsePath.Root
            }
            2 -> when (segments[0]) {
                KomgaCategory.COLLECTIONS.label -> KomgaBrowsePath.Collection(segments[1])
                KomgaCategory.SERIES.label -> KomgaBrowsePath.SeriesBooks(segments[1])
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
