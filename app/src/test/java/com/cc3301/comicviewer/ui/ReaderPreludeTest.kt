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
 * 打开前置（票 #108 E1-A）：点击书时先在书柜页把「打开 + 首帧解码」做完，阅读页再同步取走。
 *
 * 维护者现象：点开一本书先出现黑底「准备打开」整页。修法是**留在书柜页等**（等首批解码），就绪后一次性切页；
 * 因此这两件事必须成立：① 前置确实按**这本书自己的落点**解码首帧（否则切页后首帧不是要显示的那一页）；
 * ② 前置是**可兑现一次**的槽（否则反复取用会让阅读页拿到过期句柄）。
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
    fun `前置按这本书自己的落点解码首帧`() = runTest {
        val src = source()
        store.write("root", 2, 3) // 读到第 3 页
        val decoded = mutableListOf<Triple<String, Int, Int>>()

        val opening = preloadReaderOpening(src, "root", alwaysFirstPage = false, targetWidthPx = 1080) { handle, index, width ->
            decoded += Triple(handle.id, index, width)
        }

        assertEquals("落点是这本书自己的进度", 2, opening.startIndex)
        assertEquals("前置句柄就是这本书的", "root", opening.handle.id)
        assertEquals("按落点解码一次，且用阅读页的目标宽度", listOf(Triple("root", 2, 1080)), decoded)
    }

    @Test
    fun `开启始终从第一页时前置也落在第一页`() = runTest {
        val src = source()
        store.write("root", 2, 3)
        val decoded = mutableListOf<Int>()

        preloadReaderOpening(src, "root", alwaysFirstPage = true, targetWidthPx = 1080) { _, index, _ ->
            decoded += index
        }

        assertEquals("开关开启 ⇒ 首帧解第 1 页", listOf(0), decoded)
    }

    @Test
    fun `首帧解码失败照常交出前置`() = runTest {
        val src = source()

        val opening = preloadReaderOpening(src, "root", alwaysFirstPage = false, targetWidthPx = 1080) { _, _, _ ->
            throw IllegalStateException("来源在解码这一步断了")
        }

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
