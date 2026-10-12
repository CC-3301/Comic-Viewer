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
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * 下拉更新的**失效范围**：一次下拉更新要失效什么、不该失效什么，
 * 以及「封面重取」为什么必须与「清封面字节缓存」同时发生。
 *
 * 两件事分头写在两处（[com.cc3301.comicviewer.core.source.Source.invalidateForRefresh] 与界面的
 * `BrowserScreen.reloadTick`，经 `CoverPlan.reloadKey` 进解码键），没有任何一处说明它们必须配对；
 * 收成 [applyPullToRefresh] 之后，这里的断言就是这个入口的契约。
 *
 * 失效范围：
 * - 清：**整个来源**的封面字节缓存（列表那一半由「每次进目录真列一次 + 增量比对」保证，
 *   清掉列表快照反而会毁掉比对基线——它由 `DocumentTreeListingSnapshotTest` 的
 *   「下拉更新不动落盘快照」钉住）；
 * - 不清：列表快照（内存 + 落盘）——下一次进这一层因此仍是 0 次探测；
 * - 推：封面重取键前进一次（可见行封面换代重取）。
 *
 * 不在本测试范围：走 uri 的本地图片封面**有意**不吃重取键（见 `CoverRoute.uriKey`），
 * 设备上「下拉后封面视觉刷新」是渲染观感（按 SPEC 走设备验收）。
 *
 * 夹具走仓内惯例（`Files.createTempDirectory`，与 `DocumentTreeListingSnapshotTest` / `DocumentTreeCoverCacheEvictionTest`
 * 同构）：门禁把测试 JVM 的 `java.io.tmpdir` 钉到仓库内 `tmp/tests`（`app/build.gradle.kts`），
 * 因此夹具落在 worktree 内、随 `@After` 一起删，不会堆到系统盘。
 */
class BrowseRefreshTest {

    private lateinit var workDir: File

    @Before
    fun setUp() {
        workDir = Files.createTempDirectory("browse-refresh").toFile()
    }

    @After
    fun tearDown() {
        workDir.deleteRecursively()
    }

    @Test
    fun `下拉更新不清列表快照 比对基线留着 别层也不受影响`() = runBlocking {
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
        var refetchKeyAdvances = 0

        applyPullToRefresh(source, "root/第001话") { refetchKeyAdvances++ }

        assertEquals("封面重取键前进一次（封面重取是这次失效的下游）", 1, refetchKeyAdvances)
        assertNotNull(
            "这一层的落盘快照留着当增量比对基线（清了下次进这层就整层重探）",
            store.read("root/第001话"),
        )
        assertNotNull("这一层的内存快照同样留着", source.cachedEntries("root/第001话", SortMode.NAME))
        assertNotNull("别的层的落盘快照不受影响", store.read("root/第002话"))
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
