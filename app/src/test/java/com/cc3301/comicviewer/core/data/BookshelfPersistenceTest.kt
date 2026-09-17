package com.cc3301.comicviewer.core.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** 书柜条目持久化（票 17 验收标准 1；spec 故事 43） */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class BookshelfPersistenceTest {

    private lateinit var context: Context
    private lateinit var db: AppDatabase

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
    }

    @After
    fun tearDown() {
        db.close()
    }

    private fun entry(connectionId: Long, bookId: String, name: String = bookId, addedAtMs: Long = 0L) =
        BookshelfEntryEntity(connectionId, bookId, name, coverUri = null, addedAtMs = addedAtMs)

    @Test
    fun `加入后可读到，移出后消失`() = runTest {
        val dao = db.bookshelfDao()
        dao.add(entry(1, "a", name = "第一本"))
        assertEquals(listOf("第一本"), dao.observeByConnection(1).first().map { it.name })

        dao.remove(1, "a")
        assertEquals(emptyList<BookshelfEntryEntity>(), dao.observeByConnection(1).first())
    }

    @Test
    fun `同一本书重复加入只留一条`() = runTest {
        val dao = db.bookshelfDao()
        dao.add(entry(1, "a", name = "旧名", addedAtMs = 1))
        dao.add(entry(1, "a", name = "新名", addedAtMs = 2))

        val entries = dao.observeByConnection(1).first()
        assertEquals(1, entries.size)
        assertEquals("新名", entries.single().name)
    }

    @Test
    fun `不同连接的条目互不串柜`() = runTest {
        val dao = db.bookshelfDao()
        dao.add(entry(1, "a"))
        dao.add(entry(2, "b"))

        assertEquals(listOf("a"), dao.observeByConnection(1).first().map { it.bookId })
        assertEquals(listOf("b"), dao.observeByConnection(2).first().map { it.bookId })
        assertEquals(listOf("a", "b"), dao.observeAll().first().map { it.bookId })
    }

    @Test
    fun `移出只影响指定连接的那一本`() = runTest {
        val dao = db.bookshelfDao()
        dao.add(entry(1, "a"))
        dao.add(entry(2, "a"))

        dao.remove(1, "a")
        assertEquals(emptyList<BookshelfEntryEntity>(), dao.observeByConnection(1).first())
        assertEquals(listOf("a"), dao.observeByConnection(2).first().map { it.bookId })
    }

    @Test
    fun `删除连接时清空该书柜且不动其它连接与阅读进度`() = runTest {
        db.bookshelfDao().add(entry(1, "a"))
        db.bookshelfDao().add(entry(2, "b"))
        db.readingProgressDao().write("a", pageIndex = 3, totalPages = 10, updatedAtMs = 1L)

        // 连接删除页面的顺序：先清书柜条目，再删连接
        db.bookshelfDao().removeByConnection(1)
        db.connectionDao().deleteById(1)

        assertEquals(emptyList<BookshelfEntryEntity>(), db.bookshelfDao().observeByConnection(1).first())
        assertEquals(listOf("b"), db.bookshelfDao().observeByConnection(2).first().map { it.bookId })
        assertEquals(3, db.readingProgressDao().read("a")?.pageIndex)
    }

    @Test
    fun `重开数据库条目仍在`() = runTest {
        val fileDb = Room.databaseBuilder(context, AppDatabase::class.java, DB_NAME)
            .allowMainThreadQueries()
            .build()
        fileDb.bookshelfDao().add(entry(1, "a", name = "第一本"))
        fileDb.close()

        val reopened = Room.databaseBuilder(context, AppDatabase::class.java, DB_NAME)
            .allowMainThreadQueries()
            .build()
        try {
            assertEquals(listOf("第一本"), reopened.bookshelfDao().observeByConnection(1).first().map { it.name })
        } finally {
            reopened.close()
            context.deleteDatabase(DB_NAME)
        }
    }

    @Test
    fun `空书柜读不到任何条目`() = runTest {
        assertNull(db.bookshelfDao().observeByConnection(1).first().firstOrNull())
    }

    private companion object {
        const val DB_NAME = "bookshelf-persistence-test.db"
    }
}
