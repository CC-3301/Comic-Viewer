package com.cc3301.comicviewer.core.source.komga

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
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Komga HTTP 层测试（票 13）：用 MockWebServer（纯 JVM）验证 REST 路径/查询串/认证头、
 * Spring Data 分页解析、按页取图与封面、状态码归类。
 *
 * 本机无 Docker（无法跑容器化真实 Komga 实例），这一层补上「REST 契约」的主要风险面；
 * 不同 Komga 版本的字段差异由票面真机清单覆盖。
 *
 * 用 Robolectric：JSON 解析走 Android 自带 org.json（JVM 单测里是空壳实现）。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class HttpKomgaApiTest {

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

    private fun config(apiKey: String = "secret-key") = KomgaConnectionConfig(
        baseUrl = server.url("/").toString().trimEnd('/'),
        apiKey = apiKey,
    )

    private fun basicConfig() = KomgaConnectionConfig(
        baseUrl = server.url("/").toString().trimEnd('/'),
        username = "me@example.com",
        password = "pw",
    )

    private fun seriesPage(vararg ids: String) = """
{"content":[
${ids.joinToString(",") { """{"id":"$it","name":"Name $it","booksCount":7,"metadata":{"title":"Title $it","titleSort":"Title $it"}}""" }}
],"page":0,"size":500,"totalElements":${ids.size},"totalPages":1,"last":true}
"""

    private fun bookPage(vararg ids: String) = """
{"content":[
${ids.joinToString(",") { """{"id":"$it","seriesId":"s1","name":"Raw $it","number":"3","media":{"pagesCount":42,"mediaType":"application/zip"},"metadata":{"title":"Title $it","number":"3","releaseDate":"2020-05-01"}}""" }}
],"page":0,"size":500,"totalElements":${ids.size},"totalPages":1,"last":true}
"""

    @Test
    fun `系列列表用 POST _api_v1_series_list 带分页与排序 解析 content`() {
        server.enqueue(MockResponse().setResponseCode(200).setBody(seriesPage("s1", "s2")))

        val result = HttpKomgaApi(config()).listSeries(page = 0, size = 500, sort = "metadata.titleSort,asc")

        val request = server.takeRequest()
        assertEquals("POST", request.method)
        assertEquals("/api/v1/series/list", request.path!!.substringBefore('?'))
        val query = request.path!!
        assertTrue("分页参数要在查询串里", query.contains("page=0") && query.contains("size=500"))
        assertTrue("排序要透传", query.contains("metadata.titleSort"))
        assertEquals("secret-key", request.getHeader("X-API-Key"))
        assertEquals("{}", request.body.readUtf8())
        assertEquals(listOf("Title s1", "Title s2"), result.items.map { it.title })
        assertEquals(7, result.items.first().booksCount)
        assertTrue("last=true 表示没有下一页", !result.hasNext)
    }

    @Test
    fun `书列表带 series_id 且发布时间排序用 metadata_releaseDate`() {
        server.enqueue(MockResponse().setResponseCode(200).setBody(bookPage("b1")))

        val result = HttpKomgaApi(config()).listBooks("s1", 0, 500, "metadata.releaseDate,desc")

        val query = server.takeRequest().path!!
        assertTrue(query.contains("/api/v1/books/list"))
        assertTrue("必须限定系列", query.contains("series_id=s1"))
        assertTrue("发布时间排序必须走服务器端", query.contains("metadata.releaseDate"))
        val book = result.items.single()
        assertEquals("Title b1", book.title)
        assertEquals("3", book.number)
        assertEquals(42, book.pageCount)
        assertEquals("2020-05-01", book.releaseDate)
    }

    @Test
    fun `分页 last=false 时 hasNext 为真`() {
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """{"content":[{"id":"s1","name":"A","booksCount":1}],"last":false,"number":0}""",
            ),
        )
        assertTrue(HttpKomgaApi(config()).listSeries(0, 1, "name,asc").hasNext)
    }

    @Test
    fun `缺少 last 字段时按本页装满与否判断是否还有下一页`() {
        // 本页装满 → 可能还有（继续翻页）
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """{"content":[{"id":"s1","name":"A","booksCount":1},{"id":"s2","name":"B","booksCount":1}]}""",
            ),
        )
        assertTrue(HttpKomgaApi(config()).listSeries(0, size = 2, sort = "name,asc").hasNext)

        // 未装满 → 到底了（不能因为「有内容」就把同一页重复取满上限）
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """{"content":[{"id":"s1","name":"A","booksCount":1}]}""",
            ),
        )
        assertTrue(!HttpKomgaApi(config()).listSeries(0, size = 2, sort = "name,asc").hasNext)
    }

    @Test
    fun `取页返回空体时报错 而不是给出空字节`() {
        server.enqueue(MockResponse().setResponseCode(200))
        val thrown = assertThrows(KomgaException::class.java) { HttpKomgaApi(config()).pageBytes("b1", 1) }
        assertTrue(thrown.message!!.contains("空页面"))
    }

    @Test
    fun `Basic 认证在配置邮箱密码时使用`() {
        server.enqueue(MockResponse().setResponseCode(200).setBody(seriesPage("s1")))
        HttpKomgaApi(basicConfig()).listSeries(0, 10, "name,asc")
        val request = server.takeRequest()
        assertTrue(request.getHeader("Authorization")!!.startsWith("Basic "))
        assertNull("Basic 模式不发 X-API-Key", request.getHeader("X-API-Key"))
    }

    @Test
    fun `页列表解析 编号与 MIME 取自响应`() {
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """[{"number":1,"fileName":"1.jpg","mediaType":"image/jpeg","width":800,"height":1200},
                    {"number":2,"fileName":"2.png","mediaType":"image/png"}]""",
            ),
        )

        val pages = HttpKomgaApi(config()).bookPages("b1")

        assertEquals("/api/v1/books/b1/pages", server.takeRequest().path)
        assertEquals(listOf(1, 2), pages.map { it.number })
        assertEquals("image/png", pages[1].mediaType)
    }

    @Test
    fun `按页取图返回原始字节`() {
        server.enqueue(MockResponse().setResponseCode(200).setBody(Buffer().write("JPEGDATA".toByteArray())))

        val bytes = HttpKomgaApi(config()).pageBytes("b1", 3)

        assertEquals("/api/v1/books/b1/pages/3", server.takeRequest().path)
        assertEquals("JPEGDATA", String(bytes))
    }

    @Test
    fun `封面 404 视为没有封面 不报错`() {
        server.enqueue(MockResponse().setResponseCode(404))
        assertNull(HttpKomgaApi(config()).bookThumbnail("b1"))
        server.enqueue(MockResponse().setResponseCode(204))
        assertNull(HttpKomgaApi(config()).seriesThumbnail("s1"))
    }

    @Test
    fun `状态码归类 认证失败与不存在给出区分原因`() {
        server.enqueue(MockResponse().setResponseCode(401))
        val auth = assertThrows(KomgaException::class.java) { HttpKomgaApi(config()).listSeries(0, 10, "name,asc") }
        assertEquals(KomgaFailureKind.AUTH, auth.kind)
        assertTrue(auth.message!!.contains("API Key"))

        server.enqueue(MockResponse().setResponseCode(404))
        val missing = assertThrows(KomgaException::class.java) { HttpKomgaApi(config()).bookPages("nope") }
        assertEquals(KomgaFailureKind.NOT_FOUND, missing.kind)

        server.enqueue(MockResponse().setResponseCode(405))
        val method = assertThrows(KomgaException::class.java) { HttpKomgaApi(config()).listSeries(0, 10, "name,asc") }
        assertTrue(method.message!!.contains("Komga"))
    }

    @Test
    fun `读取服务器进度 204 与 404 都视为没读过`() {
        server.enqueue(MockResponse().setResponseCode(204))
        assertNull(HttpKomgaApi(config()).readProgress("b1"))
        assertEquals("/api/v1/books/b1/read-progress", server.takeRequest().path)

        server.enqueue(MockResponse().setResponseCode(404))
        assertNull(HttpKomgaApi(config()).readProgress("b1"))
    }

    @Test
    fun `读取服务器进度解析 page 与 completed`() {
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """{"page":7,"completed":false,"readDate":"2024-01-01T00:00:00Z"}""",
            ),
        )
        val progress = HttpKomgaApi(config()).readProgress("b1")!!
        assertEquals(7, progress.page)
        assertTrue(!progress.completed)
    }

    @Test
    fun `回传进度用 PATCH 且请求体含 page 与 completed`() {
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"page":3,"completed":true}"""))

        val result = HttpKomgaApi(config()).writeProgress("b1", page = 3, completed = true)

        val request = server.takeRequest()
        assertEquals("PATCH", request.method)
        assertEquals("/api/v1/books/b1/read-progress", request.path)
        val body = request.body.readUtf8()
        assertTrue("请求体要带 page", body.contains("\"page\":3"))
        assertTrue("请求体要带 completed", body.contains("\"completed\":true"))
        assertEquals(3, result!!.page)
        assertTrue(result.completed)
    }

    @Test
    fun `回传失败按状态码归类`() {
        server.enqueue(MockResponse().setResponseCode(401))
        val thrown = assertThrows(KomgaException::class.java) {
            HttpKomgaApi(config()).writeProgress("b1", page = 1, completed = false)
        }
        assertEquals(KomgaFailureKind.AUTH, thrown.kind)
    }

    @Test
    fun `归类装饰器把 IO 失败转成带中文提示的 KomgaException`() {
        val transport = HttpKomgaApi(KomgaConnectionConfig(baseUrl = "http://127.0.0.1:1", apiKey = "k"))
        val classified = ClassifyingKomgaApi(transport, KomgaConnectionConfig(baseUrl = "http://127.0.0.1:1"))
        val thrown = assertThrows(KomgaException::class.java) { classified.listSeries(0, 10, "name,asc") }
        assertEquals(KomgaFailureKind.UNREACHABLE, thrown.kind)
        assertTrue(thrown.message!!.contains("无法连接"))
    }
}
