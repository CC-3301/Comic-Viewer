package com.cc3301.comicviewer.ui

import com.cc3301.comicviewer.core.source.komga.FakeKomgaApi
import com.cc3301.comicviewer.core.source.komga.KOMGA_PAGE_SIZE
import com.cc3301.comicviewer.core.source.komga.KomgaBrowsePath
import com.cc3301.comicviewer.core.source.komga.KomgaBrowsePaths
import com.cc3301.comicviewer.core.source.komga.KomgaCollection
import com.cc3301.comicviewer.core.source.komga.KomgaSeries
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 路径选择器的按需加载（票 #119 步骤 2）：首屏只取服务器一页，不把整层拉完。
 *
 * 判别力：把 [KomgaPathPicker.children] 改回一次 `komgaLoadAll`（全量），
 * 本文件的「只问服务器一次 / `hasMore` 为假」断言即变红——这正是本轮要钉住的行为。
 */
class KomgaPathPickerTest {

    private val seriesPath = KomgaBrowsePaths.format(KomgaBrowsePath.Series)
    private val collectionsPath = KomgaBrowsePaths.format(KomgaBrowsePath.Collections)

    /** 服务器上 1200 条、每页 500 ⇒ 改动前会连问 3 页；改动后首屏只问 1 页 */
    private fun manySeries(count: Int = 1200) =
        (1..count).map { KomgaSeries(id = "s$it", title = "Series $it", booksCount = 1) }

    @Test
    fun `系列层首屏只取一页 不发起全量请求`() = runBlocking<Unit> {
        val api = FakeKomgaApi(series = manySeries(), pageSize = KOMGA_PAGE_SIZE)

        val first = KomgaPathPicker(api).children(seriesPath, 0)

        assertEquals("首屏只问服务器一次（全量实现会连问 20 页）", 1, api.seriesSortRequests.size)
        assertEquals(KOMGA_PAGE_SIZE, first.items.size)
        assertTrue("1200 条一页取不完，后面还有", first.hasMore)
    }

    @Test
    fun `滚到底取下一页时只多发一次请求 且两页不重复`() = runBlocking<Unit> {
        val api = FakeKomgaApi(series = manySeries(), pageSize = KOMGA_PAGE_SIZE)
        val picker = KomgaPathPicker(api)

        val first = picker.children(seriesPath, 0)
        val second = picker.children(seriesPath, 1)

        assertEquals("每页各一次请求", 2, api.seriesSortRequests.size)
        assertEquals(KOMGA_PAGE_SIZE, second.items.size)
        assertTrue("500+500+200：第 2 页后还有一页", second.hasMore)
        val paths = (first.items + second.items).map { it.path }
        assertEquals("两页条目不重复", paths.size, paths.toSet().size)
    }

    @Test
    fun `收藏层也按页取 只问一次`() = runBlocking<Unit> {
        val collections = (1..600).map { KomgaCollection(id = "c$it", name = "Collection $it") }
        val api = FakeKomgaApi(collections = collections, pageSize = KOMGA_PAGE_SIZE)

        val first = KomgaPathPicker(api).children(collectionsPath, 0)

        assertEquals(1, api.collectionSortRequests.size)
        assertEquals(KOMGA_PAGE_SIZE, first.items.size)
        assertTrue(first.hasMore)
    }

    @Test
    fun `根层四个入口一页给完 不发请求`() = runBlocking<Unit> {
        val api = FakeKomgaApi()

        val root = KomgaPathPicker(api).children(KomgaBrowsePaths.ROOT, 0)

        assertEquals(listOf("收藏", "系列", "书籍", "阅读过"), root.items.map { it.label })
        assertFalse(root.hasMore)
        assertEquals("根层是本地常量，没有服务端请求", 0, api.seriesSortRequests.size + api.collectionSortRequests.size)
    }
}
