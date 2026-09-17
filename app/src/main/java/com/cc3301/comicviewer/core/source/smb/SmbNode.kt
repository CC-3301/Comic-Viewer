package com.cc3301.comicviewer.core.source.smb

import com.cc3301.comicviewer.core.source.fs.FsBackend
import com.cc3301.comicviewer.core.source.fs.FsNode
import com.cc3301.comicviewer.core.source.zip.RandomAccessBytes

/**
 * SMB 文件树节点（票 11）：id 与 imageUri 都带 "smb://主机/共享" 前缀，
 * 因此不同主机/共享下的同名路径不会互相碰撞（id 会当书 id 用于进度键与导航参数）。
 *
 * [imageUri] 是标识串而非系统可解码 uri（SMB 没有 content:// 可给）：
 * 浏览列表解码封面失败时会回退 `Source.coverBytes`（见 BrowserScreen 的 Thumb）。
 */
class SmbNode(
    private val transport: SmbTransport,
    private val host: String,
    private val share: String,
    val path: String,
    private val entry: SmbEntry? = null,
    private val isDirectoryOverride: Boolean? = null,
    private val port: Int = SmbConnectionConfig.DEFAULT_PORT,
) : FsNode {

    private val prefix: String = SmbPaths.idPrefix(host, share, port)

    /** 根（共享根或配置的起始目录）不带尾斜杠，保证 id 可原样回传 */
    override val id: String = if (path == SmbPaths.ROOT) prefix else prefix + path

    override val name: String = if (path == SmbPaths.ROOT) share else path.substringAfterLast('/')

    override val isDirectory: Boolean = entry?.isDirectory ?: isDirectoryOverride ?: false

    override val lastModifiedMs: Long? = entry?.lastModifiedMs

    override val imageUri: String = id

    override fun children(): List<FsNode> =
        transport.list(path).map { SmbNode(transport, host, share, it.path, it, null, port) }

    override fun parent(): FsNode? {
        if (path == SmbPaths.ROOT) return null
        val parentPath = SmbPaths.parent(path) ?: return null
        return SmbNode(transport, host, share, parentPath, entry = null, isDirectoryOverride = true, port = port)
    }

    override fun readBytes(): ByteArray = transport.readBytes(path)

    override fun openRandomAccess(): RandomAccessBytes = transport.openRandomAccess(path)
}

/**
 * SMB 文件树后端（票 11）：把共享内路径映射成 [FsNode] 树，
 * 交给 DocumentTreeSource 复用浏览/排序/封面/进度/压缩包全套能力。
 */
class SmbBackend(
    private val transport: SmbTransport,
    private val config: SmbConnectionConfig,
    rootPath: String = config.rootPath,
) : FsBackend, AutoCloseable {

    private val startPath: String = SmbPaths.normalize(rootPath)
    private val prefix: String = SmbPaths.idPrefix(config.host, config.share, config.port)

    /**
     * 起始节点：一次 stat 确定存在与类型；失败（不存在/认证/超时）在这里就冒泡，
     * 让「进入连接」能立刻给出明确错误，而不是等到浏览列表才报“无效引用”。
     *
     * 注意：失败时已建立的会话（socket）必须关掉，否则每次点「进入连接」都会漏一条连接。
     */
    override val root: FsNode = try {
        SmbNode(
            transport = transport,
            host = config.host,
            share = config.share,
            path = startPath,
            entry = transport.stat(startPath) ?: throw SmbException(
                SmbFailureKind.NOT_FOUND,
                smbFailureMessage(SmbFailureKind.NOT_FOUND, config.host, config.share + startPath),
                null,
            ),
            isDirectoryOverride = true,
            port = config.port,
        )
    } catch (t: Throwable) {
        runCatching { transport.close() }
        throw t
    }

    /**
     * id → 节点：只接受本主机+共享前缀（并限制在配置的起始目录内），
     * 其余返回 null（越界引用）；传输层失败必须冒泡，不能吞成 null。
     */
    override fun resolve(id: String): FsNode? {
        val path = when {
            id == prefix -> SmbPaths.ROOT
            id.startsWith(prefix + "/") -> SmbPaths.normalize(id.removePrefix(prefix))
            else -> return null
        }
        if (!SmbPaths.isWithin(startPath, path)) return null
        val entry = transport.stat(path) ?: return null
        return SmbNode(transport, config.host, config.share, path, entry, null, config.port)
    }

    override fun close() {
        transport.close()
    }
}
