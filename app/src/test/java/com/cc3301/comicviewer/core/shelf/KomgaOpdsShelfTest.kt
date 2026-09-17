package com.cc3301.comicviewer.core.shelf

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.cc3301.comicviewer.core.data.AppDatabase
import com.cc3301.comicviewer.core.data.BookshelfEntryEntity
import com.cc3301.comicviewer.core.data.RoomProgressStore
import com.cc3301.comicviewer.core.data.progressByBook
import com.cc3301.comicviewer.core.source.InMemoryProgressStore
import com.cc3301.comicviewer.core.source.SortMode
import com.cc3301.comicviewer.core.source.komga.FakeKomgaApi
import com.cc3301.comicviewer.core.source.komga.KomgaBook
import com.cc3301.comicviewer.core.source.komga.KomgaConnectionConfig
import com.cc3301.comicviewer.core.source.komga.KomgaSeries
import com.cc3301.comicviewer.core.source.komga.KomgaSource
import com.cc3301.comicviewer.core.source.opds.FakeOpdsApi
import com.cc3301.comicviewer.core.source.opds.OpdsCache
import com.cc3301.comicviewer.core.source.opds.OpdsConnectionConfig
import com.cc3301.comicviewer.core.source.opds.OpdsSource
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.nio.file.Files

/**
 * Komga 与 OPDS 来源接入书柜（票 19，spec 故事 43/44/45）：Komga 与 OPDS 的书入柜后，
 * 分柜隔离、封面、进度都复用文件源那一套通路。全部在 JVM 上用 Fake 服务器验证，无 Docker/真机。
 *
 * 进度用 Room（与生产同一份投影通路 `progressByBook`），封面/进度键必须是入柜时的那个 bookId——
 * 中途换 id 会让柜页取不到封面、进度条永远为空。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class KomgaOpdsShelfTest {

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

    private val opdsFeedUrl = "http://opds.example.com/opds"
    private val opdsThumbUrl = "http://opds.example.com/opds/thumb2.jpg"
    private val opdsThumbBytes = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 9, 9)

    private fun opdsFeed(): String = """<?xml version="1.0" encoding="utf-8"?>
<feed xmlns="http://www.w3.org/2005/Atom">
  <title>我的书库</title>
  <entry>
    <title>第 2 话</title>
    <id>urn:book:2</id>
    <link rel="http://opds-spec.org/image/thumbnail" href="$opdsThumbUrl" type="image/jpeg"/>
    <link rel="http://opds-spec.org/acquisition" href="$opdsFeedUrl/book2.cbz" type="application/zip"/>
  </entry>
</feed>"""

    private fun opdsApi() = FakeOpdsApi(
        feeds = mapOf(opdsFeedUrl to opdsFeed()),
        downloads = mapOf(opdsThumbUrl to opdsThumbBytes),
    )

    private fun opdsSource(api: FakeOpdsApi = opdsApi(), cache: OpdsCache = newOpdsCache()) =
        OpdsSource(
            api = api,
            config = OpdsConnectionConfig(feedUrl = opdsFeedUrl),
            progressStore = InMemoryProgressStore(),
            cache = cache,
        )

    private fun newOpdsCache(): OpdsCache = OpdsCache(Files.createTempDirectory("opds-shelf").toFile())

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

    private fun entry(connectionId: Long, bookId: String, name: String) =
        BookshelfEntryEntity(connectionId, bookId, name, coverUri = null, addedAtMs = 0L)

    // ---------- AC3 分柜隔离 ----------

    @Test
    fun `Komga 与 OPDS 各自成柜 柜内不混排`() {
        val cabinets = groupIntoCabinets(
            connections = listOf(CabinetRef(1, "Komga 服务器"), CabinetRef(2, "OPDS 书库")),
            entries = listOf(
                entry(1, "komga-http://komga:25600/series/s1/book/b1", "第一卷"),
                entry(2, "opds-http://opds.example.com/opds/book/abc", "第 2 话"),
                entry(1, "komga-http://komga:25600/series/s1/book/b2", "第二卷"),
            ),
        )

        assertEquals(listOf("Komga 服务器", "OPDS 书库"), cabinets.map { it.displayName })
        assertEquals(listOf("第一卷", "第二卷"), cabinets[0].entries.map { it.name })
        assertEquals(listOf("第 2 话"), cabinets[1].entries.map { it.name })
    }

    // ---------- AC1 封面通路（Komga 按 bookId 取，与会话无关） ----------

    @Test
    fun `Komga 柜页封面按入柜 bookId 取到 新建来源实例同样可用`() = runTest {
        val api = komgaApi()
        val browserSession = KomgaSource(api, komgaConfig, InMemoryProgressStore())
        val bookId = browserSession.listEntries(null, SortMode.NAME)
            .let { series -> browserSession.listEntries(series.first().id, SortMode.NAME) }
            .first { it.name == "第一卷" }
            .id

        // 柜页会新建一个来源实例：Komga 的 id 自带系列/书标识，封面不必依赖任何会话态
        val cover = KomgaSource(api, komgaConfig, InMemoryProgressStore()).coverBytes(bookId)

        assertArrayEquals("cover-book-b1".toByteArray(), cover)
    }

    // ---------- AC2 封面通路（OPDS 跨来源实例命中磁盘缓存） ----------

    @Test
    fun `OPDS 柜页封面跨来源实例命中磁盘缓存 不重复下载`() = runTest {
        val api = opdsApi()
        val cache = newOpdsCache()

        // 浏览页：列表见过这本书（拿到缩略图地址），封面按需下载并落盘
        val browserSession = opdsSource(api, cache)
        val bookId = browserSession.listEntries(null, SortMode.NAME).first { it.isBook }.id
        assertArrayEquals(opdsThumbBytes, browserSession.coverBytes(bookId))

        // 柜页：新建来源实例，缩略图地址表是空的，但仍应命中磁盘缓存（这正是票 19 要的）
        val cabinetSession = opdsSource(api, cache)
        assertArrayEquals(opdsThumbBytes, cabinetSession.coverBytes(bookId))
        assertEquals("柜页取封面不得二次下载", 1, api.downloadCounts[opdsThumbUrl])
    }

    @Test
    fun `OPDS 从未缓存过封面时返回 null 不退化成异常`() = runTest {
        val api = opdsApi()
        // 只列出条目、不取封面：磁盘上没有封面文件，且新实例的地址表为空
        val src = opdsSource(api)
        val bookId = src.listEntries(null, SortMode.NAME).first { it.isBook }.id

        assertNull(opdsSource(api, newOpdsCache()).coverBytes(bookId))
    }

    // ---------- AC1/AC2 进度一致（入柜 bookId = 进度存储 bookId） ----------

    @Test
    fun `Komga 阅读进度写入后柜页进度投影一致`() = runTest {
        val store = RoomProgressStore(db.readingProgressDao())
        val api = komgaApi()
        val src = KomgaSource(api, komgaConfig, store)
        val bookId = src.listEntries(null, SortMode.NAME)
            .let { series -> src.listEntries(series.first().id, SortMode.NAME) }
            .first { it.name == "第一卷" }
            .id

        src.writeProgress(bookId, 2, 5)

        // 柜页取值走的是同一份投影（BookshelfScreen: progressByBook(readAll())）
        val projected = progressByBook(db.readingProgressDao().readAll().first())
        assertEquals(2, projected[bookId]?.pageIndex)
        assertEquals(5, projected[bookId]?.totalPages)
    }

    @Test
    fun `OPDS 阅读进度写入后柜页进度投影一致`() = runTest {
        val store = RoomProgressStore(db.readingProgressDao())
        val src = OpdsSource(
            api = opdsApi(),
            config = OpdsConnectionConfig(feedUrl = opdsFeedUrl),
            progressStore = store,
            cache = newOpdsCache(),
        )
        val bookId = src.listEntries(null, SortMode.NAME).first { it.isBook }.id

        src.writeProgress(bookId, 1, 4)

        val projected = progressByBook(db.readingProgressDao().readAll().first())
        assertEquals(1, projected[bookId]?.pageIndex)
        assertEquals(4, projected[bookId]?.totalPages)
    }
}
