package com.cc3301.comicviewer.core.data

import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.RoomDatabase
import androidx.room.Update
import com.cc3301.comicviewer.core.source.ProgressStore
import com.cc3301.comicviewer.core.source.ReadingProgress
import kotlinx.coroutines.flow.Flow

/** 来源连接配置（SMB/WebDAV/Komga/OPDS；LOCAL 无需连接） */
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

/** ProgressStore 的 Room 实现（供各 Source 注入） */
class RoomProgressStore(private val dao: ReadingProgressDao) : ProgressStore {
    override suspend fun read(bookId: String): ReadingProgress? =
        dao.read(bookId)?.let { ReadingProgress(it.pageIndex, it.totalPages, it.updatedAtMs) }

    override suspend fun write(bookId: String, pageIndex: Int, totalPages: Int) =
        dao.write(bookId, pageIndex, totalPages, System.currentTimeMillis())
}

@Database(
    entities = [ConnectionEntity::class, ReadingProgressEntity::class],
    version = 1,
    exportSchema = false,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun connectionDao(): ConnectionDao
    abstract fun readingProgressDao(): ReadingProgressDao
}
