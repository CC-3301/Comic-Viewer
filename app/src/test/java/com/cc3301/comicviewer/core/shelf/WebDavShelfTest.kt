package com.cc3301.comicviewer.core.shelf

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.cc3301.comicviewer.core.data.AppDatabase
import com.cc3301.comicviewer.core.data.RoomProgressStore
import com.cc3301.comicviewer.core.data.progressByBook
import com.cc3301.comicviewer.core.source.BrowseEntry
import com.cc3301.comicviewer.core.source.DocumentTreeSource
import com.cc3301.comicviewer.core.source.InMemoryProgressStore
import com.cc3301.comicviewer.core.source.ProgressStore
import com.cc3301.comicviewer.core.source.ReadingProgress
import com.cc3301.comicviewer.core.source.SortMode
import com.cc3301.comicviewer.core.source.Source
import com.cc3301.comicviewer.core.source.SourceType
import com.cc3301.comicviewer.core.source.progressForEntry
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
import org.junit.Assert.assertFalse
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
 * WebDAV 接入书柜（票 31，spec 故事 43/44/45）。WebDAV 与本地/SMB 共用 [DocumentTreeSource]，
 * 柜内数据源就是该连接的根条目（`listEntries(null)`，不需要新的 Source 方法）：
 * 一级文件夹与根目录下直接的书/压缩包全部陈列、点容器进浏览列表、点书直接打开；
 * 封面按需取（与浏览列表同一条通路）、进度与阅读进度同源、多连接不混排（粒度 = 连接）。
 *
 * 本机无 Docker、无真实 WebDAV 服务器：传输层用文件系统伪装的 [FakeWebDavTransport]（与票 12 同一套），
 * 因此 **没有做过容器化/真机验证** —— 这是 SPEC Seam ①「WebDAV = 容器化服务」的已知偏差，
 * 与票 12/13/24 记录一致；真实服务器的 PROPFIND/GET/Range 链路由真机验收清单覆盖。
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
     * DAV 根 fixture（对应柜列表要展示的根条目）：
     * - `卷一/`            目录书，封面 = 目录内第一张图
     * - `单行本.cbz`       压缩包书，封面 = 包内首页
     * - `设定集.jpg`       图片书，封面 = 图片本身
     * - `合集/第二部/…`    容器（只含子目录），封面 = 逐级下取到的第一张图
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
        File(root, "合集/第二部").mkdirs()
        File(root, "合集/第二部/001.jpg").writeBytes("deep-cover".toByteArray())
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

    /** 柜内根条目（柜页取的就是这一份：`listEntries(null, sort)`） */
    private suspend fun rootEntries(source: Source) = source.listEntries(null, SortMode.NAME)

    /** 计数型来源：锁定「柜页枚举期不调封面通路」（票 31 决策 3） */
    private class CountingSource(private val delegate: Source) : Source by delegate {
        var coverBytesCalls: Int = 0
            private set

        override suspend fun coverBytes(entryId: String): ByteArray? {
            coverBytesCalls++
            return delegate.coverBytes(entryId)
        }
    }

    // ---------- 柜内陈列该连接的根条目（票 31 决策 1） ----------

    @Test
    fun `柜内陈列根条目全部 一级文件夹与根目录下直接的书都在`() = runTest {
        val entries = rootEntries(webdavSource())

        // 根下直接的书不得被排除（否则它们在书柜里凭空消失）
        assertEquals(
            setOf("卷一", "单行本.cbz", "设定集.jpg", "合集"),
            entries.map { it.name }.toSet(),
        )
        assertTrue("根下的压缩包与图片是书", entries.filter { it.name != "合集" }.all { it.isBook })
        assertFalse("只含子目录的一级文件夹是容器", entries.first { it.name == "合集" }.isBook)
    }

    @Test
    fun `柜内点容器进浏览列表继续下钻`() = runTest {
        val src = webdavSource()
        val container = rootEntries(src).first { !it.isBook }

        // 点容器 = Routes.browser(connId, 容器 id)：浏览列表按同一个 id 列出下一层
        val children = src.listEntries(container.id, SortMode.NAME)
        assertEquals(listOf("第二部"), children.map { it.name })
        // 二级文件夹本身是一本书，从浏览列表可直接打开
        assertEquals(1, src.openBook(children.single().id).pageCount)
    }

    @Test
    fun `柜内点书可直接打开 bookId 带本连接 scheme 与 DAV 根前缀`() = runTest {
        val src = webdavSource()
        val book = rootEntries(src).first { it.name == "卷一" }

        assertEquals("webdav-http://nas:5006/dav/卷一", book.id)
        // 柜页点书会把柜里的 bookId 原样交给来源，前缀不对就取不到页
        assertEquals(2, src.openBook(book.id).pageCount)
    }

    // ---------- 多连接不混排（粒度 = 连接，spec 故事 44） ----------

    @Test
    fun `两个连接各自成柜 柜内条目只来自本柜来源`() = runTest {
        val httpsConfig = WebDavConnectionConfig(baseUrl = "https://nas:5006/dav")
        val httpEntries = rootEntries(webdavSource())
        val httpsEntries = rootEntries(webdavSource(cfg = httpsConfig))

        val cabinets = groupIntoCabinets(
            connections = listOf(CabinetRef(1, config.displayName), CabinetRef(2, httpsConfig.displayName)),
            rootEntries = mapOf(1L to httpEntries, 2L to httpsEntries),
        )

        // 柜名用连接展示名（带 scheme）：同名路径的两条连接在柜列表里能区分
        assertNotEquals(config.displayName, httpsConfig.displayName)
        assertEquals(2, cabinets.size)
        assertTrue("本柜条目必须全带本连接的 id 前缀", cabinets[0].entries.all { it.id.startsWith("webdav-http://nas:5006/dav/") })
        assertTrue(
            "另一柜条目必须全带另一连接的 id 前缀",
            cabinets[1].entries.all { it.id.startsWith("webdav-https://nas:5006/dav/") },
        )
        assertTrue(
            "同名路径的两条连接不得串 id（否则进度与封面会互相写错库）",
            cabinets[0].entries.map { it.id }.intersect(cabinets[1].entries.map { it.id }.toSet()).isEmpty(),
        )
    }

    @Test
    fun `同主机 http 与 https 连接的进度不串键`() = runTest {
        val store = RoomProgressStore(db.readingProgressDao())
        val httpSource = webdavSource(store)
        val httpsSource = webdavSource(store, cfg = WebDavConnectionConfig(baseUrl = "https://nas:5006/dav"))
        val httpBook = rootEntries(httpSource).first { it.name == "卷一" }
        val httpsBook = rootEntries(httpsSource).first { it.name == "卷一" }

        httpSource.writeProgress(httpBook.id, pageIndex = 1, totalPages = 3)

        val projected = progressByBook(db.readingProgressDao().readAll().first())
        assertEquals(1, projected[httpBook.id]?.pageIndex)
        assertNull("https 连接的同名书不得拿到 http 的进度", projected[httpsBook.id])
    }

    // ---------- 封面：与浏览列表同一条按需通路（票 31 决策 3） ----------

    @Test
    fun `柜内封面按需取 压缩包取包内首页`() = runTest {
        val src = webdavSource()

        assertArrayEquals("cbz-cover".toByteArray(), src.coverBytes(rootEntries(src).first { it.name == "单行本.cbz" }.id))
    }

    @Test
    fun `柜内封面按需取 目录书取目录内第一张图`() = runTest {
        val src = webdavSource()

        assertArrayEquals("dir-cover".toByteArray(), src.coverBytes(rootEntries(src).first { it.name == "卷一" }.id))
    }

    @Test
    fun `柜内封面按需取 图片取本身`() = runTest {
        val src = webdavSource()

        assertArrayEquals("img-cover".toByteArray(), src.coverBytes(rootEntries(src).first { it.name == "设定集.jpg" }.id))
    }

    @Test
    fun `容器封面按需取 逐级下取第一张图`() = runTest {
        val src = webdavSource()

        assertArrayEquals("deep-cover".toByteArray(), src.coverBytes(rootEntries(src).first { it.name == "合集" }.id))
    }

    @Test
    fun `柜页枚举期不调封面通路 封面只走可见行的按需通路`() = runTest {
        // 口径边界：DocumentTreeSource 枚举内部对容器 coverUri 的逐级下取属 #30 的验收范围（票 31 Out of scope），
        // 本票锁定的是柜页自己不提前取封面（柜格先占位，可见行才调 coverBytes）
        val counting = CountingSource(webdavSource())

        val entries = counting.listEntries(null, SortMode.NAME)

        assertEquals("枚举期一次都不该调封面通路", 0, counting.coverBytesCalls)
        assertEquals(4, entries.size)
        // 可见行（CabinetCell 的 CoverThumb.loadBytes）才按需取；容器封面同样只在此时逐级下取
        assertArrayEquals("deep-cover".toByteArray(), counting.coverBytes(entries.first { it.name == "合集" }.id))
        assertEquals(1, counting.coverBytesCalls)
    }

    @Test
    fun `枚举根条目不取封面字节 容器封面只在需要时取`() = runTest {
        // 只含嵌套目录的 fixture：封面图在二级目录里，除了封面通路没有任何理由去读字节
        val nested = Files.createTempDirectory("webdav-shelf-nested").toFile()
        File(nested, "合集/第二部").mkdirs()
        File(nested, "合集/第二部/001.jpg").writeBytes("deep-cover".toByteArray())
        val transport = FakeWebDavTransport(nested)
        val src = webdavSource(transport = transport)
        transport.resetCalls()

        val entries = src.listEntries(null, SortMode.NAME)

        assertFalse(entries.single().isBook)
        assertEquals("枚举期不得取任何封面字节", 0, transport.readCalls)

        assertArrayEquals("deep-cover".toByteArray(), src.coverBytes(entries.single().id))
        assertTrue("封面字节只在真正需要时传输", transport.readCalls > 0)
    }

    @Test
    fun `来源离线时封面取不到返回 null 不中断罗列`() = runTest {
        val transport = FakeWebDavTransport(root)
        val src = webdavSource(transport = transport)
        val book = rootEntries(src).first { it.name == "卷一" }

        // 柜页先建来源（成功），随后服务器掉线：封面退化为占位底色，格子与进度条照常显示
        transport.alwaysFailWith(WebDavException(WebDavFailureKind.UNREACHABLE, "连不上 WebDAV 服务器"))

        assertNull(src.coverBytes(book.id))
    }

    // ---------- 离线（票 31 决策 7）：柜名照常显示，柜内报加载失败 ----------

    @Test
    fun `连接离线时柜名照常显示 柜内取不到条目报错而不是空柜`() = runTest {
        // 柜列表只读连接配置：柜名不需要会话，离线连接的柜照样出现
        val onlineBook = BrowseEntry("smb://nas/share/a.cbz", "a.cbz", isBook = true, coverUri = null)
        val offline = groupIntoCabinets(
            connections = listOf(CabinetRef(1, "在线的 SMB"), CabinetRef(connId, config.displayName)),
            rootEntries = mapOf(1L to listOf(onlineBook)),
        )
        assertEquals(listOf("在线的 SMB", config.displayName), offline.map { it.displayName })
        assertEquals(listOf("a.cbz"), offline[0].entries.map { it.name })
        assertTrue("离线连接取不到根条目：柜内为空", offline[1].entries.isEmpty())

        // 离线时来源根本建不起来（建来源要 stat DAV 根），对应柜页内联的「加载失败 + 重试」
        val offlineTransport = FakeWebDavTransport(root).apply {
            alwaysFailWith(WebDavException(WebDavFailureKind.UNREACHABLE, "连不上 WebDAV 服务器"))
        }
        val buildFailure = runCatching { webdavSource(transport = offlineTransport) }.exceptionOrNull()
        assertTrue("离线必须报错而不是建出空来源", buildFailure is WebDavException)

        // 建好来源后再掉线：枚举必须冒泡（界面显示加载失败），不能伪装成「这个书柜还没有内容」
        val transport = FakeWebDavTransport(root)
        val src = webdavSource(transport = transport)
        transport.alwaysFailWith(WebDavException(WebDavFailureKind.UNREACHABLE, "连不上 WebDAV 服务器"))
        val listFailure = runCatching { rootEntries(src) }.exceptionOrNull()
        assertTrue("离线枚举必须冒泡成失败", listFailure is WebDavException)
    }

    // ---------- 进度条：只有书条目显示，与阅读进度实时同源（票 31 决策 4） ----------

    @Test
    fun `柜内书条目进度与阅读进度一致 容器没有进度`() = runTest {
        val src = webdavSource(RoomProgressStore(db.readingProgressDao()))
        val entries = rootEntries(src)
        val book = entries.first { it.name == "卷一" }
        val container = entries.first { it.name == "合集" }

        src.writeProgress(book.id, 2, 5)

        // 柜页取值走的是同一份投影（CabinetScreen: progressByBook(readAll())）+ 同一套门控（progressForEntry）
        val projected = progressByBook(db.readingProgressDao().readAll().first())
        val bar = progressForEntry(book, projected[book.id])
        assertEquals(2, bar?.pageIndex)
        assertEquals(5, bar?.totalPages)

        // 门控本身可测：容器不是书，即使它名下有一条进度行也不画进度条
        assertFalse(container.isBook)
        assertNull(
            "容器不显示进度条",
            progressForEntry(container, ReadingProgress(pageIndex = 1, totalPages = 2, updatedAtMs = 1L)),
        )
    }
}
