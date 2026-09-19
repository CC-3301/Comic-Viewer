package com.cc3301.comicviewer.core.source

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 换书后的落点（票 #68）：一次打开只认**这本书自己**的进度 + 当前开关值。
 *
 * 维护者复现：A 读到第 3 页 → 换 B → 换回 A，开关开着却仍停在第 3 页。落点公式本身没错
 * （见 ProgressMathTest），错在阅读页把上一本的页位当成了宿主态；因此这里钉的是
 * 「落点只属于被打开的那本书」——A/B/A 三次打开各自算自己的，开关开启时还连落盘进度一起覆盖成第 1 页。
 */
class OpenForReadingTest {

    private val store = InMemoryProgressStore()

    /** 同一根下的两本书：a=3 页、b=2 页（页数不同，用来暴露「页数/页位带了上一本的」） */
    private fun source(): Source = DocumentTreeSource(
        backend = FakeTreeBackend(
            fakeDir("root").add(
                fakeDir("root/a").add(
                    fakeFile("root/a/1.jpg"),
                    fakeFile("root/a/2.jpg"),
                    fakeFile("root/a/3.jpg"),
                ),
                fakeDir("root/b").add(fakeFile("root/b/1.jpg"), fakeFile("root/b/2.jpg")),
            ),
        ),
        progressStore = store,
    )

    @Test
    fun `开关开启 换到哪本都落在第1页并覆盖该书的落盘进度`() = runTest {
        val source = source()
        store.write("root/a", 2, 3) // A 读到第 3 页

        assertEquals("A：开关开启固定第 1 页", 0, openForReading(source, "root/a", alwaysFirstPage = true).startIndex)
        assertEquals("B：第 1 页，A 的第 3 页不得带过来", 0, openForReading(source, "root/b", alwaysFirstPage = true).startIndex)
        assertEquals("换回 A：B 读过什么都不影响", 0, openForReading(source, "root/a", alwaysFirstPage = true).startIndex)

        assertEquals("A 的落盘进度被覆盖为第 1 页（进入马上退出也只算读了 1 页）", 0, store.read("root/a")!!.pageIndex)
        assertEquals("B 同理", 0, store.read("root/b")!!.pageIndex)
    }

    @Test
    fun `开关关闭 换书各自回自己保存的页码且不互相改写`() = runTest {
        val source = source()
        store.write("root/a", 2, 3)
        store.write("root/b", 1, 2)

        assertEquals("A 回自己的第 3 页", 2, openForReading(source, "root/a", alwaysFirstPage = false).startIndex)
        assertEquals("B 回自己的第 2 页（不串 A 的页位）", 1, openForReading(source, "root/b", alwaysFirstPage = false).startIndex)
        assertEquals("再换回 A 仍是 A 自己的页位", 2, openForReading(source, "root/a", alwaysFirstPage = false).startIndex)

        assertEquals("开关关闭时打开不改写进度", 2, store.read("root/a")!!.pageIndex)
        assertEquals("换书也不改写另一本的进度", 1, store.read("root/b")!!.pageIndex)
    }

    @Test
    fun `未读的书从第1页开始 且不落盘`() = runTest {
        val source = source()

        assertEquals(0, openForReading(source, "root/b", alwaysFirstPage = false).startIndex)
        assertNull("开关关闭时打开不写进度（只有开关开启才覆盖）", store.read("root/b"))
    }

    @Test
    fun `句柄与页数都是被打开的那本书的`() = runTest {
        val source = source()

        val b = openForReading(source, "root/b", alwaysFirstPage = false)

        assertEquals("root/b", b.handle.id)
        assertEquals("页数取新书：A=3 页不得带过来", 2, b.handle.pageCount)
    }
}
