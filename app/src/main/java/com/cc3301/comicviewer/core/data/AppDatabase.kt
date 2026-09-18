package com.cc3301.comicviewer.core.data

import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.RoomDatabase
import androidx.room.Update
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.cc3301.comicviewer.core.source.ProgressStore
import com.cc3301.comicviewer.core.source.ReadingProgress
import com.cc3301.comicviewer.core.source.SourceType
import com.cc3301.comicviewer.core.source.komga.KomgaConnectionConfig
import com.cc3301.comicviewer.core.source.smb.SmbConnectionConfig
import com.cc3301.comicviewer.core.source.webdav.WebDavConnectionConfig
import kotlinx.coroutines.flow.Flow

/** 来源连接配置（SMB/WebDAV/Komga；LOCAL 无需连接） */
@Entity(tableName = "connections")
data class ConnectionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val sourceType: String,
    val displayName: String,
    /** 各来源私有配置（地址/凭据/路径等），JSON 序列化 */
    val configJson: String,
)

@Dao
interface ConnectionDao {
    @Query("SELECT * FROM connections ORDER BY displayName")
    fun observeAll(): Flow<List<ConnectionEntity>>

    @Insert
    suspend fun insert(connection: ConnectionEntity): Long

    @Update
    suspend fun update(connection: ConnectionEntity)

    @Query("DELETE FROM connections WHERE id = :id")
    suspend fun deleteById(id: Long)

    /** 启动页按 id 取连接（票 20）：启动直接进阅读器/浏览页时先备会话来源 */
    @Query("SELECT * FROM connections WHERE id = :id")
    suspend fun byId(id: Long): ConnectionEntity?
}

/** 阅读进度（键=来源内 bookId；Komga 另有双向同步层在票 14） */
@Entity(tableName = "reading_progress", primaryKeys = ["bookId"])
data class ReadingProgressEntity(
    val bookId: String,
    val pageIndex: Int,
    val totalPages: Int,
    val updatedAtMs: Long,
)

@Dao
interface ReadingProgressDao {
    @Query("SELECT * FROM reading_progress WHERE bookId = :bookId")
    suspend fun read(bookId: String): ReadingProgressEntity?

    @Query("SELECT * FROM reading_progress")
    fun readAll(): Flow<List<ReadingProgressEntity>>

    @Query("REPLACE INTO reading_progress (bookId, pageIndex, totalPages, updatedAtMs) VALUES (:bookId, :pageIndex, :totalPages, :updatedAtMs)")
    suspend fun write(bookId: String, pageIndex: Int, totalPages: Int, updatedAtMs: Long)
}

/**
 * 阅读进度批量投影（票 05 浏览列表 / 票 31 书柜同款）：bookId → 领域进度。
 * 列表进度条取值走这里，两个界面的取值方式因此永远一致。
 */
fun progressByBook(list: List<ReadingProgressEntity>): Map<String, ReadingProgress> =
    list.associate { it.bookId to ReadingProgress(it.pageIndex, it.totalPages, it.updatedAtMs) }

/** ProgressStore 的 Room 实现（供各 Source 注入） */
class RoomProgressStore(private val dao: ReadingProgressDao) : ProgressStore {
    override suspend fun read(bookId: String): ReadingProgress? =
        dao.read(bookId)?.let { ReadingProgress(it.pageIndex, it.totalPages, it.updatedAtMs) }

    override suspend fun write(bookId: String, pageIndex: Int, totalPages: Int) =
        dao.write(bookId, pageIndex, totalPages, System.currentTimeMillis())
}

@Database(
    entities = [ConnectionEntity::class, ReadingProgressEntity::class],
    version = 5,
    exportSchema = false,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun connectionDao(): ConnectionDao
    abstract fun readingProgressDao(): ReadingProgressDao

    companion object {
        /**
         * v1 → v2（票 17）：只新建书柜表，connections 与 reading_progress 原样保留，
         * 既有用户的连接配置与阅读进度不会丢（旧库升级走本迁移，不做破坏性重建）。
         */
        val MIGRATION_1_2: Migration = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `bookshelf_entries` (" +
                        "`connectionId` INTEGER NOT NULL, " +
                        "`bookId` TEXT NOT NULL, " +
                        "`name` TEXT NOT NULL, " +
                        "`coverUri` TEXT, " +
                        "`addedAtMs` INTEGER NOT NULL, " +
                        "PRIMARY KEY(`connectionId`, `bookId`))",
                )
            }
        }

        /**
         * v2 → v3（票 33）：OPDS 来源下线，清掉指纹明显的存量——
         * `connections` 里的 OPDS 连接行与 `reading_progress` 里的 OPDS 进度行
         * （bookId 前缀 `opds-`，见票 15 的 OpdsIds），其余来源的数据一律原样保留。
         * 书柜表不在本迁移的处置范围（它由票 31 的 v3 → v4 删除），因此不动 bookshelf_entries。
         */
        val MIGRATION_2_3: Migration = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("DELETE FROM connections WHERE sourceType = 'OPDS'")
                db.execSQL("DELETE FROM reading_progress WHERE bookId LIKE 'opds-%'")
            }
        }

        /**
         * v3 → v4（票 31）：书柜语义重定义——书柜改为按连接陈列该连接的根条目，逐本「加入书柜」废弃，
         * 因此删掉 bookshelf_entries 表（原收藏名单随之丢失，为维护者已知悉并接受的取舍）。
         * connections 与 reading_progress 一律原样保留。
         */
        val MIGRATION_3_4: Migration = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("DROP TABLE IF EXISTS `bookshelf_entries`")
            }
        }

        /**
         * v4 → v5（票 #27）：存量连接的明文凭据改写成密文（SMB/WebDAV 的 password、Komga 的 apiKey/password）。
         *
         * 只改 configJson 里敏感字段的**值**：行本身（displayName、地址等）与阅读进度一律原样保留，
         * 用户不必重新填写，旧连接升级后照旧可浏览。旧明文读路径仍认，所以单行失败时保留原行不阻断升级
         * （迁移里抛异常会让 app 每次启动都打不开库，代价远大于留一行明文；用户下次编辑该连接时会重新落密文）。
         * 幂等：已是密文的值不再动，重复执行结果一致。
         */
        val MIGRATION_4_5: Migration = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                protectStoredCredentials(db)
            }
        }
    }
}

/**
 * 把 connections 表里的明文凭据改写成密文（票 #27，[AppDatabase.MIGRATION_4_5] 用）：
 * 按 sourceType 分派到各来源自己的 JSON 形状（字段名是各配置的存储契约）；
 * LOCAL（configJson 是 SAF uri）与未知类型不含凭据，原样跳过。
 * 先把待改写的行收齐再 UPDATE，避免边遍历游标边写同一张表。
 */
private fun protectStoredCredentials(db: SupportSQLiteDatabase) {
    val rewrites = mutableListOf<Pair<Long, String>>()
    db.query("SELECT id, sourceType, configJson FROM connections").use { cursor ->
        val idAt = cursor.getColumnIndexOrThrow("id")
        val typeAt = cursor.getColumnIndexOrThrow("sourceType")
        val jsonAt = cursor.getColumnIndexOrThrow("configJson")
        while (cursor.moveToNext()) {
            val json = cursor.getString(jsonAt)
            val protectedJson = when (cursor.getString(typeAt)) {
                SourceType.SMB.name -> SmbConnectionConfig.protectSecrets(json)
                SourceType.WEBDAV.name -> WebDavConnectionConfig.protectSecrets(json)
                SourceType.KOMGA.name -> KomgaConnectionConfig.protectSecrets(json)
                else -> null
            }
            if (protectedJson != null && protectedJson != json) {
                rewrites.add(cursor.getLong(idAt) to protectedJson)
            }
        }
    }
    rewrites.forEach { (id, json) ->
        db.execSQL("UPDATE connections SET configJson = ? WHERE id = ?", arrayOf(json, id))
    }
}
