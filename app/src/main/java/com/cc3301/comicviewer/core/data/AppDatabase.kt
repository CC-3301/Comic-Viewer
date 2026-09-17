package com.cc3301.comicviewer.core.data

import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.RoomDatabase
import androidx.room.Update
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.cc3301.comicviewer.core.source.ProgressStore
import com.cc3301.comicviewer.core.source.ReadingProgress
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
 * 书柜条目（票 17，spec 故事 43/44）：一本书在某个连接的入柜记录。
 * [name] 与 [coverUri] 是入柜时的快照——连接离线时书柜照样罗列，封面退化为占位图。
 */
@Entity(tableName = "bookshelf_entries", primaryKeys = ["connectionId", "bookId"])
data class BookshelfEntryEntity(
    val connectionId: Long,
    val bookId: String,
    val name: String,
    val coverUri: String?,
    val addedAtMs: Long,
)

@Dao
interface BookshelfDao {
    @Query("SELECT * FROM bookshelf_entries ORDER BY addedAtMs, name")
    fun observeAll(): Flow<List<BookshelfEntryEntity>>

    @Query("SELECT * FROM bookshelf_entries WHERE connectionId = :connectionId ORDER BY addedAtMs, name")
    fun observeByConnection(connectionId: Long): Flow<List<BookshelfEntryEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun add(entry: BookshelfEntryEntity)

    @Query("DELETE FROM bookshelf_entries WHERE connectionId = :connectionId AND bookId = :bookId")
    suspend fun remove(connectionId: Long, bookId: String)

    @Query("DELETE FROM bookshelf_entries WHERE connectionId = :connectionId")
    suspend fun removeByConnection(connectionId: Long)
}

/**
 * 阅读进度批量投影（票 05 浏览列表 / 票 17 书柜同款）：bookId → 领域进度。
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
    entities = [ConnectionEntity::class, ReadingProgressEntity::class, BookshelfEntryEntity::class],
    version = 3,
    exportSchema = false,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun connectionDao(): ConnectionDao
    abstract fun readingProgressDao(): ReadingProgressDao
    abstract fun bookshelfDao(): BookshelfDao

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
         * 书柜表不在本迁移的处置范围（其去留由票 31 定夺），因此不动 bookshelf_entries。
         */
        val MIGRATION_2_3: Migration = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("DELETE FROM connections WHERE sourceType = 'OPDS'")
                db.execSQL("DELETE FROM reading_progress WHERE bookId LIKE 'opds-%'")
            }
        }
    }
}
