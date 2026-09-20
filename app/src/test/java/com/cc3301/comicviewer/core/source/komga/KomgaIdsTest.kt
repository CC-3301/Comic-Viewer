package com.cc3301.comicviewer.core.source.komga

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Komga 节点 id 规则（票 13）：前缀含 scheme、系列/书 id 自解析 */
class KomgaIdsTest {

    private val prefix = KomgaIds.prefix("http://komga:25600")

    @Test
    fun `前缀带 scheme 主机 端口与路径 同主机不同 scheme 不碰撞`() {
        assertEquals("komga-http://komga:25600", prefix)
        assertEquals("komga-https://komga/dav/komga", KomgaIds.prefix("https://komga/dav/komga/"))
        assertTrue(KomgaIds.prefix("https://komga:443") != KomgaIds.prefix("http://komga:443"))
    }

    @Test
    fun `系列与书 id 往返`() {
        val series = KomgaIds.seriesId(prefix, "s1")
        val book = KomgaIds.bookId(prefix, "s1", "b1")

        assertEquals("komga-http://komga:25600/series/s1", series)
        assertEquals("komga-http://komga:25600/series/s1/book/b1", book)
        assertEquals("s1", KomgaIds.rawSeriesId(prefix, series))
        assertEquals("s1", KomgaIds.seriesOfBook(prefix, book))
        assertEquals("b1", KomgaIds.rawBookId(prefix, book))
    }

    @Test
    fun `分类与收藏 id 往返 且用独立命名空间`() {
        // 票 #78：新增的分类/收藏容器不得与系列/书两个命名空间碰撞（尤其是书 id 是存量进度键）
        val category = KomgaIds.categoryId(prefix, KomgaCategory.SERIES.kind)
        val collection = KomgaIds.collectionId(prefix, "c1")

        assertEquals("komga-http://komga:25600/cat/series", category)
        assertEquals("komga-http://komga:25600/collection/c1", collection)
        assertEquals("series", KomgaIds.rawCategory(prefix, category))
        assertEquals("c1", KomgaIds.rawCollectionId(prefix, collection))
        assertEquals(KomgaCategory.SERIES, KomgaCategory.ofKind(KomgaIds.rawCategory(prefix, category)))
    }

    @Test
    fun `分类与收藏 id 不被既有系列书解析误认`() {
        val category = KomgaIds.categoryId(prefix, KomgaCategory.BOOKS.kind)
        val collection = KomgaIds.collectionId(prefix, "c1")

        // 系列/书解析不能把新命名空间当成自己的
        assertNull(KomgaIds.rawSeriesId(prefix, category))
        assertNull(KomgaIds.rawSeriesId(prefix, collection))
        assertNull(KomgaIds.rawBookId(prefix, category))
        assertNull(KomgaIds.rawBookId(prefix, collection))
        assertNull(KomgaIds.seriesOfBook(prefix, collection))
        // 反过来也一样
        assertNull(KomgaIds.rawCategory(prefix, KomgaIds.seriesId(prefix, "s1")))
        assertNull(KomgaIds.rawCollectionId(prefix, KomgaIds.seriesId(prefix, "s1")))
        assertNull(KomgaIds.rawCategory(prefix, KomgaIds.bookId(prefix, "s1", "b1")))
        assertEquals("s1", KomgaIds.rawSeriesId(prefix, KomgaIds.seriesId(prefix, "s1")))
        assertEquals("b1", KomgaIds.rawBookId(prefix, KomgaIds.bookId(prefix, "s1", "b1")))
    }

    @Test
    fun `格式不对的新 id 返回 null`() {
        assertNull(KomgaIds.rawCategory(prefix, prefix + "/cat"))
        assertNull(KomgaIds.rawCategory(prefix, prefix + "/cat/books/extra"))
        assertNull(KomgaIds.rawCollectionId(prefix, prefix + "/collection"))
        assertNull(KomgaIds.rawCollectionId(prefix, prefix + "/cat/books"))
        assertNull(KomgaIds.rawCategory(prefix, "komga-http://other/cat/books"))
        assertNull(KomgaIds.rawCollectionId(prefix, "komga-http://other/collection/c1"))
    }

    @Test
    fun `不属于本连接的 id 一律拒绝`() {
        assertFalse(KomgaIds.belongsTo(prefix, "komga-http://other/series/s1"))
        assertNull(KomgaIds.rawSeriesId(prefix, "komga-http://other/series/s1"))
        assertNull(KomgaIds.rawBookId(prefix, "komga-http://other/series/s1/book/b1"))
        assertNull(KomgaIds.seriesOfBook(prefix, "komga-http://other/series/s1/book/b1"))
    }

    @Test
    fun `格式不对的 id 返回 null 不抛异常`() {
        assertNull(KomgaIds.rawSeriesId(prefix, prefix + "/series"))
        assertNull(KomgaIds.rawSeriesId(prefix, prefix + "/series/s1/book/b1"))
        assertNull(KomgaIds.rawBookId(prefix, prefix + "/series/s1"))
        assertNull(KomgaIds.rawBookId(prefix, prefix + "/series/s1/book"))
        assertNull(KomgaIds.rawBookId(prefix, prefix + "/books/b1"))
        assertNull(KomgaIds.seriesOfBook(prefix, prefix + "/series/s1"))
    }
}
