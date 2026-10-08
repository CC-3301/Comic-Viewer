package com.cc3301.comicviewer.ui

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.cc3301.comicviewer.core.data.AppDatabase
import com.cc3301.comicviewer.core.data.ConnectionDao
import com.cc3301.comicviewer.core.data.ConnectionEntity
import com.cc3301.comicviewer.core.source.DocumentTreeSource
import com.cc3301.comicviewer.core.source.FakeTreeBackend
import com.cc3301.comicviewer.core.source.InMemoryProgressStore
import com.cc3301.comicviewer.core.source.ListingSnapshotStore
import com.cc3301.comicviewer.core.source.SourceType
import com.cc3301.comicviewer.core.source.SortMode
import com.cc3301.comicviewer.core.source.StoredCredential
import com.cc3301.comicviewer.core.source.TestCredentialCipherRule
import com.cc3301.comicviewer.core.source.fakeDir
import com.cc3301.comicviewer.core.source.fakeFile
import com.cc3301.comicviewer.core.source.listingSnapshotDir
import com.cc3301.comicviewer.ui.session.ConnectionStore
import com.cc3301.comicviewer.ui.session.SessionState
import java.lang.reflect.Modifier
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 连接的写面（`ui/session/ConnectionStore`）：添加 / 编辑 / 删除各是一句调用，
 * 「写库 / 释放该连接的会话级来源 / 作废它名下的落盘列表快照」三件事的顺序是模块内部的不变量
 * ——编辑**先**写库后清理，删除**先**清理后删行。
 *
 * 为什么清理必须成对：[SessionState.closeBrowsingSource] 只清会话（内存列表快照随之清空），
 * 落盘快照（键 = 连接 id + 容器 id）不在它里面；漏掉清落盘时，编辑或删除连接后重进该柜会照旧命中旧快照
 * ——旧数据复活，正是这里要收口的那条因果链。两个变更用例都从**真实列目录**落一份盘上快照
 * （而不是手搓一个文件），因此「重进不再命中旧快照」这句是拿 [ListingSnapshotStore.read] 直接证的。
 *
 * 断言打在写面上（用例自己那份 [SessionState] + Robolectric 沙箱里的真实 cacheDir + 内存库）；
 * 库用内存库而不是 [ServiceLocator] 的单例库——那个沙箱库文件与别的用例共用，
 * Robolectric 的 SQLite shadow 状态又按用例重置（既有纪律见 `LocalRootsDeleteTest` 的类注释）。
 * 界面部分（两个屏保存/删除按钮的接线）走设备清单。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ConnectionChangeTest {

    @get:Rule
    val credentialCipher = TestCredentialCipherRule()

    /** 每次解析新建的后端：`closeCount` 就是「该连接的会话被释放了几次」 */
    private val backends = mutableListOf<FakeTreeBackend>()

    /** 每次解析新建的落盘快照表（来源拿的就是它）：用例拿它证「重进到底还命不命中旧快照」 */
    private val snapshotStores = mutableListOf<ListingSnapshotStore>()

    /** 本类自己那份会话状态：来源构造器按用例注入（生产那份由组合根持有） */
    private lateinit var session: SessionState

    private lateinit var db: AppDatabase

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        ServiceLocator.init(context)
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        // 测试接缝：真实 backend 换成内存目录树，落盘快照走生产那条路径（appContext.cacheDir）
        session = SessionState(
            scope = ServiceLocator.appScope,
            sourceFactory = { conn ->
                val backend = FakeTreeBackend(
                    fakeDir("conn").add(fakeDir(CONTAINER).add(fakeFile("$CONTAINER/001.jpg"))),
                )
                backends += backend
                val store = ListingSnapshotStore(listingSnapshotDir(context.cacheDir), conn.id)
                snapshotStores += store
                DocumentTreeSource(
                    backend = backend,
                    progressStore = InMemoryProgressStore(),
                    sourceType = SourceType.LOCAL,
                    listingSnapshots = store,
                )
            },
            recordBrowsingPath = {},
        )
    }

    @After
    fun tearDown() {
        db.close()
        backends.clear()
        snapshotStores.clear()
    }

    @Test
    fun `删除连接一声调用 清理与会话来源一起落地 且删行排在清理之后`() {
        val conn = insert(connection(id = 41L))
        val source = runBlocking { session.browsingSourceFor(conn) }
        val store = snapshotStores.single()
        runBlocking { source.listEntries(CONTAINER, SortMode.NAME) }
        assertNotNull("前置：这一层真的落了一份盘上快照（重进本会命中它）", store.read(CONTAINER))

        // 删行那一刻取一次「落盘快照还在不在」：先清理后删行的排法下，那一刻它已经不在了
        val probe = DeleteOrderProbeDao(db.connectionDao()) { store.read(CONTAINER) != null }
        runBlocking { ConnectionStore(probe, session).delete(conn.id) }

        assertNull("删除后重进不再命中旧快照（漏了清落盘 = 旧数据复活）", store.read(CONTAINER))
        assertNull("会话槽位同步清空", session.browsingSourceIfResolved(conn.id))
        awaitCloseCount("会话来源也必须释放（同一实例只关一次）", 1, backends.single()::closeCount)
        assertNull("连接行也删掉了（写面自己删行）", runBlocking { db.connectionDao().byId(conn.id) })
        assertEquals(
            "删行那一刻落盘快照已清：清理排在删行之前",
            false,
            probe.snapshotAliveWhenRowDeleted,
        )
    }

    @Test
    fun `编辑连接一声调用 落盘快照与会话来源一起清掉`() {
        val conn = insert(connection(id = 42L))
        val source = runBlocking { session.browsingSourceFor(conn) }
        val store = snapshotStores.single()
        runBlocking { source.listEntries(CONTAINER, SortMode.NAME) }
        assertNotNull("前置：这一层真的落了一份盘上快照", store.read(CONTAINER))

        val saved = SavedConnection(displayName = "本机漫画（改）", configJson = conn.configJson + "/new")
        runBlocking { ConnectionStore(db.connectionDao(), session).update(conn, saved) }

        assertNull("配置变了就整片作废（票 #74）：重进不再命中旧快照", store.read(CONTAINER))
        assertNull("会话槽位同步清空", session.browsingSourceIfResolved(conn.id))
        awaitCloseCount("旧会话来源必须释放", 1, backends.single()::closeCount)
        val row = runBlocking { db.connectionDao().byId(conn.id) }!!
        assertEquals("行按新值写回：名字", saved.displayName, row.displayName)
        assertEquals("行按新值写回：configJson", saved.configJson, row.configJson)
    }

    @Test
    fun `编辑连接 configJson 没变就不清理`() {
        val conn = insert(connection(id = 44L))
        val source = runBlocking { session.browsingSourceFor(conn) }
        val store = snapshotStores.single()
        runBlocking { source.listEntries(CONTAINER, SortMode.NAME) }
        assertNotNull("前置：这一层真的落了一份盘上快照", store.read(CONTAINER))

        val saved = SavedConnection(displayName = "只改名字", configJson = conn.configJson)
        runBlocking { ConnectionStore(db.connectionDao(), session).update(conn, saved) }

        assertEquals("行按新值写回：名字", saved.displayName, runBlocking { db.connectionDao().byId(conn.id) }?.displayName)
        assertNotNull("配置文本没变就不作废落盘快照（命中判据本就命中）", store.read(CONTAINER))
        assertSame("配置文本没变就复用同一个会话来源实例", source, session.browsingSourceIfResolved(conn.id))
        awaitCloseCount("配置文本没变就不释放（重建会话是白搭）", 0, backends.single()::closeCount)
    }

    @Test
    fun `编辑保存必然改文本 因此会话必然重建`() {
        // 因果链（本用例把它钉住）：凭据每次加密都用新随机 IV，
        // 所以「重新保存一次、什么都没改」也会算出不同的 configJson 文本；
        // 而会话槽的命中判据正是这段文本（会话状态的 `browsingSourceFor` 的 browsingConfig 比较），
        // 于是保存连接 = 下一次解析必 miss = 重建会话。这条链断了（比如换成固定 IV），
        // 「编辑连接后旧会话已失效」就会静默变成复用旧实例。
        val first = StoredCredential.protect("s3cret")
        val second = StoredCredential.protect("s3cret")
        assertNotEquals("同一份明文两次加密必须不同（随机 IV）", first, second)

        val conn = connection(id = 43L)
        val before = runBlocking { session.browsingSourceFor(conn) }
        assertSame(
            "配置文本没变就复用实例（槽位按文本判命中）",
            before,
            runBlocking { session.browsingSourceFor(conn) },
        )

        val resaved = conn.copy(configJson = conn.configJson + StoredCredential.ENCRYPTED_PREFIX + second)
        val after = runBlocking { session.browsingSourceFor(resaved) }

        assertNotSame("文本变了必新建实例（编辑保存即重建会话）", before, after)
        awaitCloseCount("换出的旧实例要释放", 1, backends.first()::closeCount)
    }

    @Test
    fun `对外面恰为那三个成员`() {
        // 只看本类自己声明的**公开方法**（构造器、合成方法与内部符号不算），公开成员多一个本断言失败。
        val entries = ConnectionStore::class.java.declaredMethods
            .filter { Modifier.isPublic(it.modifiers) && !it.isSynthetic && it.name != "<init>" && '$' !in it.name }
            .map { it.name }
            .sorted()
        assertEquals(
            "ConnectionStore 的公开成员恰为：添加 / 编辑 / 删除",
            listOf("add", "delete", "update"),
            entries,
        )
    }

    private fun connection(id: Long) = ConnectionEntity(
        id = id,
        sourceType = SourceType.LOCAL.name,
        displayName = "本机漫画",
        configJson = "content://tree/$id",
    )

    private fun insert(conn: ConnectionEntity): ConnectionEntity {
        runBlocking { db.connectionDao().insert(conn) }
        return conn
    }

    /** 释放走 appScope，先轮询等它落地，再静置确认没有第二次关闭（先例：`BrowsingSourceSessionTest.awaitCloseCount`） */
    private fun awaitCloseCount(message: String, expected: Int, count: () -> Int, timeoutMs: Long = 5_000L) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (count() < expected && System.currentTimeMillis() < deadline) Thread.sleep(10L)
        assertEquals(message, expected, count())
        Thread.sleep(200L)
        assertEquals("同一实例只关一次：" + message, expected, count())
    }

    private companion object {
        /** 用例里真去列一层的那下级容器：它的 id 就是落盘快照的容器键 */
        const val CONTAINER = "conn/第001话"
    }
}

/**
 * 记录「删行那一刻」的 DAO：直接证清理排在删行之前（落盘清理是同步的，会话释放异步，因此看落盘这一半）。
 */
private class DeleteOrderProbeDao(
    private val delegate: ConnectionDao,
    /** 取一次「该连接的落盘快照还在不在」 */
    private val snapshotStillThere: () -> Boolean,
) : ConnectionDao {

    /** 删行那一刻快照还在不在；null = 删行没发生过 */
    var snapshotAliveWhenRowDeleted: Boolean? = null

    override fun observeAll(): Flow<List<ConnectionEntity>> = delegate.observeAll()

    override suspend fun insert(connection: ConnectionEntity): Long = delegate.insert(connection)

    override suspend fun update(connection: ConnectionEntity) = delegate.update(connection)

    override suspend fun deleteById(id: Long) {
        snapshotAliveWhenRowDeleted = snapshotStillThere()
        delegate.deleteById(id)
    }

    override suspend fun updateDisplayName(id: Long, displayName: String) =
        delegate.updateDisplayName(id, displayName)

    override suspend fun byId(id: Long): ConnectionEntity? = delegate.byId(id)
}
