package com.cc3301.comicviewer.core.source.komga

import com.cc3301.comicviewer.core.source.remote.endpointParts

/**
 * Komga 节点 id 规则（票 13）：id 同时是书 id、进度键与导航参数，必须
 * 1) 跨服务器/跨路径不碰撞；2) 自带系列信息，让「相邻书」不必再查一次接口。
 *
 * 形式：
 * - 系列：`komga-scheme://主机[:端口]/路径/../series/<seriesId>`
 * - 书　：`.../series/<seriesId>/book/<bookId>`
 * - 分类（票 #78）：`.../cat/<kind>`（kind ∈ collections/series/books/read）
 * - 收藏（票 #78）：`.../collection/<collectionId>`
 *
 * **书 id 的形状（`.../series/<seriesId>/book/<bookId>`）自票 #78 起不变**——它是存量阅读进度的键，
 * 改了就让用户读到一半的位置全丢；新增的分类/收藏容器用各自的命名空间，不与既有两个命名空间碰撞。
 */
object KomgaIds {

    /** 连接级前缀（含 scheme，避免同主机 http/https 两条连接互串进度） */
    fun prefix(baseUrl: String): String = endpointParts(baseUrl).idPrefix("komga")

    fun seriesId(prefix: String, seriesId: String): String = prefix + SERIES + "/" + seriesId

    fun bookId(prefix: String, seriesId: String, bookId: String): String =
        prefix + SERIES + "/" + seriesId + BOOK + "/" + bookId

    /** 该 id 是否属于本连接（不同服务器的 id 互不认账） */
    fun belongsTo(prefix: String, id: String): Boolean = id.startsWith(prefix + "/")

    /** 从书 id 取出所属系列 id；格式不对返回 null */
    fun seriesOfBook(prefix: String, bookId: String): String? = segments(prefix, bookId)
        ?.takeIf { it.size == 4 && it[2] == BOOK_SEGMENT }
        ?.get(1)

    /** 从书 id 取出 Komga 的 bookId；格式不对返回 null */
    fun rawBookId(prefix: String, bookId: String): String? = segments(prefix, bookId)
        ?.takeIf { it.size == 4 && it[2] == BOOK_SEGMENT }
        ?.get(3)

    /** 从系列 id 取出 Komga 的 seriesId；格式不对返回 null */
    fun rawSeriesId(prefix: String, seriesId: String): String? = segments(prefix, seriesId)
        ?.takeIf { it.size == 2 && it[0] == SERIES_SEGMENT }
        ?.get(1)

    /** 分类容器（票 #78）：`.../cat/<kind>`，[kind] 取 [KomgaCategory.kind] */
    fun categoryId(prefix: String, kind: String): String = prefix + CATEGORY + "/" + kind

    /** 从分类容器 id 取出 kind；格式不对返回 null */
    fun rawCategory(prefix: String, containerId: String): String? = segments(prefix, containerId)
        ?.takeIf { it.size == 2 && it[0] == CATEGORY_SEGMENT }
        ?.get(1)

    /** 收藏容器（票 #78）：`.../collection/<collectionId>` */
    fun collectionId(prefix: String, collectionId: String): String = prefix + COLLECTION + "/" + collectionId

    /** 从收藏容器 id 取出 collectionId；格式不对返回 null */
    fun rawCollectionId(prefix: String, containerId: String): String? = segments(prefix, containerId)
        ?.takeIf { it.size == 2 && it[0] == COLLECTION_SEGMENT }
        ?.get(1)

    /** 去掉前缀后的段列表；不属于本连接返回 null */
    private fun segments(prefix: String, id: String): List<String>? {
        if (!belongsTo(prefix, id)) return null
        return id.removePrefix(prefix + "/").split('/')
    }

    private const val SERIES = "/series"
    private const val BOOK = "/book"
    private const val SERIES_SEGMENT = "series"
    private const val BOOK_SEGMENT = "book"
    private const val CATEGORY = "/cat"
    private const val CATEGORY_SEGMENT = "cat"
    private const val COLLECTION = "/collection"
    private const val COLLECTION_SEGMENT = "collection"
}
