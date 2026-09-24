package com.cc3301.comicviewer.ui

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.cc3301.comicviewer.core.data.ConnectionEntity
import com.cc3301.comicviewer.core.source.DocumentTreeSource
import com.cc3301.comicviewer.core.source.FakeTreeBackend
import com.cc3301.comicviewer.core.source.InMemoryProgressStore
import com.cc3301.comicviewer.core.source.LISTING_SNAPSHOT_FILE_SUFFIX
import com.cc3301.comicviewer.core.source.ListingSnapshotStore
import com.cc3301.comicviewer.core.source.SourceType
import com.cc3301.comicviewer.core.source.StoredCredential
import com.cc3301.comicviewer.core.source.TestCredentialCipherRule
import com.cc3301.comicviewer.core.source.fakeDir
import com.cc3301.comicviewer.core.source.listingSnapshotDir
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * 连接变更的唯一入口（票 #136）：[ServiceLocator.connectionChanged] / [ServiceLocator.connectionDeleted]
 * 把原先两个屏各自按序调的两个方法收成一声——「释放会话来源」与「清落盘快照」**成对**发生。
 *
 * 为什么必须成对：[ServiceLocator.closeBrowsingSource] 只清会话（内存列表快照随之清空），落盘快照
 * （票 #74，键 = 连接 id + 容器 id）不在它里面；漏掉 [ServiceLocator.purgeListingSnapshots] 时，
 * 编辑或删除连接后重进该柜会照旧命中旧快照——旧数据复活，正是这张票要收口的那条因果链。
 *
 * 断言打在 App 接线上（[ServiceLocator] 的会话槽 + Robolectric 沙箱里的真实 cacheDir），
 * 不碰 Room：变更入口本身不读写连接表，删行仍由调用点做。
 * 界面部分（两个屏保存/删除按钮的接线）走真机清单。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ConnectionChangeTest {

    @get:Rule
    val credentialCipher = TestCredentialCipherRule()

    /** 每次解析新建的后端：`closeCount` 就是「该连接的会话被释放了几次」 */
    private val backends = mutableListOf<FakeTreeBackend>()

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        ServiceLocator.init(context)
        // 测试接缝：真实 backend 换成内存目录树，落盘快照走生产那条路径（appContext.cacheDir）
        ServiceLocator.sourceFactory = { conn ->
            val backend = FakeTreeBackend(fakeDir("conn").add(fakeDir("conn/第001话")))
            backends += backend
            DocumentTreeSource(
                backend = backend,
                progressStore = InMemoryProgressStore(),
                sourceType = SourceType.LOCAL,
                listingSnapshots = ListingSnapshotStore(listingSnapshotDir(context.cacheDir), conn.id),
            )
        }
        ServiceLocator.closeBrowsingSource()
        ServiceLocator.currentSource = null
        ServiceLocator.currentConnId = null
    }

    @After
    fun tearDown() {
        ServiceLocator.closeBrowsingSource()
        ServiceLocator.currentSource = null
        ServiceLocator.currentConnId = null
        ServiceLocator.sourceFactory = { ServiceLocator.sourceForConnection(it) }
        backends.clear()
    }

    @Test
    fun `删除连接一声调用 落盘快照与会话来源一起清掉`() {
        val conn = connection(id = 41L)
        val snapshot = writeSnapshotFile(conn.id)
        runBlocking { ServiceLocator.browsingSourceFor(conn) }
        val backend = backends.single()

        ServiceLocator.connectionDeleted(conn.id)

        assertFalse("落盘快照必须随连接作废（漏了它 = 删除后重进命中旧数据）", snapshot.exists())
        assertNull("会话槽位同步清空", ServiceLocator.browsingSourceIfResolved(conn.id))
        awaitCloseCount("会话来源也必须释放（同一实例只关一次）", 1, backend::closeCount)
    }

    @Test
    fun `编辑连接一声调用 落盘快照与会话来源一起清掉`() {
        val conn = connection(id = 42L)
        val snapshot = writeSnapshotFile(conn.id)
        runBlocking { ServiceLocator.browsingSourceFor(conn) }
        val backend = backends.single()

        ServiceLocator.connectionChanged(conn.id)

        assertFalse("落盘快照必须随配置变化作废（票 #74 的整片失效）", snapshot.exists())
        assertNull("会话槽位同步清空", ServiceLocator.browsingSourceIfResolved(conn.id))
        awaitCloseCount("旧会话来源必须释放", 1, backend::closeCount)
    }

    @Test
    fun `编辑保存必然改文本 因此会话必然重建`() {
        // 票 #27 的因果链（票 #136 把它钉成测试）：凭据每次加密都用新随机 IV，
        // 所以「重新保存一次、什么都没改」也会算出不同的 configJson 文本；
        // 而会话槽的命中判据正是这段文本（ServiceLocator.browsingSourceFor 的 browsingConfig 比较），
        // 于是保存连接 = 下一次解析必 miss = 重建会话。这条链断了（比如换成固定 IV），
        // 「编辑连接后旧会话已失效」就会静默变成复用旧实例。
        val first = StoredCredential.protect("s3cret")
        val second = StoredCredential.protect("s3cret")
        assertNotEquals("同一份明文两次加密必须不同（随机 IV）", first, second)

        val conn = connection(id = 43L)
        val before = runBlocking { ServiceLocator.browsingSourceFor(conn) }
        assertSame("配置文本没变就复用实例（槽位按文本判命中）", before, runBlocking { ServiceLocator.browsingSourceFor(conn) })

        val resaved = conn.copy(configJson = conn.configJson + StoredCredential.ENCRYPTED_PREFIX + second)
        val after = runBlocking { ServiceLocator.browsingSourceFor(resaved) }

        assertNotSame("文本变了必新建实例（编辑保存即重建会话）", before, after)
        awaitCloseCount("换出的旧实例要释放", 1, backends.first()::closeCount)
    }

    private fun connection(id: Long) = ConnectionEntity(
        id = id,
        sourceType = SourceType.LOCAL.name,
        displayName = "本机漫画",
        configJson = "content://tree/$id",
    )

    /** 造一份该连接名下的落盘快照：文件名规则与生产同一处（[ListingSnapshotStore.connectionFileNamePrefix]） */
    private fun writeSnapshotFile(connId: Long): File {
        val dir = listingSnapshotDir(context.cacheDir)
        dir.mkdirs()
        val file = File(dir, ListingSnapshotStore.connectionFileNamePrefix(connId) + "deadbeef" + LISTING_SNAPSHOT_FILE_SUFFIX)
        file.writeText("v1\n")
        return file
    }

    /** 释放走 appScope，先轮询等它落地，再静置确认没有第二次关闭（先例：`BrowsingSourceSessionTest.awaitCloseCount`） */
    private fun awaitCloseCount(message: String, expected: Int, count: () -> Int, timeoutMs: Long = 5_000L) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (count() < expected && System.currentTimeMillis() < deadline) Thread.sleep(10L)
        assertEquals(message, expected, count())
        Thread.sleep(200L)
        assertEquals("同一实例只关一次：" + message, expected, count())
    }
}
