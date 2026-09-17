package com.cc3301.comicviewer.ui

import com.cc3301.comicviewer.core.data.ConnectionEntity
import com.cc3301.comicviewer.core.source.DocumentTreeSource
import com.cc3301.comicviewer.core.source.FakeTreeBackend
import com.cc3301.comicviewer.core.source.InMemoryProgressStore
import com.cc3301.comicviewer.core.source.SortMode
import com.cc3301.comicviewer.core.source.SourceType
import com.cc3301.comicviewer.core.source.fakeDir
import com.cc3301.comicviewer.core.source.fakeFile
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
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
        await("换连接必须释放上一个会话（票 11：不同时挂 N 个会话）") { backends.first().closeCount == 1 }
        assertEquals("当前连接的会话不受影响", 0, backends.last().closeCount)
    }

    @Test
    fun `阅读器正在用的实例换连接时先不关 会话来源被替换后由 setter 释放`() {
        val first = runBlocking { ServiceLocator.browsingSourceFor(smbConnection(id = 7)) }
        ServiceLocator.currentSource = first // 阅读器路由只认它

        val second = runBlocking { ServiceLocator.browsingSourceFor(smbConnection(id = 8)) }
        assertNotSame(first, second)
        assertEquals("阅读器正在用的实例不能立刻关（守卫见 releaseLocalSource）", 0, backends.first().closeCount)

        ServiceLocator.currentSource = second // 打开第二本书：会话来源被替换，旧实例此时才释放
        await("会话来源被替换后旧实例由 setter 释放") { backends.first().closeCount == 1 }
    }

    @Test
    fun `连接配置变化时释放上一个来源`() {
        val conn = smbConnection(id = 7)
        val first = runBlocking { ServiceLocator.browsingSourceFor(conn) }

        val edited = runBlocking {
            ServiceLocator.browsingSourceFor(conn.copy(configJson = "{\"host\":\"nas2\",\"share\":\"comics\"}"))
        }

        assertNotSame(first, edited)
        await("编辑连接（configJson 变化）后旧会话必须释放") { backends.first().closeCount == 1 }
    }

    @Test
    fun `按连接释放会话来源 只关指定连接`() {
        runBlocking { ServiceLocator.browsingSourceFor(smbConnection(id = 7)) }

        ServiceLocator.closeBrowsingSource(connId = 8)
        assertEquals("别的连接不受影响", 0, backends.single().closeCount)

        ServiceLocator.closeBrowsingSource(connId = 7) // 删除连接走这条
        await("删除连接必须释放该连接的会话来源") { backends.single().closeCount == 1 }
    }

    @Test
    fun `App 级释放入口关掉当前会话来源 槽位清空后再次解析是新建实例`() {
        val first = runBlocking { ServiceLocator.browsingSourceFor(smbConnection(id = 7)) }

        ServiceLocator.closeBrowsingSource() // App 退出（MainActivity.onDestroy）走这条
        await("App 级入口必须关掉跨页面存活的会话来源") { backends.single().closeCount == 1 }

        val again = runBlocking { ServiceLocator.browsingSourceFor(smbConnection(id = 7)) }
        assertNotSame("已释放的实例不会被复用", first, again)
        assertEquals("槽位已清空：再解析是新建实例", 2, backends.size)
    }

    /** 夹具库：根下两个目录书（各自一张图）——根 mtime 可得，故根列表入缓存 */
    private fun libraryTree() = fakeDir("root").add(
        fakeDir("root/第001话").add(fakeFile("root/第001话/001.jpg")),
        fakeDir("root/第002话").add(fakeFile("root/第002话/001.jpg")),
    )

    private fun smbConnection(id: Long) = ConnectionEntity(
        id = id,
        sourceType = SourceType.SMB.name,
        displayName = "NAS $id",
        configJson = "{\"host\":\"nas$id\",\"share\":\"comics\"}",
    )

    /** 释放走 appScope（既有纪律），因此轮询等它落地（先例：CountingSmbTransport.awaitInFlight） */
    private fun await(message: String, timeoutMs: Long = 5_000L, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (!condition() && System.currentTimeMillis() < deadline) Thread.sleep(10L)
        assertTrue(message, condition())
    }
}
