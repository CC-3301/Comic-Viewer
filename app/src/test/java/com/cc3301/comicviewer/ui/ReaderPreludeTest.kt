package com.cc3301.comicviewer.ui

import com.cc3301.comicviewer.core.source.DocumentTreeSource
import com.cc3301.comicviewer.core.source.FakeTreeBackend
import com.cc3301.comicviewer.core.source.InMemoryProgressStore
import com.cc3301.comicviewer.core.source.Source
import com.cc3301.comicviewer.core.source.fakeDir
import com.cc3301.comicviewer.core.source.fakeFile
import com.cc3301.comicviewer.core.source.openForReading
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 打开前置（票 #108 E1-A）：点击书时先在书柜页把「打开 + 首批解码」做完，阅读页再同步取走。
 *
 * 维护者现象：点开一本书先出现黑底「准备打开」整页。修法是**留在书柜页等**（等首批解码），就绪后一次性切页；
 * 因此这三件事必须成立：① 前置确实按**这本书自己的落点**开始解；② 解的**不止落点那一页**
 * （票面原话「附近几页加载完成后再切过去」，条漫首屏通常不止一页）；③ 前置是**可兑现一次**的槽
 * （否则反复取用会让阅读页拿到过期句柄）。
 *
 * 未覆盖：切页那一帧到底是不是图片（组合期首帧）——本仓库没有 Compose UI 测试基建，
 * 按 SPEC 的 Testing Decisions 走真机验收，残余风险写进 `evidence-impl.md`。
 */
class ReaderPreludeTest {

    private val store = InMemoryProgressStore()

    /** 一本书 3 页（root 下只有图片 → root 本身是一本书） */
    private fun source(): Source = DocumentTreeSource(
        backend = FakeTreeBackend(
            fakeDir("root").add(
                fakeFile("root/1.jpg"),
                fakeFile("root/2.jpg"),
                fakeFile("root/3.jpg"),
            ),
        ),
        progressStore = store,
    )

    @Test
    fun `前置按这本书自己的落点开始解首批`() = runTest {
        val src = source()
        store.write("root", 2, 3) // 读到第 3 页
        val decoded = mutableListOf<Triple<String, Int, Int>>()

        val opening = preloadReaderOpening(src, "root", alwaysFirstPage = false, targetWidthPx = 1080) { handle, index, width ->
            decoded += Triple(handle.id, index, width)
        }

        assertEquals("落点是这本书自己的进度", 2, opening.startIndex)
        assertEquals("前置句柄就是这本书的", "root", opening.handle.id)
        assertEquals(
            "从落点起解（它是末页，只剩这一页），且用阅读页的目标宽度",
            listOf(Triple("root", 2, 1080)),
            decoded,
        )
    }

    @Test
    fun `首批是落点加随后页 不是只解落点那一页`() = runTest {
        // 票面原话「等打开、并且附近几页加载完成后再切过去」：只解落点那一页的话，
        // 切过去后条漫首屏的其余页仍要现解（占位一波波补齐）
        val src = source() // 3 页
        store.write("root", 0, 3)
        val decoded = mutableListOf<Int>()

        preloadReaderOpening(src, "root", alwaysFirstPage = false, targetWidthPx = 1080) { _, index, _ ->
            decoded += index
        }

        assertEquals("落点在第 1 页 ⇒ 连解 1、2、3 页", listOf(0, 1, 2), decoded)
    }

    @Test
    fun `末页附近只解剩下的那几页`() = runTest {
        val src = source()
        store.write("root", 1, 3) // 落点第 2 页
        val decoded = mutableListOf<Int>()

        preloadReaderOpening(src, "root", alwaysFirstPage = false, targetWidthPx = 1080) { _, index, _ ->
            decoded += index
        }

        assertEquals("第 2 页起只剩 2 页可解，不越界", listOf(1, 2), decoded)
    }

    @Test
    fun `开启始终从第一页时首批从第一页起解`() = runTest {
        val src = source()
        store.write("root", 2, 3)
        val decoded = mutableListOf<Int>()

        preloadReaderOpening(src, "root", alwaysFirstPage = true, targetWidthPx = 1080) { _, index, _ ->
            decoded += index
        }

        assertEquals("开关开启 ⇒ 首批从第 1 页起（这是这本书的全部 3 页）", listOf(0, 1, 2), decoded)
    }

    @Test
    fun `前置解的页序夹在两端`() {
        assertEquals("常规：落点起连续 3 页", listOf(1, 2, 3), preloadPageIndices(1, 10))
        assertEquals("末页附近：只剩剩下的页", listOf(8, 9), preloadPageIndices(8, 10))
        assertEquals("最后一页：只解它一个", listOf(9), preloadPageIndices(9, 10))
        assertEquals("只有 2 页的书全解", listOf(0, 1), preloadPageIndices(0, 2))
        assertEquals("空书不解码", emptyList<Int>(), preloadPageIndices(0, 0))
        assertEquals("落点越界也夹回界内", listOf(1), preloadPageIndices(7, 2))
    }

    @Test
    fun `首批里一页失败就停 不会把余下的都试一遍`() = runTest {
        val src = source()
        val attempts = mutableListOf<Int>()

        val opening = preloadReaderOpening(src, "root", alwaysFirstPage = true, targetWidthPx = 1080) { _, index, _ ->
            attempts += index
            if (index >= 1) throw IllegalStateException("来源在解码这一步断了")
        }

        assertEquals("第 2 页失败后不再试第 3 页（同一本书后续页多半同样失败）", listOf(0, 1), attempts)
        assertEquals("打开成功即前置有效（解码失败只吞掉，不把人留在书柜页）", 3, opening.handle.pageCount)
    }

    @Test
    fun `前置只兑现一次`() = runTest {
        val src = source()
        val prelude = ReaderPrelude()
        prelude.put("root", openForReading(src, "root", alwaysFirstPage = false))

        assertNotNull("第一次取到", prelude.take("root"))
        assertNull("取走即清槽：同一次打开只兑现一次", prelude.take("root"))
    }

    @Test
    fun `别的书的前置不认 也不清槽`() = runTest {
        val src = source()
        val prelude = ReaderPrelude()
        prelude.put("root", openForReading(src, "root", alwaysFirstPage = false))

        assertNull("导航参数与槽位错配时不把 A 的句柄交给 B", prelude.take("root/a"))
        assertNotNull("错配的取用不清槽，本主儿仍能取到", prelude.take("root"))
    }

    @Test
    fun `连点另一本会覆盖槽位`() = runTest {
        val src = DocumentTreeSource(
            backend = FakeTreeBackend(
                fakeDir("root").add(
                    fakeDir("root/a").add(fakeFile("root/a/1.jpg")),
                    fakeDir("root/b").add(fakeFile("root/b/1.jpg"), fakeFile("root/b/2.jpg")),
                ),
            ),
            progressStore = store,
        )
        val prelude = ReaderPrelude()
        prelude.put("root/a", openForReading(src, "root/a", alwaysFirstPage = false))
        prelude.put("root/b", openForReading(src, "root/b", alwaysFirstPage = false))

        assertTrue("后来者的前置生效", prelude.take("root/b")?.handle?.id == "root/b")
        assertNull("被覆盖的那本不再有前置", prelude.take("root/a"))
    }
}
