package com.cc3301.comicviewer.ui

import androidx.test.core.app.ApplicationProvider
import com.cc3301.comicviewer.core.nav.LastRead
import com.cc3301.comicviewer.core.source.DocumentTreeSource
import com.cc3301.comicviewer.core.source.FakeTreeBackend
import com.cc3301.comicviewer.core.source.InMemoryProgressStore
import com.cc3301.comicviewer.core.source.Source
import com.cc3301.comicviewer.core.source.fakeDir
import com.cc3301.comicviewer.core.source.fakeFile
import com.cc3301.comicviewer.core.source.openBookAtLanding
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 切进阅读页这一刻的落地（票 #110）：阅读进度与「上次阅读位置」写在**同一时点**。
 *
 * 维护者指示（补记 2）：点开一本书后立刻取消（改点另一本 / 返回 / 切走）时，书没被打开，
 * 但「上次阅读位置」已经被改写成这本没打开的书——启动还原与抽屉「阅读器」入口读的就是它。
 * 口径是：两者一起推迟到**真正切进阅读页那一刻**，并且只保留**一个**新记录写入点
 * （[recordReaderEntry]，前置分支经 [commitReaderEntry] 走它）。点击路径（`BrowserScreen`）与
 * 读内换书（`AppNav` 的 `onOpenBook`）都不再写。
 *
 * 观测点是**落盘的那份记录**（`StartupStore.lastRead()`）：它正是「继续上次阅读」读的东西，
 * 因此这里同时钉住「不得新建第二份记录」——不是只看内存里的 `ServiceLocator.lastRead`。
 *
 * 走 Robolectric 是必要的：`ServiceLocator.lastRead` 的 setter 直落 SharedPreferences（票 20），
 * 纯 JVM 下拿不到 Context。用真接线的收益是断言打在**生产那条写**上，不为测试另开注入缝。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ReaderEntryLandingTest {

    private val store = InMemoryProgressStore()

    @Before
    fun setUp() {
        ServiceLocator.init(ApplicationProvider.getApplicationContext())
        ServiceLocator.lastRead = null
    }

    @After
    fun tearDown() {
        ServiceLocator.lastRead = null
    }

    /** 两本书（各 2 页）：用来表达「先点 a、再点 b」与「被顶替的那本不留记录」 */
    private fun source(): Source = DocumentTreeSource(
        backend = FakeTreeBackend(
            fakeDir("root").add(
                fakeDir("root/a").add(fakeFile("root/a/1.jpg"), fakeFile("root/a/2.jpg")),
                fakeDir("root/b").add(fakeFile("root/b/1.jpg"), fakeFile("root/b/2.jpg")),
            ),
        ),
        progressStore = store,
    )

    @Test
    fun `点开就取消 进度与上次阅读位置都不变`() = runTest {
        val src = source()
        store.write("root/a", 1, 2) // 已读到第 2 页
        val prelude = ReaderPrelude()
        // 点击路径只做这一件事：把「要开哪本」登记进前置槽（BrowserScreen 的点击闸）
        prelude.put(connId = 7, bookId = "root/a", openBookAtLanding(src, "root/a", alwaysFirstPage = true))
        // 取消：前置没人取走（改点另一本 / 返回 / 切走）

        assertNull("取消不得写「上次阅读位置」（启动还原读的就是这条）", StartupStore.lastRead())
        assertEquals("取消不得改进度", 1, store.read("root/a")?.pageIndex)
    }

    @Test
    fun `超时没兑现的前置 进度与上次阅读位置都不变`() = runTest {
        val src = source()
        store.write("root/a", 1, 2)
        var readyCalled = false

        awaitReaderPrelude(
            workScope = this,
            timeoutMillis = 100,
            preload = {
                // 慢来源：书已打开（前置体跑到了这一步），但首批解码迟迟不返回 ⇒ 上限到点放行
                openBookAtLanding(src, "root/a", alwaysFirstPage = true)
                delay(10_000)
                null
            },
            onReady = { readyCalled = true },
            isRequestCurrent = { true },
            navigate = {},
        )

        assertFalse("超时的那次不交句柄（照旧进阅读页，由阅读页自己开书）", readyCalled)
        assertNull("超时没兑现：不得写「上次阅读位置」", StartupStore.lastRead())
        assertEquals("也不得改进度", 1, store.read("root/a")?.pageIndex)
    }

    @Test
    fun `切进阅读页那一刻 进度与上次阅读位置一起落地`() = runTest {
        val src = source()
        store.write("root/a", 1, 2)
        val prelude = ReaderPrelude()
        prelude.put(connId = 7, bookId = "root/a", openBookAtLanding(src, "root/a", alwaysFirstPage = true))

        assertNull("还没切页：两样都还没写", StartupStore.lastRead())
        assertEquals("前置只开书不写进度", 1, store.read("root/a")?.pageIndex)

        // 阅读页组合期取走前置 → 同一处落地（ReaderScreen 的前置分支）
        val opening = prelude.take(connId = 7, bookId = "root/a")!!
        commitReaderEntry(src, connId = 7, bookId = "root/a", alwaysFirstPage = true, opening)

        assertEquals("启动还原 / 抽屉「阅读器」读的就是这一条记录", LastRead(7, "root/a"), StartupStore.lastRead())
        assertEquals(
            "同一时点落地的进度覆盖（故事 40：进入马上退出也只算读了 1 页）",
            0,
            store.read("root/a")?.pageIndex,
        )
    }

    @Test
    fun `被后一次点击顶替 只有后者生效`() = runTest {
        val src = source()
        val prelude = ReaderPrelude()
        prelude.put(connId = 7, bookId = "root/a", openBookAtLanding(src, "root/a", alwaysFirstPage = true))
        prelude.put(connId = 7, bookId = "root/b", openBookAtLanding(src, "root/b", alwaysFirstPage = true))

        val opening = prelude.take(connId = 7, bookId = "root/b")!!
        commitReaderEntry(src, connId = 7, bookId = "root/b", alwaysFirstPage = true, opening)

        assertEquals("只有后者被记成上次阅读位置", LastRead(7, "root/b"), StartupStore.lastRead())
        assertEquals("后者的进度落地", 0, store.read("root/b")?.pageIndex)
        assertNull("被顶替的那本不留任何记录（书柜上的「在读」与进度都不该出现）", store.read("root/a"))
    }
}
