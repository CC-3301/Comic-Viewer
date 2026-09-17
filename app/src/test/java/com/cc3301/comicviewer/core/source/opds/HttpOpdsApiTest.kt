package com.cc3301.comicviewer.core.source.opds

import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * OPDS HTTP 层测试（票 15 review P1）：用 MockWebServer（纯 JVM，不需要 Docker）验证
 * feed 请求（Accept/Basic 头、非 2xx 归类、空体）、下载（进度回调、`.part` 原子改名、失败不留半成品）。
 */
class HttpOpdsApiTest {

    private lateinit var server: MockWebServer
    private lateinit var dir: File

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        dir = Files.createTempDirectory("opds-http").toFile()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private fun api(username: String = "", password: String = "") = HttpOpdsApi(
        OpdsConnectionConfig(
            feedUrl = server.url("/opds").toString(),
            username = username,
            password = password,
        ),
    )

    private fun target() = File(dir, "book.cbz")

    @Test
    fun `取 feed 带 Accept 头 并返回正文`() {
        server.enqueue(MockResponse().setResponseCode(200).setBody("<feed><title>x</title></feed>"))

        val xml = api().fetchFeed(server.url("/opds").toString())

        val request = server.takeRequest()
        assertEquals("GET", request.method)
        assertTrue("要声明接受 Atom", request.getHeader("Accept")!!.contains("atom"))
        assertEquals("<feed><title>x</title></feed>", xml)
    }

    @Test
    fun `有凭据时发 Basic 认证头`() {
        server.enqueue(MockResponse().setResponseCode(200).setBody("<feed/>"))
        api(username = "reader", password = "pw").fetchFeed(server.url("/opds").toString())
        assertTrue(server.takeRequest().getHeader("Authorization")!!.startsWith("Basic "))
    }

    @Test
    fun `feed 状态码分类 认证失败与不存在给出区分原因`() {
        val url = server.url("/opds").toString()
        server.enqueue(MockResponse().setResponseCode(401))
        val auth = assertThrows(OpdsException::class.java) { api().fetchFeed(url) }
        assertEquals(OpdsFailureKind.AUTH, auth.kind)
        assertTrue(auth.message!!.contains("认证失败"))

        server.enqueue(MockResponse().setResponseCode(404))
        val missing = assertThrows(OpdsException::class.java) { api().fetchFeed(url) }
        assertEquals(OpdsFailureKind.NOT_FOUND, missing.kind)

        // 空响应体也要报错（不能把空 feed 当成有效内容）
        server.enqueue(MockResponse().setResponseCode(200))
        val empty = assertThrows(OpdsException::class.java) { api().fetchFeed(url) }
        assertTrue(empty.message!!.contains("feed 内容为空"))
    }

    @Test
    fun `下载成功写入目标文件并报告进度 不留下 part 文件`() {
        val payload = ByteArray(200_000) { (it % 251).toByte() }
        server.enqueue(MockResponse().setResponseCode(200).setBody(Buffer().write(payload)))
        val progress = mutableListOf<Pair<Long, Long?>>()

        api().download(server.url("/book.cbz").toString(), target()) { done, total -> progress += done to total }

        assertEquals(payload.size.toLong(), target().length())
        assertTrue("要按块回报进度", progress.size > 1)
        assertEquals("进度单调递增到总量", progress.last(), payload.size.toLong() to payload.size.toLong())
        assertFalse("不能留下 .part", File(dir, "book.cbz.part").exists())
    }

    @Test
    fun `下载失败既不留目标文件也不留 part 文件`() {
        server.enqueue(MockResponse().setResponseCode(500).setBody("boom"))

        assertThrows(OpdsException::class.java) {
            api().download(server.url("/book.cbz").toString(), target()) { _, _ -> }
        }
        assertFalse(target().exists())
        assertFalse(File(dir, "book.cbz.part").exists())
    }

    @Test
    fun `下载内容为空时也要成功落盘（由上层按魔数判定）`() {
        server.enqueue(MockResponse().setResponseCode(200))
        api().download(server.url("/book.cbz").toString(), target()) { _, _ -> }
        // 空响应没有 Content-Length：进度回调可能为 0 次，文件为 0 字节（缓存层会当未命中）
        assertEquals(0L, target().length())
    }
}
