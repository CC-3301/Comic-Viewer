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
import org.json.JSONObject
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
 *
 * 书列表筛选体形状（票 #77）：按上游 tag 1.26.3 的 `BookSearch`（`condition: SearchCondition.Book?`）+
 * `SeriesId`（`@JsonProperty("seriesId")`）+ `SearchOperator.Equality`（判别属性 `operator`，`@JsonTypeName("is")`）
 * 即 `{"condition":{"seriesId":{"operator":"is","value":…}}}`；测试里用 JSONObject 构造成期望值，不手抄字面量。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class HttpKomgaApiTest {

    private lateinit var server: MockWebServer

    /**
     * 期望的书列表筛选体（票 #77）：与 `HttpKomgaApi` 一样用 JSONObject 构造，
     * 逐字节等于生产代码发出的体；手抄字面量一旦拼错就测不出形状偏差。
     */
    private val seriesSearchBody: String = JSONObject()
        .put(
            "condition",
            JSONObject().put(
                "seriesId",
                JSONObject().put("operator", "is").put("value", "s1"),
            ),
        )
        .toString()

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

    private fun bookPage(
        vararg ids: String,
        seriesId: String = "s1",
        page: Int = 0,
        last: Boolean = true,
        size: Int = 500,
        totalPages: Int = 1,
        totalElements: Int = ids.size,
    ) = """
{"content":[
${ids.joinToString(",") { """{"id":"$it","seriesId":"$seriesId","name":"Raw $it","number":"3","media":{"pagesCount":42,"mediaType":"application/zip"},"metadata":{"title":"Title $it","number":"3","releaseDate":"2020-05-01"}}""" }}
],"page":$page,"size":$size,"totalElements":$totalElements,"totalPages":$totalPages,"last":$last}
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
    fun `书列表的系列筛选在请求体里 查询串只有分页排序`() {
        server.enqueue(MockResponse().setResponseCode(200).setBody(bookPage("b1")))

        val result = HttpKomgaApi(config()).listBooks("s1", 0, 500, "metadata.releaseDate,desc")

        val request = server.takeRequest()
        val query = request.path!!
        assertTrue(query.contains("/api/v1/books/list"))
        // 票 #77：查询串只认 page/size/sort，旧的 series_id 写法被静默忽略（会返回全库的书）
        assertTrue("筛选条件不能再走查询串", !query.contains("series_id"))
        assertTrue("分页参数要在查询串里", query.contains("page=0") && query.contains("size=500"))
        assertTrue("发布时间排序必须走服务器端", query.contains("metadata.releaseDate"))
        assertEquals("筛选条件必须在请求体的 BookSearch 条件 DSL 里", seriesSearchBody, request.body.readUtf8())
        val book = result.items.single()
        assertEquals("Title b1", book.title)
        assertEquals("s1", book.seriesId)
        assertEquals("3", book.number)
        assertEquals(42, book.pageCount)
        assertEquals("2020-05-01", book.releaseDate)
    }

    @Test
    fun `翻页时系列筛选持续生效 且 hasNext 语义不变`() {
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                bookPage("b1", page = 0, last = false, size = 1, totalPages = 2, totalElements = 2),
            ),
        )
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                bookPage("b2", page = 1, last = true, size = 1, totalPages = 2, totalElements = 2),
            ),
        )

        val api = HttpKomgaApi(config())
        val first = api.listBooks("s1", 0, 1, "metadata.titleSort,asc")
        val second = api.listBooks("s1", 1, 1, "metadata.titleSort,asc")

        assertTrue("last=false 表示还有下一页", first.hasNext)
        assertTrue("last=true 表示没有下一页", !second.hasNext)
        val firstRequest = server.takeRequest()
        val secondRequest = server.takeRequest()
        assertTrue(firstRequest.path!!.contains("page=0"))
        assertTrue(secondRequest.path!!.contains("page=1"))
        assertEquals(seriesSearchBody, firstRequest.body.readUtf8())
        assertEquals(seriesSearchBody, secondRequest.body.readUtf8())
    }

    @Test
    fun `筛选未生效返回别的系列时 报中文提示不静默返回全库`() {
        // 守卫的可见边界：只有服务器回了条目的 seriesId 且它与请求的系列不同，才能判定筛选没生效。
        // 真机（Komga v1.26.3）复现：体形状不被服务器支持时，筛选被静默忽略并按 titleSort 返回全库的书。
        server.enqueue(MockResponse().setResponseCode(200).setBody(bookPage("b9", seriesId = "s2")))

        val thrown = assertThrows(KomgaException::class.java) {
            HttpKomgaApi(config()).listBooks("s1", 0, 500, "metadata.titleSort,asc")
        }

        val message = thrown.message!!
        assertTrue("提示要说清是筛选未生效：" + message, message.contains("书列表筛选未生效"))
        assertTrue("提示要带上期望的系列：" + message, message.contains("期望 s1"))
        assertTrue("提示要带上实际返回的系列：" + message, message.contains("实际 s2"))
        assertTrue("提示要给出可能的修法：" + message, message.contains("筛选体的形状"))
        assertTrue("不能把形状问题误诊成版本旧：" + message, !message.contains("版本过旧"))
        assertTrue("不能让人去升级：" + message, !message.contains("升级"))
    }

    @Test
    fun `条目不带 seriesId 字段时不抛 列表照常可用`() {
        // 服务器不回 seriesId 就无法判定归属：不能假装检测到了不匹配（否则老版本/精简 payload 下整列表不可用）
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """{"content":[{"id":"b1","name":"Raw","media":{"pagesCount":42}}],"last":true}""",
            ),
        )

        val result = HttpKomgaApi(config()).listBooks("s1", 0, 500, "metadata.titleSort,asc")

        assertEquals("s1", result.items.single().seriesId)
        assertEquals(42, result.items.single().pageCount)
    }

    @Test
    fun `releaseDate 原样保留 不做时区归一化`() {
        // 票 #22：上游 gotson/komga#818 的发布日期偏差只发生在 webui 显示（0.153.0 / PR #875 标题限定 webui），
        // 且 APP 侧只需原样透传服务器给的字符串——带偏移量或 Z 的值都不换算，不做 Instant/UTC 归一化。
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """
{"content":[
{"id":"b1","seriesId":"s1","media":{"pagesCount":1},"metadata":{"releaseDate":"2020-04-30T20:00:00-04:00"}},
{"id":"b2","seriesId":"s1","media":{"pagesCount":1},"metadata":{"releaseDate":"2020-05-01T00:00:00Z"}},
{"id":"b3","seriesId":"s1","media":{"pagesCount":1},"metadata":{"title":"no date"}}
],"last":true}
""",
            ),
        )

        val books = HttpKomgaApi(config()).listBooks("s1", 0, 500, "metadata.releaseDate,desc").items

        assertEquals(
            listOf("2020-04-30T20:00:00-04:00", "2020-05-01T00:00:00Z", null),
            books.map { it.releaseDate },
        )
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
    fun `读取服务器进度走书详情接口 该路径只接受 PATCH 不带 read-progress`() {
        server.enqueue(
            MockResponse().setResponseCode(200).setBody("""{"id":"b1","media":{"pagesCount":42}}"""),
        )

        assertNull(HttpKomgaApi(config()).readProgress("b1"))

        val request = server.takeRequest()
        assertEquals("GET", request.method)
        // Komga 对 read-progress 只有 PATCH/DELETE，没有 GET（向它发 GET 会拿到 405）
        assertEquals("/api/v1/books/b1", request.path)
    }

    @Test
    fun `读取服务器进度 404 与 204 都视为没读过`() {
        server.enqueue(MockResponse().setResponseCode(404))
        assertNull(HttpKomgaApi(config()).readProgress("b1"))

        server.enqueue(MockResponse().setResponseCode(204))
        assertNull(HttpKomgaApi(config()).readProgress("b1"))
    }

    @Test
    fun `读取服务器进度解析书详情里的 readProgress`() {
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """{"id":"b1","readProgress":{"page":7,"completed":false,"readDate":"2024-01-01T00:00:00Z"}}""",
            ),
        )
        val progress = HttpKomgaApi(config()).readProgress("b1")!!
        assertEquals(7, progress.page)
        assertTrue(!progress.completed)

        // completed 缺失按未读完处理
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"id":"b1","readProgress":{"page":3}}"""))
        val partial = HttpKomgaApi(config()).readProgress("b1")!!
        assertEquals(3, partial.page)
        assertTrue(!partial.completed)

        // 读完
        server.enqueue(
            MockResponse().setResponseCode(200).setBody("""{"id":"b1","readProgress":{"page":42,"completed":true}}"""),
        )
        assertTrue(HttpKomgaApi(config()).readProgress("b1")!!.completed)
    }

    @Test
    fun `读取服务器进度 401 归类为认证失败`() {
        server.enqueue(MockResponse().setResponseCode(401))
        val thrown = assertThrows(KomgaException::class.java) {
            HttpKomgaApi(config()).readProgress("b1")
        }
        assertEquals(KomgaFailureKind.AUTH, thrown.kind)
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
