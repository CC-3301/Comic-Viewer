package com.cc3301.comicviewer.core.source

import com.cc3301.comicviewer.core.source.fs.FsBackend
import com.cc3301.comicviewer.core.source.fs.FsNode
import com.cc3301.comicviewer.core.source.zip.RandomAccessBytes
import java.util.concurrent.atomic.AtomicInteger

private const val DEFAULT_MTIME: Long = 1_700_000_000_000L

/**
 * 内存目录树后端（票 #30 测试夹具）：造「容器 / 目录书 / 纯目录」层级，并统计每层目录被列了几次
 * （列目录次数就是 SMB 的 `list` 往返、SAF 的 provider IPC，本票的验收指标），
 * 可对指定目录注入列目录失败、可让节点没有 mtime、可改目录 mtime、可统计 close 次数（会话是否被释放）。
 *
 * [resolve] 返回带**当前** mtime 的新节点视图，而 [root] 是构造期快照——真实后端就是这个形状
 * （FileNode/SafNode/SmbNode 都是每次 resolve 新建、mtime 在构造时取），用来区分「构造期快照」与「现取的新鲜值」。
 *
 * 先例：ListEntriesPageCountTest 的 CountingBackend（文件系统后端，只能统计读字节）；
 * 本夹具把统计点放在 `children()` 上，用于锁定列表缓存与探测失败策略。
 */
class FakeTreeBackend(override val root: FakeTreeNode) : FsBackend, AutoCloseable {

    private val byId = mutableMapOf<String, FakeTreeNode>()

    private val closeCounter = AtomicInteger(0)

    /** 该后端被 close 的次数（生产里 SMB 后端 close = 关掉会话）；原子计数：用例会跨线程轮询它 */
    val closeCount: Int get() = closeCounter.get()

    init {
        index(root)
    }

    override fun resolve(id: String): FsNode? = byId[id]?.let(::ResolvedNode)

    override fun close() {
        closeCounter.incrementAndGet()
    }

    private fun index(node: FakeTreeNode) {
        byId[node.id] = node
        node.childrenList.forEach { index(it) }
    }

    /** 重取出来的节点视图：id/children 都指向同一棵树的同一个节点，只有 mtime 取当前值 */
    private class ResolvedNode(private val delegate: FakeTreeNode) : FsNode {
        override val id: String get() = delegate.id
        override val name: String get() = delegate.name
        override val isDirectory: Boolean get() = delegate.isDirectory
        override val lastModifiedMs: Long? get() = delegate.currentMtime
        override val imageUri: String get() = delegate.id
        override fun children(): List<FsNode> = delegate.children()
        override fun parent(): FsNode? = delegate.parent()
        override fun readBytes(): ByteArray = delegate.readBytes()
        override fun openRandomAccess(): RandomAccessBytes = delegate.openRandomAccess()
    }
}

/**
 * 内存节点：目录可挂子节点；文件节点只用来表达「目录里有没有图/包」（`isBook` 判定只看名字扩展名）。
 * id 约定为「相对根路径」形式（`root/第001话/001.jpg`），便于断言时认人。
 */
class FakeTreeNode(
    override val id: String,
    override val name: String,
    override val isDirectory: Boolean,
    override val lastModifiedMs: Long? = DEFAULT_MTIME,
) : FsNode {

    val childrenList = mutableListOf<FakeTreeNode>()

    /**
     * 目录当前的修改时间（用例改它 = 文件被改动）。
     * [lastModifiedMs] 是本节点实例上的**快照**（真实后端如 FileNode/SafNode 的 mtime 都是构造期 val），
     * [FakeTreeBackend.resolve] 会返回带当前值的新视图，两者不同才能表达「构造期快照 vs 现取的新鲜值」。
     */
    var currentMtime: Long? = lastModifiedMs

    private val childrenCallCount = AtomicInteger(0)

    /** `children()` 被调用次数：每层目录只列一次（列目录往返计数）的断言对象；原子计数：探测是并发的 */
    val childrenCalls: Int get() = childrenCallCount.get()

    /** 非 null 时列本目录抛它（探测失败降级 / 传输故障冒泡两条边界） */
    var failChildrenWith: Throwable? = null

    /** 父目录（[add] 时回填）：上一本/下一本要从书的父目录取邻位 */
    private var parentRef: FakeTreeNode? = null

    override val imageUri: String get() = id

    fun add(vararg kids: FakeTreeNode): FakeTreeNode = apply {
        kids.forEach { it.parentRef = this }
        childrenList += kids
    }

    override fun children(): List<FsNode> {
        childrenCallCount.incrementAndGet()
        failChildrenWith?.let { throw it }
        return childrenList
    }

    override fun parent(): FsNode? = parentRef

    override fun readBytes(): ByteArray = throw UnsupportedOperationException("本夹具不读字节")

    override fun openRandomAccess(): RandomAccessBytes = throw UnsupportedOperationException("本夹具不开包")
}

/** 目录节点；[mtime] 传 null 用来模拟「拿不到修改时间的容器」（SMB 共享根） */
fun fakeDir(id: String, mtime: Long? = DEFAULT_MTIME): FakeTreeNode =
    FakeTreeNode(id = id, name = id.substringAfterLast('/'), isDirectory = true, lastModifiedMs = mtime)

/** 文件节点：名字带图片/压缩包扩展名即参与「是书还是容器」判定 */
fun fakeFile(id: String): FakeTreeNode =
    FakeTreeNode(id = id, name = id.substringAfterLast('/'), isDirectory = false)
