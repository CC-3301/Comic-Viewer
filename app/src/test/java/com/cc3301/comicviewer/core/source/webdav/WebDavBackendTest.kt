package com.cc3301.comicviewer.core.source.webdav

import com.cc3301.comicviewer.core.source.DocumentTreeSource
import com.cc3301.comicviewer.core.source.InMemoryProgressStore
import com.cc3301.comicviewer.core.source.SourceType
import com.cc3301.comicviewer.core.source.remote.isRecoverableRemoteFailure
import com.cc3301.comicviewer.core.source.remote.retryOnce
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.net.SocketException
import java.net.UnknownHostException
import java.nio.file.Files

/**
 * WebDAV 后端边界（票 12）：id 前缀与越界引用、传输层失败的归类与冒泡、children 元数据。
 */
class WebDavBackendTest {

    private val config = WebDavConnectionConfig(baseUrl = "http://nas:5006/dav")

    private fun fixture(): File = Files.createTempDirectory("webdav-backend").toFile().apply {
        File(this, "series-a").mkdirs()
        File(this, "series-a/page1.jpg").writeBytes("p1".toByteArray())
        File(this, "series-a/page2.jpg").writeBytes("p2".toByteArray())
    }

    private fun backend(root: File, cfg: WebDavConnectionConfig = config): WebDavBackend =
        WebDavBackend(ClassifyingWebDavTransport(FakeWebDavTransport(root), cfg), cfg)

    @Test
    fun `非本 DAV 根的引用一律拒绝`() {
        val backend = backend(fixture())
        assertNull(backend.resolve("webdav-http://other:5006/dav/series-a"))
        assertNull(backend.resolve("webdav-http://nas:5006/other/series-a"))
        assertNull(backend.resolve("webdav-http://nas:5006/dav2/series-a"))
        assertNull(backend.resolve("/series-a"))
        assertNull(backend.resolve(""))
    }

    @Test
    fun `起始目录之外的引用按越界拒绝`() {
        val root = fixture()
        val scoped = WebDavConnectionConfig(baseUrl = "http://nas:5006/dav", rootPath = "series-a")
        val backend = backend(root, scoped)

        assertEquals("webdav-http://nas:5006/dav/series-a", backend.root.id)
        assertNull("起始目录之外必须拒绝", backend.resolve("webdav-http://nas:5006/dav/other-book"))
        assertEquals(
            "webdav-http://nas:5006/dav/series-a/page1.jpg",
            backend.resolve("webdav-http://nas:5006/dav/series-a/page1.jpg")!!.id,
        )
    }

    @Test
    fun `children 给出路径 名称 目录标记与父节点`() {
        val backend = backend(fixture())

        val dirs = backend.root.children()
        assertEquals(listOf("webdav-http://nas:5006/dav/series-a"), dirs.map { it.id })
        assertEquals(listOf("series-a"), dirs.map { it.name })
        assertTrue(dirs.all { it.isDirectory })
        assertNull("根节点没有父节点", backend.root.parent())

        val pages = dirs.first().children()
        assertEquals(
            listOf("webdav-http://nas:5006/dav/series-a/page1.jpg", "webdav-http://nas:5006/dav/series-a/page2.jpg"),
            pages.map { it.id },
        )
        assertFalse(pages.first().isDirectory)
        assertEquals("webdav-http://nas:5006/dav/series-a", pages.first().parent()!!.id)
        assertEquals("p1", String(pages.first().readBytes()))
    }

    @Test
    fun `起始目录不存在时进入连接即报明确错误 并关闭传输层`() {
        // root 是 val：进入连接（构造后端）时就应该报错，而不是等到浏览列表
        val fake = FakeWebDavTransport(fixture())
        val thrown = assertThrows(WebDavException::class.java) {
            WebDavBackend(
                ClassifyingWebDavTransport(fake, config),
                WebDavConnectionConfig(baseUrl = "http://nas:5006/dav", rootPath = "no-such-dir"),
            )
        }
        assertEquals(WebDavFailureKind.NOT_FOUND, thrown.kind)
        assertTrue("失败必须关闭传输层", fake.closed)
    }

    @Test
    fun `传输层异常冒泡为带原因的 WebDavException`() {
        val fake = FakeWebDavTransport(fixture())
        val fresh = WebDavBackend(ClassifyingWebDavTransport(fake, config), config)

        fake.alwaysFailWith(UnknownHostException("nas"))
        val thrown = assertThrows(WebDavException::class.java) {
            fresh.resolve("webdav-http://nas:5006/dav/series-a")
        }
        assertEquals(WebDavFailureKind.UNREACHABLE, thrown.kind)
        assertTrue("提示要带主机", thrown.message!!.contains("nas"))
    }

    @Test
    fun `断连一次后按重连策略即可恢复浏览`() {
        val fake = FakeWebDavTransport(fixture())
        val backend = WebDavBackend(ClassifyingWebDavTransport(fake, config), config)

        fake.failNextWith(1, SocketException("Connection reset"))
        val dirs = retryOnce(
            isRecoverable = ::isRecoverableRemoteFailure,
            reconnect = { fake.close() },
            block = { backend.root.children() },
        )

        assertEquals("重连后应拿到真实列表", listOf("series-a"), dirs.map { it.name })
    }

    @Test
    fun `浏览失败时抛出超时错误而不是空列表`() = runBlocking<Unit> {
        val fake = FakeWebDavTransport(fixture())
        val source = DocumentTreeSource(
            backend = WebDavBackend(ClassifyingWebDavTransport(fake, config), config),
            progressStore = InMemoryProgressStore(),
            sourceType = SourceType.WEBDAV,
        )

        fake.alwaysFailWith(java.net.SocketTimeoutException("timed out"))
        val thrown = assertThrows(WebDavException::class.java) {
            runBlocking { source.listEntries(null, com.cc3301.comicviewer.core.source.SortMode.NAME) }
        }
        assertEquals(WebDavFailureKind.TIMEOUT, thrown.kind)
        Unit
    }
}
