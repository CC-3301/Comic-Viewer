package com.cc3301.comicviewer.core.data

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.cc3301.comicviewer.core.source.TestCredentialCipherRule
import com.cc3301.comicviewer.core.source.komga.KomgaConnectionConfig
import com.cc3301.comicviewer.core.source.smb.SmbConnectionConfig
import com.cc3301.comicviewer.core.source.webdav.WebDavConnectionConfig
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 数据库迁移：v1 → v2 建书柜表（票 17），v2 → v3 清 OPDS 存量（票 33），
 * v3 → v4 删书柜表（票 31，书柜改为按连接陈列根条目，逐本「加入书柜」废弃），
 * v4 → v5 把存量明文凭据改写成密文（票 #27）。
 * 旧库建表语句逐字取自 Room 为对应版本生成的 DDL，保证是真实旧库结构。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AppDatabaseMigrationTest {

    @get:Rule
    val credentialCipher = TestCredentialCipherRule()

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
    fun `v1 升 v5 保留连接与阅读进度并最终删掉书柜表`() = runTest {
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

            // v1 → v2 建出来的书柜表到 v4 必须已经删掉（否则 Room 迁移校验会失败）
            assertFalse("书柜表必须随 v3 → v4 删除", hasTable(db, "bookshelf_entries"))
            assertEquals(5, db.openHelper.writableDatabase.version)
        } finally {
            db.close()
        }
    }

    @Test
    fun `v2 升 v5 清 OPDS 存量 其余来源的连接与进度原样保留`() = runTest {
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

            assertFalse(hasTable(db, "bookshelf_entries"))
            assertEquals(5, db.openHelper.writableDatabase.version)
        } finally {
            db.close()
        }
    }

    @Test
    fun `v3 升 v5 删掉书柜表 其它来源的连接与阅读进度原样保留`() = runTest {
        // v3 就是本票落地前的现网结构：书柜表里还留着用户的收藏名单（本票明确废弃它）
        createLegacyDatabase(
            version = 3,
            inserts = listOf(
                "INSERT INTO connections (sourceType, displayName, configJson) VALUES ('SMB', 'NAS', '{\"host\":\"nas\"}')",
                "INSERT INTO connections (sourceType, displayName, configJson) VALUES ('KOMGA', 'Komga', '{}')",
                "INSERT INTO reading_progress (bookId, pageIndex, totalPages, updatedAtMs) VALUES ('book-1', 7, 20, 100)",
                "INSERT INTO reading_progress (bookId, pageIndex, totalPages, updatedAtMs) " +
                    "VALUES ('komga-http://komga:25600/book/b1', 3, 9, 200)",
                "INSERT INTO bookshelf_entries (connectionId, bookId, name, coverUri, addedAtMs) " +
                    "VALUES (1, 'book-1', '第一本', NULL, 400)",
            ),
        )

        val db = openMigratedDatabase()
        try {
            // 书柜表随迁移消失（收藏名单由维护者已知悉并接受地丢失）
            assertFalse("书柜表必须随迁移删除", hasTable(db, "bookshelf_entries"))

            // 其它来源的连接与进度一律原样保留
            val connections = db.connectionDao().observeAll().first()
            assertEquals(listOf("KOMGA", "SMB"), connections.map { it.sourceType })
            assertEquals("{\"host\":\"nas\"}", connections.first { it.displayName == "NAS" }.configJson)

            val progress = db.readingProgressDao().readAll().first().associateBy { it.bookId }
            assertEquals(setOf("book-1", "komga-http://komga:25600/book/b1"), progress.keys)
            assertEquals(7, progress["book-1"]?.pageIndex)
            assertEquals(20, progress["book-1"]?.totalPages)
            assertEquals(200L, progress["komga-http://komga:25600/book/b1"]?.updatedAtMs)

            // 升完库仍可正常增删改：连接与进度两张表都在
            val newId = db.connectionDao().insert(
                ConnectionEntity(sourceType = "WEBDAV", displayName = "NAS DAV", configJson = "{}"),
            )
            assertTrue(newId > 0L)
            assertEquals(3, db.connectionDao().observeAll().first().size)
            db.readingProgressDao().write("book-1", pageIndex = 8, totalPages = 20, updatedAtMs = 500L)
            assertEquals(8, db.readingProgressDao().read("book-1")?.pageIndex)
            assertEquals(5, db.openHelper.writableDatabase.version)
        } finally {
            db.close()
        }
    }

    @Test
    fun `v4 升 v5 把明文凭据改写成密文 连接与阅读进度原样保留`() = runTest {
        // v4 就是本票落地前的现网结构：SMB/WebDAV/Komga 的凭据以明文躺在 configJson 里
        createLegacyDatabase(
            version = 4,
            inserts = listOf(
                "INSERT INTO connections (sourceType, displayName, configJson) VALUES ('SMB', " +
                    "'comics @ nas.local', '$LEGACY_SMB')",
                "INSERT INTO connections (sourceType, displayName, configJson) VALUES ('WEBDAV', " +
                    "'http://nas:5006/dav', '$LEGACY_WEBDAV')",
                "INSERT INTO connections (sourceType, displayName, configJson) VALUES ('KOMGA', " +
                    "'http://komga:25600', '$LEGACY_KOMGA')",
                // 本地来源的 configJson 是 SAF uri（不是 JSON）：不该被动
                "INSERT INTO connections (sourceType, displayName, configJson) VALUES ('LOCAL', '本地', 'content://tree/1')",
                "INSERT INTO reading_progress (bookId, pageIndex, totalPages, updatedAtMs) " +
                    "VALUES ('smb://nas.local/comics/manga/p1.jpg', 7, 20, 100)",
            ),
        )

        val db = openMigratedDatabase()
        try {
            val connections = db.connectionDao().observeAll().first().associateBy { it.sourceType }

            // 不落明文：整张 connections 表的 configJson 里再也找不到任何一个明文凭据
            val storedText = connections.values.joinToString(separator = "|") { it.configJson }
            listOf("s3cret", "dav-pw", "komga-pw", "api-key-1").forEach { secret ->
                assertFalse("迁移后不得残留明文：" + secret, storedText.contains(secret))
            }

            // 旧连接照旧可读（升级后不用重填）：解出来的配置与票前形状逐字段一致
            assertEquals(
                SmbConnectionConfig(
                    host = "nas.local",
                    share = "comics",
                    rootPath = "manga",
                    username = "reader",
                    password = "s3cret",
                    domain = "WORKGROUP",
                    port = 4450,
                ),
                SmbConnectionConfig.fromJson(connections.getValue("SMB").configJson),
            )
            assertEquals(
                WebDavConnectionConfig(
                    baseUrl = "http://nas:5006/dav",
                    rootPath = "comics",
                    username = "reader",
                    password = "dav-pw",
                ),
                WebDavConnectionConfig.fromJson(connections.getValue("WEBDAV").configJson),
            )
            assertEquals(
                KomgaConnectionConfig(
                    baseUrl = "http://komga:25600",
                    username = "me@example.com",
                    password = "komga-pw",
                    apiKey = "api-key-1",
                ),
                KomgaConnectionConfig.fromJson(connections.getValue("KOMGA").configJson),
            )

            // 行本身不动：展示名与本地来源的 uri 原样
            assertEquals("comics @ nas.local", connections.getValue("SMB").displayName)
            assertEquals("content://tree/1", connections.getValue("LOCAL").configJson)
            // 阅读进度一律原样
            assertEquals(7, db.readingProgressDao().read("smb://nas.local/comics/manga/p1.jpg")?.pageIndex)
            assertEquals(5, db.openHelper.writableDatabase.version)
        } finally {
            db.close()
        }
    }

    @Test
    fun `凭据改写幂等 同一份数据连续迁移两次结果一致`() = runTest {
        createLegacyDatabase(
            version = 4,
            inserts = listOf(
                "INSERT INTO connections (sourceType, displayName, configJson) VALUES ('SMB', 'NAS', '$LEGACY_SMB')",
            ),
        )

        val db = openMigratedDatabase()
        try {
            // 开库时 v4 → v5 已经跑过一次
            val once = db.connectionDao().observeAll().first().single().configJson
            assertFalse("第一次迁移后不得含明文", once.contains("s3cret"))
            assertEquals("s3cret", SmbConnectionConfig.fromJson(once)!!.password)

            // 再跑一次：已是密文的值不再动（不会二次加密），连密文串本身都不变
            AppDatabase.MIGRATION_4_5.migrate(db.openHelper.writableDatabase)
            val twice = db.connectionDao().observeAll().first().single().configJson

            assertEquals(once, twice)
            assertEquals("s3cret", SmbConnectionConfig.fromJson(twice)!!.password)
        } finally {
            db.close()
        }
    }

    /** 迁移链：既有用户从 v1/v2/v3/v4 升级都要能一路升到当前版本 */
    private fun openMigratedDatabase(): AppDatabase =
        Room.databaseBuilder(context, AppDatabase::class.java, DB_NAME)
            .addMigrations(
                AppDatabase.MIGRATION_1_2,
                AppDatabase.MIGRATION_2_3,
                AppDatabase.MIGRATION_3_4,
                AppDatabase.MIGRATION_4_5,
            )
            .allowMainThreadQueries()
            .build()

    /** 表是否还在（票 31：删表只能靠 sqlite_master 断言，Room 侧已无对应实体） */
    private fun hasTable(db: AppDatabase, name: String): Boolean =
        db.openHelper.writableDatabase
            .query("SELECT name FROM sqlite_master WHERE type = 'table' AND name = ?", arrayOf(name))
            .use { it.count > 0 }

    /**
     * 建旧库：[version] = 1 时只有 connections / reading_progress；
     * v2/v3 再加书柜表（v1 → v2 建的，v3 结构与 v2 相同，只差数据）；
     * v4 起书柜表已随 v3 → v4 删掉（也就回到只有两张表的形状）。
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
        if (version in 2..3) {
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

        /** 票 #27 之前的库形状：三个来源的凭据都是明文 */
        const val LEGACY_SMB =
            "{\"host\":\"nas.local\",\"share\":\"comics\",\"rootPath\":\"manga\",\"username\":\"reader\"," +
                "\"password\":\"s3cret\",\"domain\":\"WORKGROUP\",\"port\":4450}"
        const val LEGACY_WEBDAV =
            "{\"baseUrl\":\"http://nas:5006/dav\",\"rootPath\":\"comics\",\"username\":\"reader\"," +
                "\"password\":\"dav-pw\"}"
        const val LEGACY_KOMGA =
            "{\"baseUrl\":\"http://komga:25600\",\"username\":\"me@example.com\"," +
                "\"password\":\"komga-pw\",\"apiKey\":\"api-key-1\"}"
    }
}
