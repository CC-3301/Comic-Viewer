package com.cc3301.comicviewer.ui

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.cc3301.comicviewer.core.nav.LastBrowsing
import com.cc3301.comicviewer.core.nav.LastRead
import com.cc3301.comicviewer.core.source.SortMode
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 启动状态持久化（票 20，spec 故事 47/48）：判定发生在进程启动时，
 * 所以「上次停留的位置」「上次阅读的位置」「是否正在看书」必须跨进程重启可读。
 * 本测试把落盘与读回分开调用（StartupStore 不缓存），等价于进程重启后的读取。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class StartupStoreTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        ServiceLocator.init(context)
        context.getSharedPreferences("startup", Context.MODE_PRIVATE).edit().clear().commit()
    }

    @After
    fun tearDown() {
        context.getSharedPreferences("startup", Context.MODE_PRIVATE).edit().clear().commit()
        ServiceLocator.currentSource = null
    }

    @Test
    fun `无记录时状态为空 判定退化首页`() {
        assertNull(StartupStore.lastRead())
        assertNull(StartupStore.lastBrowsing())
        assertFalse(StartupStore.state().wasReading)
    }

    @Test
    fun `上次停留的位置 层级与排序方式跨重启可读`() {
        StartupStore.recordBrowsing(LastBrowsing(connId = 7, containerId = "dir-x", sortMode = SortMode.MODIFIED_TIME))

        val restored = StartupStore.lastBrowsing()
        assertEquals(7L, restored?.connId)
        assertEquals("dir-x", restored?.containerId)
        assertEquals(SortMode.MODIFIED_TIME, restored?.sortMode)
    }

    @Test
    fun `根目录位置 containerId 为空 也能往返`() {
        StartupStore.recordBrowsing(LastBrowsing(connId = 2, containerId = null))

        val restored = StartupStore.lastBrowsing()
        assertEquals(2L, restored?.connId)
        assertNull(restored?.containerId)
        assertEquals(SortMode.NAME, restored?.sortMode)
    }

    @Test
    fun `排序方式键损坏时回退名称排序`() {
        StartupStore.recordBrowsing(LastBrowsing(connId = 3, containerId = null, sortMode = SortMode.NAME))
        context.getSharedPreferences("startup", Context.MODE_PRIVATE).edit()
            .putString("last_browsing_sort", "bogus").commit()

        assertEquals(SortMode.NAME, StartupStore.lastBrowsing()?.sortMode)
    }

    @Test
    fun `最近阅读的书跨重启可读 清空后消失`() {
        StartupStore.recordLastRead(LastRead(connId = 5, bookId = "book-9"))
        assertEquals(LastRead(5, "book-9"), StartupStore.lastRead())

        StartupStore.recordLastRead(null)
        assertNull(StartupStore.lastRead())
    }

    @Test
    fun `会话 lastRead 写入即落盘`() {
        ServiceLocator.lastRead = LastRead(connId = 4, bookId = "book-4")
        assertEquals(LastRead(4, "book-4"), StartupStore.lastRead())
    }

    @Test
    fun `是否正在看书 标志往返`() {
        StartupStore.recordReading(true)
        assertEquals(true, StartupStore.state().wasReading)

        StartupStore.recordReading(false)
        assertEquals(false, StartupStore.state().wasReading)
    }
}
