package com.cc3301.comicviewer.core.reader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** 旋转/主题/音量键纯函数（票 20） */
class DisplayModesTest {

    @Test
    fun `旋转默认跟随系统 未知键回退`() {
        assertEquals(OrientationMode.FOLLOW_SYSTEM, OrientationMode.fromKey(null))
        assertEquals(OrientationMode.FOLLOW_SYSTEM, OrientationMode.fromKey("nonsense"))
        assertEquals(OrientationMode.PORTRAIT, OrientationMode.fromKey(OrientationMode.PORTRAIT.key))
        assertEquals(OrientationMode.LANDSCAPE, OrientationMode.fromKey(OrientationMode.LANDSCAPE.key))
    }

    @Test
    fun `主题默认跟随系统 未知键回退`() {
        assertEquals(ThemeMode.FOLLOW_SYSTEM, ThemeMode.fromKey(null))
        assertEquals(ThemeMode.FOLLOW_SYSTEM, ThemeMode.fromKey("dark-mode"))
        assertEquals(ThemeMode.DARK, ThemeMode.fromKey(ThemeMode.DARK.key))
        assertEquals(ThemeMode.LIGHT, ThemeMode.fromKey(ThemeMode.LIGHT.key))
    }

    @Test
    fun `音量下向后 音量上向前`() {
        assertEquals(VolumeAction.NEXT, volumeKeyAction(KEYCODE_VOLUME_DOWN, enabled = true))
        assertEquals(VolumeAction.PREV, volumeKeyAction(KEYCODE_VOLUME_UP, enabled = true))
    }

    @Test
    fun `关闭开关后音量键不再翻页`() {
        assertNull(volumeKeyAction(KEYCODE_VOLUME_DOWN, enabled = false))
        assertNull(volumeKeyAction(KEYCODE_VOLUME_UP, enabled = false))
    }

    @Test
    fun `其他按键不处理`() {
        assertNull(volumeKeyAction(4, enabled = true))     // KEYCODE_BACK
        assertNull(volumeKeyAction(0, enabled = true))
    }

    @Test
    fun `手动主题优先于系统 跟随系统才看系统`() {
        // spec 故事 51：深色/浅色手动覆盖优先级高于系统
        assertEquals(true, isDarkTheme(ThemeMode.DARK, systemDark = false))
        assertEquals(false, isDarkTheme(ThemeMode.LIGHT, systemDark = true))
        assertEquals(true, isDarkTheme(ThemeMode.FOLLOW_SYSTEM, systemDark = true))
        assertEquals(false, isDarkTheme(ThemeMode.FOLLOW_SYSTEM, systemDark = false))
    }
}
