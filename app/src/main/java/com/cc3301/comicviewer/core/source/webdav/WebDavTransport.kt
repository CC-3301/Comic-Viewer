package com.cc3301.comicviewer.core.source.webdav

import com.cc3301.comicviewer.core.source.zip.RandomAccessBytes

/** 目录项（票 12）：path 为 DAV 根内的规范路径（以 / 开头，根 = /） */
data class WebDavEntry(
    val path: String,
    val name: String,
    val isDirectory: Boolean,
    val lastModifiedMs: Long?,
    val size: Long,
)

/**
 * WebDAV 访问窄接口（票 12）：把 HTTP 细节（PROPFIND/Range/认证）压在这一层之下，
 * 上层（WebDavBackend/DocumentTreeSource）只处理路径与节点。
 *
 * 实现：`HttpWebDavTransport`（OkHttp）；测试：`FakeWebDavTransport`（文件系统伪装，
 * 让 WebDAV 后端能跑与本地同一套 SourceBehaviorContract）。
 */
interface WebDavTransport : AutoCloseable {
    /** 目录项；目录不存在或不可读时抛异常（不返回空列表，避免把错误伪装成“空目录”） */
    fun list(path: String): List<WebDavEntry>

    /** 元数据；不存在返回 null，其它失败（认证/超时/网络）必须抛异常 */
    fun stat(path: String): WebDavEntry?

    fun readBytes(path: String): ByteArray

    fun openRandomAccess(path: String): RandomAccessBytes
}

/**
 * 失败归类装饰器（票 12）：把底层 HTTP/IO 异常统一转成 [WebDavException]，
 * 使 UI 的错误提示能区分 地址不通 / 认证失败 / 超时（AC：错误提示明确）。
 */
class ClassifyingWebDavTransport(
    private val delegate: WebDavTransport,
    private val config: WebDavConnectionConfig,
) : WebDavTransport {

    // 各操作都带上路径：提示要能指到具体文件，而不是只报主机
    override fun list(path: String): List<WebDavEntry> = classify(path) { delegate.list(path) }

    override fun stat(path: String): WebDavEntry? = classify(path) { delegate.stat(path) }

    override fun readBytes(path: String): ByteArray = classify(path) { delegate.readBytes(path) }

    override fun openRandomAccess(path: String): RandomAccessBytes = classify(path) { delegate.openRandomAccess(path) }

    override fun close() {
        classify { delegate.close() }
    }

    private fun <T> classify(path: String? = null, block: () -> T): T = try {
        block()
    } catch (t: Throwable) {
        throw asWebDavException(t, config.displayName + path.orEmpty())
    }
}
