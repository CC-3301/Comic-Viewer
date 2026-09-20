package com.cc3301.comicviewer.core.source

import java.nio.file.Files
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 列表快照落盘与跨实例命中（票 #74）：退出 APP（会话来源被释放）后再次进入同一目录，
 * 只要目录 mtime 没变就 0 次列目录、0 次探测；mtime 变化 / TTL 过期 / 手动刷新则重新列目录。
 *
 * 用 [FakeTreeBackend] 统计每层目录的列目录次数（生产里 = SMB list / SAF provider IPC）与
 * 取节点次数（mtime 比对），新实例即「APP 退出后再打开」。
 */
class DocumentTreeListingSnapshotTest {

    private val dir = Files.createTempDirectory("listing-snapshots").toFile()

    private var clock = 1_000_000L

    private fun source(backend: FakeTreeBackend, connId: Long = 7L) = DocumentTreeSource(
        backend = backend,
        progressStore = InMemoryProgressStore(),
        listingSnapshots = ListingSnapshotStore(dir, connId, nowMs = { clock }),
    )

    /** 「一个子文件夹一本书」的库：根 + 一个容器 */
    private fun library() = fakeDir("root")
        .add(fakeDir("root/第001话").add(fakeFile("root/第001话/001.jpg")))

    @Test
    fun `退出 APP 再进入同一目录 命中落盘快照 0 次列目录 0 次探测`() = runTest {
        val root = library()
        val backend = FakeTreeBackend(root)
        source(backend).also { it.listEntries(null, SortMode.NAME) }.close() // 会话关闭 = APP 退出

        val book = root.childrenList.first()
        backend.resetResolveCount()
        val reopened = source(backend)

        assertEquals(listOf("第001话"), reopened.listEntries(null, SortMode.NAME).map { it.name })
        assertEquals("命中落盘快照：整层不再列目录", 1, root.childrenCalls)
        assertEquals("命中落盘快照：不再逐个子目录探测", 1, book.childrenCalls)
        assertEquals("mtime 一致：只花一次取节点比对", 1, backend.resolveCalls)
    }

    @Test
    fun `父目录 mtime 变化后落盘快照失效并重新列目录`() = runTest {
        val root = library()
        val backend = FakeTreeBackend(root)
        source(backend).also { it.listEntries(null, SortMode.NAME) }.close()

        root.currentMtime = root.lastModifiedMs!! + 60_000
        root.add(fakeDir("root/第002话").add(fakeFile("root/第002话/001.jpg")))

        val reopened = source(backend)
        assertEquals(
            "mtime 变了 → 重新列目录（配上 #75 的增量重探）",
            listOf("第001话", "第002话"),
            reopened.listEntries(null, SortMode.NAME).map { it.name },
        )
        assertEquals("重新列目录确实发生", 2, root.childrenCalls)
    }

    @Test
    fun `取不到 mtime 的容器命中落盘快照 连一次取节点都不发`() = runTest {
        val root = fakeDir("root", mtime = null).add(fakeDir("root/第001话", mtime = null))
        val backend = FakeTreeBackend(root)
        source(backend).also { it.listEntries(null, SortMode.NAME) }.close()

        backend.resetResolveCount()
        val book = root.childrenList.first()
        val reopened = source(backend)

        assertEquals(listOf("第001话"), reopened.listEntries(null, SortMode.NAME).map { it.name })
        assertEquals("无 mtime 的层：0 次列目录", 1, root.childrenCalls)
        assertEquals("无 mtime 的层：0 次探测", 1, book.childrenCalls)
        assertEquals("无 mtime 的层：mtime 不比，因此一次取节点都没有", 0, backend.resolveCalls)
    }

    @Test
    fun `离线取 mtime 失败时仍用落盘快照把列表显示出来`() = runTest {
        val root = library()
        val backend = FakeTreeBackend(root)
        source(backend).also { it.listEntries(null, SortMode.NAME) }.close()

        backend.failResolveWith = IllegalStateException("服务器不可达") // 取 mtime 失败
        val offline = source(backend)

        assertEquals(
            "离线可见上次目录，不报「加载失败」",
            listOf("第001话"),
            offline.listEntries(null, SortMode.NAME).map { it.name },
        )
        assertEquals("离线时不再去列目录/探测", 1, root.childrenCalls)
    }

    @Test
    fun `落盘快照超过 7 天 TTL 后强制重列一次`() = runTest {
        val root = library()
        val backend = FakeTreeBackend(root)
        source(backend).also { it.listEntries(null, SortMode.NAME) }.close()

        clock += 7L * 24 * 60 * 60 * 1000 + 1
        root.add(fakeDir("root/第002话").add(fakeFile("root/第002话/001.jpg")))

        val reopened = source(backend)
        assertEquals(
            "TTL 过期 → 即使 mtime 没变也重列",
            listOf("第001话", "第002话"),
            reopened.listEntries(null, SortMode.NAME).map { it.name },
        )
        assertEquals(2, root.childrenCalls)
    }

    @Test
    fun `下拉更新同时清落盘快照 新实例重新列目录`() = runTest {
        val root = library()
        val backend = FakeTreeBackend(root)
        val first = source(backend)
        first.listEntries(null, SortMode.NAME)

        first.invalidateListCache(null)

        val reopened = source(backend)
        reopened.listEntries(null, SortMode.NAME)
        assertEquals("显式失效（下拉更新）必须同时清落盘快照", 2, root.childrenCalls)
    }

    @Test
    fun `同一会话内离线时内存快照仍可用`() = runTest {
        val root = library()
        val backend = FakeTreeBackend(root)
        val src = source(backend)
        src.listEntries(null, SortMode.NAME)

        backend.failResolveWith = IllegalStateException("服务器不可达")

        assertEquals(
            "同一次会话里服务器掉线：已列过的层照样显示",
            listOf("第001话"),
            src.listEntries(null, SortMode.NAME).map { it.name },
        )
        assertEquals("离线命中内存快照：不再列目录", 1, root.childrenCalls)
    }

    @Test
    fun `别的连接的快照与本连接互不影响`() = runTest {
        val root = library()
        val backend = FakeTreeBackend(root)
        source(backend, connId = 7).also { it.listEntries(null, SortMode.NAME) }

        // 连接 8 的同样目录内容：自己的快照另存一份，同样 1 次列目录
        val other = source(backend, connId = 8)
        other.listEntries(null, SortMode.NAME)
        assertEquals("连接 8 自己的快照（不受连接 7 影响）", 2, root.childrenCalls)
    }

    @Test
    fun `同步快照访问器不列目录 也不发 mtime 往返`() = runTest {
        val root = library()
        val backend = FakeTreeBackend(root)
        val src = source(backend)
        src.listEntries(null, SortMode.NAME)
        backend.resetResolveCount()

        val cached = src.cachedEntries(null, SortMode.MODIFIED_TIME)

        assertEquals(listOf("第001话"), cached!!.map { it.name })
        assertEquals("同步读快照：0 次列目录", 1, root.childrenCalls)
        assertEquals("同步读快照：0 次取节点（不等 mtime 比对）", 0, backend.resolveCalls)
    }

    @Test
    fun `没有快照时同步访问器返回 null`() = runTest {
        val backend = FakeTreeBackend(library())

        assertEquals(null, source(backend).cachedEntries(null, SortMode.NAME))
    }
}
