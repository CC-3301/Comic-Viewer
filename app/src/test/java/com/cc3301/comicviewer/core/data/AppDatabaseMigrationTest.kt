package com.cc3301.comicviewer.core.data

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * v1 → v2 迁移（票 17）：书柜表是新增表，既有连接配置与阅读进度必须原样保留。
 * v1 建表语句逐字取自 Room 为 v1 生成的 DDL，保证是真实旧库结构。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AppDatabaseMigrationTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
    }

    @After
    fun tearDown() {
        context.deleteDatabase(DB_NAME)
    }

    @Test
    fun `v1 升 v2 保留连接与阅读进度并新建书柜表`() = runTest {
        createV1Database()

        val db = Room.databaseBuilder(context, AppDatabase::class.java, DB_NAME)
            .addMigrations(AppDatabase.MIGRATION_1_2)
            .allowMainThreadQueries()
            .build()
        try {
            val connection = db.connectionDao().observeAll().first().single()
            assertEquals("SMB", connection.sourceType)
            assertEquals("NAS", connection.displayName)
            assertEquals("{}", connection.configJson)

            assertEquals(7, db.readingProgressDao().read("book-1")?.pageIndex)
            assertEquals(20, db.readingProgressDao().read("book-1")?.totalPages)

            // 新表可用：升完库即可入柜与读回
            db.bookshelfDao().add(BookshelfEntryEntity(connection.id, "book-1", "第一本", null, addedAtMs = 1L))
            assertEquals(listOf("第一本"), db.bookshelfDao().observeByConnection(connection.id).first().map { it.name })

            assertEquals(2, db.openHelper.writableDatabase.version)
        } finally {
            db.close()
        }
    }

    private fun createV1Database() {
        val file = context.getDatabasePath(DB_NAME)
        file.parentFile?.mkdirs()
        val raw = SQLiteDatabase.openOrCreateDatabase(file, null)
        raw.execSQL(
            "CREATE TABLE IF NOT EXISTS `connections` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                "`sourceType` TEXT NOT NULL, `displayName` TEXT NOT NULL, `configJson` TEXT NOT NULL)",
        )
        raw.execSQL(
            "CREATE TABLE IF NOT EXISTS `reading_progress` (`bookId` TEXT NOT NULL, `pageIndex` INTEGER NOT NULL, " +
                "`totalPages` INTEGER NOT NULL, `updatedAtMs` INTEGER NOT NULL, PRIMARY KEY(`bookId`))",
        )
        raw.execSQL("INSERT INTO connections (sourceType, displayName, configJson) VALUES ('SMB', 'NAS', '{}')")
        raw.execSQL(
            "INSERT INTO reading_progress (bookId, pageIndex, totalPages, updatedAtMs) VALUES ('book-1', 7, 20, 100)",
        )
        raw.version = 1
        raw.close()
    }

    private companion object {
        const val DB_NAME = "migration-v1-v2-test.db"
    }
}
