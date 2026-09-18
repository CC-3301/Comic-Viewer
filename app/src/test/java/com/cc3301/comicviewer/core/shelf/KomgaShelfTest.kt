package com.cc3301.comicviewer.core.shelf

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.cc3301.comicviewer.core.data.AppDatabase
import com.cc3301.comicviewer.core.data.RoomProgressStore
import com.cc3301.comicviewer.core.data.progressByBook
import com.cc3301.comicviewer.core.source.InMemoryProgressStore
import com.cc3301.comicviewer.core.source.ReadingProgress
import com.cc3301.comicviewer.core.source.SortMode
import com.cc3301.comicviewer.core.source.progressForEntry
import com.cc3301.comicviewer.core.source.komga.FakeKomgaApi
import com.cc3301.comicviewer.core.source.komga.KomgaBook
import com.cc3301.comicviewer.core.source.komga.KomgaConnectionConfig
import com.cc3301.comicviewer.core.source.komga.KomgaSeries
import com.cc3301.comicviewer.core.source.komga.KomgaSource
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Komga 来源接入书柜（票 31，spec 故事 43/44/45）：柜内数据源是 `listEntries(null)`（= 服务器系列列表），
 * 与浏览列表共用同一套通路，不需要新的 Source 方法；点系列进浏览列表、点书进阅读器，
 * 封面按需取、进度用 Room 投影（与生产同一份 `progressByBook`）。
 * 全部在 JVM 上用 Fake 服务器验证，无 Docker/真机。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class KomgaShelfTest {

    private lateinit var context: Context
    private lateinit var db: AppDatabase

    private val komgaConfig = KomgaConnectionConfig(baseUrl = "http://komga:25600", apiKey = "k")

    private fun komgaApi() = FakeKomgaApi(
        series = listOf(KomgaSeries(id = "s1", title = "Series A", booksCount = 2)),
        books = mapOf(
            "s1" to listOf(
                KomgaBook(id = "b1", seriesId = "s1", title = "第一卷", number = "1", pageCount = 5, releaseDate = "2020-01-01"),
                KomgaBook(id = "b2", seriesId = "s1", title = "第二卷", number = "2", pageCount = 3, releaseDate = "2020-02-01"),
            ),
        ),
    )

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
    }

    @After
    fun tearDown() {
        db.close()
    }

    // ---------- 柜内数据源 = 该连接的根条目（Komga = 系列列表） ----------

    @Test
    fun `柜内就是系列列表 系列是容器需要继续下钻`() = runTest {
        val src = KomgaSource(komgaApi(), komgaConfig, InMemoryProgressStore())

        val roots = src.listEntries(null, SortMode.NAME)
        assertEquals(listOf("Series A"), roots.map { it.name })
        assertFalse("系列不是书：柜内点它进浏览列表，不进阅读器", roots.single().isBook)

        // 点系列 → 进现有浏览列表（Routes.browser(connId, 系列 id)）：该系列的书法条目
        val books = src.listEntries(roots.single().id, SortMode.NAME)
        assertEquals(setOf("第一卷", "第二卷"), books.map { it.name }.toSet())
        assertTrue("点书法条目才是进阅读器", books.all { it.isBook })
    }

    @Test
    fun `柜的根条目由单柜页自己枚举 Komga 就是系列列表`() = runTest {
        val src = KomgaSource(komgaApi(), komgaConfig, InMemoryProgressStore())

        val cabinet = groupIntoCabinets(connections = listOf(CabinetRef(4, "Komga 主库"))).single()

        assertEquals("Komga 主库", cabinet.displayName)
        assertEquals(4L, cabinet.connectionId)
        // 柜内条目由单柜页按柜的 connectionId 取来源后列出的根条目（票 41：分柜不再拼条目）
        assertEquals(listOf("Series A"), src.listEntries(null, SortMode.NAME).map { it.name })
    }

    // ---------- 柜内封面按需取（与浏览列表同一条通路） ----------

    @Test
    fun `柜内书法条目封面按需取到 新建来源实例同样可用`() = runTest {
        val src = KomgaSource(komgaApi(), komgaConfig, InMemoryProgressStore())
        val bookId = src.listEntries(null, SortMode.NAME)
            .let { series -> src.listEntries(series.first().id, SortMode.NAME) }
            .first { it.name == "第一卷" }
            .id

        // 柜页会新建一个来源实例：Komga 的 id 自带书标识，封面不必依赖任何会话态
        val cover = KomgaSource(komgaApi(), komgaConfig, InMemoryProgressStore()).coverBytes(bookId)

        assertArrayEquals("cover-book-b1".toByteArray(), cover)
    }

    // ---------- 柜内进度条：只有书条目有，且与阅读进度实时一致 ----------

    @Test
    fun `柜内书条目进度与阅读进度一致 系列不显示进度条`() = runTest {
        val src = KomgaSource(komgaApi(), komgaConfig, RoomProgressStore(db.readingProgressDao()))
        val series = src.listEntries(null, SortMode.NAME).single()
        val book = src.listEntries(series.id, SortMode.NAME).first { it.name == "第一卷" }

        src.writeProgress(book.id, 2, 5)

        // 柜页取值走的是同一份投影（票 #49 起是浏览页：progressByBook(readAll())）+ 同一套门控（progressForEntry）
        val projected = progressByBook(db.readingProgressDao().readAll().first())
        val bar = progressForEntry(book, projected[book.id])
        assertEquals(2, bar?.pageIndex)
        assertEquals(5, bar?.totalPages)

        // 门控本身可测：系列不是书，即使它名下有一条进度行也不画进度条
        assertFalse(series.isBook)
        assertNull("系列不显示进度条", progressForEntry(series, ReadingProgress(pageIndex = 1, totalPages = 2, updatedAtMs = 1L)))
    }
}
