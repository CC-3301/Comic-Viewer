package com.cc3301.comicviewer.core.source.webdav

import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * 真实 HTTP 层测试（票 12 review P1）：用 MockWebServer（纯 JVM，不需要 Docker）
 * 覆盖 PROPFIND 的 Depth/认证头、状态码→失败原因、Range 206 切片与整包回退、块缓存效果。
 *
 * 这一层补上了「容器化 WebDAV 服务」在本机无法运行时的主要风险面：
 * 协议交互与状态码语义不再只靠真机清单。
 */
class HttpWebDavTransportTest {

    private lateinit var server: MockWebServer

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private fun config(username: String = "reader", password: String = "pw") = WebDavConnectionConfig(
        baseUrl = server.url("/dav").toString().trimEnd('/'),
        username = username,
        password = password,
    )

    private fun multistatus(vararg body: String) =
        """<?xml version="1.0" encoding="utf-8"?>
<D:multistatus xmlns:D="DAV:">
${body.joinToString("\n")}
</D:multistatus>"""

    private fun responseXml(href: String, directory: Boolean, length: Long? = null) = """
  <D:response>
    <D:href>$href</D:href>
    <D:propstat>
      <D:prop>
        <D:resourcetype>${if (directory) "<D:collection/>" else ""}</D:resourcetype>
        ${if (length != null) "<D:getcontentlength>$length</D:getcontentlength>" else ""}
        <D:getlastmodified>Wed, 21 Oct 2015 07:28:00 GMT</D:getlastmodified>
      </D:prop>
      <D:status>HTTP/1.1 200 OK</D:status>
    </D:propstat>
  </D:response>"""

    @Test
    fun `PROPFIND 列目录 带 Depth 1 与认证头 排除自身 解析出条目`() {
        server.enqueue(
            MockResponse().setResponseCode(207).setBody(
                multistatus(
                    responseXml("/dav/comics/", directory = true),
                    responseXml("/dav/comics/series-a/", directory = true),
                    responseXml("/dav/comics/ep%2010.cbz", directory = false, length = 2048),
                ),
            ),
        )

        val entries = HttpWebDavTransport(config()).list("/comics")

        val request = server.takeRequest()
        assertEquals("PROPFIND", request.method)
        assertEquals("1", request.getHeader("Depth"))
        assertEquals("/dav/comics", request.path)
        assertTrue("要带 Basic 认证头", request.getHeader("Authorization")!!.startsWith("Basic "))
        // 传输层契约（票 #120 第 2 条）：按名称升序，与服务端返回次序无关。
        // 此处 XML 里 series-a 在前，但名称序是 ep 10.cbz < series-a。
        assertEquals(listOf("/comics/ep 10.cbz", "/comics/series-a"), entries.map { it.path })
        assertEquals(1, entries.count { it.isDirectory })
        assertEquals(2048L, entries.first { !it.isDirectory }.size)
    }

    @Test
    fun `列目录按名称排序 不依赖服务端返回顺序`() {
        // 服务端按自己的顺序返回（此处故意乱序）：传输层契约是与 SMB 一致的名称升序
        server.enqueue(
            MockResponse().setResponseCode(207).setBody(
                multistatus(
                    responseXml("/dav/comics/zeta.cbz", directory = false, length = 3),
                    responseXml("/dav/comics/alpha/", directory = true),
                    responseXml("/dav/comics/beta.cbz", directory = false, length = 2),
                ),
            ),
        )

        val names = HttpWebDavTransport(config()).list("/comics").map { it.name }

        assertEquals(listOf("alpha", "beta.cbz", "zeta.cbz"), names)
    }

    @Test
    fun `未配置凭据时不发送认证头`() {
        server.enqueue(MockResponse().setResponseCode(207).setBody(multistatus()))
        HttpWebDavTransport(config(username = "", password = "")).list("/")
        assertNull(server.takeRequest().getHeader("Authorization"))
    }

    @Test
    fun `stat 不存在返回 null 认证失败与超时给出区分原因`() {
        server.enqueue(MockResponse().setResponseCode(404))
        assertNull(HttpWebDavTransport(config()).stat("/nope"))

        server.enqueue(MockResponse().setResponseCode(401))
        val auth = assertThrows(WebDavException::class.java) { HttpWebDavTransport(config()).stat("/x") }
        assertEquals(WebDavFailureKind.AUTH, auth.kind)
        assertTrue(auth.message!!.contains("认证失败"))

        server.enqueue(MockResponse().setResponseCode(504))
        val timeout = assertThrows(WebDavException::class.java) { HttpWebDavTransport(config()).stat("/x") }
        assertEquals(WebDavFailureKind.TIMEOUT, timeout.kind)

        // Depth:0 的 PROPFIND 请求头
        val depthRequest = server.takeRequest()
        assertEquals("0", depthRequest.getHeader("Depth"))
    }

    @Test
    fun `405 给出不是 WebDAV 服务的明确提示`() {
        server.enqueue(MockResponse().setResponseCode(405))
        val thrown = assertThrows(WebDavException::class.java) { HttpWebDavTransport(config()).list("/") }
        assertTrue(thrown.message!!.contains("请求方法"))
        assertTrue(thrown.message!!.contains("WebDAV"))
        assertTrue(thrown.message!!.contains("405"))
    }

    @Test
    fun `readBytes 成功返回字节 401 归类认证失败`() {
        server.enqueue(MockResponse().setResponseCode(200).setBody("hello"))
        assertEquals("hello", String(HttpWebDavTransport(config()).readBytes("/a.txt")))
        assertEquals("GET", server.takeRequest().method)

        server.enqueue(MockResponse().setResponseCode(403))
        val thrown = assertThrows(WebDavException::class.java) { HttpWebDavTransport(config()).readBytes("/a.txt") }
        assertEquals(WebDavFailureKind.AUTH, thrown.kind)
    }

    @Test
    fun `Range 读 206 精确按请求区间返回 且块缓存减少往返`() {
        val size = 200_000L
        // 1) Depth:0 stat
        server.enqueue(MockResponse().setResponseCode(207).setBody(multistatus(responseXml("/dav/big.cbz", false, size))))
        // 2) 第一次小读：块缓存会预读 64KB*4（或到文件末尾）
        server.enqueue(MockResponse().setResponseCode(206).setBody(Buffer().write(ByteArray(200_000) { (it % 251).toByte() })))

        val transport = HttpWebDavTransport(config())
        val bytes = transport.openRandomAccess("/big.cbz")
        assertEquals(size, bytes.size)
        val head = bytes.read(0, 4)
        assertEquals(listOf<Byte>(0, 1, 2, 3), head.toList())
        // 3) 相邻小读命中块缓存：不再发请求
        assertEquals((10 % 251).toByte(), bytes.read(10, 1)[0])
        assertEquals("stat + 一次预读 = 2 个请求", 2, server.requestCount)
        val rangeRequest = server.takeRequest()
        assertEquals("0", rangeRequest.getHeader("Depth"))
        assertEquals("bytes=0-199999", server.takeRequest().getHeader("Range"))
    }

    @Test
    fun `服务器忽略 Range 返回整包时按区间切片并缓存整包`() {
        val size = 1024L
        val body = ByteArray(size.toInt()) { (it % 97).toByte() }
        server.enqueue(MockResponse().setResponseCode(207).setBody(multistatus(responseXml("/dav/small.cbz", false, size))))
        server.enqueue(MockResponse().setResponseCode(200).setBody(Buffer().write(body)))

        val bytes = HttpWebDavTransport(config()).openRandomAccess("/small.cbz")
        assertEquals(listOf<Byte>(0, 1, 2, 3), bytes.read(0, 4).toList())
        assertEquals(body[500], bytes.read(500, 1)[0])
        assertEquals("整包已缓存：第二次读不再请求", 2, server.requestCount)
    }

    @Test
    fun `PROPFIND 未给大小则回退 HEAD 取 Content-Length`() {
        server.enqueue(MockResponse().setResponseCode(207).setBody(multistatus(responseXml("/dav/x.cbz", false, length = null))))
        server.enqueue(MockResponse().setResponseCode(200).setHeader("Content-Length", "4096"))

        val bytes = HttpWebDavTransport(config()).openRandomAccess("/x.cbz")

        assertEquals(4096L, bytes.size)
        assertEquals("PROPFIND", server.takeRequest().method)
        assertEquals("HEAD", server.takeRequest().method)
    }

    @Test
    fun `服务器既不给大小也不给 Content-Length 时报明确错误`() {
        server.enqueue(MockResponse().setResponseCode(207).setBody(multistatus(responseXml("/dav/y.cbz", false, length = null))))
        server.enqueue(MockResponse().setResponseCode(200))

        val thrown = assertThrows(WebDavException::class.java) {
            HttpWebDavTransport(config()).openRandomAccess("/y.cbz")
        }
        assertTrue(thrown.message!!.contains("文件大小"))
    }
}
