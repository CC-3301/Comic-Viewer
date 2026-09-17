package com.cc3301.comicviewer.ui

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.cc3301.comicviewer.core.nav.StartupPage
import com.cc3301.comicviewer.core.reader.OrientationMode
import com.cc3301.comicviewer.core.reader.ThemeMode
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * AppSettings 默认值与落盘（票 20）：spec 故事 39「音量键默认开」、50/51「默认跟随系统」，
 * 以及 revision 契约（任一设置写入都会 +1，供 MainActivity 建立重组依赖）。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AppSettingsTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        ServiceLocator.init(context)
        // 每个用例从干净设置开始（AppSettings 每次访问现取同一份 prefs）
        context.getSharedPreferences("settings", Context.MODE_PRIVATE).edit().clear().commit()
    }

    @After
    fun tearDown() {
        context.getSharedPreferences("settings", Context.MODE_PRIVATE).edit().clear().commit()
    }

    @Test
    fun `默认值 音量键默认开 旋转主题默认跟随系统`() {
        assertTrue(AppSettings.volumeKeysEnabled)
        assertEquals(OrientationMode.FOLLOW_SYSTEM, AppSettings.orientation)
        assertEquals(ThemeMode.FOLLOW_SYSTEM, AppSettings.themeMode)
    }

    @Test
    fun `票 20 三项设置可写可读 每次写入都递增版本号`() {
        val before = AppSettings.revision

        AppSettings.orientation = OrientationMode.LANDSCAPE
        AppSettings.themeMode = ThemeMode.DARK
        AppSettings.volumeKeysEnabled = false

        assertEquals(OrientationMode.LANDSCAPE, AppSettings.orientation)
        assertEquals(ThemeMode.DARK, AppSettings.themeMode)
        assertFalse(AppSettings.volumeKeysEnabled)
        assertEquals(before + 3, AppSettings.revision)
    }

    @Test
    fun `启动页面默认上次阅读的位置 可写可读并递增版本号`() {
        assertEquals(StartupPage.LAST_READ, AppSettings.startupPage)

        val before = AppSettings.revision
        AppSettings.startupPage = StartupPage.BOOKSHELF

        assertEquals(StartupPage.BOOKSHELF, AppSettings.startupPage)
        assertEquals(before + 1, AppSettings.revision)
    }

    @Test
    fun `既有设置同样递增版本号 revision 契约无例外`() {
        val before = AppSettings.revision

        AppSettings.alwaysOpenFirstPage = true
        AppSettings.readingMode = com.cc3301.comicviewer.core.reader.ReadingMode.PAGED
        AppSettings.pageDirection = com.cc3301.comicviewer.core.reader.PageDirection.RTL
        AppSettings.doubleTapScale = 3.0f

        assertEquals(before + 4, AppSettings.revision)
        assertTrue(AppSettings.alwaysOpenFirstPage)
    }
}
