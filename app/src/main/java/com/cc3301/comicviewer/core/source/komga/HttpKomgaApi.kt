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
 * （服务器端 `sort=metadata.releaseDate`）、`GET /api/v1/books/{id}/pages`、按页取图、封面缩略图。
 *
 * 接口版本假设（无法在本机验证真实服务器，见票面偏差说明）：Komga 1.x 的 v1 路径 + Spring Data
 * 分页结构（`content/last/number`）。真机若遇到字段差异，只需调整本文件的解析，Source 与 UI 不受影响。
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
        val json = postPage("/api/v1/series/list", page, size, sort, extraQuery = null)
        parsePage(json, size) { obj ->
            KomgaSeries(
                id = obj.getString("id"),
                title = titleOf(obj),
                booksCount = obj.optInt("booksCount", 0),
            )
        }
    }

    override fun listBooks(seriesId: String, page: Int, size: Int, sort: String): KomgaPageResult<KomgaBook> =
        withRetry("书列表") {
            val json = postPage("/api/v1/books/list", page, size, sort, extraQuery = "series_id=" + encode(seriesId))
            parsePage(json, size) { obj ->
                KomgaBook(
                    id = obj.getString("id"),
                    seriesId = obj.optString("seriesId", seriesId),
                    title = titleOf(obj),
                    number = numberOf(obj),
                    pageCount = obj.optJSONObject("media")?.optInt("pagesCount", 0) ?: 0,
                    releaseDate = obj.optJSONObject("metadata")?.optString("releaseDate", "")?.takeIf { it.isNotEmpty() },
                    // Komga 在书列表里直接带 readProgress（缺失表示未读）
                    readProgress = obj.optJSONObject("readProgress")?.let {
                        KomgaReadProgress(
                            page = it.optInt("page", 1).coerceAtLeast(1),
                            completed = it.optBoolean("completed", false),
                        )
                    },
                )
            }
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
        val path = "/api/v1/books/" + encode(bookId) + "/read-progress"
        val body = getStringOrNull(path) ?: return@withRetry null
        parseReadProgress(body)
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

    /** read-progress 响应 → 进度；字段缺失时按「第 1 页未读完」处理 */
    private fun parseReadProgress(json: String): KomgaReadProgress {
        val obj = JSONObject(json)
        return KomgaReadProgress(
            page = obj.optInt("page", 1).coerceAtLeast(1),
            completed = obj.optBoolean("completed", false),
        )
    }

    // ---------- 内部 ----------

    /** Spring Data 分页 POST：`page/size/sort` 走查询串，条件留空（列表接口允许空 JSON 体） */
    private fun postPage(path: String, page: Int, size: Int, sort: String, extraQuery: String?): String {
        val query = buildString {
            append(path)
            append("?page=").append(page)
            append("&size=").append(size)
            append("&sort=").append(encode(sort))
            if (extraQuery != null) append("&").append(extraQuery)
        }
        val request = baseRequest(query)
            .post("{}".toRequestBody(JSON_MEDIA_TYPE))
            .header("Accept", "application/json")
            .build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw httpFailure(response.code, path)
            return response.body?.string() ?: "{}"
        }
    }

    /** 分页 JSON → 条目列表 + 是否还有下一页 */
    private fun <T> parsePage(json: String, size: Int, map: (JSONObject) -> T): KomgaPageResult<T> {
        val root = JSONObject(json)
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
