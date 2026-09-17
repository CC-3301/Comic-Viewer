package com.cc3301.comicviewer.ui

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.cc3301.comicviewer.core.nav.LastBrowsing
import com.cc3301.comicviewer.core.nav.LastRead
import com.cc3301.comicviewer.core.nav.StartupPage
import com.cc3301.comicviewer.core.nav.StartupTarget
import com.cc3301.comicviewer.core.nav.resolveStartupTarget
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
 * 「柜页切换排序不调用 recordBrowsing」是界面接线，仓库没有 Compose UI 测试：这里用
 * 「切换全局排序后，启动仍恢复到退出时那个目录层级」的往返断言把它守住，界面侧另有真机清单。
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
    fun `上次停留的位置 目录层级跨重启可读`() {
        StartupStore.recordBrowsing(LastBrowsing(connId = 7, containerId = "dir-x"))

        val restored = StartupStore.lastBrowsing()
        assertEquals(7L, restored?.connId)
        assertEquals("dir-x", restored?.containerId)
    }

    @Test
    fun `根目录位置 containerId 为空 也能往返`() {
        StartupStore.recordBrowsing(LastBrowsing(connId = 2, containerId = null))

        val restored = StartupStore.lastBrowsing()
        assertEquals(2L, restored?.connId)
        assertNull(restored?.containerId)
    }

    @Test
    fun `排序切换不改写退出时的目录层级 启动仍恢复到该层级`() {
        // 票 #29 增补（#31 AC-2）：退出前停在子目录、之后切了排序档位与方向，
        // 启动恢复的必须是那个子目录本身（旧 #31 实现会把位置改写成连接根 containerId=null）。
        // 覆盖：切换全局排序不触碰 startup prefs 里的浏览位置（两个 store 各存各的）。
        // 不覆盖：柜页/浏览页是否真的没调 recordBrowsing——界面接线的守卫见类注释。
        StartupStore.recordBrowsing(LastBrowsing(connId = 7, containerId = "dir-x"))

        SortSettingStore.setting = SortSettingStore.setting
            .select(SortMode.MODIFIED_TIME)
            .select(SortMode.MODIFIED_TIME)   // 档位与方向都变

        assertEquals(
            StartupTarget.OpenBrowser(LastBrowsing(connId = 7, containerId = "dir-x")),
            resolveStartupTarget(StartupPage.LAST_BROWSING, StartupStore.state()),
        )
        assertEquals(SortMode.MODIFIED_TIME, SortSettingStore.setting.mode)
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
