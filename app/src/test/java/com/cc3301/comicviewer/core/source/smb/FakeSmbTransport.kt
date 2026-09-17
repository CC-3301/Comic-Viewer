package com.cc3301.comicviewer.core.source.smb

import com.cc3301.comicviewer.core.source.zip.FileRandomAccess
import com.cc3301.comicviewer.core.source.zip.RandomAccessBytes
import java.io.File

/**
 * 文件系统伪装的 SMB transport（票 11 测试用）。
 *
 * 本机没有 Docker/Samba（无法按 SPEC 的「容器化 Samba」跑真实服务），
 * 这里用真实文件系统顶替协议层，让 SMB 后端能跑与本地同一套 SourceBehaviorContract；
 * smbj 协议层由真机验收清单覆盖。
 */
class FakeSmbTransport(
    private val root: File,
    private val host: String = "nas",
    private val share: String = "comics",
) : SmbTransport {

    /** 记录 close 是否被调用（验证构造失败时不泄漏会话） */
    var closed: Boolean = false
        private set

    /** 非 null 时后续所有调用都抛它（验证失败归类与冒泡） */
    private var failure: Throwable? = null

    /** 剩余失败次数：>0 时调用抛 [recoveringFailure]，归零后恢复正常（验证断链后重连） */
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

    override fun list(path: String): List<SmbEntry> {
        failIfNeeded()
        val norm = SmbPaths.normalize(path)
        val dir = fileOf(norm)
        if (!dir.isDirectory) {
            throw SmbException(SmbFailureKind.NOT_FOUND, "目录不存在：" + norm)
        }
        return dir.listFiles().orEmpty().map { entryOf(norm, it) }
    }

    override fun stat(path: String): SmbEntry? {
        failIfNeeded()
        val norm = SmbPaths.normalize(path)
        if (norm == SmbPaths.ROOT) {
            return SmbEntry(
                path = SmbPaths.ROOT,
                name = share,
                isDirectory = true,
                lastModifiedMs = root.lastModified().takeIf { it > 0L },
                size = 0L,
            )
        }
        val f = fileOf(norm)
        if (!f.exists()) return null
        return SmbEntry(
            path = norm,
            name = f.name,
            isDirectory = f.isDirectory,
            lastModifiedMs = f.lastModified().takeIf { it > 0L },
            size = if (f.isFile) f.length() else 0L,
        )
    }

    override fun readBytes(path: String): ByteArray {
        failIfNeeded()
        val f = fileOf(SmbPaths.normalize(path))
        if (!f.isFile) throw SmbException(SmbFailureKind.NOT_FOUND, "文件不存在：" + path)
        return f.readBytes()
    }

    override fun openRandomAccess(path: String): RandomAccessBytes {
        failIfNeeded()
        val f = fileOf(SmbPaths.normalize(path))
        if (!f.isFile) throw SmbException(SmbFailureKind.NOT_FOUND, "文件不存在：" + path)
        return FileRandomAccess(f)
    }

    override fun close() {
        // closed 先置位：即使处于失败注入状态，也要能观察到清理动作
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
        File(root, SmbPaths.toWindows(normPath).replace('\\', File.separatorChar))

    private fun entryOf(parentPath: String, f: File): SmbEntry = SmbEntry(
        path = SmbPaths.child(parentPath, f.name),
        name = f.name,
        isDirectory = f.isDirectory,
        lastModifiedMs = f.lastModified().takeIf { it > 0L },
        size = if (f.isFile) f.length() else 0L,
    )
}
