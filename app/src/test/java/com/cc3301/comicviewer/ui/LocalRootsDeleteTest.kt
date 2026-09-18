package com.cc3301.comicviewer.ui

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.cc3301.comicviewer.core.data.ConnectionEntity
import com.cc3301.comicviewer.core.nav.LastBrowsing
import com.cc3301.comicviewer.core.nav.StartupTarget
import com.cc3301.comicviewer.core.nav.fallbackWhenConnectionMissing
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
 * 「测试自己调一次 DAO」的场景；界面的二次确认弹窗本仓库无 Compose UI 测试，由真机清单守护。
 *
 * **碰库的断言只写在一个用例里**：`ServiceLocator.db` 是进程级单例（app classloader 里跨用例存活），
 * 而 Robolectric 的 SQLite shadow 状态按用例重置——同一个类里第二个碰库的用例会拿着上一个用例
 * 环境里的连接指针（`Illegal connection pointer`）。同一用例内的多次读写没有这个问题。
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
    fun `删除本地连接 行柜位与会话来源一起收口 启动指针走既有兜底`() {
        val kept = insertLocal(name = "本机漫画")
        val deleted = insertLocal(name = "已失效的授权")
        // 前置：两条连接各一个柜位；被删的那条挂着会话级来源（SafBackend = 一次 provider 连接）；
        // 「上次停留的位置」正指向它
        assertEquals(2, cabinetsOf().size)
        runBlocking { ServiceLocator.browsingSourceFor(deleted) }
        assertEquals("前置：已建了一个会话级来源", 1, backends.size)
        StartupStore.recordBrowsing(LastBrowsing(connId = deleted.id, containerId = "第001话"))

        runBlocking { deleteLocalConnection(deleted.id) }

        // 1) 连接行真的没了：本地根列表那一行、书柜柜位一起消失，重读一份列表也不再有它
        assertNull(
            "行必须真的从库里删掉",
            runBlocking { ServiceLocator.db.connectionDao().byId(deleted.id) },
        )
        val remaining = runBlocking { ServiceLocator.db.connectionDao().observeAll().first() }
        assertEquals("本地根列表那一行消失", listOf(kept.id), localRoots(remaining).map { it.id })
        assertEquals("书柜柜位随之消失（票 31 起一条连接一个柜）", listOf(kept.id), cabinetsOf().map { it.connectionId })

        // 2) 会话级来源同时释放（票 #30 P1：不留未关闭的会话与陈旧列表缓存）
        awaitCloseCount("删除必须释放该连接的会话级来源", 1, backends.single()::closeCount)
        runBlocking { deleteLocalConnection(deleted.id) } // 重复点确认：不再关一次，也不抛
        assertEquals("同一实例只关一次（票 #30 P1 纪律）", 1, backends.single().closeCount)

        // 3) 「上次停留的位置」指向它：启动由既有兜底（票 #33/#26）落回首页，不停在加载页。
        //    AppNav.prepareStartup 走这条分支的判据就是「库按 id 查不到这条连接」（上面已断言）。
        val pointer = StartupStore.lastBrowsing()!!
        assertEquals(deleted.id, pointer.connId)
        assertEquals(
            "指向已删连接的启动目标由既有兜底落成首页",
            StartupTarget.OpenHome,
            fallbackWhenConnectionMissing(StartupTarget.OpenBrowser(pointer)),
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

    /** 书柜那一层拿到的柜列表（BookshelfScreen 的取法：连接行 → [CabinetRef] → 分柜） */
    private fun cabinetsOf(): List<CabinetRef> = runBlocking {
        localRoots(ServiceLocator.db.connectionDao().observeAll().first()).map { CabinetRef(it.id, it.displayName) }
    }

    /** 释放走 appScope（既有纪律），先轮询等它落地，再静置确认没有第二次关闭（先例：BrowsingSourceSessionTest.awaitCloseCount） */
    private fun awaitCloseCount(message: String, expected: Int, count: () -> Int, timeoutMs: Long = 5_000L) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (count() < expected && System.currentTimeMillis() < deadline) Thread.sleep(10L)
        Thread.sleep(200L)
        assertEquals(message, expected, count())
    }
}
