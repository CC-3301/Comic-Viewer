package com.cc3301.comicviewer.core.source

import java.nio.file.Files
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 进目录的失效判据 = **每次真列 1 次本层目录 + 按子项自身 mtime 增量比对**：
 * 只重探新增 / 自身 mtime 变化的子目录，其余行沿用上次结论、不新发探测请求；
 * 下拉更新**不清**列表快照（它是比对基线），因此仍是 0 次探测。
 *
 * 有意**不**看容器自己的 mtime：SMB 服务器不更新父目录的 mtime，往目录里加一本书、给书加页都不会动它
 * ⇒ 拿它当失效判据等于「永远判定没变」。本文件里所有用例都不再动父目录的 mtime——这正是要钉住的形状。
 *
 * 计数点同在一处（[FakeTreeBackend]）：一次 `children()` = 一次 list/PROPFIND 往返，
 * 因此「本次探测请求数」就是各子目录 `childrenCalls` 的增量。
 * 「父层这次列出来的子目录 mtime」由夹具 [FakeTreeNode.children] 交出的节点视图决定（真实后端每次列目录都当场取 mtime），
 * 用例改 `currentMtime` 即等于「这个目录自己被动过」。
 *
 * 增量判定本身是纯函数（第一个用例直接钉 [canReuseProbe]）；打点口径见最后两个用例。
 */
class DocumentTreeIncrementalProbeTest {

    private fun documentSource(backend: FakeTreeBackend) =
        DocumentTreeSource(backend = backend, progressStore = InMemoryProgressStore())

    /** 跨重启用的来源：带落盘快照表，同一条连接 */
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

    // ---------- 进入已列举过的层：只重探新增 / 变化的行 ----------

    @Test
    fun `父层 mtime 不动也能看到新增 1 个子目录 其余 1000 行沿用旧结论`() = runTest {
        val (root, books) = bookLibrary(count = 1000)
        val source = documentSource(FakeTreeBackend(root))

        assertEquals("前置：1000 本都在列表里", 1000, source.listEntries(null, SortMode.NAME).size)
        val probedFirst = books.map { it.childrenCalls }
        assertEquals("前置：首次枚举整层探测一遍", 1, probedFirst.distinct().single())

        // 实际场景（设备上「新增书看不到」的形状）：往这层加一本书，**父目录自己的 mtime 一动不动**
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

        // 拷进图片（子目录自身 mtime 变）
        changed.add(fakeFile("root/第001话/001.jpg"))
        changed.currentMtime = changed.currentMtime!! + 60_000

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

    // ---------- 跨重启：落盘快照是比对基线 ----------

    @Test
    fun `跨重启首次进入用落盘快照做增量重探 只探新增与自身变化的行`() = runTest {
        val dir = Files.createTempDirectory("incremental-probe").toFile()
        val root = fakeDir("root")
        val kept = bookFolder("root/第001话", 1).also { root.add(it) }
        val changed = bookFolder("root/第002话", 2).also { root.add(it) }
        val backend = FakeTreeBackend(root)
        persistedSource(backend, dir).also { it.listEntries(null, SortMode.NAME) }.close() // APP 退出

        // 一个新子目录 + 一个子目录内部动过
        changed.currentMtime = changed.currentMtime!! + 60_000
        val added = bookFolder("root/第003话", 3)
        root.add(added)

        val listsBefore = root.childrenCalls
        val entries = persistedSource(backend, dir).listEntries(null, SortMode.NAME)

        assertEquals(setOf("第001话", "第002话", "第003话"), entries.map { it.name }.toSet())
        assertEquals("本次只列一次目录（落盘快照拿来当比对基线）", listsBefore + 1, root.childrenCalls)
        assertEquals("未变化的行沿用快照里的结论：0 次探测", 1, kept.childrenCalls)
        assertEquals("自身 mtime 变化的那一行重探", 2, changed.childrenCalls)
        assertEquals("新增的行探测一次", 1, added.childrenCalls)
    }

    @Test
    fun `二次进入无变化 列 1 次目录 0 次探测`() = runTest {
        val (root, books) = bookLibrary(count = 3)
        val source = documentSource(FakeTreeBackend(root))
        source.listEntries(null, SortMode.NAME)
        val probedFirst = books.map { it.childrenCalls }

        val listsBefore = root.childrenCalls
        val entries = source.listEntries(null, SortMode.NAME)

        assertEquals("列表照旧完整", 3, entries.size)
        assertEquals("进目录总是真列一次父目录（验收：childrenCalls ≥ 1）", listsBefore + 1, root.childrenCalls)
        assertEquals("一行都没变 ⇒ 0 次探测（验收：probes = 0）", probedFirst, books.map { it.childrenCalls })
    }

    @Test
    fun `跨重启且内容已变 单段交付新列表 不先交旧快照`() = runTest {
        val dir = Files.createTempDirectory("single-phase").toFile()
        val root = fakeDir("root")
        val kept = bookFolder("root/第001话", 1).also { root.add(it) }
        val backend = FakeTreeBackend(root)
        persistedSource(backend, dir).also { it.listEntries(null, SortMode.NAME) }.close() // APP 退出

        val added = bookFolder("root/第002话", 2)
        root.add(added)

        val listsBefore = root.childrenCalls
        val fresh = persistedSource(backend, dir).listEntries(null, SortMode.NAME)

        assertEquals("返回值就是重列后的新内容（落盘快照只当比对基线、不作为返回值）", listOf("第001话", "第002话"), fresh.map { it.name })
        assertEquals("恰好 1 次列目录", listsBefore + 1, root.childrenCalls)
        assertEquals("未变化的行沿用快照里的结论：0 次新探测", 1, kept.childrenCalls)
        assertEquals("新增的行探测一次", 1, added.childrenCalls)
    }

    // ---------- 下拉更新与失败重试 ----------

    @Test
    fun `下拉更新不清列表快照 下一次进这一层仍是增量 0 次探测`() = runTest {
        val (root, books) = bookLibrary(count = 3)
        val source = documentSource(FakeTreeBackend(root))
        source.listEntries(null, SortMode.NAME)
        val probedFirst = books.map { it.childrenCalls }

        // 下拉更新：只清封面字节缓存（清理的契约由 `BrowseRefreshTest` 钉住）；
        // 列表那一半交给「每次真列一次 + 增量比对」，因此清单快照留着当基线。
        source.invalidateForRefresh(null)

        root.add(bookFolder("root/第004话", 4))
        val listsBefore = root.childrenCalls
        val entries = source.listEntries(null, SortMode.NAME)

        assertEquals("列表反映新增条目（快照被留着当比对基线）", 4, entries.size)
        assertEquals("只列一次目录", listsBefore + 1, root.childrenCalls)
        assertEquals(
            "其余三条沿用旧结论：下拉更新不引发整层重探（验收：无变化时 probes = 0）",
            probedFirst,
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

        root.add(bookFolder("root/第003话", 3))
        broken.failChildrenWith = null

        val second = source.listEntries(null, SortMode.NAME)

        assertTrue("失败的行在增量路径上重试后恢复判定", second.first { it.name == "第002话" }.isBook)
        assertEquals("失败的那条重试恰好一次", 2, broken.childrenCalls)
        assertEquals("成功的未变化行不重探", 1, ok.childrenCalls)
    }

    // ---------- 打点 ----------

    @Test
    fun `打点计数 进目录必列一次 无变化则零探测`() = runTest {
        // 这三个数字只进 logcat（`adb logcat -s ComicViewerPerf`），因此单测直接调 enumerateEntries 读它们。
        val dir = Files.createTempDirectory("enumeration-stats").toFile()
        val (root, _) = bookLibrary(count = 2)
        val source = persistedSource(FakeTreeBackend(root), dir)

        val first = EnumerationStats()
        source.enumerateEntries(null, SortMode.NAME, first)
        assertEquals("首次进入：没有比对基线 → 本次真列目录", 1, first.childrenCalls)
        assertEquals("逐个子目录探测", 2, first.probes)
        assertEquals("没有可复用的结论", 0, first.reused)

        val cached = EnumerationStats()
        source.enumerateEntries(null, SortMode.NAME, cached)
        assertEquals("二次进入仍真列一次目录", 1, cached.childrenCalls)
        assertEquals("一行都没变 ⇒ 0 次探测", 0, cached.probes)
        assertEquals("两行都沿用旧结论", 2, cached.reused)

        // 跨重启（内存表空、落盘快照在）：落盘快照当比对基线，同样只列 1 次、0 次探测
        val disk = EnumerationStats()
        persistedSource(FakeTreeBackend(root), dir).enumerateEntries(null, SortMode.NAME, disk)
        assertEquals("跨重启也真列一次目录", 1, disk.childrenCalls)
        assertEquals("落盘快照当基线 ⇒ 0 次探测", 0, disk.probes)
        assertEquals(2, disk.reused)

        root.add(bookFolder("root/第003话", 3))
        val relisted = EnumerationStats()
        source.enumerateEntries(null, SortMode.NAME, relisted)
        assertEquals("新增一本后：仍然只列一次目录", 1, relisted.childrenCalls)
        assertEquals("只探新增的那一条", 1, relisted.probes)
        assertEquals("其余两条沿用旧结论", 2, relisted.reused)
    }

    @Test
    fun `打点行如实反映三个计数`() {
        val relisted = enumerationLogLine(
            "root/第001话",
            SortMode.NAME,
            1001,
            EnumerationStats(childrenCalls = 1, probes = 1, reused = 1000),
            9,
        )
        assertTrue(
            "本次列目录 1 次 / 探测 1 条 / 复用 1000 条",
            relisted.contains("childrenCalls=1") && relisted.contains("probes=1") && relisted.contains("reused=1000"),
        )
        assertTrue(
            "容器与排序方式照常在打点里",
            relisted.contains("container=root/第001话") && relisted.contains("sort=NAME"),
        )
        assertFalse(
            "不再有恒为 none 的 snapshotSource（旧语义与新方案对不上）",
            relisted.contains("snapshotSource"),
        )
        assertFalse(
            "枚举行不得再用邻位两行的 `snapshot=` 键（同名两义）",
            relisted.contains("snapshot="),
        )
    }
}
