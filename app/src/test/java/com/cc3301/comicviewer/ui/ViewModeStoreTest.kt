package com.cc3301.comicviewer.ui

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.cc3301.comicviewer.core.view.ViewMode
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 全局视图档位的落盘（票 #53 AC）：全 app 一份，跨连接、跨重启保持，非法值回落网格 2 列。
 * 本测试把落盘与读回分开调用（Store 不缓存），等价于进程重启后的读取。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ViewModeStoreTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        ServiceLocator.init(context)
        context.getSharedPreferences("settings", Context.MODE_PRIVATE).edit().clear().commit()
    }

    @After
    fun tearDown() {
        context.getSharedPreferences("settings", Context.MODE_PRIVATE).edit().clear().commit()
    }

    @Test
    fun `全新安装没有该设置时默认网格 2 列`() {
        assertEquals(ViewMode.GRID_2, ViewModeStore.setting)
    }

    @Test
    fun `档位跨重启可读 选中哪一档就是哪一档`() {
        listOf(ViewMode.GRID_3, ViewMode.LIST, ViewMode.GRID_4, ViewMode.GRID_2).forEach { mode ->
            ViewModeStore.setting = mode
            // 重新从落盘读（Store 不缓存）＝ 重启后的读取
            assertEquals(mode, ViewModeStore.setting)
        }
    }

    @Test
    fun `落盘键损坏时回落网格 2 列`() {
        context.getSharedPreferences("settings", Context.MODE_PRIVATE).edit()
            .putString("view_mode", "bogus")
            .commit()

        assertEquals(ViewMode.GRID_2, ViewModeStore.setting)
    }

    @Test
    fun `写入递增版本号 供 Compose 建立重组依赖`() {
        val before = ViewModeStore.revision

        ViewModeStore.setting = ViewMode.GRID_4

        assertEquals(before + 1, ViewModeStore.revision)
        assertEquals(ViewMode.GRID_4, ViewModeStore.setting)
    }

    @Test
    fun `切档只改视图档位 不碰排序设置`() {
        val sortRevisionBefore = SortSettingStore.revision

        ViewModeStore.setting = ViewMode.LIST

        // 两者是各自独立的落盘键与版本号（同一份 prefs）：切视图不动排序的落盘与版本号
        assertEquals(ViewMode.LIST, ViewModeStore.setting)
        assertEquals(sortRevisionBefore, SortSettingStore.revision)
    }
}
