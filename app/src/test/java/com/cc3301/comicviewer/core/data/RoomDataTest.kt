package com.cc3301.comicviewer.core.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.cc3301.comicviewer.core.source.ReadingProgress
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

/** Room 建库可读写：连接配置表 + 进度表（票 01 验收标准） */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RoomDataTest {

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
    fun `连接表插入查询更新删除`() = runTest {
        val dao = db.connectionDao()
        val id = dao.insert(ConnectionEntity(sourceType = "SMB", displayName = "NAS", configJson = "{}"))
        val all = dao.observeAll().first()
        assertEquals(1, all.size)
        assertEquals("NAS", all[0].displayName)

        dao.update(all[0].copy(displayName = "NAS-2"))
        assertEquals("NAS-2", dao.observeAll().first()[0].displayName)

        dao.deleteById(id)
        assertEquals(0, dao.observeAll().first().size)
    }

    @Test
    fun `进度表写读覆盖`() = runTest {
        val dao = db.readingProgressDao()
        assertNull(dao.read("book-1"))

        dao.write("book-1", pageIndex = 1, totalPages = 10, updatedAtMs = 100L)
        val first = dao.read("book-1")!!
        assertEquals(1, first.pageIndex)

        dao.write("book-1", pageIndex = 5, totalPages = 10, updatedAtMs = 200L)
        val second = dao.read("book-1")!!
        assertEquals(5, second.pageIndex)
        assertEquals(200L, second.updatedAtMs)
    }

    @Test
    fun `RoomProgressStore 包装行为一致`() = runTest {
        val store = RoomProgressStore(db.readingProgressDao())
        store.write("b", pageIndex = 3, totalPages = 9)
        val progress: ReadingProgress? = store.read("b")
        assertEquals(3, progress!!.pageIndex)
        assertEquals(9, progress.totalPages)
    }
}
