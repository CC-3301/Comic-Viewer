package com.cc3301.comicviewer.ui

import com.cc3301.comicviewer.core.source.DocumentTreeSource
import com.cc3301.comicviewer.core.source.EnumerationStats
import com.cc3301.comicviewer.core.source.FakeTreeBackend
import com.cc3301.comicviewer.core.source.InMemoryProgressStore
import com.cc3301.comicviewer.core.source.ListingSnapshotStore
import com.cc3301.comicviewer.core.source.SortMode
import com.cc3301.comicviewer.core.source.SnapshotHit
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
import java.nio.file.Files

/**
 * 硬切档「先落快照再切」（票 #111 ②，维护者 2026-09-27 拍板走 B「预置会话槽」）。
 *
 * 契约（票面四条约束里的第 2、3 条）：
 * - **垫进会话槽的必须是目标层**（[primeLayerSnapshot] / [Source.primeCachedEntries]）：写成别的层会让新屏
 *   显示另一层的内容——比「闪一下」严重得多，本类的第一条用例就钉这一条（把 containerId 换成父层即红）；
 * - 垫进去之后新屏**构造期**那句同步读（`BrowserScreen` 的 `preloaded` = `Source.cachedEntries`）就有内容
 *   ⇒ `BrowsePageLoader` 的第一帧就是这份列表（不是「加载中…」）；
 * - **代价有界**：预置只读本地落盘快照，不列目录、不探测、不发请求（用例按 `EnumerationStats` 核对），
 *   且预置的层就是这次点击的目标层（没有「猜错」可言）。
 *
 * 夹具与 `BrowseRefreshTest` 同构（`Files.createTempDirectory` 落在门禁钉到仓库内的 `tmp/tests`，
 * 随 `@After` 删除，不堆到系统盘）：**两个来源实例共用一个落盘快照目录**——第一个负责把快照写上盘，
 * 第二个是「会话内存为空」的那个（真实场景：进程重启后第一次进这一层，或内存表被上界腾掉）。
 */
class BrowseSnapshotPrimeTest {

    private lateinit var workDir: File

    /** 目标层（`root/A`）与它的两个兄弟层；`root/A` 本层是容器（有子目录），父子关系键都在 id 里 */
    private val parentLayer = "root/A"
    private val siblingLayer = "root/A/第002话"
    private val targetLayer = "root/A/第001话"

    @Before
    fun setUp() {
        workDir = Files.createTempDirectory("browse-prime").toFile()
    }

    @After
    fun tearDown() {
        workDir.deleteRecursively()
    }

    private fun library(): FakeTreeBackend = FakeTreeBackend(
        fakeDir("root")
            .add(
                fakeDir(parentLayer)
                    .add(fakeDir(targetLayer).add(fakeFile("$targetLayer/001.jpg")))
                    .add(fakeDir(siblingLayer).add(fakeFile("$siblingLayer/001.jpg"))),
            ),
    )

    private fun source(backend: FakeTreeBackend): DocumentTreeSource = DocumentTreeSource(
        backend = backend,
        progressStore = InMemoryProgressStore(),
        listingSnapshots = ListingSnapshotStore(listingSnapshotDir(workDir), connectionId = 7L),
    )

    /** 第一个实例把这三层的落盘快照写上盘；返回**会话内存为空**的第二个实例（同一份落盘目录） */
    private fun sourceWithPersistedSnapshotsOnly(): DocumentTreeSource = runBlocking {
        val writer = source(library())
        writer.listEntries(parentLayer, SortMode.NAME)
        writer.listEntries(targetLayer, SortMode.NAME)
        writer.listEntries(siblingLayer, SortMode.NAME)
        assertNotNull("前置：目标层的落盘快照已写上盘", writer.snapshotEntries(targetLayer, SortMode.NAME))
        source(library())
    }

    @Test
    fun `预置会话槽只垫目标层 父层与兄弟层仍为空`() = runBlocking<Unit> {
        val source = sourceWithPersistedSnapshotsOnly()
        assertNull("前置：新实例的会话槽是空的（冷启动/腾空后的形状）", source.cachedEntries(targetLayer, SortMode.NAME))

        primeLayerSnapshot(source, targetLayer, SortMode.NAME)

        assertEquals(
            "垫进去的是**目标层**的内容（写成父层/兄弟层的键就是「新屏显示别的层」这种硬故障）",
            listOf("001.jpg"),
            source.cachedEntries(targetLayer, SortMode.NAME)?.map { it.name },
        )
        assertNull("父层的键没被碰过", source.cachedEntries(parentLayer, SortMode.NAME))
        assertNull("兄弟层的键也没被碰过", source.cachedEntries(siblingLayer, SortMode.NAME))
    }

    @Test
    fun `预置之后新屏构造期那一帧就是目标层内容 不先显示加载中`() = runBlocking<Unit> {
        val source = sourceWithPersistedSnapshotsOnly()
        // 未预置的那一份：构造期读不到东西 ⇒ 新屏第一帧只能显示「加载中…」（界面的 entries 为 null）
        assertFalse(
            "不预置时首帧没有内容（这一条就是本票要消掉的那一下空）",
            BrowsePageLoader(
                source,
                targetLayer,
                SortMode.NAME,
                snapshot = source.cachedEntries(targetLayer, SortMode.NAME),
            ).loaded,
        )

        primeLayerSnapshot(source, targetLayer, SortMode.NAME)

        val pager = BrowsePageLoader(
            source,
            targetLayer,
            SortMode.NAME,
            snapshot = source.cachedEntries(targetLayer, SortMode.NAME),
        )
        assertTrue("预置之后构造完就是已落帧状态（不用等任何 effect）", pager.loaded)
        assertEquals(
            "首帧画的就是目标层的列表",
            listOf("001.jpg"),
            pager.entries.map { it.name },
        )
    }

    @Test
    fun `没有落盘快照的层预置是空操作 不抛也不写错键`() = runBlocking<Unit> {
        val source = sourceWithPersistedSnapshotsOnly()

        primeLayerSnapshot(source, "root/没有这一层", SortMode.NAME)

        assertNull("没快照可垫：会话槽照旧为空", source.cachedEntries("root/没有这一层", SortMode.NAME))
        assertNull("别人的键不被顺手写入", source.cachedEntries(parentLayer, SortMode.NAME))
    }

    @Test
    fun `预置只读本地落盘快照 不因此多列一次目录`() = runBlocking<Unit> {
        val source = sourceWithPersistedSnapshotsOnly()

        primeLayerSnapshot(source, targetLayer, SortMode.NAME)
        val stats = EnumerationStats()
        val entries = source.enumerateEntries(targetLayer, SortMode.NAME, stats)

        assertEquals("预置进来的就是这一层：枚举命中会话槽（不列目录、不探测）", SnapshotHit.MEMORY, stats.hit)
        assertEquals("0 次列目录（票面第 3 条：预置不能让列表请求变多）", 0, stats.childrenCalls)
        assertEquals(0, stats.probes)
        assertEquals(listOf("001.jpg"), entries.map { it.name })
    }
}
