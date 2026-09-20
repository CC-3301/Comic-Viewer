package com.cc3301.comicviewer.ui

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.cc3301.comicviewer.core.data.AppDatabase
import com.cc3301.comicviewer.core.source.CONNECTION_NAME_MAX_LENGTH
import com.cc3301.comicviewer.core.source.SourceType
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
 * 本地连接的添加动作（票 #72 r3）：「添加文件夹」经 SAF 授权回调走 [addLocalConnection]——落列名与
 * 重命名路径**同一处清洗**（`core/source/ConnectionName.kt` 的 `sanitizeConnectionName`：去首尾空白 +
 * 40 码点截断），因此文件夹名再长也不会越过上限进 `displayName` 列（AC5「超长名称被截断且排版不破」），
 * 且按码点截断不劈代理对。configJson 仍是授权 uri 原样。
 *
 * 库用**内存库**（同 [LocalRootsRenameTest] 的理由：沙箱里那个共用库文件容不下第二个写事务的测试类）。
 * 未覆盖的界面部分（只能真机验）：`OpenDocumentTree` 授权回调本身与 `takePersistableUriPermission`、
 * 以及文件夹名取不到时的「本地目录」兜底（`localFolderName` 要读 SAF，测试沙箱里拿不到真实授权目录）。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class LocalRootsAddTest {

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
    fun `超长文件夹名经添加路径落库被截断到 40 码点 且不劈代理对`() {
        val dao = db.connectionDao()
        val longFolderName = "名".repeat(CONNECTION_NAME_MAX_LENGTH + 7)

        runBlocking { addLocalConnection(dao, folderName = longFolderName, uri = "content://tree/long") }

        val row = runBlocking { dao.observeAll().first() }.single()
        assertEquals(
            "超长文件夹名必须截到上限（AC5），否则列表行与顶栏排版会被撑破",
            CONNECTION_NAME_MAX_LENGTH,
            row.displayName.codePointCount(0, row.displayName.length),
        )
        assertEquals(longFolderName.take(CONNECTION_NAME_MAX_LENGTH), row.displayName)
        assertEquals("只清洗名字：授权 uri 原样落", "content://tree/long", row.configJson)
        assertEquals(SourceType.LOCAL.name, row.sourceType)
        // 本地根列表陈列的就是这一列，因此截断后的名字就是列表里显示的名字
        assertEquals(listOf(row.displayName), localRoots(runBlocking { dao.observeAll().first() }).map { it.displayName })

        // 代理对（emoji）不被劈成半个字符：截断按码点算
        val emojiFolderName = "🙂".repeat(CONNECTION_NAME_MAX_LENGTH + 2)
        runBlocking { addLocalConnection(dao, folderName = emojiFolderName, uri = "content://tree/emoji") }
        val emojiRow = runBlocking { dao.observeAll().first() }.first { it.configJson == "content://tree/emoji" }
        assertEquals("🙂".repeat(CONNECTION_NAME_MAX_LENGTH), emojiRow.displayName)

        // 不超长的文件夹名原样落库（清洗只去首尾空白）
        runBlocking { addLocalConnection(dao, folderName = " 文件夹甲 ", uri = "content://tree/short") }
        val shortRow = runBlocking { dao.observeAll().first() }.first { it.configJson == "content://tree/short" }
        assertEquals("文件夹甲", shortRow.displayName)
    }
}
