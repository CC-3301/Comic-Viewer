package com.cc3301.comicviewer.core.source

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

/**
 * 相邻书（上一本/下一本）解析：压缩包书的邻位口径（票 #90）与「只读会话快照、绝不整层探测」的代价口径（票 #93）。
 *
 * 票 #90：浏览列表把压缩包（CBZ/ZIP）当书展示（[DocumentTreeSource] 的 `listingOf` 里 `isBook=true`），
 * 换书却拿不到邻位——同一目录里的书序列不能有两套口径。
 * 票 #93：邻位只从**已有会话快照**里取，父层本次会话没被列过时降级为不给邻居（本用例把两种情形都钉住）。
 *
 * 夹具 = [FakeTreeBackend]（仓库 Seam ①）：`childrenCalls` 就是「这一层被列了几次」= SMB 的 `list` 往返 /
 * SAF 的 provider IPC，子目录的 `childrenCalls` 即本票数的那次「是不是书」属性探测。
 *
 * 因此每个用例的开头都有一步 `listEntries`（= 浏览页列出这一层）：票 #93 之后邻位不再自己去列。
 */
class DocumentTreeNeighborsTest {

    private fun source(backend: FakeTreeBackend) =
        DocumentTreeSource(backend = backend, progressStore = InMemoryProgressStore())

    private fun dirBook(id: String) = fakeDir(id).add(fakeFile("$id/001.jpg"))

    /** 子目录探测次数（本票的验收指标）：把要数的几层目录的列目录次数加起来 */
    private fun probesOf(vararg dirs: FakeTreeNode): Int = dirs.sumOf { it.childrenCalls }

    /** 同目录 A(目录) B(CBZ) C(ZIP) D(目录)：名称序 A < B.cbz < C.zip < D */
    private fun mixedFixture(): FakeTreeBackend = FakeTreeBackend(
        fakeDir("root").add(
            dirBook("root/A"),
            fakeFile("root/B.cbz"),
            fakeFile("root/C.zip"),
            dirBook("root/D"),
        ),
    )

    @Test
    fun `目录书与压缩包书在同一份同目录书序列里排位`() = runTest {
        val src = source(mixedFixture())
        src.listEntries(null, SortMode.NAME) // 浏览页先列过这一层（票 #93：邻位只读已有快照）

        assertEquals("首位的目录书没有上一本", Neighbors(null, "root/B.cbz"), src.neighbors("root/A"))
        assertEquals("压缩包书取上一本=目录书", Neighbors("root/A", "root/C.zip"), src.neighbors("root/B.cbz"))
        assertEquals("压缩包书取下一本=目录书", Neighbors("root/B.cbz", "root/D"), src.neighbors("root/C.zip"))
        assertEquals("末位的目录书没有下一本", Neighbors("root/C.zip", null), src.neighbors("root/D"))
    }

    @Test
    fun `压缩包占位不跳空 相邻的两个压缩包互为邻位`() = runTest {
        val src = source(
            FakeTreeBackend(
                fakeDir("root").add(
                    fakeFile("root/01.cbz"),
                    fakeFile("root/02.zip"),
                    fakeFile("root/03.cbz"),
                ),
            ),
        )
        src.listEntries(null, SortMode.NAME) // 浏览页先列过这一层

        assertEquals("压缩包在首位：无上一本、不越界", Neighbors(null, "root/02.zip"), src.neighbors("root/01.cbz"))
        assertEquals("相邻的两个压缩包互为邻位（不跳过彼此去找目录书）", Neighbors("root/01.cbz", "root/03.cbz"), src.neighbors("root/02.zip"))
        assertEquals("压缩包在末位：无下一本、不越界", Neighbors("root/02.zip", null), src.neighbors("root/03.cbz"))
    }

    @Test
    fun `压缩包与目录书混排时互认邻位`() = runTest {
        val src = source(
            FakeTreeBackend(
                fakeDir("root/mixed").add(
                    dirBook("root/mixed/book-b"),
                    fakeFile("root/mixed/m.cbz"),
                    fakeFile("root/mixed/cover1.png"),
                    fakeFile("root/mixed/cover2.png"),
                    dirBook("root/mixed/z-book"),
                ),
            ),
        )
        src.listEntries("root/mixed", SortMode.NAME) // 浏览页先列过这一层（子目录容器，非根层）

        // 名称序：book-b < cover1.png < cover2.png < m.cbz < z-book
        assertEquals("目录书是首本、下一本是图片条目", Neighbors(null, "root/mixed/cover1.png"), src.neighbors("root/mixed/book-b"))
        assertEquals("图片条目的邻居不变（既有路径不回归）", Neighbors("root/mixed/book-b", "root/mixed/cover2.png"), src.neighbors("root/mixed/cover1.png"))
        assertEquals("图片条目的下一本是压缩包书", Neighbors("root/mixed/cover1.png", "root/mixed/m.cbz"), src.neighbors("root/mixed/cover2.png"))
        assertEquals("压缩包书夹在图片条目与目录书之间", Neighbors("root/mixed/cover2.png", "root/mixed/z-book"), src.neighbors("root/mixed/m.cbz"))
        assertEquals("末位目录书无下一本", Neighbors("root/mixed/m.cbz", null), src.neighbors("root/mixed/z-book"))
    }

    @Test
    fun `根层的压缩包书同样能取到邻位`() = runTest {
        val src = source(
            FakeTreeBackend(
                fakeDir("root").add(
                    dirBook("root/A"),
                    fakeFile("root/B.cbz"),
                ),
            ),
        )
        src.listEntries(null, SortMode.NAME) // 浏览页先列过这一层（父节点即来源根）

        val neighbors = src.neighbors("root/B.cbz")
        assertNotNull("根层的压缩包书（父节点即来源根）也必须能解析邻位", neighbors)
        assertEquals(Neighbors("root/A", null), neighbors)
    }

    @Test
    fun `非书节点的输入安全返回空邻位`() = runTest {
        val src = source(
            FakeTreeBackend(
                fakeDir("root").add(
                    fakeFile("root/notes.txt"),
                    fakeDir("root/plain").add(fakeFile("root/plain/note.txt")),
                    fakeFile("root/B.cbz"),
                ),
            ),
        )
        src.listEntries(null, SortMode.NAME) // 先列过这一层：下面的空邻位只可能来自「不是书」，不是快照缺失

        assertEquals("纯文本文件不是书", Neighbors(null, null), src.neighbors("root/notes.txt"))
        assertEquals("容器（目录但本身不是书）不在书序列里", Neighbors(null, null), src.neighbors("root/plain"))
        assertEquals("书序列只含书：压缩包在容器之后仍无上一本", Neighbors(null, null), src.neighbors("root/B.cbz"))
    }

    // ---------- 票 #93：只读已有快照，绝不为邻位列父层 / 探测子目录 ----------

    @Test
    fun `父层未列过时降级为不给邻居 且不为此列目录或探测子目录`() = runTest {
        // 维护者主诉：打开一本书顺带「一次打开所有子文件夹的所有书」。
        // 降级口径（本票选定并在证据里写明）：本次不给邻居（Neighbors(null, null) → 界面照既有口径提示
        // 「无上一本/无下一本」），而不是去列父层 + 逐子目录探测。
        val a = dirBook("root/A")
        val c = dirBook("root/C")
        val root = fakeDir("root").add(a, fakeFile("root/B.cbz"), c)
        val backend = FakeTreeBackend(root)
        val src = source(backend)

        assertEquals("父层本次会话没被列过：本次不给邻居", Neighbors(null, null), src.neighbors("root/B.cbz"))

        assertEquals("降级路径下父层一次都不列", 0, root.childrenCalls)
        assertEquals("降级路径下一次子目录探测都不发生（本票主诉）", 0, probesOf(a, c))
        assertEquals("也不为邻位去按 id 取节点（resolve 只发生在被打开的这本书自己）", 1, backend.resolveCalls)
    }

    @Test
    fun `父层列过后邻居正确 且不新增任何探测`() = runTest {
        val a = dirBook("root/A")
        val c = dirBook("root/C")
        val root = fakeDir("root").add(a, fakeFile("root/B.cbz"), c)
        val src = source(FakeTreeBackend(root))
        src.listEntries(null, SortMode.NAME) // 浏览页：列表已经算过这一层的展示顺序
        val listsAfterBrowsing = root.childrenCalls
        val probesAfterBrowsing = probesOf(a, c)

        assertEquals(
            "邻居来自已有列表：压缩包书夹在两个目录书之间",
            Neighbors("root/A", "root/C"),
            src.neighbors("root/B.cbz"),
        )
        assertEquals("打开书不再重列父层", listsAfterBrowsing, root.childrenCalls)
        assertEquals("打开书不再探测任何子目录", probesAfterBrowsing, probesOf(a, c))
    }

    @Test
    fun `快照里的探测失败条目不算书 邻位不重试它也不冒泡`() = runTest {
        // 列目录侧既有口径（票 #30/#51）：子目录探测失败降级为容器（不算书）、不拖垮整表。
        // 邻位只读那份快照，因此失败条目同样不参与书序列，且不会再为它发一次探测。
        val a = dirBook("root/A")
        val b = dirBook("root/B")
        b.failChildrenWith = IllegalStateException("目录不可读")
        val root = fakeDir("root").add(a, b)
        val src = source(FakeTreeBackend(root))
        src.listEntries(null, SortMode.NAME)
        val probesAfterBrowsing = probesOf(a, b)

        assertEquals("失败条目在快照里不算书：A 之后没有下一本", Neighbors(null, null), src.neighbors("root/A"))
        assertEquals("不为失败条目重试探测", probesAfterBrowsing, probesOf(a, b))
    }
}
