package com.cc3301.comicviewer.ui

import com.cc3301.comicviewer.core.source.BrowseEntry
import com.cc3301.comicviewer.core.source.InMemoryProgressStore
import com.cc3301.comicviewer.core.source.SortMode
import com.cc3301.comicviewer.core.source.Source
import com.cc3301.comicviewer.core.source.komga.FakeKomgaApi
import com.cc3301.comicviewer.core.source.komga.KomgaBook
import com.cc3301.comicviewer.core.source.komga.KomgaConnectionConfig
import com.cc3301.comicviewer.core.source.komga.KomgaSeries
import com.cc3301.comicviewer.core.source.komga.KomgaSource
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 浏览列表与柜内共用的「列条目 + 回填条目名」小件（票 41 收口）。
 *
 * 名字回填是 Komga 的标题通路（票 13）：系列/书的 id 只有 UUID，标题只能靠列表见过一次，
 * 因此每次枚举都要把名字记进会话缓存（[ServiceLocator.entryNames]）——原先两屏各写一遍，
 * 收在一处后由这里钉住：透传容器与排序方式、且真的把名字记下来，否则浏览页与阅读器的标题
 * 会退化成 id。会话缓存是进程级的，用例自己收尾。
 */
class ListCompositionTest {

    /** 记录交给来源的容器与排序方式（小件必须原样透传，不能自己写死 null 或名称序） */
    private class RecordingSource(private val delegate: Source) : Source by delegate {
        var lastContainerId: String? = null
            private set
        var lastSort: SortMode? = null
            private set

        override suspend fun listEntries(containerId: String?, sort: SortMode): List<BrowseEntry> {
            lastContainerId = containerId
            lastSort = sort
            return delegate.listEntries(containerId, sort)
        }
    }

    private val komgaConfig = KomgaConnectionConfig(baseUrl = "http://komga:25600", apiKey = "k")

    /** 用例写进会话缓存的名字键（收尾时清掉，别留给后面的用例） */
    private val rememberedIds = mutableListOf<String>()

    private fun komgaSource(): Source = KomgaSource(
        api = FakeKomgaApi(
            series = listOf(KomgaSeries(id = "s1", title = "Series A", booksCount = 1)),
            books = mapOf(
                "s1" to listOf(
                    KomgaBook(id = "b1", seriesId = "s1", title = "第一卷", number = "1", pageCount = 5, releaseDate = "2020-01-01"),
                ),
            ),
        ),
        config = komgaConfig,
        progressStore = InMemoryProgressStore(),
    )

    @After
    fun tearDown() {
        rememberedIds.forEach { ServiceLocator.entryNames.remove(it) }
        rememberedIds.clear()
    }

    /** 记住本次枚举写进缓存的名字键 */
    private fun remember(entries: List<BrowseEntry>): List<BrowseEntry> = entries.also {
        rememberedIds += it.map { entry -> entry.id }
    }

    @Test
    fun `列条目时容器与排序方式原样交给来源`() = runBlocking {
        val komga = komgaSource()
        val seriesId = remember(komga.listEntries(null, SortMode.NAME)).single().id
        val recording = RecordingSource(komga)

        val entries = remember(listEntriesRememberingNames(recording, containerId = seriesId, sort = SortMode.MODIFIED_TIME))

        assertEquals("容器原样透传：传系列 id 就得拿到该系列的书", listOf("第一卷"), entries.map { it.name })
        assertEquals(seriesId, recording.lastContainerId)
        assertEquals(SortMode.MODIFIED_TIME, recording.lastSort)
    }

    @Test
    fun `列出的条目名回填进会话缓存 Komga 的标题因此拿得到`() = runBlocking {
        val komga = komgaSource()
        val series = komga.listEntries(null, SortMode.NAME).single()
        assertNotEquals("前置：Komga 的 id 不是标题（UUID + 连接前缀）", series.id, series.name)
        assertNull("前置：枚举前缓存里还没有它", ServiceLocator.entryNames[series.id])

        val entries = remember(listEntriesRememberingNames(komga, containerId = null, sort = SortMode.NAME))

        assertEquals(listOf(series.id), entries.map { it.id })
        assertEquals("列表见过一次就把名字记下：浏览页标题与阅读器标题靠它", series.name, ServiceLocator.entryNames[series.id])
    }
}
