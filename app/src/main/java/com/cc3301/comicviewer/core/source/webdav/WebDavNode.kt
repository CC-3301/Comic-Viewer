package com.cc3301.comicviewer.core.source.webdav

import com.cc3301.comicviewer.core.source.fs.FsBackend
import com.cc3301.comicviewer.core.source.fs.FsNode
import com.cc3301.comicviewer.core.source.remote.endpointParts
import com.cc3301.comicviewer.core.source.zip.RandomAccessBytes

/**
 * WebDAV 文件树节点（票 12）：id 与 imageUri 都带 "webdav-scheme://主机/DAV根" 前缀，
 * 因此不同服务器、不同 scheme（http/https）、不同 DAV 根下的同名路径不会互相碰撞
 * （id 会当书 id 用于进度键与导航参数）。
 *
 * [imageUri] 是标识串而非系统可解码 uri（HTTP 没有 content:// 可给）：
 * 浏览列表解码封面失败时会回退 `Source.coverBytes`（与 SMB 同一机制）。
 */
class WebDavNode(
    private val transport: WebDavTransport,
    private val prefix: String,
    val path: String,
    private val entry: WebDavEntry? = null,
    private val isDirectoryOverride: Boolean? = null,
) : FsNode {

    /** 根（DAV 根或配置的起始目录）不带尾斜杠，保证 id 可原样回传 */
    override val id: String = if (path == WebDavPaths.ROOT) prefix else prefix + path

    override val name: String = if (path == WebDavPaths.ROOT) prefix.substringAfterLast('/') else path.substringAfterLast('/')

    override val isDirectory: Boolean = entry?.isDirectory ?: isDirectoryOverride ?: false

    override val lastModifiedMs: Long? = entry?.lastModifiedMs

    override val imageUri: String = id

    override fun children(): List<FsNode> =
        transport.list(path).map { WebDavNode(transport, prefix, it.path, it) }

    override fun parent(): FsNode? {
        if (path == WebDavPaths.ROOT) return null
        val parentPath = WebDavPaths.parent(path) ?: return null
        return WebDavNode(transport, prefix, parentPath, entry = null, isDirectoryOverride = true)
    }

    override fun readBytes(): ByteArray = transport.readBytes(path)

    override fun openRandomAccess(): RandomAccessBytes = transport.openRandomAccess(path)
}

/**
 * WebDAV 文件树后端（票 12）：把 DAV 根内路径映射成 [FsNode] 树，
 * 交给 DocumentTreeSource 复用浏览/排序/封面/进度/压缩包全套能力。
 */
class WebDavBackend(
    private val transport: WebDavTransport,
    private val config: WebDavConnectionConfig,
    rootPath: String = config.rootPath,
) : FsBackend, AutoCloseable {

    private val startPath: String = WebDavPaths.normalize(rootPath)
    private val prefix: String = idPrefix(config)

    /**
     * 起始节点：一次 stat 确定存在与类型；失败（不存在/认证/超时）在这里就冒泡，
     * 让「进入连接」能立刻给出明确错误，而不是等到浏览列表才报“无效引用”。
     * 失败时关闭传输层（HTTP 连接池），避免每次重试都留一份连接。
     */
    override val root: FsNode = try {
        WebDavNode(
            transport = transport,
            prefix = prefix,
            path = startPath,
            entry = transport.stat(startPath) ?: throw WebDavException(
                WebDavFailureKind.NOT_FOUND,
                webDavFailureMessage(WebDavFailureKind.NOT_FOUND, config.displayName + startPath),
                null,
            ),
            isDirectoryOverride = true,
        )
    } catch (t: Throwable) {
        runCatching { transport.close() }
        throw t
    }

    /**
     * id → 节点：只接受本 DAV 根前缀（并限制在配置的起始目录内），
     * 其余返回 null（越界引用）；传输层失败必须冒泡，不能吞成 null。
     */
    override fun resolve(id: String): FsNode? {
        val path = when {
            id == prefix -> WebDavPaths.ROOT
            id.startsWith(prefix + "/") ->
                // 含 .. 或非法的 id 按「无效引用」处理（返回 null），不抛异常：与 FsNode 契约一致
                runCatching { WebDavPaths.normalize(id.removePrefix(prefix)) }.getOrNull() ?: return null
            else -> return null
        }
        if (!WebDavPaths.isWithin(startPath, path)) return null
        val entry = transport.stat(path) ?: return null
        return WebDavNode(transport, prefix, path, entry)
    }

    override fun close() {
        transport.close()
    }

    private companion object {
        /** 节点 id 前缀：`webdav-scheme://主机[:端口]/DAV根`（同主机不同 scheme/不同 DAV 根不碰撞） */
        fun idPrefix(config: WebDavConnectionConfig): String =
            endpointParts(config.baseUrl).idPrefix("webdav")
    }
}
