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
    fun `发布时间排序下同步访问器 0 次取节点 0 次开包（落盘恢复的条目）`() = runTest {
        // 票 #74 修复轮 P1：cachedEntries 在组合期（主线程）被调，其「不做任何 IO」契约在 RELEASE_TIME 下也不能破。
        // 落盘恢复的条目没有节点：旧写法会走 nodeOf → resolve，并对压缩包开包读 ComicInfo.xml（1000 条就是 1000 次往返）。
        val root = fakeDir("root").add(fakeFile("root/单行本.cbz"))
        val backend = FakeTreeBackend(root)
        source(backend).also { it.listEntries(null, SortMode.NAME) }.close() // APP 退出，只剩落盘快照

        val reopened = source(backend)
        reopened.listEntries(null, SortMode.NAME) // 异步路径把落盘快照装进内存（它自己 0 次列目录）
        val cbz = root.childrenList.first()
        backend.resetResolveCount()
        cbz.resetRandomAccessCount()

        val cached = reopened.cachedEntries(null, SortMode.RELEASE_TIME)

        assertEquals(listOf("单行本.cbz"), cached!!.map { it.name })
        assertEquals("同步路径不得按 id 取节点", 0, backend.resolveCalls)
        assertEquals("同步路径不得开包读 ComicInfo.xml", 0, cbz.randomAccessCalls)
    }

    @Test
    fun `发布时间排序下同步访问器不开包不取节点（同会话）`() = runTest {
        // 同一个口径对「内存里有节点」的条目也成立：键没算过就不读包，用快照里的 mtime 兜底
        val root = fakeDir("root").add(fakeFile("root/单行本.cbz"))
        val backend = FakeTreeBackend(root)
        val src = source(backend)
        src.listEntries(null, SortMode.NAME)
        val cbz = root.childrenList.first()
        backend.resetResolveCount()
        cbz.resetRandomAccessCount()

        val cached = src.cachedEntries(null, SortMode.RELEASE_TIME)

        assertEquals(listOf("单行本.cbz"), cached!!.map { it.name })
        assertEquals(0, backend.resolveCalls)
        assertEquals(0, cbz.randomAccessCalls)
    }

    @Test
    fun `发布时间排序命中已算过的键时 同步访问器 0 次取节点 0 次开包`() = runTest {
        // 票 #74 第 3 轮 S1：两个压缩包的发布键与 mtime 相反序——异步枚举算过真实键后，
        // 同步首帧必须复用那份键（否则首帧顺序与异步列表相反，一帧后才自校正），且仍 0 次 resolve/开包。
        val a = FakeTreeNode("root/A.cbz", "A.cbz", isDirectory = false, lastModifiedMs = 2_000L)
            .apply { packBytes = cbzBytes(year = 2021) }
        val b = FakeTreeNode("root/B.cbz", "B.cbz", isDirectory = false, lastModifiedMs = 3_000L)
            .apply { packBytes = cbzBytes(year = 2020) }
        val backend = FakeTreeBackend(fakeDir("root").add(a, b))

        val first = source(backend)
        assertEquals(
            "异步枚举按发布键排：A(2021) 在 B(2020) 前",
            listOf("A.cbz", "B.cbz"),
            first.listEntries(null, SortMode.RELEASE_TIME).map { it.name },
        )
        first.close() // APP 退出：内存快照没了，只剩落盘快照

        val reopened = source(backend)
        assertEquals(
            "落盘恢复后异步列表仍是真实发布键序",
            listOf("A.cbz", "B.cbz"),
            reopened.listEntries(null, SortMode.RELEASE_TIME).map { it.name },
        )
        backend.resetResolveCount()
        a.resetRandomAccessCount()
        b.resetRandomAccessCount()

        val cached = reopened.cachedEntries(null, SortMode.RELEASE_TIME)

        assertEquals("同步首帧与异步列表同序（复用已算过的真实发布键）", listOf("A.cbz", "B.cbz"), cached!!.map { it.name })
        assertEquals("命中缓存：0 次取节点", 0, backend.resolveCalls)
        assertEquals("命中缓存：0 次开包", 0, a.randomAccessCalls + b.randomAccessCalls)
    }

    /** 带 ComicInfo.xml 的最小 CBZ（只给 Year；Month/Day 缺失按 1 处理） */
    private fun cbzBytes(year: Int): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        java.util.zip.ZipOutputStream(out).use { zip ->
            zip.putNextEntry(java.util.zip.ZipEntry("ComicInfo.xml"))
            zip.write("<ComicInfo><Year>$year</Year></ComicInfo>".toByteArray())
            zip.closeEntry()
            zip.putNextEntry(java.util.zip.ZipEntry("001.jpg"))
            zip.write("page".toByteArray())
            zip.closeEntry()
        }
        return out.toByteArray()
    }

    @Test
    fun `没有快照时同步访问器返回 null`() = runTest {
        val backend = FakeTreeBackend(library())

        assertEquals(null, source(backend).cachedEntries(null, SortMode.NAME))
    }
}
