package com.cc3301.comicviewer.core.shelf

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.cc3301.comicviewer.core.data.AppDatabase
import com.cc3301.comicviewer.core.data.RoomProgressStore
import com.cc3301.comicviewer.core.data.progressByBook
import com.cc3301.comicviewer.core.source.InMemoryProgressStore
import com.cc3301.comicviewer.core.source.SortMode
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
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Komga 来源接入书柜（票 19，spec 故事 43/44/45）：Komga 的书入柜后，
 * 封面与进度都复用文件源那一套通路。全部在 JVM 上用 Fake 服务器验证，无 Docker/真机。
 *
 * 进度用 Room（与生产同一份投影通路 `progressByBook`），封面/进度键必须是入柜时的那个 bookId——
 * 中途换 id 会让柜页取不到封面、进度条永远为空。
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
}
