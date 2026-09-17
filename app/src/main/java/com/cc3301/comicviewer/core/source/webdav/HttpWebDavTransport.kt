package com.cc3301.comicviewer.core.source.webdav

import com.cc3301.comicviewer.core.source.remote.BlockCachedRandomAccess
import com.cc3301.comicviewer.core.source.remote.ClassifyingRandomAccess
import com.cc3301.comicviewer.core.source.remote.RemoteFailureKind
import com.cc3301.comicviewer.core.source.remote.httpFailureKind
import com.cc3301.comicviewer.core.source.remote.httpFailureMessage
import com.cc3301.comicviewer.core.source.remote.isRecoverableRemoteFailure
import com.cc3301.comicviewer.core.source.remote.retryOnce
import com.cc3301.comicviewer.core.source.zip.RandomAccessBytes
import okhttp3.Credentials
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

/** 服务器不支持 Range 时整包返回：超过此大小的整包不做内存缓存（避免大 CBZ 撑爆内存） */
private const val WHOLE_BODY_CACHE_LIMIT = 8L * 1024 * 1024

/**
 * 真实 WebDAV 传输（票 12）：PROPFIND 列目录（Depth:1）/ Depth:0 取元数据，GET + Range 取数据。
 *
 * 设计要点：
 * - 只抛底层异常，由 [ClassifyingWebDavTransport] 统一归类成「地址不通/认证失败/超时/证书问题/路径不存在」；
 *   非 2xx 响应在这里就用 [httpFailureKind] 归类成 [WebDavException]（状态码是最可靠的依据）。
 * - [openRandomAccess] 用 Range 请求按需读：CBZ 只需读中央目录与当前页，不必整包下载
 *   （spec：解析中央目录 + 随机访问）；块缓存与失败归类复用 core/source/remote 的共享实现。
 * - 连接级失败（超时/不通/传输中断）重连一次后重试（AC：错误提示明确 + 可恢复）。
 *
 * 已知限制（审查记录）：只支持 Basic 认证（Digest-only 服务器会报认证失败）；
 * 明文 http 需要清单里的 `usesCleartextTraffic`（自签 https 会归类为「证书不受信任」）。
 *
 * 本类依赖网络与真实 DAV 服务器：协议之上的行为由 WebDavSourceContractTest（FakeWebDavTransport）
 * 与 PropfindParserTest（固定响应样本）覆盖，HTTP 语义由 HttpWebDavTransportTest（MockWebServer）覆盖，
 * 真实服务器链路走票面验收清单。
 */
class HttpWebDavTransport(
    private val config: WebDavConnectionConfig,
    private val client: OkHttpClient = defaultClient(),
) : WebDavTransport {

    /** Basic 认证头；RFC 7617 默认 ISO-8859-1（OkHttp 默认行为），非 ASCII 密码在协商为 UTF-8 的服务器上可能需换实现 */
    private val authHeader: String? =
        if (config.username.isEmpty() && config.password.isEmpty()) {
            null
        } else {
            Credentials.basic(config.username, config.password)
        }

    override fun list(path: String): List<WebDavEntry> = withRetry(path) {
        parsePropfind(propfind(path, depth = 1), config.baseUrl, path)
    }

    override fun stat(path: String): WebDavEntry? = withRetry(path) { statOrNull(path) }

    override fun readBytes(path: String): ByteArray = withRetry(path) {
        val request = baseRequest(WebDavPaths.join(config.baseUrl, path)).get().build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw httpFailure(response.code, path)
            response.body?.bytes() ?: ByteArray(0)
        }
    }

    override fun openRandomAccess(path: String): RandomAccessBytes = withRetry(path) {
        val entry = statOrNull(path)
            ?: throw WebDavException(
                RemoteFailureKind.NOT_FOUND,
                webDavFailureMessage(RemoteFailureKind.NOT_FOUND, config.displayName + path),
                null,
            )
        if (entry.isDirectory) {
            throw WebDavException(
                RemoteFailureKind.OTHER,
                "这是一个目录，不是文件：" + config.displayName + path,
                null,
            )
        }
        val url = WebDavPaths.join(config.baseUrl, path)
        val size = if (entry.size > 0L) entry.size else contentLength(url)
            ?: throw WebDavException(
                RemoteFailureKind.OTHER,
                "服务器未返回文件大小，无法按需读取：" + config.displayName + path,
                null,
            )
        // 读/关闭发生在后台线程：断链必须归类成 WebDavException（TransportFailure），
        // 否则上层会把「网络断了」当成「这本 CBZ 损坏」而静默显示 0 页
        ClassifyingRandomAccess(HttpRandomAccess(client, url, authHeader, size)) {
            asWebDavException(it, config.displayName + path)
        }
    }

    override fun close() {
        // 释放连接池里的 socket（连接本该复用，但换来源时不该继续占着）
        runCatching { client.connectionPool.evictAll() }
    }

    // ---------- 内部 ----------

    /** Depth:1/0 的 PROPFIND，返回响应 XML */
    private fun propfind(path: String, depth: Int): String {
        val request = baseRequest(WebDavPaths.join(config.baseUrl, path))
            .method("PROPFIND", PROPFIND_BODY.toRequestBody(XML_MEDIA_TYPE))
            .header("Depth", depth.toString())
            .build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw httpFailure(response.code, path)
            return response.body?.string() ?: ""
        }
    }

    /** Depth:0 stat；404 → null（“不存在”是正常结果，不是错误） */
    private fun statOrNull(path: String): WebDavEntry? {
        val request = baseRequest(WebDavPaths.join(config.baseUrl, path))
            .method("PROPFIND", PROPFIND_BODY.toRequestBody(XML_MEDIA_TYPE))
            .header("Depth", "0")
            .build()
        client.newCall(request).execute().use { response ->
            if (response.code == 404 || response.code == 410) return null
            if (!response.isSuccessful) throw httpFailure(response.code, path)
            val xml = response.body?.string() ?: return null
            // Depth:0 只返回自身；解析时不能排除 selfPath（否则永远为空）
            return parsePropfind(xml, config.baseUrl, selfPath = "\u0000").firstOrNull()
        }
    }

    /** HEAD 取 Content-Length（服务器没在 PROPFIND 里给 getcontentlength 时兜底） */
    private fun contentLength(url: String): Long? = runCatching {
        client.newCall(baseRequest(url).head().build()).execute().use { response ->
            if (!response.isSuccessful) return@use null
            response.header("Content-Length")?.trim()?.toLongOrNull()?.takeIf { it > 0L }
        }
    }.getOrNull()

    private fun baseRequest(url: String): Request.Builder =
        Request.Builder().url(url).apply { authHeader?.let { header("Authorization", it) } }

    private fun httpFailure(code: Int, path: String): WebDavException {
        val kind = httpFailureKind(code)
        val target = config.displayName + path
        return WebDavException(kind, httpFailureMessage(kind, code, target, "WebDAV"), null)
    }

    /** 连接级失败重试一次；认证/路径类错误直接上抛，不做无意义重试 */
    private fun <T> withRetry(path: String, block: () -> T): T = retryOnce(
        isRecoverable = ::isRecoverableRemoteFailure,
        reconnect = { runCatching { client.connectionPool.evictAll() } },
        block = {
            try {
                block()
            } catch (t: Throwable) {
                // HTTP 4xx/5xx 已经带明确原因：原样抛出；其余（IO/TLS）包装成带上下文的中文异常
                if (t is WebDavException) throw t
                throw asWebDavException(t, config.displayName + path)
            }
        },
    )

    private companion object {
        val XML_MEDIA_TYPE = "application/xml; charset=utf-8".toMediaType()

        const val PROPFIND_BODY = """<?xml version="1.0" encoding="utf-8" ?>
<D:propfind xmlns:D="DAV:">
  <D:prop>
    <D:resourcetype/>
    <D:getcontentlength/>
    <D:getlastmodified/>
  </D:prop>
</D:propfind>
"""

        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(45, TimeUnit.SECONDS)
            // 一次调用整体上限：避免服务器半死不活时无限等待
            .callTimeout(90, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build()
    }
}

/**
 * Range 随机访问（票 12）：ZIP 中央目录解析与按条目解压都基于它。
 * 块缓存见 [BlockCachedRandomAccess]；服务器忽略 Range（返回 200 整包）时按需切片，
 * 且整包不大时缓存下来，避免每次小读都重新下载整包。
 */
private class HttpRandomAccess(
    private val client: OkHttpClient,
    private val url: String,
    private val authHeader: String?,
    override val size: Long,
) : BlockCachedRandomAccess() {

    private var wholeBody: ByteArray? = null

    override fun fetch(offset: Long, len: Int): ByteArray {
        wholeBody?.let { body ->
            val start = offset.toInt()
            if (start >= body.size) return ByteArray(0)
            return body.copyOfRange(start, minOf(start + len, body.size))
        }
        val request = Request.Builder()
            .url(url)
            .header("Range", "bytes=" + offset + "-" + (offset + len - 1))
            .apply { authHeader?.let { header("Authorization", it) } }
            .build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw httpFailure(response.code)
            val bytes = response.body?.bytes() ?: ByteArray(0)
            if (response.code != 206) {
                // 整包返回：按请求区间切片，并在体积可控时缓存整包
                if (size <= WHOLE_BODY_CACHE_LIMIT && bytes.size.toLong() == size) wholeBody = bytes
                val start = offset.toInt()
                if (offset > Int.MAX_VALUE || start >= bytes.size) return ByteArray(0)
                return bytes.copyOfRange(start, minOf(start + len, bytes.size))
            }
            return bytes
        }
    }

    override fun close() {
        wholeBody = null
        // 无长连接句柄：OkHttp 连接由连接池管理
    }

    private fun httpFailure(code: Int): WebDavException {
        val kind = httpFailureKind(code)
        return WebDavException(kind, webDavFailureMessage(kind, url) + "（HTTP " + code + "）", null)
    }
}
