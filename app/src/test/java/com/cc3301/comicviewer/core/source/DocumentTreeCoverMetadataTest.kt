package com.cc3301.comicviewer.core.source

import java.nio.file.Files
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 取封面字节时**不再为元数据多问一次来源**。
 *
 * 判据是 [FakeTreeBackend.resolveCalls]（按 id 取节点的次数）：生产里每一次 `resolve`
 * 在网络来源上就是一次 stat/PROPFIND（WebDAV = Depth:0 PROPFIND，SMB = folderExists + 取文件信息）。
 * 列目录时已经把「这条是文件夹还是文件、改过没有」拿在手里，**目录条目**的封面就没有理由再问一遍；
 * 单个文件条目（图片 / 压缩包）**照旧**`resolve`：它的 mtime 就是「文件自己改过没有」的唯一信号
 * （原地换掉内容不会动父目录的时间），由 `DocumentTreeEnumerationPerformanceTest` 的
 * 「压缩包换内容后不残留旧封面缓存文件」那一钉住。
 *
 * 三条路各一条用例：
 * - 本层本次会话列过 → 用会话快照里的元数据；
 * - 本层本次会话没列过、但**落盘快照**在（退出 APP 再进来）→ 用落盘快照里的元数据；
 * - 两者都没有 → 照旧 `resolve` 一次（回落路径，仍然取得到封面）。
 */
class DocumentTreeCoverMetadataTest {

    private val dir = Files.createTempDirectory("cover-metadata").toFile()

    private fun source(backend: FakeTreeBackend, connId: Long = 7L) = DocumentTreeSource(
        backend = backend,
        progressStore = InMemoryProgressStore(),
        listingSnapshots = ListingSnapshotStore(dir, connId),
    )

    /** 「一个子文件夹一本书」的库：根 + 一个目录书（探测期会记住它是目录，且首图就在手里） */
    private fun library(): FakeTreeNode {
        val bookDir = fakeDir("root/第001话")
        bookDir.add(fakeFile("root/第001话/001.jpg").apply { bytes = "cover-001".toByteArray() })
        return fakeDir("root").add(bookDir)
    }

    @Test
    fun `列过这一层之后取封面 不再按 id 取节点`() = runTest {
        val root = library()
        val backend = FakeTreeBackend(root)
        val source = source(backend)
        val book = source.listEntries(null, SortMode.NAME).single()

        backend.resetResolveCount()

        assertEquals("目录书的封面是探测期记住的首图", "cover-001", String(source.coverBytes(book.id)!!))
        assertEquals("列目录时已拿到「是目录 + mtime」，取封面不得再问一次来源", 0, backend.resolveCalls)
    }

    @Test
    fun `单个文件条目仍问一次来源 原地换内容才能被发现`() = runTest {
        val root = fakeDir("root").add(fakeFile("root/001.cbz"))
        val backend = FakeTreeBackend(root)
        val source = source(backend)
        val archive = source.listEntries(null, SortMode.NAME).single()

        backend.resetResolveCount()

        // 压缩包封面要开包，本夹具不开包 → 返回 null；本条只问「有没有问过来源」
        assertNull(source.coverBytes(archive.id))
        assertEquals("文件条目的 mtime 只能现问来源（目录条目的才省）", 1, backend.resolveCalls)
    }

    @Test
    fun `落盘快照恢复之后取封面 同样不按 id 取节点`() = runTest {
        val root = library()
        val backend = FakeTreeBackend(root)
        source(backend).also { it.listEntries(null, SortMode.NAME) }.close() // 会话关闭 = APP 退出

        backend.resetResolveCount()
        val reopened = source(backend)
        val restored = reopened.snapshotEntries(null, SortMode.NAME)!!.single()

        assertEquals("落盘快照里也留着「是目录 + mtime」", "cover-001", String(reopened.coverBytes(restored.id)!!))
        assertEquals("恢复后取封面不得先 stat 一次（升级后第一次进目录会重列一次，之后就不该再付）", 0, backend.resolveCalls)
    }

    @Test
    fun `没列过这一层时取封面 回落到按 id 取节点 且仍然取得到`() = runTest {
        val root = library()
        val backend = FakeTreeBackend(root)
        val source = source(backend)

        assertEquals("没有元数据可用时照旧能取到封面", "cover-001", String(source.coverBytes("root/第001话")!!))
        assertEquals("这一条只能真问一次来源（回落路径，不是错）", 1, backend.resolveCalls)
    }

    @Test
    fun `同一张封面第二次取 命中字节缓存 一次节点都不取`() = runTest {
        val root = library()
        val backend = FakeTreeBackend(root)
        val source = source(backend)
        val book = source.listEntries(null, SortMode.NAME).single()
        source.coverBytes(book.id)

        backend.resetResolveCount()

        assertEquals("cover-001", String(source.coverBytes(book.id)!!))
        assertEquals("字节缓存命中连节点都不必构造", 0, backend.resolveCalls)
    }

    @Test
    fun `下拉刷新之后元数据一并失效 重新列目录再取封面`() = runTest {
        val root = library()
        val backend = FakeTreeBackend(root)
        val source = source(backend)
        val book = source.listEntries(null, SortMode.NAME).single()

        source.invalidateListCache(null)
        backend.resetResolveCount()

        assertEquals("刷新后元数据一并清掉，本条又得问一次来源", "cover-001", String(source.coverBytes(book.id)!!))
        assertEquals("元数据没了就只能真问一次来源", 1, backend.resolveCalls)

        source.listEntries(null, SortMode.NAME)
        backend.resetResolveCount()
        assertEquals("重新列过之后又不必再问", "cover-001", String(source.coverBytes(book.id)!!))
        assertEquals(0, backend.resolveCalls)
    }
}
