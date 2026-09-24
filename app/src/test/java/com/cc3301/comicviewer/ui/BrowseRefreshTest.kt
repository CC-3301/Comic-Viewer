package com.cc3301.comicviewer.ui

import com.cc3301.comicviewer.core.source.CountingBackend
import com.cc3301.comicviewer.core.source.DocumentTreeSource
import com.cc3301.comicviewer.core.source.FakeTreeBackend
import com.cc3301.comicviewer.core.source.InMemoryProgressStore
import com.cc3301.comicviewer.core.source.ListingSnapshotStore
import com.cc3301.comicviewer.core.source.SortMode
import com.cc3301.comicviewer.core.source.fakeDir
import com.cc3301.comicviewer.core.source.fakeFile
import com.cc3301.comicviewer.core.source.listingSnapshotDir
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

/**
 * 下拉更新的**失效范围**（票 #136 步骤②，验收项 2 的最后一条）：一次下拉更新要失效什么、
 * 不该失效什么，以及「封面重取」为什么必须与「列表快照清理」同时发生。
 *
 * 现状（改前）是两条路互不知情：清列表快照与封面字节缓存写在**两个来源实现各自的**
 * [com.cc3301.comicviewer.core.source.Source.invalidateListCache] 里，而封面重取键（`BrowserScreen.reloadTick`，
 * 经 `CoverPlan.reloadKey` 进解码键）是界面自己 `++` 的——两处各写一遍、没有任何一处说明它们必须配对。
 * 收成 [applyPullToRefresh] 之后，这里的断言就是这个入口的契约。
 *
 * 失效范围（按现状钉住，**不含**任何扩大）：
 * - 清：**当前这一层**的列表快照（内存 + 落盘，票 #74）与**整个来源**的封面字节缓存（票 #51）；
 * - 不清：别的层（别的容器）的快照——它们没被刷新，下一次进那层照旧命中；
 * - 推：封面重取键前进一次（列表重新枚举、可见行封面换代重取）。
 *
 * 不在本测试范围：走 uri 的本地图片封面**有意**不吃重取键（票 #53 口径，见 `CoverRoute.uriKey`），
 * 真机上「下拉后封面视觉刷新」是渲染观感（按 SPEC 走真机验收）。
 *
 * 夹具落在 `build/tmp/browse-refresh`（Gradle 单测的 cwd 是模块目录，即 worktree 内的 `app/build`）：
 * 可随构建产物一起删，不进系统临时目录。
 */
class BrowseRefreshTest {

    private val workDir = File(System.getProperty("user.dir"), "build/tmp/browse-refresh")

    @Before
    fun setUp() {
        workDir.deleteRecursively()
        workDir.mkdirs()
    }

    @After
    fun tearDown() {
        workDir.deleteRecursively()
    }

    @Test
    fun `下拉更新只失效这一层 落盘与内存快照一起清 封面重取键前进一次`() = runBlocking {
        // 「一个子文件夹一本书」的库：两个兄弟容器，各有一本书
        val backend = FakeTreeBackend(
            fakeDir("root")
                .add(fakeDir("root/第001话").add(fakeFile("root/第001话/001.jpg")))
                .add(fakeDir("root/第002话").add(fakeFile("root/第002话/001.jpg"))),
        )
        val store = ListingSnapshotStore(listingSnapshotDir(workDir), connectionId = 7L)
        val source = DocumentTreeSource(
            backend = backend,
            progressStore = InMemoryProgressStore(),
            listingSnapshots = store,
        )
        source.listEntries("root/第001话", SortMode.NAME)
        source.listEntries("root/第002话", SortMode.NAME)
        assertNotNull("前置：被刷的那一层有落盘快照", store.read("root/第001话"))
        assertNotNull("前置：另一层也有落盘快照", store.read("root/第002话"))
        var refetchKeyAdvances = 0

        applyPullToRefresh(source, "root/第001话") { refetchKeyAdvances++ }

        assertEquals("封面重取键前进一次（封面重取是这次失效的下游）", 1, refetchKeyAdvances)
        assertNull(
            "这一层的落盘快照要真作废（否则重进这一层命中旧数据）",
            store.read("root/第001话"),
        )
        assertNull("这一层的内存快照同步清掉", source.cachedEntries("root/第001话", SortMode.NAME))
        assertNotNull("失效范围 = 这一层：别的层的落盘快照不受影响", store.read("root/第002话"))
        assertNotNull("别的层的内存快照也不受影响", source.cachedEntries("root/第002话", SortMode.NAME))
    }

    @Test
    fun `下拉更新清封面字节缓存 于是封面真的重新读一次字节`() = runBlocking {
        val dir = File(workDir, "cover-tree").apply { mkdirs() }
        File(dir, "001.jpg").writeBytes(byteArrayOf(1, 2, 3))
        File(dir, "002.jpg").writeBytes(byteArrayOf(4, 5, 6))
        val backend = CountingBackend(dir)
        val source = DocumentTreeSource(backend, InMemoryProgressStore())
        val book = source.listEntries(null, SortMode.NAME).first()
        assertNotNull("前置：取到封面字节", source.coverBytes(book.id))
        assertTrue("前置：字节缓存里有它", source.hasCachedCoverBytes(book.id))
        backend.resetCounters()

        applyPullToRefresh(source, null) { }

        assertFalse(
            "下拉更新要清封面字节缓存（不清的话同一条目还会把旧封面还回去）",
            source.hasCachedCoverBytes(book.id),
        )
        assertNotNull("清掉之后封面照旧取得到", source.coverBytes(book.id))
        assertEquals(
            "清缓存之后确实重新读了一次字节（旧口径这里会是空列表：仍命中缓存）",
            listOf("/001.jpg"),
            backend.readPaths,
        )
    }

    @Test
    fun `来源还没解析出来时也要推进重取键 不抛`() {
        var refetchKeyAdvances = 0

        applyPullToRefresh(source = null, containerId = null) { refetchKeyAdvances++ }

        assertEquals("没就绪也照样换代：列表要重新解析", 1, refetchKeyAdvances)
    }
}
