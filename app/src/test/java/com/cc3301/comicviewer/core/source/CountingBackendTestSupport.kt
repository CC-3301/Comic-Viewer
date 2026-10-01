package com.cc3301.comicviewer.core.source

import com.cc3301.comicviewer.core.source.fs.FileBackend
import com.cc3301.comicviewer.core.source.fs.FsBackend
import com.cc3301.comicviewer.core.source.fs.FsNode
import com.cc3301.comicviewer.core.source.zip.RandomAccessBytes
import java.io.File

/**
 * 计数型 [FsBackend]（票 #115：DocumentTreeCoverDescentTest 与 ListEntriesPageCountTest 各一份近重复合成一份）。
 * 记录三种代价，供「枚举期零读取」「每层目录只列一次」「每层最多开一个包」这类断言当观测对象——
 * 只看返回值证明不了没有发生读取：
 *
 * - [readPaths]：被读了字节的节点（`readBytes` 与 `openRandomAccess` 都算读：开包是先读来的）；
 * - [openedPaths]：被 `openRandomAccess` 的节点（开一个包比读一份字节贵，单独记一份）；
 * - [childrenCalls]：每个目录被列了几次。
 *
 * 路径一律「相对根、带前导 `/`」：日志与断言里一眼看出是哪一层。
 */
internal class CountingBackend(val rootDir: File) : FsBackend {
    private val delegate = FileBackend(rootDir)
    private val rootPath = rootDir.absoluteFile.path

    val readPaths = mutableListOf<String>()
    val openedPaths = mutableListOf<String>()
    val childrenCalls = mutableMapOf<String, Int>()

    override val root: FsNode = CountingNode(delegate.root, this)

    override fun resolve(id: String): FsNode? = delegate.resolve(id)?.let { CountingNode(it, this) }

    fun resetCounters() {
        readPaths.clear()
        openedPaths.clear()
        childrenCalls.clear()
    }

    fun noteRead(id: String) {
        readPaths += relativePath(id)
    }

    fun noteOpen(id: String) {
        val path = relativePath(id)
        openedPaths += path
        readPaths += path
    }

    fun noteChildren(id: String) {
        val path = relativePath(id)
        childrenCalls[path] = (childrenCalls[path] ?: 0) + 1
    }

    private fun relativePath(id: String): String =
        id.removePrefix(rootPath).replace(File.separatorChar, '/')
}

internal class CountingNode(private val delegate: FsNode, private val counter: CountingBackend) : FsNode {
    override val id: String get() = delegate.id
    override val name: String get() = delegate.name
    override val isDirectory: Boolean get() = delegate.isDirectory
    override val lastModifiedMs: Long? get() = delegate.lastModifiedMs
    override val imageUri: String get() = delegate.imageUri

    override fun children(): List<FsNode> {
        counter.noteChildren(delegate.id)
        return delegate.children().map { CountingNode(it, counter) }
    }

    override fun parent(): FsNode? = delegate.parent()?.let { CountingNode(it, counter) }

    override fun readBytes(): ByteArray {
        counter.noteRead(delegate.id)
        return delegate.readBytes()
    }

    override fun openRandomAccess(): RandomAccessBytes {
        counter.noteOpen(delegate.id)
        return delegate.openRandomAccess()
    }
}
