package com.cc3301.comicviewer.ui

import com.cc3301.comicviewer.core.source.BookOpening
import com.cc3301.comicviewer.core.source.DocumentTreeSource
import com.cc3301.comicviewer.core.source.FakeTreeBackend
import com.cc3301.comicviewer.core.source.InMemoryProgressStore
import com.cc3301.comicviewer.core.source.Source
import com.cc3301.comicviewer.core.source.fakeDir
import com.cc3301.comicviewer.core.source.fakeFile
import com.cc3301.comicviewer.core.source.openForReading
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.withContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
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

    // ---------- r5/r6：有界等待 + 放行（真机「点了没反应、卡在书柜」的真因） ----------

    @Test
    fun `前置成功先交句柄再放行`() = runTest {
        val src = source()
        val opening = openForReading(src, "root", alwaysFirstPage = false)
        var ready: BookOpening? = null
        var navigated = false

        awaitReaderPrelude(
            workScope = this,
            timeoutMillis = PRELUDE_TIMEOUT_MILLIS,
            preload = { opening },
            onReady = { ready = it },
            isRequestCurrent = { true },
            navigate = { navigated = true },
        )

        assertSame("就绪的句柄交给阅读页（省掉它自己那次打开）", opening, ready)
        assertTrue("就绪后照常放行", navigated)
    }

    @Test
    fun `前置拿不到前置也放行`() = runTest {
        var navigated = false
        var readyCalled = false

        awaitReaderPrelude(
            workScope = this,
            timeoutMillis = PRELUDE_TIMEOUT_MILLIS,
            preload = { null }, // 来源还没解析完
            onReady = { readyCalled = true },
            isRequestCurrent = { true },
            navigate = { navigated = true },
        )

        assertTrue("来源未就绪不是错误，照样进阅读页", navigated)
        // 守卫写成可观察状态：`onReady` 里直接 error(...) 会被本函数内部的 catch(Throwable) 吞掉，
        // 用例即使实现错误地交了句柄也永远绿（票 #108 r5 评审 standards P2-3）
        assertFalse("拿不到前置时不得交句柄", readyCalled)
    }

    @Test
    fun `前置抛错也放行`() = runTest {
        var readyCalled = false
        var navigated = false

        awaitReaderPrelude(
            workScope = this,
            timeoutMillis = PRELUDE_TIMEOUT_MILLIS,
            preload = { throw IllegalStateException("SMB 断链") },
            onReady = { readyCalled = true },
            isRequestCurrent = { true },
            navigate = { navigated = true },
        )

        assertTrue("前置失败必须进阅读页（那里有失败提示与重试，书柜页没有提示位）", navigated)
        assertFalse("失败的这次不交句柄", readyCalled)
    }

    @Test
    fun `前置超时也放行 且等待被上限截断`() = runTest {
        var readyCalled = false
        var navigated = false
        var preloadFinished = false

        awaitReaderPrelude(
            workScope = this,
            timeoutMillis = 100,
            preload = {
                delay(10_000) // 慢来源：取页长时间不返回
                preloadFinished = true
                null
            },
            onReady = { readyCalled = true },
            isRequestCurrent = { true },
            navigate = { navigated = true },
        )

        assertTrue("超时也必须进阅读页（进去后由阅读器自己显示加载态）", navigated)
        assertFalse("等待被上限截断：没有等完前置那 10s", preloadFinished)
        assertFalse("超时的那次不交句柄", readyCalled)
    }

    @Test
    fun `前置体阻塞不响应取消时也到点放行`() = runTest {
        // 真机来源里 Komga 的打开/取页是**阻塞** OkHttp（调用期间协程在运行、不在挂起），取消要等它自己返回。
        // 上限因此必须包在**等待侧**（`deferred.await()` 是可取消挂起点），不能包在阻塞的前置体上。
        val blocked = CompletableDeferred<Unit>()
        val workScope = CoroutineScope(Dispatchers.Default)
        var navigated = false
        var preloadFinished = false
        var readyCalled = false

        awaitReaderPrelude(
            workScope = workScope,
            timeoutMillis = PRELUDE_TIMEOUT_MILLIS,
            preload = {
                withContext(NonCancellable) { blocked.await() } // 阻塞且不响应取消
                preloadFinished = true
                null
            },
            onReady = { readyCalled = true },
            isRequestCurrent = { true },
            navigate = { navigated = true },
        )

        assertTrue("阻塞体拦不住 1.5s 放行（上限包的是等待）", navigated)
        assertFalse("到点时前置体还没返回", preloadFinished)
        assertFalse("阻塞的前置没有句柄可交", readyCalled)
        blocked.complete(Unit) // 收尾：让后台工作结束，不留下悬挂的协程
        workScope.cancel()
    }

    @Test
    fun `被取消时不导航`() = runTest {
        // 仓库口径（`ui/Cancellation.kt` 票 #26 / `docs/SPEC.md:208`）：取消照常传播，**不在取消后导航**。
        // 本函数的取消只可能来自「被后一次点击顶替」与「已离开组合」，两处都不该把人拉进阅读页。
        var navigated = false
        var readyCalled = false
        val job = launch {
            awaitReaderPrelude(
                workScope = this@runTest,
                timeoutMillis = 3_600_000, // 远大于取消：这一条不是超时路径
                preload = {
                    delay(10_000)
                    null
                },
                onReady = { readyCalled = true },
                isRequestCurrent = { true },
                navigate = { navigated = true },
            )
        }

        runCurrent()
        job.cancelAndJoin()

        assertFalse("取消不是「放行」：组合已销毁/已被顶替时不得导航", navigated)
        assertFalse("被取消的那次不交句柄", readyCalled)
    }

    @Test
    fun `守卫为假时不导航`() = runTest {
        // 第二道守卫（组合仍存活 + 仍是当前那次点击）：取消已经挡住绝大多数，这一道防「取消还没送达」的窄窗口
        val src = source()
        val opening = openForReading(src, "root", alwaysFirstPage = false)
        var ready: BookOpening? = null
        var navigated = false

        awaitReaderPrelude(
            workScope = this,
            timeoutMillis = PRELUDE_TIMEOUT_MILLIS,
            preload = { opening },
            onReady = { ready = it },
            isRequestCurrent = { false }, // 已离开组合 / 已被后一次点击顶替
            navigate = { navigated = true },
        )

        assertSame("就绪的句柄照旧交出来（阅读页下次进来还能用）", opening, ready)
        assertFalse("守卫为假时不得导航", navigated)
    }

    @Test
    fun `前置等待上限不超过维护者要求的 1_5 秒`() {
        assertTrue(
            "维护者口径：超时上限 ≤1.5s（进去后由阅读器自己显示加载态），当前 $PRELUDE_TIMEOUT_MILLIS",
            PRELUDE_TIMEOUT_MILLIS <= 1_500,
        )
    }
}
