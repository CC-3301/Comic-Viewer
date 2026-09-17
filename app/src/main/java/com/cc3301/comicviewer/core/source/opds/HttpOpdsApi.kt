package com.cc3301.comicviewer.core.source.opds

import com.cc3301.comicviewer.core.source.remote.isRecoverableRemoteFailure
import com.cc3301.comicviewer.core.source.remote.retryOnce
import okhttp3.Credentials
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * OPDS HTTP 实现（票 15）：GET feed XML（Atom），GET 获取链接下载到缓存目录。
 *
 * 要点：
 * - 下载写临时文件再原子改名（`*.part` → 目标）：中断/失败不会留下「看着像完整文件」的半成品；
 *   这也是缓存 LRU 与「读完再打开」的前提。
 * - 进度按流式读取回报（服务器没给 Content-Length 时总量为 null，界面显示不确定进度）。
 * - 认证：有凭据时发 Basic（OPDS 服务器普遍如此）。
 * - 连接级失败重试一次（共享策略）；HTTP 4xx/5xx 直接用状态码归类。
 *
 * 本机无 Docker（无法跑容器化 OPDS 服务）：feed 解析由 OpdsFeedParserTest（静态 feed fixture）覆盖，
 * HTTP 语义由 HttpOpdsApiTest（MockWebServer）覆盖，真实服务器走票面验收清单。
 */
class HttpOpdsApi(
    private val config: OpdsConnectionConfig,
    private val client: OkHttpClient = defaultClient(),
) : OpdsApi {

    private val authHeader: String? =
        if (config.username.isEmpty() && config.password.isEmpty()) null
        else Credentials.basic(config.username, config.password)

    override fun fetchFeed(url: String): String = withRetry(url) {
        client.newCall(baseRequest(url).get().header("Accept", OPDS_ACCEPT).build()).execute().use { response ->
            if (!response.isSuccessful) throw opdsHttpException(response.code, url)
            val xml = response.body?.string()
            // 空体（含 200 + 空响应）不是有效 feed：报错而不是让上层解析出「空目录」
            if (xml.isNullOrBlank()) throw OpdsException(OpdsFailureKind.OTHER, "feed 内容为空：" + url, null)
            xml
        }
    }

    override fun download(url: String, target: File, onProgress: DownloadListener) = withRetry(url) {
        val temp = File(target.parentFile, target.name + ".part")
        temp.parentFile?.mkdirs()
        client.newCall(baseRequest(url).get().build()).execute().use { response ->
            if (!response.isSuccessful) throw opdsHttpException(response.code, url)
            val body = response.body ?: throw OpdsException(OpdsFailureKind.OTHER, "没有内容可下载：" + url, null)
            val total = body.contentLength().takeIf { it > 0 }
            var downloaded = 0L
            try {
                body.byteStream().use { input ->
                    temp.outputStream().use { output ->
                        val buffer = ByteArray(DOWNLOAD_BUFFER_BYTES)
                        while (true) {
                            val read = input.read(buffer)
                            if (read <= 0) break
                            output.write(buffer, 0, read)
                            downloaded += read
                            onProgress(downloaded, total)
                        }
                    }
                }
            } catch (t: Throwable) {
                // 半成品必须删掉：否则缓存里会留下一个打不开的「完整文件」
                runCatching { temp.delete() }
                throw t
            }
        }
        if (!temp.renameTo(target)) {
            runCatching { temp.delete() }
            throw OpdsException(OpdsFailureKind.OTHER, "缓存写入失败：" + target.name, null)
        }
    }

    override fun close() {
        runCatching { client.connectionPool.evictAll() }
    }

    private fun baseRequest(url: String): Request.Builder =
        Request.Builder().url(url).apply { authHeader?.let { header("Authorization", it) } }

    private fun <T> withRetry(url: String, block: () -> T): T = retryOnce(
        isRecoverable = ::isRecoverableRemoteFailure,
        reconnect = { runCatching { client.connectionPool.evictAll() } },
        block = {
            try {
                block()
            } catch (t: Throwable) {
                if (t is OpdsException) throw t
                throw asOpdsException(t, config.displayName + " 的 " + url)
            }
        },
    )

    private companion object {
        const val DOWNLOAD_BUFFER_BYTES = 64 * 1024

        /** 只接受 Atom/OPDS：避免服务器返回 HTML 错误页被当成 feed 解析 */
        const val OPDS_ACCEPT = "application/atom+xml,application/xml;q=0.9,*/*;q=0.1"

        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            // 下载大文件可能很慢：整体超时给足（单页取图不在这里，另有 90s 的调用上限）
            .callTimeout(30, TimeUnit.MINUTES)
            .retryOnConnectionFailure(true)
            .build()
    }
}
