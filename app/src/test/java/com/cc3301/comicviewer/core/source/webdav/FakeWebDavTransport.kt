package com.cc3301.comicviewer.core.source.webdav

import com.cc3301.comicviewer.core.source.zip.FileRandomAccess
import com.cc3301.comicviewer.core.source.zip.RandomAccessBytes
import java.io.File

/**
 * 文件系统伪装的 WebDAV transport（票 12 测试用）。
 *
 * 本机没有 Docker（无法按 SPEC 的「容器化 WebDAV 服务」跑真实服务），
 * 这里用真实文件系统顶替 HTTP 层，让 WebDAV 后端能跑与本地同一套 SourceBehaviorContract；
 * PROPFIND 解析由 PropfindParserTest 用固定响应样本覆盖，真实服务器链路由真机验收清单覆盖。
 */
class FakeWebDavTransport(
    private val root: File,
) : WebDavTransport {

    /** 记录 close 是否被调用（验证构造失败时不泄漏连接池） */
    var closed: Boolean = false
        private set

    /** 非 null 时后续所有调用都抛它（验证失败归类与冒泡） */
    private var failure: Throwable? = null

    /** 剩余失败次数：>0 时调用抛 [recoveringFailure]，归零后恢复正常（验证断连后重连） */
    private var failuresLeft = 0
    private var recoveringFailure: Throwable? = null

    fun alwaysFailWith(t: Throwable) {
        failure = t
    }

    /** 接下来 [times] 次调用失败，之后恢复正常（模拟服务端闪断） */
    fun failNextWith(times: Int, t: Throwable) {
        failuresLeft = times
        recoveringFailure = t
    }

    override fun list(path: String): List<WebDavEntry> {
        failIfNeeded()
        val norm = WebDavPaths.normalize(path)
        val dir = fileOf(norm)
        if (!dir.isDirectory) {
            throw WebDavException(WebDavFailureKind.NOT_FOUND, "目录不存在：" + norm)
        }
        // 契约：目录不可读必须抛，不能伪装成空目录（否则会掩盖真实实现的空表回归）
        val children = dir.listFiles()
            ?: throw WebDavException(WebDavFailureKind.OTHER, "目录不可读：" + norm)
        return children.map { entryOf(norm, it) }
    }

    override fun stat(path: String): WebDavEntry? {
        failIfNeeded()
        val norm = WebDavPaths.normalize(path)
        if (norm == WebDavPaths.ROOT) {
            return WebDavEntry(
                path = WebDavPaths.ROOT,
                name = "dav",
                isDirectory = true,
                lastModifiedMs = root.lastModified().takeIf { it > 0L },
                size = 0L,
            )
        }
        val f = fileOf(norm)
        if (!f.exists()) return null
        return WebDavEntry(
            path = norm,
            name = f.name,
            isDirectory = f.isDirectory,
            lastModifiedMs = f.lastModified().takeIf { it > 0L },
            size = if (f.isFile) f.length() else 0L,
        )
    }

    override fun readBytes(path: String): ByteArray {
        failIfNeeded()
        val f = fileOf(WebDavPaths.normalize(path))
        if (!f.isFile) throw WebDavException(WebDavFailureKind.NOT_FOUND, "文件不存在：" + path)
        return f.readBytes()
    }

    override fun openRandomAccess(path: String): RandomAccessBytes {
        failIfNeeded()
        val f = fileOf(WebDavPaths.normalize(path))
        if (!f.isFile) throw WebDavException(WebDavFailureKind.NOT_FOUND, "文件不存在：" + path)
        return FileRandomAccess(f)
    }

    override fun close() {
        closed = true
        failIfNeeded()
    }

    private fun failIfNeeded() {
        failure?.let { throw it }
        if (failuresLeft > 0) {
            failuresLeft--
            recoveringFailure?.let { throw it }
        }
    }

    private fun fileOf(normPath: String): File =
        File(root, normPath.trimStart('/').replace('/', File.separatorChar))

    private fun entryOf(parentPath: String, f: File): WebDavEntry = WebDavEntry(
        path = WebDavPaths.child(parentPath, f.name),
        name = f.name,
        isDirectory = f.isDirectory,
        lastModifiedMs = f.lastModified().takeIf { it > 0L },
        size = if (f.isFile) f.length() else 0L,
    )
}
