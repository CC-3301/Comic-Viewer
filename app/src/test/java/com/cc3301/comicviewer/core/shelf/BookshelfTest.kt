package com.cc3301.comicviewer.core.shelf

import com.cc3301.comicviewer.core.data.BookshelfEntryEntity
import com.cc3301.comicviewer.core.source.BrowseEntry
import com.cc3301.comicviewer.core.source.SourceType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 书柜分柜与来源覆盖面（票 17 验收标准 2，spec 故事 44） */
class BookshelfTest {

    private fun entry(connectionId: Long, bookId: String, name: String = bookId, addedAtMs: Long = 0L) =
        BookshelfEntryEntity(connectionId, bookId, name, coverUri = null, addedAtMs = addedAtMs)

    // ---------- 分柜规则 ----------

    @Test
    fun `多来源不混排`() {
        val cabinets = groupIntoCabinets(
            connections = listOf(CabinetRef(1, "本机漫画"), CabinetRef(2, "NAS")),
            entries = listOf(entry(1, "a"), entry(2, "x"), entry(1, "b")),
        )

        assertEquals(listOf("本机漫画", "NAS"), cabinets.map { it.displayName })
        assertEquals(listOf("a", "b"), cabinets[0].entries.map { it.bookId })
        assertEquals(listOf("x"), cabinets[1].entries.map { it.bookId })
    }

    @Test
    fun `柜序沿用连接序，柜内序沿用入参序`() {
        val cabinets = groupIntoCabinets(
            connections = listOf(CabinetRef(7, "乙"), CabinetRef(3, "甲")),
            entries = listOf(entry(3, "第二", addedAtMs = 2), entry(7, "只此一本"), entry(3, "第一", addedAtMs = 1)),
        )

        assertEquals(listOf(7L, 3L), cabinets.map { it.connectionId })
        assertEquals(listOf("第二", "第一"), cabinets[1].entries.map { it.name })
    }

    @Test
    fun `空柜与没有条目的连接都不显示`() {
        val cabinets = groupIntoCabinets(
            connections = listOf(CabinetRef(1, "空柜"), CabinetRef(2, "有书"), CabinetRef(3, "空柜")),
            entries = listOf(entry(2, "b")),
        )

        assertEquals(listOf(2L), cabinets.map { it.connectionId })
    }

    @Test
    fun `连接已删除的孤儿条目跳过`() {
        val cabinets = groupIntoCabinets(
            connections = listOf(CabinetRef(2, "有书")),
            entries = listOf(entry(2, "b"), entry(99, "孤儿")),
        )

        assertEquals(1, cabinets.size)
        assertEquals(listOf("b"), cabinets.flatMap { it.entries }.map { it.bookId })
    }

    @Test
    fun `没有任何条目时没有柜`() {
        assertTrue(groupIntoCabinets(listOf(CabinetRef(1, "本机漫画")), emptyList()).isEmpty())
        assertTrue(groupIntoCabinets(emptyList(), listOf(entry(1, "a"))).isEmpty())
    }

    // ---------- 书柜覆盖的来源（票 17 本地/SMB + 票 19 Komga/OPDS + 票 24 WebDAV） ----------

    @Test
    fun `五种来源都暴露加入书柜`() {
        // SPEC 故事 43 列举的五种来源逐个锁定；漏掉一种在这里就会红
        assertTrue(SourceType.LOCAL.supportsBookshelf())
        assertTrue(SourceType.SMB.supportsBookshelf())
        assertTrue(SourceType.WEBDAV.supportsBookshelf())
        assertTrue(SourceType.KOMGA.supportsBookshelf())
        assertTrue(SourceType.OPDS.supportsBookshelf())
    }

    @Test
    fun `只有书条目能入柜 系列容器与 feed 容器不显示入柜动作`() {
        // 与 BrowserScreen 的门控同式：showShelfAction = supportsBookshelf() && entry.isBook
        fun canAdd(type: SourceType, entry: BrowseEntry) = type.supportsBookshelf() && entry.isBook

        val series = BrowseEntry("s1", "系列", isBook = false, coverUri = null)
        val komgaBook = BrowseEntry("b1", "第一卷", isBook = true, coverUri = null)
        val navNode = BrowseEntry("f1", "漫画", isBook = false, coverUri = null)
        val feedBook = BrowseEntry("b2", "第 1 话", isBook = true, coverUri = null)
        val webdavBook = BrowseEntry("webdav-http://nas:5006/dav/卷一", "卷一", isBook = true, coverUri = null)
        val webdavContainer = BrowseEntry("webdav-http://nas:5006/dav/漫画", "漫画", isBook = false, coverUri = null)

        assertFalse("Komga 系列是容器，不入柜", canAdd(SourceType.KOMGA, series))
        assertTrue(canAdd(SourceType.KOMGA, komgaBook))
        assertFalse("OPDS 导航节点是容器，不入柜", canAdd(SourceType.OPDS, navNode))
        assertTrue(canAdd(SourceType.OPDS, feedBook))
        // WebDAV 与本地/SMB 同为文件源（票 24）：书条目可入柜，只含子目录的容器不给
        assertTrue(canAdd(SourceType.WEBDAV, webdavBook))
        assertFalse("WebDAV 容器是只含子目录的文件夹，不入柜", canAdd(SourceType.WEBDAV, webdavContainer))
    }
}
