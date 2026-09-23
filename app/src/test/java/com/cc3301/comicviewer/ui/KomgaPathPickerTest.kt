package com.cc3301.comicviewer.ui

import com.cc3301.comicviewer.core.source.komga.FakeKomgaApi
import com.cc3301.comicviewer.core.source.komga.KOMGA_MAX_PAGES
import com.cc3301.comicviewer.core.source.komga.KOMGA_PAGE_SIZE
import com.cc3301.comicviewer.core.source.komga.KomgaBook
import com.cc3301.comicviewer.core.source.komga.KomgaBrowsePath
import com.cc3301.comicviewer.core.source.komga.KomgaBrowsePaths
import com.cc3301.comicviewer.core.source.komga.KomgaCollection
import com.cc3301.comicviewer.core.source.komga.KomgaCollectionItem
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
    fun `取下一页时只多发一次请求 且两页不重复`() = runBlocking<Unit> {
        // 本用例只钉**数据层**的取页：界面上「滚到底触发下一页」那段接线（`Lazy` 尾项可见 + 尾部触发件）
        // 在组合测试里，不在本文件（票 #124 B 组：旧用例名「滚到底取下一页…」承诺了本用例没测的那一半）。
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

    @Test
    fun `收藏内容整页都是被过滤的书时仍能取到下一页`() = runBlocking<Unit> {
        // 收藏内容里书不是可下钻的层（被过滤掉）；整页都是书时界面不该停在「加载中…」（票 #119 修复轮）
        val books = listOf(
            KomgaCollectionItem.Book(book("b1", "Vol 1")),
            KomgaCollectionItem.Book(book("b2", "Vol 2")),
        )
        val series = listOf(KomgaCollectionItem.Series(KomgaSeries(id = "s1", title = "Series A", booksCount = 1)))
        val api = FakeKomgaApi(collectionContents = mapOf("c1" to books + series), pageSize = 2)
        val picker = KomgaPathPicker(api)
        val path = KomgaBrowsePaths.format(KomgaBrowsePath.Collection("c1"))

        val (page, next) = picker.pageWithVisibleItems(path, fromPage = 0)

        assertEquals("跳过整页被过滤的书，取到下一页的系列", listOf("Series A"), page.items.map { it.label })
        assertEquals("下一页号 = 跳过的页数 + 1", 2, next)
        assertEquals("两页各问一次", 2, api.collectionContentRequests.size)
    }

    /**
     * 每一页都空（内容都被过滤掉）且恒有下一页的假选择器（票 #125 P1-2 的形态：`alwaysHasNext` + 整页被过滤）。
     * 请求数超过 [limit] 直接抛错——没有页数守卫的实现不会返回，只会一直取下去；
     * 本用例因此以「请求数超上限」的形式变红，而不是把测试挂死。
     */
    private class AlwaysEmptyPathPicker(private val limit: Int) : PathPicker {
        var requests = 0
            private set

        override val rootPath: String = KomgaBrowsePaths.ROOT

        override suspend fun children(path: String, page: Int): PathPickerPage {
            requests++
            if (requests > limit) throw AssertionError("请求数超过 $limit：无限取数（没有页数守卫）")
            return PathPickerPage(items = emptyList(), hasMore = true)
        }

        override fun parent(path: String): String = path
    }

    @Test
    fun `恒有下一页且整页被过滤时 跳空页的请求数有上限`() = runBlocking<Unit> {
        // 票 #125 P1-2：跳空页的循环若只以 !hasMore 退出，「服务器永远还有下一页」时就是无限取数
        val picker = AlwaysEmptyPathPicker(limit = KOMGA_MAX_PAGES)

        val (page, next) = picker.pageWithVisibleItems(KomgaBrowsePaths.ROOT, fromPage = 0)

        assertEquals("请求数 = 与 komgaLoadAll 同一个上限", KOMGA_MAX_PAGES, picker.requests)
        assertEquals("跳满上限仍没有可见条目：返回空页", emptyList<PathPickerItem>(), page.items)
        assertFalse("不再往后取", page.hasMore)
        assertEquals("下一页号 = 跳过的页数", KOMGA_MAX_PAGES, next)
    }

    private fun book(id: String, title: String) = KomgaBook(
        id = id,
        seriesId = "s1",
        title = title,
        number = "",
        pageCount = 2,
        releaseDate = "2020-01-01",
    )
}
