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
    fun `容器 mtime 不可得时不落缓存 每次都重新枚举`() = runTest {
        // SMB 共享根拿不到修改时间（SmbjTransport 对根硬编码 null）：若照样落缓存，
        // 「按 mtime 失效」恒不成立，往共享根加的书记远不会出现，只能靠显式刷新
        val backend = FakeTreeBackend(fakeDir("root", mtime = null).add(fakeDir("root/第001话")))
        val source = source(backend)

        source.listEntries(null, SortMode.NAME)
        source.listEntries(null, SortMode.NAME)

        assertEquals("mtime 不可得 → 不落缓存，每次进入重新枚举", 2, backend.root.childrenCalls)
    }

    @Test
    fun `子目录探测失败降级为容器 且该次枚举不留缓存 下次进入重试`() = runTest {
        val book = fakeDir("root/第001话").add(fakeFile("root/第001话/001.jpg"))
        book.failChildrenWith = IllegalStateException("目录不可读")
        val backend = FakeTreeBackend(fakeDir("root").add(book))
        val source = source(backend)

        val first = source.listEntries(null, SortMode.NAME)
        assertFalse("单条探测失败只降级为容器，不拖垮整表", first.first { it.name == "第001话" }.isBook)

        source.listEntries(null, SortMode.NAME)
        assertEquals("探测不全的那次枚举不留缓存：下次进入重新列整层", 2, backend.root.childrenCalls)
        assertEquals("重试确实又探了那条失败的子目录", 2, book.childrenCalls)

        book.failChildrenWith = null
        assertTrue(
            "目录恢复可读后重试即判定为书",
            source.listEntries(null, SortMode.NAME).first { it.name == "第001话" }.isBook,
        )
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
    fun `上一本下一本复用列目录的子目录探测策略 降级与传输故障冒泡`() = runTest {
        val first = fakeDir("root/第001话").add(fakeFile("root/第001话/001.jpg"))
        val second = fakeDir("root/第002话").add(fakeFile("root/第002话/002.jpg"))
        val broken = fakeDir("root/第003话").add(fakeFile("root/第003话/003.jpg"))
        val backend = FakeTreeBackend(fakeDir("root").add(first, second, broken))
        val source = source(backend)

        broken.failChildrenWith = IllegalStateException("目录不可读")
        assertEquals(
            "单条探测失败只让那条不算书，邻位判定不再整条失败（与列目录同一降级策略）",
            Neighbors(prev = null, next = second.id),
            source.neighbors(first.id),
        )

        broken.failChildrenWith = SmbException(SmbFailureKind.TIMEOUT, "连接超时")
        val thrown = runCatching { source.neighbors(first.id) }.exceptionOrNull()
        assertTrue("传输故障在邻位判定里同样冒泡：" + thrown, thrown is SmbException)
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
