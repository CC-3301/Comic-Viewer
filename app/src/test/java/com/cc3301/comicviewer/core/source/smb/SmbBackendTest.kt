package com.cc3301.comicviewer.core.source.smb

import com.cc3301.comicviewer.core.source.DocumentTreeSource
import com.cc3301.comicviewer.core.source.InMemoryProgressStore
import com.cc3301.comicviewer.core.source.SortMode
import com.cc3301.comicviewer.core.source.SourceType
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.nio.file.Files

/**
 * SMB 后端边界（票 11）：id 前缀与越界引用、传输层失败的归类与冒泡、children 元数据。
 */
class SmbBackendTest {

    private val config = SmbConnectionConfig(host = "nas", share = "comics")

    private fun fixture(): File = Files.createTempDirectory("smb-backend").toFile().apply {
        File(this, "series-a").mkdirs()
        File(this, "series-a/page1.jpg").writeBytes("p1".toByteArray())
        File(this, "series-a/page2.jpg").writeBytes("p2".toByteArray())
    }

    private fun backend(root: File, cfg: SmbConnectionConfig = config): SmbBackend =
        SmbBackend(ClassifyingTransport(FakeSmbTransport(root, cfg.host, cfg.share), cfg), cfg)

    @Test
    fun `非本共享前缀的引用一律拒绝`() {
        val backend = backend(fixture())
        assertNull(backend.resolve("smb://other-nas/comics/series-a"))
        assertNull(backend.resolve("smb://nas/other-share/series-a"))
        assertNull(backend.resolve("/series-a"))
        assertNull(backend.resolve(""))
        // 前缀相同但共享名不同（comics vs comics2）必须被拒绝，不能误当同共享
        assertNull(backend.resolve("smb://nas/comics2/series-a"))
    }

    @Test
    fun `起始目录之外的引用按越界拒绝`() {
        val root = fixture()
        val scoped = SmbConnectionConfig(host = "nas", share = "comics", rootPath = "series-a")
        val backend = backend(root, scoped)

        assertEquals("smb://nas/comics/series-a", backend.root.id)
        assertNull("起始目录之外必须拒绝", backend.resolve("smb://nas/comics/other-book"))
        assertEquals(
            "smb://nas/comics/series-a/page1.jpg",
            backend.resolve("smb://nas/comics/series-a/page1.jpg")!!.id,
        )
    }

    @Test
    fun `不同主机或共享的同名路径 id 不同`() {
        val root = fixture()
        val a = backend(root)
        val b = SmbBackend(
            ClassifyingTransport(FakeSmbTransport(root, host = "nas2", share = "comics"), SmbConnectionConfig(host = "nas2", share = "comics")),
            SmbConnectionConfig(host = "nas2", share = "comics"),
        )
        assertTrue(a.root.children().first().id != b.root.children().first().id)
    }

    @Test
    fun `children 给出共享内路径 名称 目录标记与父节点`() {
        val backend = backend(fixture())

        val books = backend.root.children()
        assertEquals(listOf("smb://nas/comics/series-a"), books.map { it.id })
        assertEquals(listOf("series-a"), books.map { it.name })
        assertTrue(books.all { it.isDirectory })
        assertNull("根节点没有父节点", backend.root.parent())

        val pages = books.first().children()
        assertEquals(
            listOf("smb://nas/comics/series-a/page1.jpg", "smb://nas/comics/series-a/page2.jpg"),
            pages.map { it.id },
        )
        assertEquals(listOf("page1.jpg", "page2.jpg"), pages.map { it.name })
        assertFalse(pages.first().isDirectory)
        assertEquals("smb://nas/comics/series-a", pages.first().parent()!!.id)
        assertEquals("p1", String(pages.first().readBytes()))
    }

    @Test
    fun `根起始目录不存在时进入连接即报明确错误`() {
        // root 是 val：进入连接（构造后端）时就应该报错，而不是等到浏览列表
        val thrown = assertThrows(SmbException::class.java) {
            SmbBackend(
                ClassifyingTransport(FakeSmbTransport(fixture()), config),
                SmbConnectionConfig(host = "nas", share = "comics", rootPath = "no-such-dir"),
            )
        }
        assertEquals(SmbFailureKind.NOT_FOUND, thrown.kind)
    }

    @Test
    fun `构造失败时关闭传输层 不泄漏会话`() {
        // 构造期 stat 已经连上服务器（SMBClient/Session）；失败路径必须关掉，否则每点一次「进入连接」漏一条连接
        val fake = FakeSmbTransport(fixture())
        assertThrows(SmbException::class.java) {
            SmbBackend(
                ClassifyingTransport(fake, config),
                SmbConnectionConfig(host = "nas", share = "comics", rootPath = "no-such-dir"),
            )
        }
        assertTrue("失败必须关闭传输层", fake.closed)
    }

    @Test
    fun `非默认端口不复用默认端口的 id`() {
        val cfg = SmbConnectionConfig(host = "nas", share = "comics", port = 1445)
        val fake = FakeSmbTransport(fixture())
        val backend = SmbBackend(ClassifyingTransport(fake, cfg), cfg)

        assertEquals("smb://nas:1445/comics", backend.root.id)
        assertNull("默认端口前缀不能在 1445 连接上生效", backend.resolve("smb://nas/comics/series-a"))
        assertEquals(
            "smb://nas:1445/comics/series-a",
            backend.resolve("smb://nas:1445/comics/series-a")!!.id,
        )
    }

    @Test
    fun `传输层异常冒泡为带原因的 SmbException`() {        val fake = FakeSmbTransport(fixture())
        val backend = SmbBackend(ClassifyingTransport(fake, config), config)

        fake.alwaysFailWith(UnknownHostException("nas"))
        val thrown = assertThrows(SmbException::class.java) {
            backend.resolve("smb://nas/comics/series-a")
        }
        assertEquals(SmbFailureKind.UNREACHABLE, thrown.kind)
        assertTrue("提示要带主机", thrown.message!!.contains("nas"))
    }

    @Test
    fun `断链一次后按重连策略即可恢复浏览`() {
        // SmbjTransport 的重连就是「丢会话 + 重试一次」；这里用同一套策略验证恢复路径能走通
        val fake = FakeSmbTransport(fixture())
        val cfg = SmbConnectionConfig(host = "nas", share = "comics")
        val backend = SmbBackend(ClassifyingTransport(fake, cfg), cfg)

        fake.failNextWith(1, SocketException("Connection reset"))
        val books = retryOnce(
            isRecoverable = ::isRecoverableSmbFailure,
            reconnect = { fake.close() },
            block = { backend.root.children() },
        )

        assertEquals("重连后应拿到真实列表", listOf("series-a"), books.map { it.name })
    }

    @Test
    fun `浏览失败时抛出超时错误而不是空列表`() = runBlocking<Unit> {
        val fake = FakeSmbTransport(fixture())
        val cfg = SmbConnectionConfig(host = "nas", share = "comics")
        val source = DocumentTreeSource(
            backend = SmbBackend(ClassifyingTransport(fake, cfg), cfg),
            progressStore = InMemoryProgressStore(),
            sourceType = SourceType.SMB,
        )

        fake.alwaysFailWith(SocketTimeoutException("timed out"))
        val thrown = assertThrows(SmbException::class.java) {
            runBlocking { source.listEntries(null, SortMode.NAME) }
        }
        assertEquals(SmbFailureKind.TIMEOUT, thrown.kind)
        Unit
    }
}
