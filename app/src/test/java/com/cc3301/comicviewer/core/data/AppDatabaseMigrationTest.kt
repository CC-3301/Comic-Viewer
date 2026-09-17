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
 * 数据库迁移：v1 → v2 建书柜表（票 17），v2 → v3 清 OPDS 存量（票 33）。
 * 旧库建表语句逐字取自 Room 为对应版本生成的 DDL，保证是真实旧库结构。
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
    fun `v1 升 v3 保留连接与阅读进度并新建书柜表`() = runTest {
        createLegacyDatabase(
            version = 1,
            inserts = listOf(
                "INSERT INTO connections (sourceType, displayName, configJson) VALUES ('SMB', 'NAS', '{}')",
                "INSERT INTO reading_progress (bookId, pageIndex, totalPages, updatedAtMs) " +
                    "VALUES ('book-1', 7, 20, 100)",
            ),
        )

        val db = openMigratedDatabase()
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

            assertEquals(3, db.openHelper.writableDatabase.version)
        } finally {
            db.close()
        }
    }

    @Test
    fun `v2 升 v3 删除 OPDS 连接与进度 其余来源原样保留`() = runTest {
        createLegacyDatabase(
            version = 2,
            inserts = listOf(
                "INSERT INTO connections (sourceType, displayName, configJson) VALUES ('SMB', 'NAS', '{\"host\":\"nas\"}')",
                "INSERT INTO connections (sourceType, displayName, configJson) VALUES ('KOMGA', 'Komga', '{}')",
                "INSERT INTO connections (sourceType, displayName, configJson) VALUES ('OPDS', 'OPDS 书库', '{\"feedUrl\":\"http://opds.example.com/opds\"}')",
                "INSERT INTO reading_progress (bookId, pageIndex, totalPages, updatedAtMs) VALUES ('book-1', 7, 20, 100)",
                "INSERT INTO reading_progress (bookId, pageIndex, totalPages, updatedAtMs) " +
                    "VALUES ('komga-http://komga:25600/book/b1', 3, 9, 200)",
                "INSERT INTO reading_progress (bookId, pageIndex, totalPages, updatedAtMs) " +
                    "VALUES ('opds-http://opds.example.com/opds/book/YWJj', 5, 12, 300)",
                // OPDS 连接的书柜条目：书柜表的去留由票 31 裁决，本迁移一律不动
                "INSERT INTO bookshelf_entries (connectionId, bookId, name, coverUri, addedAtMs) " +
                    "VALUES (3, 'opds-http://opds.example.com/opds/book/YWJj', '第 2 话', NULL, 400)",
            ),
        )

        val db = openMigratedDatabase()
        try {
            // OPDS 连接行被清；其余来源的连接原样保留
            val connections = db.connectionDao().observeAll().first()
            assertEquals(listOf("KOMGA", "SMB"), connections.map { it.sourceType })
            assertEquals(listOf("Komga", "NAS"), connections.map { it.displayName })
            assertEquals("{\"host\":\"nas\"}", connections.first { it.displayName == "NAS" }.configJson)

            // OPDS 进度行被清；其余来源的进度连页码一起保留
            val progress = db.readingProgressDao().readAll().first().associateBy { it.bookId }
            assertEquals(setOf("book-1", "komga-http://komga:25600/book/b1"), progress.keys)
            assertEquals(7, progress["book-1"]?.pageIndex)
            assertEquals(3, progress["komga-http://komga:25600/book/b1"]?.pageIndex)
            assertEquals(200L, progress["komga-http://komga:25600/book/b1"]?.updatedAtMs)

            // 书柜条目不在本迁移的处置范围（票 31）：OPDS 的那条仍在
            assertEquals(
                listOf("第 2 话"),
                db.bookshelfDao().observeByConnection(3).first().map { it.name },
            )

            assertEquals(3, db.openHelper.writableDatabase.version)
        } finally {
            db.close()
        }
    }

    /** 迁移链：既有用户从 v1 或 v2 升级都要能一路升到当前版本 */
    private fun openMigratedDatabase(): AppDatabase =
        Room.databaseBuilder(context, AppDatabase::class.java, DB_NAME)
            .addMigrations(AppDatabase.MIGRATION_1_2, AppDatabase.MIGRATION_2_3)
            .allowMainThreadQueries()
            .build()

    /**
     * 建旧库：[version] = 1 时只有 connections / reading_progress；
     * 2 时再加书柜表（v2 就是这两个版本的差别）。
     */
    private fun createLegacyDatabase(version: Int, inserts: List<String>) {
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
        if (version >= 2) {
            raw.execSQL(
                "CREATE TABLE IF NOT EXISTS `bookshelf_entries` (`connectionId` INTEGER NOT NULL, " +
                    "`bookId` TEXT NOT NULL, `name` TEXT NOT NULL, `coverUri` TEXT, " +
                    "`addedAtMs` INTEGER NOT NULL, PRIMARY KEY(`connectionId`, `bookId`))",
            )
        }
        inserts.forEach { raw.execSQL(it) }
        raw.version = version
        raw.close()
    }

    private companion object {
        const val DB_NAME = "migration-test.db"
    }
}
