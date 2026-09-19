package com.cc3301.comicviewer.core.source

import com.cc3301.comicviewer.core.source.smb.SmbException
import com.cc3301.comicviewer.core.source.smb.SmbFailureKind
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 会话级列表缓存的失效边界与子目录探测的失败策略（票 #30 修正轮 P2-1/P2-2/P2-4）。
 *
 * 用内存目录树夹具（[FakeTreeBackend]）统计每层目录的列目录次数：缓存命中 = 0 次，
 * 「不留缓存 → 下次进入重试」= 次数继续增长；传输故障按 TransportFailure 契约冒泡。
 */
class DocumentTreeListCacheTest {

    private fun source(backend: FakeTreeBackend) =
        DocumentTreeSource(backend = backend, progressStore = InMemoryProgressStore())

    @Test
    fun `根容器 mtime 变化使缓存失效`() = runTest {
        // 来源根节点是构造期快照（mtime 是构造期 val），而来源实例会话级长期存活：
        // 若用快照做缓存键，往共享根/授权根新增的书永远不会出现在根列表里。夹具的 resolve 返回带当前 mtime 的新视图，
        // 与真实后端（FileNode/SafNode/SmbNode 都是每次 resolve 新建）同形。
        val root = fakeDir("root").add(fakeDir("root/第001话").add(fakeFile("root/第001话/001.jpg")))
        val backend = FakeTreeBackend(root)
        val source = source(backend)

        assertEquals(listOf("第001话"), source.listEntries(null, SortMode.NAME).map { it.name })

        root.currentMtime = root.lastModifiedMs!! + 60_000 // 根目录被改动（拷贝进一本新书）
        root.add(fakeDir("root/第002话").add(fakeFile("root/第002话/001.jpg")))

        assertEquals(
            "根容器 mtime 变化必须使根列表缓存失效（构造期快照不算数）",
            listOf("第001话", "第002话"),
            source.listEntries(null, SortMode.NAME).map { it.name },
        )
    }

    @Test
    fun `容器 mtime 不可得时按会话缓存 只有手动刷新才失效`() = runTest {
        // 票 #51 F3：SMB 共享根（mtime 恒为 null）是「库放在共享根」这种常见布局的常态，
        // 旧实现因此该层永不落缓存——维护者说的「退出来还要卡」就包含这一条（每子目录一次探测重来一遍）。
        // 现在改成会话内缓存：只由手动刷新（下拉更新）失效，且二次进入连一次取节点都不发。
        val backend = FakeTreeBackend(fakeDir("root", mtime = null).add(fakeDir("root/第001话")))
        val source = source(backend)

        source.listEntries(null, SortMode.NAME)
        assertEquals("首次进入枚举整层", 1, backend.root.childrenCalls)

        backend.resetResolveCount()
        source.listEntries(null, SortMode.NAME)
        assertEquals("无 mtime 的容器二次进入：0 次列目录", 1, backend.root.childrenCalls)
        assertEquals("无 mtime 的容器二次进入：0 次取节点", 0, backend.resolveCalls)

        // 手动刷新仍能失效该层（刷新入口对根容器与普通容器是同一套）
        source.invalidateListCache(null)
        source.listEntries(null, SortMode.NAME)
        assertEquals("手动刷新后重新枚举整层", 2, backend.root.childrenCalls)
    }

    @Test
    fun `子目录探测失败降级为容器 成功的照常缓存 下次只重试失败的那条`() = runTest {
        // 票 #51 F4：旧实现要求「子目录全部探测成功」才落缓存，百级目录里挂一条就每次重返全量重探。
        // 现在逐条记录探测成败：成功的命中，失败的只重试它自己（列目录次数 = 1，而非整层）。
        val ok = fakeDir("root/第001话").add(fakeFile("root/第001话/001.jpg"))
        val book = fakeDir("root/第002话").add(fakeFile("root/第002话/001.jpg"))
        book.failChildrenWith = IllegalStateException("目录不可读")
        val backend = FakeTreeBackend(fakeDir("root").add(ok, book))
        val source = source(backend)

        val first = source.listEntries(null, SortMode.NAME)
        assertFalse("单条探测失败只降级为容器，不拖垮整表", first.first { it.name == "第002话" }.isBook)

        backend.resetResolveCount()
        val second = source.listEntries(null, SortMode.NAME)
        assertEquals("本层不再重列（其余条目照常命中缓存）", 1, backend.root.childrenCalls)
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
    fun `手动刷新显式失效当前层 触发一次整层重新枚举`() = runTest {
        // 票 #53：下拉更新走的就是「显式失效当前层缓存 → 重新枚举 → 可见行重取封面」这条通路，
        // 不是纯动画——本用例把「不下拉 = 0 次列目录」与「下拉 = 恰好整层一次」都钉住。
        val sub = fakeDir("root/第001话").add(fakeFile("root/第001话/001.jpg"))
        val backend = FakeTreeBackend(fakeDir("root").add(sub))
        val source = source(backend)

        source.listEntries(null, SortMode.NAME)
        val cached = backend.root.childrenCalls

        source.listEntries(null, SortMode.NAME)
        assertEquals("不下拉：二次进入命中缓存，一次列目录都不发生", cached, backend.root.childrenCalls)

        source.invalidateListCache(null)
        source.listEntries(null, SortMode.NAME)
        assertEquals("显式失效后恰好重新列一次本层", cached + 1, backend.root.childrenCalls)
        assertEquals("并重新探测子目录", 2, sub.childrenCalls)
    }


    // ---------- 票 #51：时间排序不取节点 / 邻位与换排序复用快照 ----------

    /** 100 个容器的夹具（票 #51 的场景：子文件夹极多的库） */
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
        // 票 #51 F1（主凶）：旧实现在排序比较器里 `resolve(id)?.lastModifiedMs`，而 Kotlin 的 compareBy*
        // 每次比较都调用选择器——百级目录一次排序就是上千次「取节点」，在 SMB 上每次 stat 都是网络往返。
        val (backend, _) = bigFixture()
        val source = source(backend)

        backend.resetResolveCount()
        val modified = source.listEntries(null, SortMode.MODIFIED_TIME)
        assertEquals("时间排序不得按 id 取节点", 0, backend.resolveCalls)
        assertEquals("100 条容器 + 1 个压缩包都在列表里", 101, modified.size)

        // 发布时间排序同样不得取节点（缺元数据时回退 mtime 也不额外取节点）
        source.invalidateListCache(null)
        backend.resetResolveCount()
        source.listEntries(null, SortMode.RELEASE_TIME)
        assertEquals("发布时间排序不得按 id 取节点", 0, backend.resolveCalls)
    }

    @Test
    fun `换排序方式不重列目录 排序在会话快照之上进行`() = runTest {
        // 票 #51：快照按容器一份（与排序方式无关），换排序不再重发同一批 list/PROPFIND
        val (backend, root) = bigFixture(containerCount = 10)
        val source = source(backend)

        source.listEntries(null, SortMode.NAME)
        val listsAfterFirst = root.childrenCalls

        source.listEntries(null, SortMode.MODIFIED_TIME)
        source.listEntries(null, SortMode.RELEASE_TIME)
        assertEquals("换排序方式不再列目录", listsAfterFirst, root.childrenCalls)
    }

    @Test
    fun `相邻书判定复用会话快照 不再整层探测`() = runTest {
        // 票 #51 F5：旧实现每点一次「上一本/下一本」就整层 children() + 逐子目录探测一遍
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
    fun `有 mtime 的容器二次进入只花一次取节点判断是否过期`() = runTest {
        // 与条目数无关的一次取节点（比对 mtime），取代旧实现的 O(条目数 × log 条目数) 次取节点
        val (backend, root) = bigFixture()
        val source = source(backend)

        source.listEntries(null, SortMode.NAME)
        val listsAfterFirst = root.childrenCalls
        backend.resetResolveCount()

        source.listEntries(null, SortMode.NAME)

        assertEquals("缓存命中：不重列", listsAfterFirst, root.childrenCalls)
        assertEquals("只花一次取节点（根容器 mtime 现取），与条目数无关", 1, backend.resolveCalls)
    }

    @Test
    fun `根容器改动后重列一次 之后照常命中缓存`() = runTest {
        // 根节点是构造期快照（票 #30）：首次枚举存下的 mtime 可能是旧值，重列之后必须换成现取值当键，
        // 否则下一次比对永远不等 → 每次进入都白重列一次（缓存形同虚设）
        val root = fakeDir("root").add(fakeDir("root/第001话").add(fakeFile("root/第001话/001.jpg")))
        val backend = FakeTreeBackend(root)
        val source = source(backend)
        source.listEntries(null, SortMode.NAME)

        root.currentMtime = root.lastModifiedMs!! + 60_000
        root.add(fakeDir("root/第002话").add(fakeFile("root/第002话/001.jpg")))
        assertEquals(
            "mtime 变化 → 失效并重列",
            listOf("第001话", "第002话"),
            source.listEntries(null, SortMode.NAME).map { it.name },
        )

        val listsAfterRelist = root.childrenCalls
        source.listEntries(null, SortMode.NAME)
        assertEquals("重列后再进来命中缓存", listsAfterRelist, root.childrenCalls)
    }

    @Test
    fun `close 清空列表缓存并释放后端会话`() = runTest {
        val backend = FakeTreeBackend(fakeDir("root").add(fakeDir("root/第001话")))
        val source = source(backend)

        source.listEntries(null, SortMode.NAME)
        source.listEntries(null, SortMode.NAME)
        assertEquals("二次进入命中缓存", 1, backend.root.childrenCalls)

        source.close()
        assertEquals("close 释放后端会话（SMB）", 1, backend.closeCount)

        source.listEntries(null, SortMode.NAME)
        assertEquals("close 后缓存已清空：重新枚举整层", 2, backend.root.childrenCalls)
    }

    @Test
    fun `相邻书只读会话快照 不再重探父层 失败条目与探测口径一致`() = runTest {
        // 票 #93：邻位只从已有快照里取（旧实现在快照缺失/失败时会整层重探，见下一条用例的降级口径）。
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
    fun `列表缓存有上界 超出后整体清空而不是无上限增长`() = runTest {
        val parent = fakeDir("root")
        val containers = (1..300).map { fakeDir("root/第%03d话".format(it)) }
        containers.forEach { parent.add(it) }
        val backend = FakeTreeBackend(parent)
        val source = source(backend)

        // 300 个容器超过会话级列表缓存的上界（实现里是 256 条）：最早访问的容器会被腾掉
        containers.forEach { source.listEntries(it.id, SortMode.NAME) }

        val oldest = containers.first()
        val before = oldest.childrenCalls
        source.listEntries(oldest.id, SortMode.NAME)
        assertEquals("超出上界后最早的缓存已被清掉：重新列一次", before + 1, oldest.childrenCalls)
    }
}
