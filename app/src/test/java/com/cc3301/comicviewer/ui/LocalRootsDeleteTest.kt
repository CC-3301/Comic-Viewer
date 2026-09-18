package com.cc3301.comicviewer.ui

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.cc3301.comicviewer.core.data.ConnectionEntity
import com.cc3301.comicviewer.core.nav.LastBrowsing
import com.cc3301.comicviewer.core.shelf.CabinetRef
import com.cc3301.comicviewer.core.shelf.groupIntoCabinets
import com.cc3301.comicviewer.core.source.DocumentTreeSource
import com.cc3301.comicviewer.core.source.FakeTreeBackend
import com.cc3301.comicviewer.core.source.InMemoryProgressStore
import com.cc3301.comicviewer.core.source.SourceType
import com.cc3301.comicviewer.core.source.fakeDir
import com.cc3301.comicviewer.core.source.fakeFile
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 本地连接的删除动作（票 #40）：本地根列表每行的「删除」经二次确认后走 [deleteLocalConnection]——
 * 先释放该连接的会话级来源（票 #30 P1 的释放纪律，与网络来源连接列表同一套做法），再删连接行。
 * 删掉行以后：本地根列表那一行、书柜的柜位一起消失，重新读一份列表（等价重启后）也不再有它。
 *
 * 断言打在 App 接线上（[ServiceLocator] 的会话槽 + Robolectric 沙箱里的真实 Room 库），而不是
 * 「测试自己调一次 DAO」的场景。
 *
 * 未覆盖的界面部分（只能真机验）：行尾「删除」按钮与二次确认弹窗本身、整行的点击热区；
 * 「启动退化」只到数据层——端到端退化由 `AppNav.prepareStartup`（组合期代码）加真机清单守护，
 * 它的纯函数契约由 `StartupRoutingTest` 锁定。同理，删掉最后一个连接后的退栈只锁到判定层
 * （[connectionVanished]），退栈调用点 `rememberConnectionSource` 也只在真机上跑。
 *
 * **碰库的断言只写在一个用例里**：`ServiceLocator.db` 是进程级单例（app classloader 里跨用例存活），
 * 而 Robolectric 的 SQLite shadow 状态按用例重置——同一个类里第二个碰库的用例会拿着上一个用例
 * 环境里的连接指针（`Illegal connection pointer`）。同一用例内的多次读写没有这个问题。
 * 库文件与别的测试共用（同一个沙箱临时目录）：实测跑全量时库里就带着 LegacyCredentialUpgradeTest
 * 迁移用例留下的 WEBDAV 行，所以断言一律相对「插入前」的基线写（本用例的行由 [insertedIds] 认人），
 * 不假设库是空的。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class LocalRootsDeleteTest {

    /** 每次解析新建的后端（生产里 = 新建 SafBackend，本地来源构造会做 provider IPC） */
    private val backends = mutableListOf<FakeTreeBackend>()

    private lateinit var context: Context

    /** 本用例插进去的连接行：tearDown 清掉，免得留进后续用例（库文件是同一个） */
    private val insertedIds = mutableListOf<Long>()

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        ServiceLocator.init(context)
        // 测试接缝：把真实 backend 换成内存目录树（生产默认是 sourceForConnection）
        ServiceLocator.sourceFactory = {
            val backend = FakeTreeBackend(
                fakeDir("local").add(fakeDir("local/第001话").add(fakeFile("local/第001话/001.jpg"))),
            )
            backends += backend
            DocumentTreeSource(
                backend = backend,
                progressStore = InMemoryProgressStore(),
                sourceType = SourceType.LOCAL,
            )
        }
        ServiceLocator.currentSource = null
        ServiceLocator.currentConnId = null
        StartupStore.clearBrowsing()
    }

    @After
    fun tearDown() {
        insertedIds.forEach { runBlocking { ServiceLocator.db.connectionDao().deleteById(it) } }
        insertedIds.clear()
        ServiceLocator.closeBrowsingSource()
        ServiceLocator.currentSource = null
        ServiceLocator.currentConnId = null
        ServiceLocator.sourceFactory = { ServiceLocator.sourceForConnection(it) }
        backends.clear()
        StartupStore.clearBrowsing()
    }

    @Test
    fun `本地根列表只陈列本地连接 其它来源的连接不串进来`() {
        val local = localConnection(displayName = "本机漫画", uri = "content://tree/local")
        val smb = local.copy(sourceType = SourceType.SMB.name, displayName = "NAS SMB")

        assertEquals(
            "本地页只列本地来源（其它来源有各自的连接列表页）",
            listOf(local),
            localRoots(listOf(smb, local)),
        )
        assertEquals("本地连接被删除后，这一行就从列表里消失了", emptyList<ConnectionEntity>(), localRoots(listOf(smb)))
    }

    @Test
    fun `删除本地连接 行与柜位消失 会话来源释放 删光后浏览页退栈`() {
        // 库里可能有别的用例留下的连接（实测：LegacyCredentialUpgradeTest 的迁移行）：
        // 所有断言都相对「未插之前」的基线写，不假设库是空的
        val cabinetsBefore = cabinetsOf().toSet()
        val kept = insertLocal(name = "本机漫画")
        val deleted = insertLocal(name = "已失效的授权")
        // 前置：本用例两条连接各立一个柜；被删的那条挂着会话级来源（SafBackend = 一次 provider 连接）；
        // 「上次停留的位置」正指向它
        assertEquals("前置：本用例的两条连接各立一个柜", cabinetsBefore + setOf(kept.id, deleted.id), cabinetsOf().toSet())
        assertTrue("前置：库里的本地连接含刚插的两条", myLocalIds().containsAll(listOf(kept.id, deleted.id)))
        runBlocking { ServiceLocator.browsingSourceFor(deleted) }
        assertEquals("前置：已建了一个会话级来源", 1, backends.size)
        StartupStore.recordBrowsing(LastBrowsing(connId = deleted.id, containerId = "第001话"))

        runBlocking { deleteLocalConnection(deleted.id) }

        // 1) 连接行真的没了（重读一份列表、重启后也不再有它），本地根列表那一行与书柜柜位一起消失
        assertNull(
            "行必须真的从库里删掉",
            runBlocking { ServiceLocator.db.connectionDao().byId(deleted.id) },
        )
        assertEquals("本地根列表那一行消失，另一条不受影响", listOf(kept.id), myLocalIds())
        assertEquals("书柜少了被删的那个柜，别的柜不受影响", cabinetsBefore + kept.id, cabinetsOf().toSet())

        // 2) 会话级来源同时释放（票 #30 P1：不留未关闭的会话与陈旧列表缓存）
        awaitCloseCount("删除必须释放该连接的会话级来源", 1, backends.single()::closeCount)
        runBlocking { deleteLocalConnection(deleted.id) } // 重复点确认：不再关一次，也不抛
        assertEquals("同一实例只关一次（票 #30 P1 纪律）", 1, backends.single().closeCount)

        // 3) 删除不顺带改「上次停留的位置」：指针仍指向被删的连接，改名/清指针是启动时既有退化路径的事
        //    （AppNav.prepareStartup 的浏览分支：按 id 查不到连接 → 清指针 + 回落首页）。那条分支是组合期
        //    代码，这里不假装覆盖：断言只在「删除没动指针」这个可被证伪的点上。
        val pointer = StartupStore.lastBrowsing()!!
        assertEquals("删除动作不该顺手改指针", deleted.id, pointer.connId)

        // 4) 把最后一个（本用例的）连接也删掉：界面订阅到的本地列表空了 → 退栈判定为真，
        //    浏览页不会停在「加载中…」且没有重试入口
        runBlocking { deleteLocalConnection(kept.id) }
        assertTrue("前置：本用例的本地连接已删光", myLocalIds().isEmpty())
        assertEquals("两条都删掉后柜位回到基线", cabinetsBefore, cabinetsOf().toSet())
        assertTrue(
            "已加载且一条连接都没有＝退栈（否则删掉最后一个连接后浏览页卡在「加载中」）",
            connectionVanished(myLocalIds(), deleted.id),
        )
    }

    private fun localConnection(displayName: String, uri: String) = ConnectionEntity(
        sourceType = SourceType.LOCAL.name,
        displayName = displayName,
        configJson = uri,
    )

    private fun insertLocal(name: String): ConnectionEntity {
        val conn = localConnection(displayName = name, uri = "content://tree/" + name)
        val id = runBlocking { ServiceLocator.db.connectionDao().insert(conn) }
        insertedIds += id
        return conn.copy(id = id)
    }

    /** 库里的本地根列表里、属于本用例的行（库里可能还有别的用例留下的连接，不能直接拿全量） */
    private fun myLocalIds(): List<Long> = runBlocking {
        localRoots(ServiceLocator.db.connectionDao().observeAll().first())
            .map { it.id }
            .filter { it in insertedIds }
    }

    /**
     * 柜列表（书柜那一层的立柜依据）：**全部**连接（不是只有本地连接）经 [CabinetRef] →
     * [groupIntoCabinets] 变成一个柜；柜名取自连接配置，不需要来源/会话，连接被删后柜位随之消失
     * （票 31 决策 1/7）。
     * 库里可能还有别的用例留下的连接（它们的柜也在结果里），所以断言一律与基线集合比。
     */
    private fun cabinetsOf(): List<Long> = runBlocking {
        val refs = ServiceLocator.db.connectionDao().observeAll().first().map { CabinetRef(it.id, it.displayName) }
        groupIntoCabinets(refs).map { it.connectionId }
    }

    /** 释放走 appScope（既有纪律），先轮询等它落地，再静置确认没有第二次关闭（先例：BrowsingSourceSessionTest.awaitCloseCount） */
    private fun awaitCloseCount(message: String, expected: Int, count: () -> Int, timeoutMs: Long = 5_000L) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (count() < expected && System.currentTimeMillis() < deadline) Thread.sleep(10L)
        Thread.sleep(200L)
        assertEquals(message, expected, count())
    }
}
