package com.cc3301.comicviewer.ui

import com.cc3301.comicviewer.core.data.ConnectionEntity
import com.cc3301.comicviewer.core.source.DocumentTreeSource
import com.cc3301.comicviewer.core.source.FakeTreeBackend
import com.cc3301.comicviewer.core.source.InMemoryProgressStore
import com.cc3301.comicviewer.core.source.Neighbors
import com.cc3301.comicviewer.core.source.SortMode
import com.cc3301.comicviewer.core.source.SourceType
import com.cc3301.comicviewer.core.source.fakeDir
import com.cc3301.comicviewer.core.source.fakeFile
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Before
import org.junit.Test

/**
 * 会话级浏览来源的 App 接线（票 #30 P1）：浏览页/柜页按路由 connId 解析来源时走
 * [ServiceLocator.browsingSourceFor]，同一连接复用同一个实例——挂在实例上的会话级列表缓存因此
 * 能跨页面导航生效（进子目录 → 返回上级不再重新枚举整层），同时少一次 SMB 建连；
 * 换到别的连接 / 连接被删除或编辑 / App 退出都释放，不留未关闭的会话（票 11 纪律）。
 *
 * 断言打在 App 接线上（服务定位器的解析入口 + 计数型后端），不是「测试自己持有一个 Source」的场景。
 */
class BrowsingSourceSessionTest {

    /** 每次解析新建的后端（生产里 = 新建 backend/transport，SMB 等于新建一条会话） */
    private val backends = mutableListOf<FakeTreeBackend>()

    @Before
    fun setUp() {
        // 测试接缝：把真实 backend 换成内存目录树（生产默认是 sourceForConnection）
        ServiceLocator.sourceFactory = {
            val backend = FakeTreeBackend(libraryTree())
            backends += backend
            DocumentTreeSource(
                backend = backend,
                progressStore = InMemoryProgressStore(),
                sourceType = SourceType.SMB,
            )
        }
        ServiceLocator.currentSource = null
    }

    @After
    fun tearDown() {
        ServiceLocator.closeBrowsingSource()
        ServiceLocator.currentSource = null
        // 会话连接 id 与来源同源（票 26 r2 修正 5）：本类会赋值，不还原会串进同 sandbox 的后续用例
        ServiceLocator.currentConnId = null
        ServiceLocator.sourceFactory = { ServiceLocator.sourceForConnection(it) }
        backends.clear()
    }

    @Test
    fun `同一连接两次页面级解析复用同一实例 返回上级命中会话级列表缓存`() {
        val conn = smbConnection(id = 7)

        val first = runBlocking { ServiceLocator.browsingSourceFor(conn) }
        runBlocking { first.listEntries(null, SortMode.NAME) } // 进入：整层枚举一次

        val second = runBlocking { ServiceLocator.browsingSourceFor(conn) }
        assertSame("同一连接两次页面级解析必须复用同一实例（缓存与 SMB 会话跨页面存活）", first, second)
        assertEquals("同一连接只建了一个 backend（SMB 建 backend 即建会话）", 1, backends.size)

        val root = backends.single().root
        runBlocking { second.listEntries(null, SortMode.NAME) } // 返回上级
        assertEquals("返回上级/二次进入必须命中会话级列表缓存：0 次列目录", 1, root.childrenCalls)
    }

    @Test
    fun `换到别的连接时释放上一个来源`() {
        val first = runBlocking { ServiceLocator.browsingSourceFor(smbConnection(id = 7)) }
        val second = runBlocking { ServiceLocator.browsingSourceFor(smbConnection(id = 8)) }

        assertNotSame("换连接必须换实例", first, second)
        assertEquals("新连接解析时建好了自己的 backend", 2, backends.size)
        awaitCloseCount("换连接必须释放上一个会话（票 11：不同时挂 N 个会话）", 1, backends.first()::closeCount)
        assertEquals("当前连接的会话不受影响", 0, backends.last().closeCount)
    }

    @Test
    fun `阅读器正在用的实例换连接时先不关 会话来源被替换后由 setter 释放`() {
        val first = runBlocking { ServiceLocator.browsingSourceFor(smbConnection(id = 7)) }
        ServiceLocator.currentSource = first // 阅读器路由只认它

        val second = runBlocking { ServiceLocator.browsingSourceFor(smbConnection(id = 8)) }
        assertNotSame(first, second)
        assertEquals("阅读器正在用的实例不能立刻关（守卫见 releaseReplacedSource）", 0, backends.first().closeCount)

        ServiceLocator.currentSource = second // 打开第二本书：会话来源被替换，旧实例此时才释放
        awaitCloseCount("会话来源被替换后旧实例由 setter 释放（只关一次）", 1, backends.first()::closeCount)
    }

    @Test
    fun `连接配置变化时释放上一个来源`() {
        val conn = smbConnection(id = 7)
        val first = runBlocking { ServiceLocator.browsingSourceFor(conn) }

        val edited = runBlocking {
            ServiceLocator.browsingSourceFor(conn.copy(configJson = "{\"host\":\"nas2\",\"share\":\"comics\"}"))
        }

        assertNotSame(first, edited)
        awaitCloseCount("编辑连接（configJson 变化）后旧会话必须释放", 1, backends.first()::closeCount)
    }

    @Test
    fun `删除或编辑连接时按连接释放 阅读器正在用的实例不关`() {
        runBlocking { ServiceLocator.browsingSourceFor(smbConnection(id = 7)) }

        ServiceLocator.closeBrowsingSource(connId = 8)
        assertEquals("别的连接不受影响", 0, backends.single().closeCount)

        ServiceLocator.closeBrowsingSource(connId = 7) // 删除连接走这条
        awaitCloseCount("删除连接必须释放该连接的会话来源", 1, backends.single()::closeCount)
    }

    @Test
    fun `阅读器正在用的实例按连接释放时也不关 但槽位已清空`() {
        val first = runBlocking { ServiceLocator.browsingSourceFor(smbConnection(id = 7)) }
        ServiceLocator.currentSource = first // 阅读器路由只认它

        ServiceLocator.closeBrowsingSource(connId = 7)

        assertEquals("删连接不该在半途打断正在读的那本书（守卫见 releaseReplacedSource）", 0, backends.single().closeCount)
        assertNotSame(
            "槽位已清空：下一次解析是新建实例，旧实例仍由阅读器持有",
            first,
            runBlocking { ServiceLocator.browsingSourceFor(smbConnection(id = 7)) },
        )
    }

    @Test
    fun `App 级释放入口关掉当前会话来源 槽位清空后再次解析是新建实例`() {
        val first = runBlocking { ServiceLocator.browsingSourceFor(smbConnection(id = 7)) }

        ServiceLocator.closeSession() // App 退出（MainActivity.onDestroy）走这条
        awaitCloseCount("App 级入口必须关掉跨页面存活的会话来源", 1, backends.single()::closeCount)

        val again = runBlocking { ServiceLocator.browsingSourceFor(smbConnection(id = 7)) }
        assertNotSame("已释放的实例不会被复用", first, again)
        assertEquals("槽位已清空：再解析是新建实例", 2, backends.size)
    }

    @Test
    fun `App 退出连阅读器会话来源一起关 不留未关闭的会话`() {
        val first = runBlocking { ServiceLocator.browsingSourceFor(smbConnection(id = 7)) }
        ServiceLocator.currentSource = first // 阅读器路由只认它

        ServiceLocator.closeSession()

        awaitCloseCount("App 退出时浏览来源与阅读器会话来源都要关，且只关一次", 1, backends.single()::closeCount)
    }

    /**
     * 票 26 第 3 项回归（会话不泄漏）：启动判定覆盖会话来源时（进程存活时重建 Activity，
     * 退出后再点图标）的释放语义——同一 connId 复用现实例（不新建 SMB 会话），
     * 换到别的 connId 时旧实例在会话来源被替换时释放且只释放一次。
     */
    @Test
    fun `启动覆盖会话来源 同一连接复用实例 换连接释放旧会话且只关一次`() {
        val first = runBlocking { ServiceLocator.browsingSourceFor(smbConnection(id = 7)) }
        ServiceLocator.currentSource = first // 上一会话留存（MainActivity.onDestroy 只在真正退出时关）
        ServiceLocator.currentConnId = 7

        // 启动恢复到同一连接：复用现实例，不新建会话也不关它
        val sameTarget = runBlocking { ServiceLocator.browsingSourceFor(smbConnection(id = 7)) }
        ServiceLocator.currentSource = sameTarget
        assertSame("同一 connId 的启动恢复必须复用现实例", first, sameTarget)
        assertEquals("复用不应新建 SMB 会话", 1, backends.size)
        assertEquals("复用的实例不能被关掉", 0, backends.single().closeCount)

        // 启动覆盖到另一连接：旧实例的关闭责任在会话来源侧（槽位换出时先不关）
        val second = runBlocking { ServiceLocator.browsingSourceFor(smbConnection(id = 8)) }
        assertEquals("槽位换出时先不关（阅读器守卫见 releaseReplacedSource）", 0, backends.first().closeCount)
        ServiceLocator.currentSource = second
        ServiceLocator.currentConnId = 8
        awaitCloseCount("启动覆盖会话来源必须释放上一会话，且只释放一次", 1, backends.first()::closeCount)
        assertEquals("当前连接的会话不受影响", 0, backends.last().closeCount)
    }

    /** 夹具库：根下两个目录书（各自一张图）——根 mtime 可得，故根列表入缓存 */
    private fun libraryTree() = fakeDir("root").add(
        fakeDir("root/第001话").add(fakeFile("root/第001话/001.jpg")),
        fakeDir("root/第002话").add(fakeFile("root/第002话/001.jpg")),
    )

    /**
     * 票 #93 AC2：浏览页点开书这条**主路径**上，邻位来自浏览页已经算过的那份列表，不再新增任何探测。
     *
     * 断言打在 App 接线上（[ServiceLocator.browsingSourceFor] 的实例复用 + 计数型后端）：
     * 浏览页枚举根层 → 点开压缩包书 → 阅读器（`ReaderScreen`）拿邻位时用的就是同一实例、同一份会话快照。
     *
     * **本用例是守护型（guard），不是判别型**：这条路径上快照本来就命中，旧实现同样命中同一份快照，
     * 改前改后都绿（旧行为——「未命中就真去列这一层」——只在未列过的层上才现形）。真正区分新旧实现的是
     * 「未列过父层 → 空邻位 + 计数 0」那两条：`DocumentTreeNeighborsTest.父层未列过时降级为不给邻居 且不为此列目录或探测子目录`、
     * `DocumentTreeListCacheTest.降级后在浏览页列出该层时邻位恢复`。
     */
    @Test
    fun `浏览页点开书 邻位来自已有列表 不新增任何探测`() {
        val a = fakeDir("root/a-book").add(fakeFile("root/a-book/001.jpg"))
        val z = fakeDir("root/z-book").add(fakeFile("root/z-book/001.jpg"))
        val backend = FakeTreeBackend(fakeDir("root").add(a, fakeFile("root/m.cbz"), z))
        backends += backend
        ServiceLocator.sourceFactory = {
            DocumentTreeSource(backend = backend, progressStore = InMemoryProgressStore(), sourceType = SourceType.SMB)
        }

        // 浏览页：进入连接根层（解析会话来源）→ 枚举根列表（一次整层）
        val source = runBlocking { ServiceLocator.browsingSourceFor(smbConnection(id = 7)) }
        ServiceLocator.currentSource = source
        ServiceLocator.currentConnId = 7
        val entries = runBlocking { source.listEntries(null, SortMode.NAME) }
        val cbz = entries.first { it.name == "m.cbz" }
        val listsAfterBrowsing = backend.root.childrenCalls
        val probesAfterBrowsing = a.childrenCalls + z.childrenCalls
        assertNotEquals("浏览页本身要枚举子目录是否书（既有口径，不是本票改的）", 0, probesAfterBrowsing)

        // 点开书：阅读器路由只认会话来源（与 `openEntry` 同一手法）
        val neighbors = runBlocking { ServiceLocator.currentSource!!.neighbors(cbz.id) }

        assertEquals(
            "邻位来自浏览页已经算过的那份列表（压缩包书夹在两个目录书之间）",
            Neighbors("root/a-book", "root/z-book"),
            neighbors,
        )
        assertEquals("打开书不再新增一次列父层", listsAfterBrowsing, backend.root.childrenCalls)
        assertEquals("打开书不再新增一次子目录探测", probesAfterBrowsing, a.childrenCalls + z.childrenCalls)
    }

    private fun smbConnection(id: Long) = ConnectionEntity(
        id = id,
        sourceType = SourceType.SMB.name,
        displayName = "NAS $id",
        configJson = "{\"host\":\"nas$id\",\"share\":\"comics\"}",
    )

    /** 释放走 appScope（既有纪律），先轮询等它落地，再静置确认没有第二次关闭（先例：CountingSmbTransport.awaitInFlight） */
    private fun awaitCloseCount(message: String, expected: Int, count: () -> Int, timeoutMs: Long = 5_000L) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (count() < expected && System.currentTimeMillis() < deadline) Thread.sleep(10L)
        // 静置：两个释放入口各关一次时这里会看到更大的值（本轮修的 bug 就是重复关闭）
        Thread.sleep(200L)
        assertEquals(message, expected, count())
    }
}
