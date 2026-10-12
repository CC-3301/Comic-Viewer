package com.cc3301.comicviewer.core.source

import com.cc3301.comicviewer.core.source.smb.SmbException
import com.cc3301.comicviewer.core.source.smb.SmbFailureKind
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 会话级列表快照的失效边界（= 它只是一份**增量比对基线**）与子目录探测的失败策略。
 *
 * 进目录的判据只有一条：**每次真列 1 次本层目录**，拿列目录顺手带回来的子项 mtime 与上次快照逐条比，
 * 未变化的行沿用结论（0 次探测），新增 / 自身变化的行重探。容器自己的 mtime 不参与（SMB 不更新它）。
 *
 * 用内存目录树夹具（[FakeTreeBackend]）统计每层目录的列目录次数与取节点次数；
 * 传输故障按 TransportFailure 契约冒泡。
 */
class DocumentTreeListCacheTest {

    private fun source(backend: FakeTreeBackend) =
        DocumentTreeSource(backend = backend, progressStore = InMemoryProgressStore())

    @Test
    fun `根容器新增书后能看到 与根容器 mtime 无关`() = runTest {
        // 来源根节点是构造期快照（mtime 是构造期 val），而来源实例会话级长期存活：
        // 若拿它的 mtime 当失效判据，往共享根/授权根新增的书永远不会出现在根列表里
        // （SMB 服务器也不更新父目录 mtime）。夹具的 resolve 返回带当前 mtime 的新视图，
        // 与真实后端（FileNode/SafNode/SmbNode 都是每次 resolve 新建）同形。
        val root = fakeDir("root").add(fakeDir("root/第001话").add(fakeFile("root/第001话/001.jpg")))
        val backend = FakeTreeBackend(root)
        val source = source(backend)

        assertEquals(listOf("第001话"), source.listEntries(null, SortMode.NAME).map { it.name })

        // 拷进一本新书：**不动根目录自己的 mtime**（这正是设备上的形状）
        root.add(fakeDir("root/第002话").add(fakeFile("root/第002话/001.jpg")))

        assertEquals(
            "新增的书必须出现在根列表里",
            listOf("第001话", "第002话"),
            source.listEntries(null, SortMode.NAME).map { it.name },
        )
    }

    @Test
    fun `进目录不取容器节点 mtime 不再是判据`() = runTest {
        // 旧实现每次比对容器 mtime 都要 resolve 一次；现在连这一次都不发（列目录顺手带回来的子项 mtime 就够）。
        val backend = FakeTreeBackend(fakeDir("root").add(fakeDir("root/第001话")))
        val source = source(backend)

        source.listEntries(null, SortMode.NAME)
        assertEquals("首次进入枚举整层", 1, backend.root.childrenCalls)

        backend.resetResolveCount()
        source.listEntries(null, SortMode.NAME)
        assertEquals("二次进入仍真列一次本层目录", 2, backend.root.childrenCalls)
        assertEquals("一次取节点都不发（不再比对容器 mtime）", 0, backend.resolveCalls)
    }

    @Test
    fun `容器 mtime 不可得时行为一致 无变化就是零探测`() = runTest {
        // SMB 共享根（mtime 恒为 null）是「库放在共享根」这种常见布局的常态。
        // 新判据与 mtime 无关，因此这种层与别的层走同一条路。
        val root = fakeDir("root", mtime = null)
        val book = fakeDir("root/第001话").add(fakeFile("root/第001话/001.jpg"))
        root.add(book)
        val backend = FakeTreeBackend(root)
        val source = source(backend)

        source.listEntries(null, SortMode.NAME)
        assertEquals("首次进入探测那一行", 1, book.childrenCalls)

        backend.resetResolveCount()
        val second = source.listEntries(null, SortMode.NAME)
        assertEquals("二次进入仍真列一次本层目录", 2, root.childrenCalls)
        assertEquals("无变化 ⇒ 0 次探测", 1, book.childrenCalls)
        assertEquals("取节点 0 次", 0, backend.resolveCalls)
        assertEquals(listOf("第001话"), second.map { it.name })
    }

    @Test
    fun `子目录探测失败降级为容器 成功的照常复用 下次只重试失败的那条`() = runTest {
        // 旧实现要求「子目录全部探测成功」才落缓存，百级目录里挂一条就每次重返全量重探。
        // 现在逐条记录探测成败：成功的复用上次结论，失败的只重试它自己。
        val ok = fakeDir("root/第001话").add(fakeFile("root/第001话/001.jpg"))
        val book = fakeDir("root/第002话").add(fakeFile("root/第002话/001.jpg"))
        book.failChildrenWith = IllegalStateException("目录不可读")
        val backend = FakeTreeBackend(fakeDir("root").add(ok, book))
        val source = source(backend)

        val first = source.listEntries(null, SortMode.NAME)
        assertFalse("单条探测失败只降级为容器，不拖垮整表", first.first { it.name == "第002话" }.isBook)

        backend.resetResolveCount()
        val second = source.listEntries(null, SortMode.NAME)
        assertEquals("本层照旧真列一次", 2, backend.root.childrenCalls)
        assertEquals("只重试失败的那一条子目录", 2, book.childrenCalls)
        assertEquals("成功的那条不重探", 1, ok.childrenCalls)
        assertEquals(second.first { it.name == "第001话" }, first.first { it.name == "第001话" })

        book.failChildrenWith = null
        val third = source.listEntries(null, SortMode.NAME)
        assertTrue(
            "目录恢复可读后重试即判定为书",
            third.first { it.name == "第002话" }.isBook,
        )
        assertEquals("恢复后不再重试（快照里已无失败条目）", 3, book.childrenCalls)
    }

    @Test
    fun `子目录列目录抛传输故障时冒泡 不静默降级为容器`() = runTest {
        val book = fakeDir("root/第001话").add(fakeFile("root/第001话/001.jpg"))
        book.failChildrenWith = SmbException(SmbFailureKind.TIMEOUT, "连接超时")
        val backend = FakeTreeBackend(fakeDir("root").add(book))
        val source = source(backend)

        val thrown = runCatching { source.listEntries(null, SortMode.NAME) }.exceptionOrNull()

        assertTrue(
            "断链/认证失效必须按 TransportFailure 契约冒泡，否则界面拿到「全是容器、封面全空」的假列表：" + thrown,
            thrown is SmbException,
        )
    }

    @Test
    fun `下拉更新只清封面缓存 列表快照留着当比对基线`() = runTest {
        // 下拉更新（`applyPullToRefresh`）走的就是这一条：清封面字节缓存 → 重新取数。
        // 列表那一半交给「每次真列一次 + 增量比对」，因此刷新之后仍是 0 次探测。
        val sub = fakeDir("root/第001话").add(fakeFile("root/第001话/001.jpg"))
        val backend = FakeTreeBackend(fakeDir("root").add(sub))
        val source = source(backend)

        source.listEntries(null, SortMode.NAME)
        val cached = backend.root.childrenCalls

        source.listEntries(null, SortMode.NAME)
        assertEquals("不下拉：二次进入仍真列一次本层", cached + 1, backend.root.childrenCalls)
        assertEquals("且不重探未变化的行", 1, sub.childrenCalls)

        source.invalidateForRefresh(null)
        source.listEntries(null, SortMode.NAME)
        assertEquals("下拉更新后照旧只列一次本层", cached + 2, backend.root.childrenCalls)
        assertEquals("下拉更新不引发整层重探（验收：无变化时 probes = 0）", 1, sub.childrenCalls)
    }


    // ---------- 时间排序不取节点 / 邻位与换排序复用快照 ----------

    /** 100 个容器的夹具（子文件夹极多的库） */
    private fun bigFixture(containerCount: Int = 100): Pair<FakeTreeBackend, FakeTreeNode> {
        val root = fakeDir("root")
        repeat(containerCount) { i ->
            root.add(fakeDir("root/第%03d话".format(i + 1)).add(fakeFile("root/第%03d话/001.jpg".format(i + 1))))
        }
        root.add(fakeFile("root/单行本.cbz"))
        return FakeTreeBackend(root) to root
    }

    @Test
    fun `时间类排序不按 id 取节点 取节点次数与条目数无关`() = runTest {
        // 旧实现在排序比较器里 `resolve(id)?.lastModifiedMs`，而 Kotlin 的 compareBy*
        // 每次比较都调用选择器——百级目录一次排序就是上千次「取节点」，在 SMB 上每次 stat 都是网络往返。
        val (backend, _) = bigFixture()
        val source = source(backend)

        backend.resetResolveCount()
        val modified = source.listEntries(null, SortMode.MODIFIED_TIME)
        assertEquals("时间排序不得按 id 取节点", 0, backend.resolveCalls)
        assertEquals("100 条容器 + 1 个压缩包都在列表里", 101, modified.size)

        // 发布时间排序同样不得取节点（缺元数据时回退 mtime 也不额外取节点）
        source.invalidateForRefresh(null)
        backend.resetResolveCount()
        source.listEntries(null, SortMode.RELEASE_TIME)
        assertEquals("发布时间排序不得按 id 取节点", 0, backend.resolveCalls)
    }

    @Test
    fun `换排序方式仍只列一次本层 比对基线按容器一份与排序无关`() = runTest {
        val (backend, root) = bigFixture(containerCount = 10)
        val source = source(backend)
        val book = root.childrenList.first { it.id == "root/第001话" }

        source.listEntries(null, SortMode.NAME)
        val listsAfterFirst = root.childrenCalls

        source.listEntries(null, SortMode.MODIFIED_TIME)
        source.listEntries(null, SortMode.RELEASE_TIME)

        assertEquals("换排序方式：每次都真列一次本层目录", listsAfterFirst + 2, root.childrenCalls)
        assertEquals("比对基线不因换排序失效：子目录一次都不重探", 1, book.childrenCalls)
    }

    @Test
    fun `相邻书判定复用会话快照 不再整层探测`() = runTest {
        // 旧实现每点一次「上一本/下一本」就整层 children() + 逐子目录探测一遍
        val (backend, root) = bigFixture(containerCount = 20)
        val source = source(backend)
        val books = source.listEntries(null, SortMode.NAME)
        val firstBook = books.first { it.name == "第001话" }.id
        val probesAfterList = root.childrenCalls

        val neighbors = source.neighbors(firstBook)

        assertEquals("邻位判定不再重列本层", probesAfterList, root.childrenCalls)
        assertEquals("邻位仍是同一层的名称序前后", "第002话", neighbors.next?.substringAfterLast('/'))
        // 名称自然排序里中文按拼音：单行本.cbz 排在「第…」之前，因此它是 第001话 的上一本
        assertEquals("单行本.cbz", neighbors.prev?.substringAfterLast('/'))
    }

    @Test
    fun `大目录二次进入只列一次本层 不重探任何子目录`() = runTest {
        val (backend, root) = bigFixture()
        val source = source(backend)
        source.listEntries(null, SortMode.NAME)

        backend.resetResolveCount()
        val listsBefore = root.childrenCalls
        source.listEntries(null, SortMode.NAME)

        assertEquals("二次进入只列一次本层目录（而不是整层 101 次）", listsBefore + 1, root.childrenCalls)
        assertEquals(
            "100 个子目录一个都不重探",
            List(100) { 1 },
            root.childrenList.filter { it.isDirectory }.map { it.childrenCalls },
        )
        assertEquals("取节点 0 次", 0, backend.resolveCalls)
    }

    @Test
    fun `close 清空列表快照并释放后端会话`() = runTest {
        val backend = FakeTreeBackend(fakeDir("root").add(fakeDir("root/第001话")))
        val source = source(backend)

        source.listEntries(null, SortMode.NAME)
        assertEquals("首次进入枚举整层", 1, backend.root.childrenCalls)

        source.close()
        assertEquals("close 释放后端会话（SMB）", 1, backend.closeCount)

        backend.resetResolveCount()
        source.listEntries(null, SortMode.NAME)
        assertEquals("close 后比对基线没了：重新枚举整层", 2, backend.root.childrenCalls)
    }

    @Test
    fun `相邻书只读会话快照 不再重探父层 失败条目与探测口径一致`() = runTest {
        // 邻位只从已有快照里取（旧实现在快照缺失/失败时会整层重探，见下一条用例的降级口径）。
        // 列目录侧的两条既有口径不变：探测失败的子目录降级为容器（不算书）、传输故障在**列目录**里冒泡
        // （`子目录列目录抛传输故障时冒泡 不静默降级为容器` 用例守着）；邻位这一侧没有 I/O，因此既不重探也不冒泡。
        val first = fakeDir("root/第001话").add(fakeFile("root/第001话/001.jpg"))
        val second = fakeDir("root/第002话").add(fakeFile("root/第002话/002.jpg"))
        val broken = fakeDir("root/第003话").add(fakeFile("root/第003话/003.jpg"))
        val backend = FakeTreeBackend(fakeDir("root").add(first, second, broken))
        val source = source(backend)

        broken.failChildrenWith = IllegalStateException("目录不可读")
        assertFalse(
            "探测失败降级为容器（列目录侧既有口径）",
            source.listEntries(null, SortMode.NAME).first { it.name == "第003话" }.isBook,
        )
        val probesAfterListing = first.childrenCalls + second.childrenCalls + broken.childrenCalls

        // 快照里的失败条目不算书 → 它不参与书序列；邻位不重试它、也不冒泡（这里连传输故障类型都换一遍）
        broken.failChildrenWith = SmbException(SmbFailureKind.TIMEOUT, "连接超时")
        assertEquals(
            "邻位来自快照：第001话 之后是第002话（失败的第003话不算书）",
            Neighbors(prev = null, next = second.id),
            source.neighbors(first.id),
        )
        assertEquals(
            "邻位不再重探任何子目录",
            probesAfterListing,
            first.childrenCalls + second.childrenCalls + broken.childrenCalls,
        )
    }

    @Test
    fun `降级后在浏览页列出该层时邻位恢复`() = runTest {
        // 本用例只钉「恢复」这一个独立性：降级不是永久态——用户在浏览页进过这一层（`listEntries`）邻位就恢复。
        // 「未列过父层 → 空邻位 + 子目录探测次数 0」的计数口径只有一处（`DocumentTreeNeighborsTest`）；
        // 「进阅读器后后台补齐」走 `Source.warmNeighbors`，同样只在那处。
        val first = fakeDir("root/第001话").add(fakeFile("root/第001话/001.jpg"))
        val second = fakeDir("root/第002话").add(fakeFile("root/第002话/002.jpg"))
        val source = source(FakeTreeBackend(fakeDir("root").add(first, second)))

        assertEquals(
            "前提：没列过父层时邻位未知",
            Neighbors(prev = null, next = null),
            source.neighbors(first.id),
        )

        source.listEntries(null, SortMode.NAME) // 用户回到浏览页（或本来就是从浏览页进来的）

        assertEquals(
            "列出该层后邻位恢复",
            Neighbors(prev = null, next = second.id),
            source.neighbors(first.id),
        )
    }

    @Test
    fun `列表快照有上界 被腾掉的那层要重新探测子目录`() = runTest {
        val parent = fakeDir("root")
        val containers = (1..300).map { i ->
            fakeDir("root/第%03d话".format(i)).add(fakeDir("root/第%03d话/上卷".format(i)))
        }
        containers.forEach { parent.add(it) }
        val source = source(FakeTreeBackend(parent))

        // 300 个容器超过会话级列表快照的上界（实现里是 256 条）：最早访问的容器会被腾掉
        containers.forEach { source.listEntries(it.id, SortMode.NAME) }

        val oldestInner = containers.first().childrenList.single()
        val newestInner = containers.last().childrenList.single()
        assertEquals("前置：每个容器里的子目录都被探测过一次", 1, oldestInner.childrenCalls)

        source.listEntries(containers.first().id, SortMode.NAME)
        source.listEntries(containers.last().id, SortMode.NAME)

        assertEquals("最早那层已被腾掉：它的子目录重新探测", 2, oldestInner.childrenCalls)
        assertEquals("最近那层仍在：一次都不重探", 1, newestInner.childrenCalls)
    }
}
