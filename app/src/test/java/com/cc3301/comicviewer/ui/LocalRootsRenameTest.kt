package com.cc3301.comicviewer.ui

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.cc3301.comicviewer.core.data.AppDatabase
import com.cc3301.comicviewer.core.data.ConnectionDao
import com.cc3301.comicviewer.core.data.ConnectionEntity
import com.cc3301.comicviewer.core.shelf.CabinetRef
import com.cc3301.comicviewer.core.shelf.groupIntoCabinets
import com.cc3301.comicviewer.core.source.SourceType
import com.cc3301.comicviewer.core.source.connectionDisplayName
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 本地连接的重命名动作（票 #72）：本地根列表每行的「重命名」经弹窗走 [renameLocalConnection]——
 * 只改连接名（`displayName` 列），configJson（SAF uri）与原来源类型一律不动。
 * 改名的两个消费点都读连接行，因此书柜柜名（[groupIntoCabinets]）与本地根列表（[localRoots]）同步变化；
 * `configJson` 没变，会话级来源的命中判据（连接 id + configJson，见 `ServiceLocator.browsingSourceFor`）
 * 照旧命中，改名不重建会话（该判据的语义由 `BrowsingSourceSessionTest` 锁定）。
 *
 * 库用**内存库**（[Room.inMemoryDatabaseBuilder]），不走 [ServiceLocator] 的单例库：那个沙箱库文件与
 * 别的用例共用、Robolectric 的 SQLite shadow 状态又按用例重置，同一 JVM 里被两个测试类接连写事务会
 * 炸「Illegal connection pointer」（既有纪律见 `LocalRootsDeleteTest` 的类注释，那里占着那一个位置）。
 * 本用例验的是写库语义与两个消费点的名字，不需要会话槽。
 *
 * 未覆盖的界面部分（只能真机验）：行尾「重命名」按钮与弹窗本身；「留空恢复文件夹名」在界面上取的是
 * `DocumentFile.fromTreeUri(...).name`（SAF IPC，测试沙箱里拿不到真实授权目录），此处只到
 * [connectionDisplayName] 的「留空回落」规则 + 写列这一段。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class LocalRootsRenameTest {

    private lateinit var db: AppDatabase

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun `重命名只改连接名 柜名与本地根列表同步变化`() {
        val dao = db.connectionDao()
        val (renamedId, otherId) = runBlocking {
            dao.insert(localConnection(name = "文件夹甲")) to dao.insert(localConnection(name = "文件夹乙"))
        }
        // 前置：书柜柜名与本地根列表读的是同一个 displayName 列
        assertEquals("文件夹甲", cabinetNames(dao)[renamedId])
        assertEquals("文件夹甲", localRootNames(dao)[renamedId])

        // 界面那条路径：弹窗里的文本交给规则（去首尾空白 + 超长截断）后写列
        runBlocking { renameLocalConnection(dao, renamedId, connectionDisplayName("  我家 NAS  ", autoName = "文件夹甲")) }

        assertEquals("书柜柜名跟着改", "我家 NAS", cabinetNames(dao)[renamedId])
        assertEquals("本地根列表那一行跟着改", "我家 NAS", localRootNames(dao)[renamedId])
        assertEquals("别的连接不受影响", "文件夹乙", localRootNames(dao)[otherId])
        val row = runBlocking { dao.byId(renamedId) }!!
        assertEquals("只改名字：SAF uri 不动（会话来源的命中判据因此不变）", "content://tree/文件夹甲", row.configJson)
        assertEquals("只改名字：来源类型不动", SourceType.LOCAL.name, row.sourceType)

        // 留空 = 恢复文件夹名（与三个网络来源同一套「留空回落」规则）
        runBlocking { renameLocalConnection(dao, renamedId, connectionDisplayName("", autoName = "文件夹甲")) }
        assertEquals("文件夹甲", localRootNames(dao)[renamedId])
    }

    private fun localConnection(name: String) = ConnectionEntity(
        sourceType = SourceType.LOCAL.name,
        displayName = name,
        configJson = "content://tree/" + name,
    )

    /** 本地根列表的 id → 名字（[localRoots] 按来源类型过滤后交给界面陈列） */
    private fun localRootNames(dao: ConnectionDao): Map<Long, String> =
        runBlocking { localRoots(dao.observeAll().first()).associate { it.id to it.displayName } }

    /** 书柜柜名（全部连接经 [CabinetRef] → [groupIntoCabinets]）：柜名取自连接行，不需要来源与会话 */
    private fun cabinetNames(dao: ConnectionDao): Map<Long, String> = runBlocking {
        groupIntoCabinets(dao.observeAll().first().map { CabinetRef(it.id, it.displayName) })
            .associate { it.connectionId to it.displayName }
    }
}
