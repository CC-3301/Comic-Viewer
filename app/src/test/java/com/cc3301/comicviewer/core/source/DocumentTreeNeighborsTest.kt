package com.cc3301.comicviewer.core.source

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

/**
 * 相邻书（上一本/下一本）对**压缩包书**的邻位解析（票 #90）。
 *
 * 浏览列表把压缩包（CBZ/ZIP）当书展示（[DocumentTreeSource] 的 `listingOf` 里 `isBook=true`），
 * 换书却拿不到邻位——同一目录里的书序列不能有两套口径。本用例用真 [DocumentTreeSource] +
 * [FakeTreeBackend]（仓库 Seam ①）锁住「目录书 + 压缩包书混排成一条名称序序列，压缩包占位、不跳空」。
 */
class DocumentTreeNeighborsTest {

    private fun source(backend: FakeTreeBackend) =
        DocumentTreeSource(backend = backend, progressStore = InMemoryProgressStore())

    private fun dirBook(id: String) = fakeDir(id).add(fakeFile("$id/001.jpg"))

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

        assertEquals("纯文本文件不是书", Neighbors(null, null), src.neighbors("root/notes.txt"))
        assertEquals("容器（目录但本身不是书）不在书序列里", Neighbors(null, null), src.neighbors("root/plain"))
        assertEquals("书序列只含书：压缩包在容器之后仍无上一本", Neighbors(null, null), src.neighbors("root/B.cbz"))
    }
}
