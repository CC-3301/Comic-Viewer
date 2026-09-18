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
 * 排序相关的边界用一条往返断言锁住：切换全局排序（档位 + 方向）不污染 startup prefs 里的浏览位置，
 * 且该位置经启动判定仍解析回同一个目录层级。「柜页界面是否真的没调 recordBrowsing」不在本测试覆盖内
 * （仓库无 Compose UI 测试），由真机清单守护。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class StartupStoreTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        ServiceLocator.init(context)
        clearPrefs()
    }

    @After
    fun tearDown() {
        clearPrefs()
        ServiceLocator.currentSource = null
        // 票 26 第 8 项：lastRead 是带落盘副作用的静态字段，本类会给它赋值——不还原就会串进同 sandbox 的后续用例
        ServiceLocator.lastRead = null
    }

    /**
     * 两个 prefs 都要清（票 25 卫生缺口）：`startup` 存上次状态，`settings` 存启动页等设置——
     * 本类会给后者赋值（[AppSettings.startupPage]）。只清一半会让残留串进同一 sandbox 的后续用例。
     */
    private fun clearPrefs() {
        context.getSharedPreferences("startup", Context.MODE_PRIVATE).edit().clear().commit()
        context.getSharedPreferences(AppSettings.PREFS_NAME, Context.MODE_PRIVATE).edit().clear().commit()
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
        // 覆盖：切换全局排序不触碰 startup prefs 里的浏览位置（两个 store 各存各的），位置往返解析不变。
        // 不覆盖：柜页界面是否真的没调 recordBrowsing（仓库无 Compose UI 测试），该项由真机清单守护。
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
    fun `清掉上次停留的位置后 启动判定退化首页`() {
        // 票 26 第 2 项：连接被删后启动兜底会清掉这条记录，否则每次启动都要重新走一遍退化路径
        StartupStore.recordBrowsing(LastBrowsing(connId = 7, containerId = "dir-x"))
        StartupStore.clearBrowsing()

        assertNull(StartupStore.lastBrowsing())
        assertEquals(
            StartupTarget.OpenHome,
            resolveStartupTarget(StartupPage.LAST_BROWSING, StartupStore.state()),
        )
        assertEquals(
            StartupTarget.OpenHome,
            resolveStartupTarget(StartupPage.LAST_READ, StartupStore.state()),
        )
    }

    /**
     * 票 26 r2 修正 1 / r3 修正 F：启动判定读的是一份**同步快照**（启动页面设置 + 上次状态一起现读），
     * 且两半输入都要钉住：设置半（[AppSettings.startupPage]）与状态半（[StartupStore.state]）各变一次，
     * 避免实现硬写某一个设置值也照样为绿。竞态/时序本身需要 Compose 时序，仓库无 Compose 测试基建，
     * 由真机清单守护（写点守卫另见 [StartupReadingFlagTest]）。
     */
    @Test
    fun `启动快照一次性读出设置与上次状态`() {
        StartupStore.recordLastRead(LastRead(connId = 3, bookId = "book-3"))
        StartupStore.recordBrowsing(LastBrowsing(connId = 3, containerId = "dir-x"))
        StartupStore.recordReading(false)
        // 设置半 = 默认「上次阅读的位置」；不是在看书 → 退化为上次停留的位置
        assertEquals(StartupPage.LAST_READ, AppSettings.startupPage)
        assertEquals(
            StartupTarget.OpenBrowser(LastBrowsing(connId = 3, containerId = "dir-x")),
            StartupStore.startupTarget(),
        )

        // 设置半换成「首页」：同一份状态必须判成 OpenHome（快照真的读了设置）
        AppSettings.startupPage = StartupPage.HOME
        assertEquals(StartupTarget.OpenHome, StartupStore.startupTarget())

        // 状态半：退出时正在看书（已落盘 true）→ 直接打开那本书并定位到上次页码（故事 47）
        AppSettings.startupPage = StartupPage.LAST_READ
        StartupStore.recordReading(true)
        assertEquals(StartupTarget.OpenReader(LastRead(3, "book-3")), StartupStore.startupTarget())
    }

    /**
     * 票 26 第 8 项回归：@After 必须把静态残留清干净。
     * 直接跑 tearDown 再断言（与本类实际执行的清理是同一份实现），不依赖用例执行顺序。
     */
    @Test
    fun `tearDown 清掉静态残留的 lastRead`() {
        ServiceLocator.lastRead = LastRead(connId = 4, bookId = "book-4")
        assertEquals(LastRead(4, "book-4"), ServiceLocator.lastRead)

        tearDown()

        assertNull(ServiceLocator.lastRead)
        assertNull(StartupStore.lastRead())
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
