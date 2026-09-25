package com.cc3301.comicviewer.core.source

import java.nio.file.Files
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 增量重探（票 #75）：容器 mtime 变化 / 跨重启首次进入时，只重探**新增或自身 mtime 变化**的子目录，
 * 其余行沿用上次探测结论、不新发探测请求；下拉更新仍是全量重列 + 全量重探。
 *
 * 计数点与票 #51/#74 同一处（[FakeTreeBackend]）：一次 `children()` = 一次 list/PROPFIND 往返，
 * 因此「本次探测请求数」就是各子目录 `childrenCalls` 的增量。
 * 「父层这次列出来的子目录 mtime」由夹具 [FakeTreeNode.children] 交出的节点视图决定（真实后端每次列目录都当场取 mtime），
 * 用例改 `currentMtime` 即等于「这个目录自己被动过」。
 *
 * 增量判定本身是纯函数（第一个用例直接钉 [canReuseProbe]）；两段式读取的「先快照、后新鲜」在本文件钉来源侧
 * （第一段 0 请求、两段内容可区分），界面侧的顺序由 `ListCompositionTest` 钉；打点口径见最后两个用例。
 */
class DocumentTreeIncrementalProbeTest {

    private fun documentSource(backend: FakeTreeBackend) =
        DocumentTreeSource(backend = backend, progressStore = InMemoryProgressStore())

    /** 跨重启用的来源：带落盘快照表（票 #74），同一条连接 */
    private fun persistedSource(backend: FakeTreeBackend, dir: java.io.File) = DocumentTreeSource(
        backend = backend,
        progressStore = InMemoryProgressStore(),
        listingSnapshots = ListingSnapshotStore(dir, connectionId = 7L),
    )

    /** 「一个子文件夹一本书」的一层：父目录 + [count] 本子目录书 */
    private fun bookLibrary(count: Int): Pair<FakeTreeNode, List<FakeTreeNode>> {
        val root = fakeDir("root")
        val books = (1..count).map { i -> bookFolder("root/第%04d话".format(i), i) }
        books.forEach { root.add(it) }
        return root to books
    }

    /** 一本「子目录书」：目录里直接是图（本层只有图片 → 是书） */
    private fun bookFolder(id: String, index: Int): FakeTreeNode =
        fakeDir(id).add(fakeFile("$id/%03d.jpg".format(index)))

    // ---------- 增量判定（纯函数） ----------

    @Test
    fun `增量判定 只看子目录自身 mtime 与上次探测是否成功`() {
        val previous = ProbeConclusion(mtimeMs = 1_700_000_000_000L, probed = true)

        assertTrue(
            "同一条目且它自己没变 → 沿用上次结论",
            canReuseProbe(previous, fakeDir("root/第001话", mtime = 1_700_000_000_000L)),
        )
        assertFalse(
            "没有旧结论（新增，或改名后按 id 查不到）→ 必须探测",
            canReuseProbe(null, fakeDir("root/第001话", mtime = 1_700_000_000_000L)),
        )
        assertFalse(
            "子目录自身 mtime 变了（它内部动过）→ 必须重探",
            canReuseProbe(previous, fakeDir("root/第001话", mtime = 1_700_000_000_001L)),
        )
        assertFalse(
            "上次探测失败的结论不复用（#51：下次必须重试）",
            canReuseProbe(previous.copy(probed = false), fakeDir("root/第001话", mtime = 1_700_000_000_000L)),
        )
        assertTrue(
            "两边都取不到 mtime：视同没变（与 #51 F3 的会话缓存口径一致）",
            canReuseProbe(ProbeConclusion(mtimeMs = null, probed = true), fakeDir("root/第001话", mtime = null)),
        )
    }

    // ---------- 进入已缓存层：只重探新增 / 变化的行 ----------

    @Test
    fun `容器 mtime 变化后新增 1 个子目录只探测它 其余 1000 行沿用旧结论`() = runTest {
        val (root, books) = bookLibrary(count = 1000)
        val source = documentSource(FakeTreeBackend(root))

        assertEquals("前置：1000 本都在列表里", 1000, source.listEntries(null, SortMode.NAME).size)
        val probedFirst = books.map { it.childrenCalls }
        assertEquals("前置：首次枚举整层探测一遍", 1, probedFirst.distinct().single())

        // 维护者场景：1000+ 目录里新增 1 本（父目录 mtime 随之变化）
        root.currentMtime = root.currentMtime!! + 60_000
        val added = bookFolder("root/第1001话", 1001)
        root.add(added)

        val entries = source.listEntries(null, SortMode.NAME)

        assertEquals("列表反映新增条目", 1001, entries.size)
        assertEquals("只列一次目录（而不是整层重列）", 2, root.childrenCalls)
        assertEquals("本次探测请求数 = 1（就是新增的那一条）", 1, added.childrenCalls)
        assertEquals("其余 1000 行一次探测都不发", probedFirst, books.map { it.childrenCalls })
    }

    @Test
    fun `子目录自身 mtime 变化时只重探该行 该行判定与封面随之更新`() = runTest {
        // 第001话 先是空目录（本层无图 = 容器），拷进图片后它自己变成书
        val root = fakeDir("root")
        val changed = fakeDir("root/第001话").also { root.add(it) }
        val untouched = fakeDir("root/第002话").also { root.add(it) }
        val source = documentSource(FakeTreeBackend(root))

        assertFalse(
            "前置：空目录是容器",
            source.listEntries(null, SortMode.NAME).first { it.name == "第001话" }.isBook,
        )

        // 拷进图片（子目录自身 mtime 变）；父层这次也动过（父目录 mtime 变）——增量重探发生在父层失效后的那次列目录里
        changed.add(fakeFile("root/第001话/001.jpg"))
        changed.currentMtime = changed.currentMtime!! + 60_000
        root.currentMtime = root.currentMtime!! + 60_000

        val second = source.listEntries(null, SortMode.NAME)

        val row = second.first { it.name == "第001话" }
        assertTrue("该行重探后判定为书", row.isBook)
        assertEquals(
            "封面指向本层首图（探测期顺手拿到，不额外列一次该目录）",
            "root/第001话/001.jpg",
            row.coverUri,
        )
        assertEquals("只重探变化的那一行", 2, changed.childrenCalls)
        assertEquals("未变化的行 0 次新探测", 1, untouched.childrenCalls)
        assertEquals("且只列一次父目录", 2, root.childrenCalls)
    }

    @Test
    fun `删除与改名的条目 1 次列目录即可反映 其余行不重探`() = runTest {
        val root = fakeDir("root")
        val removed = bookFolder("root/第001话", 1).also { root.add(it) }
        val renamed = bookFolder("root/第002话", 2).also { root.add(it) }
        val kept = bookFolder("root/第003话", 3).also { root.add(it) }
        val source = documentSource(FakeTreeBackend(root))
        source.listEntries(null, SortMode.NAME)

        // 删掉一本、把另一本改名（改名 = 旧 id 消失 + 新 id 出现）
        root.childrenList.remove(removed)
        root.childrenList.remove(renamed)
        val renamedNow = bookFolder("root/第002话(改)", 2)
        root.add(renamedNow)
        root.currentMtime = root.currentMtime!! + 60_000

        val entries = source.listEntries(null, SortMode.NAME)

        assertEquals(
            "1 次列目录即可反映删除与改名",
            setOf("第002话(改)", "第003话"),
            entries.map { it.name }.toSet(),
        )
        assertEquals("只列一次父目录", 2, root.childrenCalls)
        assertEquals("改名后的行按新 id 探测一次", 1, renamedNow.childrenCalls)
        assertEquals("未变化的行沿用旧结论", 1, kept.childrenCalls)
    }

    // ---------- 跨重启首次进入（配合 #74 的落盘快照） ----------

    @Test
    fun `跨重启首次进入用落盘快照做增量重探 只探新增与自身变化的行`() = runTest {
        val dir = Files.createTempDirectory("incremental-probe").toFile()
        val root = fakeDir("root")
        val kept = bookFolder("root/第001话", 1).also { root.add(it) }
        val changed = bookFolder("root/第002话", 2).also { root.add(it) }
        val backend = FakeTreeBackend(root)
        persistedSource(backend, dir).also { it.listEntries(null, SortMode.NAME) }.close() // APP 退出

        // 一个新子目录 + 一个子目录内部动过，父层 mtime 随之变化
        root.currentMtime = root.currentMtime!! + 60_000
        changed.currentMtime = changed.currentMtime!! + 60_000
        val added = bookFolder("root/第003话", 3)
        root.add(added)

        val listsBefore = root.childrenCalls
        val entries = persistedSource(backend, dir).listEntries(null, SortMode.NAME)

        assertEquals(setOf("第001话", "第002话", "第003话"), entries.map { it.name }.toSet())
        assertEquals("mtime 变了：只列一次目录（先用落盘快照当显示来源）", listsBefore + 1, root.childrenCalls)
        assertEquals("未变化的行沿用快照里的结论：0 次探测", 1, kept.childrenCalls)
        assertEquals("自身 mtime 变化的那一行重探", 2, changed.childrenCalls)
        assertEquals("新增的行探测一次", 1, added.childrenCalls)
    }

    // ---------- 两段式读取：先快照、后新鲜（票 #75 AC4） ----------

    @Test
    fun `跨重启且 mtime 已变 先交出落盘快照 再交出重列结果`() = runTest {
        val dir = Files.createTempDirectory("two-phase").toFile()
        val root = fakeDir("root")
        val kept = bookFolder("root/第001话", 1).also { root.add(it) }
        val backend = FakeTreeBackend(root)
        persistedSource(backend, dir).also { it.listEntries(null, SortMode.NAME) }.close() // APP 退出

        // 新增一本 + 父层 mtime 变化
        root.currentMtime = root.currentMtime!! + 60_000
        val added = bookFolder("root/第002话", 2)
        root.add(added)

        val listsBefore = root.childrenCalls
        val reopened = persistedSource(backend, dir)

        val snapshot = reopened.snapshotEntries(null, SortMode.NAME)
        assertEquals("第一段 = 落盘快照里的旧内容（重列前）", listOf("第001话"), snapshot?.map { it.name })
        assertEquals("第一段不发任何列目录与探测", listsBefore, root.childrenCalls)

        val fresh = reopened.listEntries(null, SortMode.NAME)
        assertEquals("第二段 = 重列后的新内容（与第一段可区分）", listOf("第001话", "第002话"), fresh.map { it.name })
        assertEquals("第二段恰好 1 次列目录", listsBefore + 1, root.childrenCalls)
        assertEquals("未变化的行沿用快照里的结论：0 次新探测", 1, kept.childrenCalls)
        assertEquals("新增的行探测一次", 1, added.childrenCalls)
    }

    // ---------- 全量路径与 #51 的失败重试 ----------

    @Test
    fun `下拉更新仍是全量重列 全量重探`() = runTest {
        val (root, books) = bookLibrary(count = 3)
        val source = documentSource(FakeTreeBackend(root))
        source.listEntries(null, SortMode.NAME)
        val probedFirst = books.map { it.childrenCalls }

        source.invalidateListCache(null) // 下拉更新：清该容器的内存与落盘快照
        root.currentMtime = root.currentMtime!! + 60_000
        source.listEntries(null, SortMode.NAME)

        assertEquals("清过快照：没有可复用的结论 → 整层重列", 2, root.childrenCalls)
        assertEquals(
            "且整层重探（每行各 +1，而不是沿用旧结论）",
            probedFirst.map { it + 1 },
            books.map { it.childrenCalls },
        )
    }

    @Test
    fun `增量路径下探测失败的行仍会重试 成功的行不重探`() = runTest {
        val root = fakeDir("root")
        val ok = bookFolder("root/第001话", 1).also { root.add(it) }
        val broken = bookFolder("root/第002话", 2).also { root.add(it) }
        broken.failChildrenWith = IllegalStateException("目录不可读")
        val source = documentSource(FakeTreeBackend(root))

        assertFalse(
            "前置：探测失败降级为容器（#51 口径）",
            source.listEntries(null, SortMode.NAME).first { it.name == "第002话" }.isBook,
        )

        root.currentMtime = root.currentMtime!! + 60_000
        root.add(bookFolder("root/第003话", 3))
        broken.failChildrenWith = null

        val second = source.listEntries(null, SortMode.NAME)

        assertTrue("失败的行在增量路径上重试后恢复判定", second.first { it.name == "第002话" }.isBook)
        assertEquals("失败的那条重试恰好一次", 2, broken.childrenCalls)
        assertEquals("成功的未变化行不重探", 1, ok.childrenCalls)
    }

    // ---------- 打点（票 #75 追加要求） ----------

    @Test
    fun `打点口径 命中快照即 0 次列目录 mtime 已变则记真列目录`() = runTest {
        // 这三个数字只进 logcat（`adb logcat -s ComicViewerPerf`），因此单测直接调 enumerateEntries 读它们。
        val dir = Files.createTempDirectory("enumeration-stats").toFile()
        val (root, _) = bookLibrary(count = 2)
        val source = persistedSource(FakeTreeBackend(root), dir)

        val first = EnumerationStats()
        source.enumerateEntries(null, SortMode.NAME, first)
        assertEquals("首次进入：没有快照可用 → 本次真列目录", SnapshotHit.NONE, first.hit)
        assertEquals(1, first.childrenCalls)
        assertEquals("逐个子目录探测", 2, first.probes)
        assertEquals("没有可复用的结论", 0, first.reused)

        val cached = EnumerationStats()
        source.enumerateEntries(null, SortMode.NAME, cached)
        assertEquals("mtime 未变：命中会话内存快照", SnapshotHit.MEMORY, cached.hit)
        assertEquals("命中快照 ⇒ 本次 0 次列目录（两者严格等价）", 0, cached.childrenCalls)
        assertEquals(0, cached.probes)

        // 跨重启（内存表空、落盘快照在、mtime 未变）：命中落盘快照
        val disk = EnumerationStats()
        persistedSource(FakeTreeBackend(root), dir).enumerateEntries(null, SortMode.NAME, disk)
        assertEquals("跨重启命中落盘快照", SnapshotHit.DISK, disk.hit)
        assertEquals("命中落盘快照 ⇒ 本次 0 次列目录、0 次探测", 0, disk.childrenCalls)
        assertEquals(0, disk.probes)

        // mtime 变化：本次确实真列目录 → 不能打成命中快照（旧写法在比对 mtime 之前就置 memory）
        root.currentMtime = root.currentMtime!! + 60_000
        root.add(bookFolder("root/第003话", 3))
        val relisted = EnumerationStats()
        source.enumerateEntries(null, SortMode.NAME, relisted)
        assertEquals("mtime 已变：本次真列目录", SnapshotHit.NONE, relisted.hit)
        assertEquals(1, relisted.childrenCalls)
        assertEquals("只探新增的那一条", 1, relisted.probes)
        assertEquals("其余两条沿用旧结论", 2, relisted.reused)
    }

    @Test
    fun `打点行如实反映三条命中来源与三个计数`() {
        val relisted = enumerationLogLine(
            "root/第001话",
            SortMode.NAME,
            1001,
            EnumerationStats(hit = SnapshotHit.NONE, childrenCalls = 1, probes = 1, reused = 1000),
            9,
        )
        assertTrue("真列目录：snapshotSource=none", relisted.contains("snapshotSource=none"))
        assertTrue(
            "本次列目录 1 次 / 探测 1 条 / 复用 1000 条",
            relisted.contains("childrenCalls=1") && relisted.contains("probes=1") && relisted.contains("reused=1000"),
        )
        assertTrue(
            "容器与排序方式照常在打点里",
            relisted.contains("container=root/第001话") && relisted.contains("sort=NAME"),
        )
        assertTrue(
            "会话内存快照命中：snapshotSource=memory",
            enumerationLogLine(null, SortMode.NAME, 3, EnumerationStats(hit = SnapshotHit.MEMORY), 2)
                .contains("snapshotSource=memory"),
        )
        assertTrue(
            "落盘快照命中：snapshotSource=disk（不得被打成 false）",
            enumerationLogLine(null, SortMode.NAME, 3, EnumerationStats(hit = SnapshotHit.DISK), 2)
                .contains("snapshotSource=disk"),
        )
        assertFalse(
            "枚举行不得再用邻位两行的 `snapshot=` 键（同名两义）",
            relisted.contains("snapshot="),
        )
    }
}
