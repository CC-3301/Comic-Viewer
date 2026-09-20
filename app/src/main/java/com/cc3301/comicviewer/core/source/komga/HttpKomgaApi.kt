package com.cc3301.comicviewer.core.source.komga

import com.cc3301.comicviewer.core.source.remote.httpFailureKind
import com.cc3301.comicviewer.core.source.remote.httpFailureMessage
import com.cc3301.comicviewer.core.source.remote.isRecoverableRemoteFailure
import com.cc3301.comicviewer.core.source.remote.retryOnce
import okhttp3.Credentials
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Komga REST 实现（票 13）：`POST /api/v1/series/list`、`POST /api/v1/books/list`
 * （服务器端 `sort=metadata.releaseDate`）、`GET /api/v1/books/{id}/pages`、按页取图、封面。
 *
 * 根层四入口（票 #78）：`GET /api/v1/collections`（收藏列表）、
 * `GET /api/v1/collections/{id}/series`（收藏内容）；书列表的筛选条件全在请求体里。
 *
 * 进度（票 14）：回传走 `PATCH /api/v1/books/{id}/read-progress`；读取走
 * `GET /api/v1/books/{id}` 的书详情 `readProgress` 字段——Komga 对 read-progress 只有
 * PATCH（标记）与 DELETE（标为未读），没有 GET，向该路径发 GET 会得到 405。
 *
 * 接口版本假设（无法在本机验证真实服务器，见票面偏差说明）：Komga 1.x 的 v1 路径 + Spring Data
 * 分页结构（`content/last/number`）+ 书详情 `media.pagesCount`/`readProgress` 字段。
 * 真机若遇到字段差异，只需调整本文件的解析，Source 与 UI 不受影响。
 *
 * 筛选条件只在请求体里（票 #77）：`POST /api/v1/books/list` 的查询串只认 `page`/`size`/`sort`；
 * 系列筛选取自上游 tag 1.26.3 的 `BookSearch.condition`（`SearchCondition.Book`）→ `SeriesId`
 * （`@JsonProperty("seriesId")`）→ `SearchOperator.Equality`（判别属性 `operator`，`@JsonTypeName("is")`），
 * 即 `{"condition":{"seriesId":{"operator":"is","value":…}}}`。
 * 守卫的可见边界：只有在服务器**回了**条目的 `seriesId` 且它不等于请求的系列时，才能判定筛选未生效
 * （抛中文提示）；服务器不回该字段时无法判定，按本系列处理。
 *
 * 认证二选一：`X-API-Key`（推荐）或 Basic（邮箱 + 密码）。
 * 连接级失败（超时/不通/传输中断）重连一次后重试；HTTP 4xx/5xx 直接用状态码归类并给出中文提示。
 *
 * 本类依赖真实服务器：协议语义由 HttpKomgaApiTest（MockWebServer + 固定 JSON）覆盖，
 * Source 行为由 KomgaSourceTest（FakeKomgaApi）覆盖，真实实例走票面验收清单。
 */
class HttpKomgaApi(
    private val config: KomgaConnectionConfig,
    private val client: OkHttpClient = defaultClient(),
) : KomgaApi {

    private val base: String = config.baseUrl.trim().trimEnd('/')

    private val authHeader: String? = when {
        config.usesApiKey -> null
        config.username.isEmpty() && config.password.isEmpty() -> null
        else -> Credentials.basic(config.username, config.password)
    }

    override fun listSeries(page: Int, size: Int, sort: String): KomgaPageResult<KomgaSeries> = withRetry("系列列表") {
        // 系列列表没有筛选条件：空 JSON 体是正确写法（服务器按 page/size/sort 返回全部系列）
        val json = postPage("/api/v1/series/list", page, size, sort, body = "{}")
        parsePage(json, size) { obj -> seriesOf(obj) }
    }

    override fun listCollections(page: Int, size: Int, sort: String): KomgaPageResult<KomgaCollection> =
        withRetry("收藏列表") {
            // 收藏列表是 GET（票 #78）：服务器端分页 + 排序，无筛选条件
            val json = getPage("/api/v1/collections", page, size, sort)
            parsePage(json, size) { obj ->
                KomgaCollection(id = obj.getString("id"), name = titleOf(obj))
            }
        }

    override fun collectionSeries(
        collectionId: String,
        page: Int,
        size: Int,
        sort: String,
    ): KomgaPageResult<KomgaSeries> = withRetry("收藏内容") {
        // 收藏的內容是 GET（票 #78）：`/collections/{id}/series`（Komga 原生结构里收藏组织系列）
        // 解析兼容分页对象与纯数组两种形状（见 [parsePage]）：不同版本该端点的包装层不一致
        val json = getPage("/api/v1/collections/" + encode(collectionId) + "/series", page, size, sort)
        parsePage(json, size) { obj -> seriesOf(obj) }
    }

    override fun listBooks(
        query: KomgaBookQuery,
        page: Int,
        size: Int,
        sort: String,
    ): KomgaPageResult<KomgaBook> = withRetry("书列表") {
        // 筛选条件必须在请求体里（票 #77）：查询串只认 page/size/sort，
        // 旧的 `series_id=`（已废弃的 GET /api/v1/books 写法）与顶层 seriesId 数组字段
        // 都不是 BookSearch 的字段，会被服务器静默忽略并返回全库的书（真机 Komga v1.26.3 已复现）
        val json = postPage("/api/v1/books/list", page, size, sort, body = searchBody(query))
        parsePage(json, size) { obj -> bookOf(obj, query) }
    }

    override fun seriesThumbnail(seriesId: String): ByteArray? =
        withRetry("系列封面") { bytesOrNull("/api/v1/series/" + encode(seriesId) + "/thumbnail") }

    override fun bookThumbnail(bookId: String): ByteArray? =
        withRetry("书封面") { bytesOrNull("/api/v1/books/" + encode(bookId) + "/thumbnail") }

    override fun bookPages(bookId: String): List<KomgaPage> = withRetry("页列表") {
        val body = getStringOrNull("/api/v1/books/" + encode(bookId) + "/pages")
            ?: throw KomgaException(KomgaFailureKind.NOT_FOUND, "书不存在或没有页：" + config.displayName + "/" + bookId, null)
        val array = JSONArray(body)
        (0 until array.length()).map { index ->
            val obj = array.getJSONObject(index)
            KomgaPage(
                number = obj.optInt("number", index + 1),
                mediaType = obj.optString("mediaType", "image/jpeg"),
            )
        }
    }

    override fun pageBytes(bookId: String, pageNumber: Int): ByteArray =
        withRetry("第 " + pageNumber + " 页") {
            val path = "/api/v1/books/" + encode(bookId) + "/pages/" + pageNumber
            client.newCall(baseRequest(path).get().build()).execute().use { response ->
                if (!response.isSuccessful) throw httpFailure(response.code, path)
                val bytes = response.body?.bytes()
                // 契约：取页错误必须抛，不能吞成空数组（否则阅读器显示空白页却不报错）
                if (bytes == null || bytes.isEmpty()) {
                    throw KomgaException(
                        KomgaFailureKind.OTHER,
                        "服务器返回了空页面：" + config.displayName + path,
                        null,
                    )
                }
                bytes
            }
        }

    override fun readProgress(bookId: String): KomgaReadProgress? = withRetry("服务器进度") {
        // Komga 没有 GET read-progress 端点（该路径只接受 PATCH/DELETE）：进度随书详情返回
        val path = "/api/v1/books/" + encode(bookId)
        val body = getStringOrNull(path) ?: return@withRetry null
        readProgressOf(JSONObject(body))
    }

    override fun writeProgress(bookId: String, page: Int, completed: Boolean): KomgaReadProgress? =
        withRetry("回传进度") {
            val path = "/api/v1/books/" + encode(bookId) + "/read-progress"
            // PATCH：只改阅读进度这一个字段（Komga 的 read-progress 接口）
            val payload = JSONObject().put("page", page).put("completed", completed).toString()
            val request = baseRequest(path)
                .patch(payload.toRequestBody(JSON_MEDIA_TYPE))
                .header("Accept", "application/json")
                .build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) throw httpFailure(response.code, path)
                response.body?.string()?.takeIf { it.isNotBlank() }?.let(::parseReadProgress)
                    ?: KomgaReadProgress(page, completed)
            }
        }

    override fun close() {
        runCatching { client.connectionPool.evictAll() }
    }

    /**
     * 书详情/书列表条目里的 `readProgress` → 进度；字段缺失（没读过）返回 null。
     * page 在 Komga 里从 1 起（服务器会校验 `1..pagesCount`），这里只做下限保护。
     */
    private fun readProgressOf(obj: JSONObject): KomgaReadProgress? =
        obj.optJSONObject("readProgress")?.let {
            KomgaReadProgress(
                page = it.optInt("page", 1).coerceAtLeast(1),
                completed = it.optBoolean("completed", false),
            )
        }

    /** read-progress 响应体（PATCH 返回 204 无体时的兜底解析）→ 进度 */
    private fun parseReadProgress(json: String): KomgaReadProgress {
        val obj = JSONObject(json)
        return KomgaReadProgress(
            page = obj.optInt("page", 1).coerceAtLeast(1),
            completed = obj.optBoolean("completed", false),
        )
    }

    // ---------- 内部 ----------

    /** 系列条目（票 #78）：系列列表与收藏內容同形，解析收在一处 */
    private fun seriesOf(obj: JSONObject): KomgaSeries = KomgaSeries(
        id = obj.getString("id"),
        title = titleOf(obj),
        booksCount = obj.optInt("booksCount", 0),
    )

    /**
     * 书列表筛选体（票 #78）：分别在 `BookSearch.condition` 的对应字段上，体里不带元数据的字段。
     * - [KomgaBookQuery.Series]：`seriesId is <id>`（票 #77 已真机验证过的写法）；
     * - [KomgaBookQuery.All]：`{}`（无筛选条件）；
     * - [KomgaBookQuery.Read]：`readStatus isNot UNREAD`（= 在读 + 已读完，票 #78；未真机验证）。
     */
    private fun searchBody(query: KomgaBookQuery): String = when (query) {
        is KomgaBookQuery.Series -> JSONObject()
            .put(
                "condition",
                JSONObject().put(
                    "seriesId",
                    JSONObject().put("operator", "is").put("value", query.seriesId),
                ),
            )
            .toString()
        KomgaBookQuery.All -> "{}"
        KomgaBookQuery.Read -> JSONObject()
            .put(
                "condition",
                JSONObject().put(
                    "readStatus",
                    JSONObject().put("operator", "isNot").put("value", "UNREAD"),
                ),
            )
            .toString()
    }

    /**
     * 书列表条目（票 #78）：兼底系列筛选失效的守卫（票 #77）与「全部书 / 阅读过」不入系列筛选的形状。
     *
     * 筛选守卫的可见边界：只有服务器**回了**条目的 `seriesId` 且它不等于请求的系列时，
     * 才能判定筛选未生效（抛中文提示）；服务器不回该字段时无法判定，按本系列处理。
     * [KomgaBookQuery.All] / [KomgaBookQuery.Read] 不校验也不兼底（本来就不筛选系列）。
     */
    private fun bookOf(obj: JSONObject, query: KomgaBookQuery): KomgaBook {
        val actual = obj.optString("seriesId", "")
        if (query is KomgaBookQuery.Series) {
            if (actual.isNotEmpty() && actual != query.seriesId) throw foreignSeriesFailure(query.seriesId, actual)
        }
        return KomgaBook(
            id = obj.getString("id"),
            seriesId = actual.ifEmpty { (query as? KomgaBookQuery.Series)?.seriesId.orEmpty() },
            title = titleOf(obj),
            number = numberOf(obj),
            pageCount = obj.optJSONObject("media")?.optInt("pagesCount", 0) ?: 0,
            releaseDate = obj.optJSONObject("metadata")?.optString("releaseDate", "")?.takeIf { it.isNotEmpty() },
            // Komga 在书列表里直接带 readProgress（缺失表示未读）
            readProgress = readProgressOf(obj),
        )
    }

    /**
     * 服务器回了别的系列的条目时的提示（票 #77）：此刻列表里混进了别的系列的书，继续渲染就是「点 A 进 B」。
     * 只陈述可观察到的事实（期望/实际），不归因版本（真机 v1.26.3 上就是体形状不被支持）；
     * 不返回空列表（那会伪装成「这个系列没有书」），也不返回全库。
     */
    private fun foreignSeriesFailure(expectedSeriesId: String, actualSeriesId: String): KomgaException =
        KomgaException(
            KomgaFailureKind.OTHER,
            "书列表筛选未生效：服务器返回的书不属于该系列（期望 " + expectedSeriesId +
                "，实际 " + actualSeriesId + "）。可能是筛选体的形状不被该服务器支持。",
            null,
        )

    /** Spring Data 分页 POST：查询串只放 page/size/sort，筛选条件全部在 JSON 体里（`{}` 表示无筛选，票 #77） */
    private fun postPage(path: String, page: Int, size: Int, sort: String, body: String): String {
        val query = buildString {
            append(path)
            append("?page=").append(page)
            append("&size=").append(size)
            append("&sort=").append(encode(sort))
        }
        val request = baseRequest(query)
            .post(body.toRequestBody(JSON_MEDIA_TYPE))
            .header("Accept", "application/json")
            .build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw httpFailure(response.code, path)
            return response.body?.string() ?: "{}"
        }
    }

    /** Spring Data 分页 GET（票 #78）：收藏列表与收藏內容用它（查询串只有 page/size/sort） */
    private fun getPage(path: String, page: Int, size: Int, sort: String): String {
        val query = buildString {
            append(path)
            append("?page=").append(page)
            append("&size=").append(size)
            append("&sort=").append(encode(sort))
        }
        return getString(query)
    }

    /**
     * 分页 JSON → 条目列表 + 是否还有下一页（票 #78 起兼容两种形状）：
     * Spring Data 分页对象（`content`/`last`）与纯数组。
     * 有的端点（如 `/collections/{id}/series`）在不同版本里包或不包分页层，两种都得能解析，
     * 否则切到别的 Komga 版本就直接报错。
     */
    private fun <T> parsePage(json: String, size: Int, map: (JSONObject) -> T): KomgaPageResult<T> {
        val trimmed = json.trim()
        if (trimmed.startsWith("[")) {
            val array = JSONArray(trimmed)
            val items = (0 until array.length()).map { map(array.getJSONObject(it)) }
            return KomgaPageResult(items, hasNext = false)
        }
        val root = JSONObject(trimmed)
        val content = root.optJSONArray("content") ?: JSONArray()
        val items = (0 until content.length()).map { map(content.getJSONObject(it)) }
        // 以 Spring Data 的 last 字段为准；缺失时按「本页装满才可能还有下一页」兜底
        // （若兜底成「有内容就还有」，服务器不回 last 时会把同一页重复取满上限）
        val hasNext = when {
            root.has("last") -> !root.getBoolean("last")
            else -> items.size >= size
        }
        return KomgaPageResult(items, hasNext)
    }

    /** 分页/详情类 GET：204 视为空体，4xx/5xx 直接归类（404 也算错误） */
    private fun getString(path: String): String =
        client.newCall(baseRequest(path).get().header("Accept", "application/json").build()).execute().use { response ->
            when {
                response.code == 204 -> "{}"
                !response.isSuccessful -> throw httpFailure(response.code, path)
                else -> response.body?.string() ?: "{}"
            }
        }

    private fun getStringOrNull(path: String): String? =
        client.newCall(baseRequest(path).get().header("Accept", "application/json").build()).execute().use { response ->
            when {
                response.code == 404 || response.code == 204 -> null
                !response.isSuccessful -> throw httpFailure(response.code, path)
                else -> response.body?.string()
            }
        }

    private fun bytesOrNull(path: String): ByteArray? =
        client.newCall(baseRequest(path).get().build()).execute().use { response ->
            when {
                // 没有封面时 Komga 返回 404/204：这不是错误，只是没有封面
                response.code == 404 || response.code == 204 -> null
                !response.isSuccessful -> throw httpFailure(response.code, path)
                else -> response.body?.bytes()
            }
        }

    private fun baseRequest(path: String): Request.Builder = Request.Builder()
        .url(base + path)
        .apply {
            authHeader?.let { header("Authorization", it) }
            if (config.usesApiKey) header("X-API-Key", config.apiKey)
        }

    private fun httpFailure(code: Int, path: String): KomgaException =
        komgaHttpException(code, config.displayName + path)

    /** 连接级失败重试一次；认证/路径类错误直接上抛，不做无意义重试 */
    private fun <T> withRetry(what: String, block: () -> T): T = retryOnce(
        isRecoverable = ::isRecoverableRemoteFailure,
        reconnect = { runCatching { client.connectionPool.evictAll() } },
        block = {
            try {
                block()
            } catch (t: Throwable) {
                if (t is KomgaException) throw t
                throw asKomgaException(t, config.displayName + " 的 " + what)
            }
        },
    )

    /** 系列/书的标题：优先 metadata.title，缺失回退 name */
    private fun titleOf(obj: JSONObject): String {
        val metadataTitle = obj.optJSONObject("metadata")?.optString("title", "").orEmpty()
        return metadataTitle.ifEmpty { obj.optString("name", "") }
    }

    /** 册号：优先 metadata.number，缺失回退 number */
    private fun numberOf(obj: JSONObject): String {
        val metadataNumber = obj.optJSONObject("metadata")?.optString("number", "").orEmpty()
        return metadataNumber.ifEmpty { obj.optString("number", "") }
    }

    private fun encode(value: String): String = java.net.URLEncoder.encode(value, "UTF-8")

    private companion object {
        val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()

        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(45, TimeUnit.SECONDS)
            .callTimeout(90, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build()
    }
}
