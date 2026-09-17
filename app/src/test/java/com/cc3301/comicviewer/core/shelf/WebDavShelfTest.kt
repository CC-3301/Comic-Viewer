package com.cc3301.comicviewer.core.shelf

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.cc3301.comicviewer.core.data.AppDatabase
import com.cc3301.comicviewer.core.data.BookshelfEntryEntity
import com.cc3301.comicviewer.core.data.RoomProgressStore
import com.cc3301.comicviewer.core.data.progressByBook
import com.cc3301.comicviewer.core.source.DocumentTreeSource
import com.cc3301.comicviewer.core.source.InMemoryProgressStore
import com.cc3301.comicviewer.core.source.ProgressStore
import com.cc3301.comicviewer.core.source.SortMode
import com.cc3301.comicviewer.core.source.Source
import com.cc3301.comicviewer.core.source.SourceType
import com.cc3301.comicviewer.core.source.webdav.ClassifyingWebDavTransport
import com.cc3301.comicviewer.core.source.webdav.FakeWebDavTransport
import com.cc3301.comicviewer.core.source.webdav.WebDavBackend
import com.cc3301.comicviewer.core.source.webdav.WebDavConnectionConfig
import com.cc3301.comicviewer.core.source.webdav.WebDavException
import com.cc3301.comicviewer.core.source.webdav.WebDavFailureKind
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * WebDAV 接入书柜（票 24，spec 故事 43/44/45）。WebDAV 与本地/SMB 共用 [DocumentTreeSource]，
 * 入柜动作、分柜、封面、进度都走既有通路；本票只把入口门控打开（`supportsBookshelf`），
 * 这里锁定「打开之后这些通路对 WebDAV 成立的」那几条：入柜快照可持久化、按连接分柜不混排、
 * 封面按入柜 bookId 取得到（取不到退化为 null）、进度投影与阅读进度同源。
 *
 * 本机无 Docker、无真实 WebDAV 服务器：传输层用文件系统伪装的 [FakeWebDavTransport]（与票 12 同一套），
 * 因此 **没有做过容器化/真机验证** —— 这是 SPEC Seam ①「WebDAV = 容器化服务」的已知偏差，
 * 与票 12/13 记录一致；真实服务器的 PROPFIND/GET/Range 链路由真机验收清单覆盖。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class WebDavShelfTest {

    private val connId = 7L
    private val config = WebDavConnectionConfig(baseUrl = "http://nas:5006/dav")

    private lateinit var root: File
    private lateinit var context: Context
    private lateinit var db: AppDatabase

    /**
     * DAV 根 fixture（对应书柜要展示的三类书）：
     * - `卷一/`       目录书，封面 = 目录内第一张图
     * - `单行本.cbz`  压缩包书，封面 = 包内首页
     * - `设定集.jpg`  图片书，封面 = 图片本身
     */
    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()

        root = Files.createTempDirectory("webdav-shelf").toFile()
        File(root, "卷一").mkdirs()
        File(root, "卷一/001.jpg").writeBytes("dir-cover".toByteArray())
        File(root, "卷一/002.jpg").writeBytes("dir-page2".toByteArray())
        File(root, "设定集.jpg").writeBytes("img-cover".toByteArray())
        ZipOutputStream(File(root, "单行本.cbz").outputStream()).use { zip ->
            listOf("page1.jpg" to "cbz-cover", "page2.jpg" to "cbz-page2").forEach { (name, bytes) ->
                zip.putNextEntry(ZipEntry(name))
                zip.write(bytes.toByteArray())
                zip.closeEntry()
            }
        }
    }

    @After
    fun tearDown() {
        db.close()
    }

    /** 与 `ServiceLocator.sourceForConnection` 的 WebDAV 分支同式：同一个后端、同一个 sourceType */
    private fun webdavSource(
        progressStore: ProgressStore = InMemoryProgressStore(),
        cfg: WebDavConnectionConfig = config,
        transport: FakeWebDavTransport = FakeWebDavTransport(root),
    ): Source = DocumentTreeSource(
        backend = WebDavBackend(ClassifyingWebDavTransport(transport, cfg), cfg),
        progressStore = progressStore,
        coverCacheDir = Files.createTempDirectory("webdav-shelf-covers").toFile(),
        sourceType = SourceType.WEBDAV,
    )

    private suspend fun bookNamed(source: Source, name: String) =
        source.listEntries(null, SortMode.NAME).first { it.name == name }

    private fun shelfEntry(connectionId: Long, bookId: String, name: String) =
        BookshelfEntryEntity(connectionId, bookId, name, coverUri = null, addedAtMs = 0L)

    // ---------- AC1 加入/移出书柜，且入柜快照按连接持久化 ----------

    @Test
    fun `WebDAV 书加入书柜后可读 移出后消失`() = runTest {
        val src = webdavSource()
        val book = bookNamed(src, "卷一")

        // BrowserScreen 的入柜动作：连接 id + 条目 id/名/封面 uri 快照（连接离线时柜页照样罗列）
        val dao = db.bookshelfDao()
        dao.add(BookshelfEntryEntity(connId, book.id, book.name, book.coverUri, addedAtMs = 1L))
        assertEquals(listOf(book.id), dao.observeByConnection(connId).first().map { it.bookId })

        dao.remove(connId, book.id)
        assertTrue(dao.observeByConnection(connId).first().isEmpty())
    }

    @Test
    fun `入柜 bookId 带 scheme 与 DAV 根前缀 可直接回传来源`() = runTest {
        val src = webdavSource()
        val book = bookNamed(src, "卷一")

        assertEquals("webdav-http://nas:5006/dav/卷一", book.id)
        // 柜页点「打开书」会把柜里的 bookId 原样交给来源，前缀不对就取不到页
        assertEquals(2, src.openBook(book.id).pageCount)
    }

    // ---------- AC2 按连接分柜，不与本地/SMB 混排（粒度 = 连接，沿用票 17/19 语义） ----------

    @Test
    fun `WebDAV 与本地 SMB 各自成柜 柜内不混排`() = runTest {
        val src = webdavSource()
        val cbz = bookNamed(src, "单行本.cbz")
        val dir = bookNamed(src, "卷一")
        assertTrue(cbz.isBook)
        assertTrue(dir.isBook)

        val cabinets = groupIntoCabinets(
            connections = listOf(CabinetRef(1, "本机漫画"), CabinetRef(2, "NAS SMB"), CabinetRef(connId, config.displayName)),
            entries = listOf(
                shelfEntry(1, "content://com.android.externalstorage.documents/tree/root/a.cbz", "本地第一本"),
                shelfEntry(connId, cbz.id, cbz.name),
                shelfEntry(2, "smb://nas/share/b.cbz", "SMB 第二本"),
                shelfEntry(connId, dir.id, dir.name),
            ),
        )

        assertEquals(listOf("本机漫画", "NAS SMB", "http://nas:5006/dav"), cabinets.map { it.displayName })
        assertEquals(listOf(cbz.name, dir.name), cabinets[2].entries.map { it.name })
        assertTrue("WebDAV 柜里不得出现别的连接的条目", cabinets[2].entries.all { it.connectionId == connId })
    }

    @Test
    fun `同主机 http 与 https 连接的展示名与 bookId 都不串键`() = runTest {
        val httpsConfig = WebDavConnectionConfig(baseUrl = "https://nas:5006/dav")
        val store = RoomProgressStore(db.readingProgressDao())
        val httpSrc = webdavSource(store)
        val httpsSrc = webdavSource(store, cfg = httpsConfig)

        val httpBook = bookNamed(httpSrc, "卷一")
        val httpsBook = bookNamed(httpsSrc, "卷一")

        // 柜名用连接展示名（带 scheme），同名路径的两条连接在柜列表里能区分
        assertNotEquals(config.displayName, httpsConfig.displayName)
        assertEquals("webdav-http://nas:5006/dav/卷一", httpBook.id)
        assertEquals("webdav-https://nas:5006/dav/卷一", httpsBook.id)

        httpSrc.writeProgress(httpBook.id, 1, 3)
        val projected = progressByBook(db.readingProgressDao().readAll().first())
        assertEquals(1, projected[httpBook.id]?.pageIndex)
        assertNull("https 连接的同名书不得拿到 http 的进度", projected[httpsBook.id])
    }

    // ---------- AC3 封面与浏览列表同一条通路（柜页用 DocumentTreeSource.coverBytes） ----------

    @Test
    fun `柜页封面按入柜 bookId 取得到 压缩包取包内首页`() = runTest {
        val src = webdavSource()

        assertArrayEquals("cbz-cover".toByteArray(), src.coverBytes(bookNamed(src, "单行本.cbz").id))
    }

    @Test
    fun `柜页封面按入柜 bookId 取得到 目录逐级下取第一张图`() = runTest {
        val src = webdavSource()

        assertArrayEquals("dir-cover".toByteArray(), src.coverBytes(bookNamed(src, "卷一").id))
    }

    @Test
    fun `柜页封面按入柜 bookId 取得到 图片取本身`() = runTest {
        val src = webdavSource()

        assertArrayEquals("img-cover".toByteArray(), src.coverBytes(bookNamed(src, "设定集.jpg").id))
    }

    @Test
    fun `来源离线时封面取不到返回 null 不中断罗列`() = runTest {
        val transport = FakeWebDavTransport(root)
        val src = webdavSource(transport = transport)
        val book = bookNamed(src, "卷一")

        // 柜页先建来源（成功），随后服务器掉线：封面退化为占位底色，条目与进度条照常显示
        transport.alwaysFailWith(WebDavException(WebDavFailureKind.UNREACHABLE, "连不上 WebDAV 服务器"))

        assertNull(src.coverBytes(book.id))
    }

    @Test
    fun `来源建不起来时封面与打开都不可用 但柜里条目照样罗列`() = runTest {
        val transport = FakeWebDavTransport(root).apply {
            alwaysFailWith(WebDavException(WebDavFailureKind.UNREACHABLE, "连不上 WebDAV 服务器"))
        }
        // 建来源要 stat DAV 根：离线时这一步就失败，对应柜页的「连接不可用，无法打开」
        val failure = runCatching { webdavSource(transport = transport) }.exceptionOrNull()
        assertTrue("离线必须报错而不是建出空来源", failure is WebDavException)

        // 柜列表只读 Room 快照，与来源死活无关
        db.bookshelfDao().add(shelfEntry(connId, "webdav-http://nas:5006/dav/卷一", "卷一"))
        assertEquals(listOf("卷一"), db.bookshelfDao().observeByConnection(connId).first().map { it.name })
    }

    // ---------- AC4 进度条与阅读进度实时同源 ----------

    @Test
    fun `WebDAV 阅读进度写入后柜页进度投影一致`() = runTest {
        val src = webdavSource(RoomProgressStore(db.readingProgressDao()))
        val book = bookNamed(src, "卷一")

        src.writeProgress(book.id, 2, 5)

        // 柜页取值走的是同一份投影（BookshelfScreen: progressByBook(readAll())）
        val projected = progressByBook(db.readingProgressDao().readAll().first())
        assertEquals(2, projected[book.id]?.pageIndex)
        assertEquals(5, projected[book.id]?.totalPages)
    }
}
