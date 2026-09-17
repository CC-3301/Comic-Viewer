package com.cc3301.comicviewer.ui

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.cc3301.comicviewer.core.reader.DEFAULT_DOUBLE_TAP_SCALE
import com.cc3301.comicviewer.core.reader.OrientationMode
import com.cc3301.comicviewer.core.reader.PageDirection
import com.cc3301.comicviewer.core.reader.ReadingMode
import com.cc3301.comicviewer.core.reader.ThemeMode
import com.cc3301.comicviewer.core.reader.clampDoubleTapScale

/**
 * 应用设置（票 05：SharedPreferences 最小实现；票 07 加阅读模式/单页方向；票 20 设置收口时统一演进）。
 */
object AppSettings {

    /**
     * 每次访问现取：测试/进程重建后不持有陈旧 Context（懒持有会让 AppSettings 绑定首个 Activity 的上下文）。
     */
    private val prefs: android.content.SharedPreferences
        get() = ServiceLocator.context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    /** 「始终从第一页打开」：打开定位第 1 页并立即覆盖进度 */
    var alwaysOpenFirstPage: Boolean
        get() = prefs.getBoolean(KEY_ALWAYS_FIRST_PAGE, false)
        set(value) {
            prefs.edit().putBoolean(KEY_ALWAYS_FIRST_PAGE, value).apply()
            notifyChanged()
        }

    /** 全局阅读模式：条漫 / 单页（只在此处切换，阅读菜单无入口） */
    var readingMode: ReadingMode
        get() = ReadingMode.fromKey(prefs.getString(KEY_READING_MODE, null))
        set(value) {
            prefs.edit().putString(KEY_READING_MODE, value.key).apply()
            notifyChanged()
        }

    /** 单页模式横向方向：左→右 / 右→左（仅单页模式生效） */
    var pageDirection: PageDirection
        get() = PageDirection.fromKey(prefs.getString(KEY_PAGE_DIRECTION, null))
        set(value) {
            prefs.edit().putString(KEY_PAGE_DIRECTION, value.key).apply()
            notifyChanged()
        }

    /** 双击放大倍率（spec 故事 31：默认 2.0x，可设 1.5x–4.0x） */
    var doubleTapScale: Float
        get() = clampDoubleTapScale(prefs.getFloat(KEY_DOUBLE_TAP_SCALE, DEFAULT_DOUBLE_TAP_SCALE))
        set(value) {
            prefs.edit().putFloat(KEY_DOUBLE_TAP_SCALE, clampDoubleTapScale(value)).apply()
            notifyChanged()
        }

    /** 页面旋转（spec 故事 50）：竖屏 / 横屏 / 跟随系统（默认） */
    var orientation: OrientationMode
        get() = OrientationMode.fromKey(prefs.getString(KEY_ORIENTATION, null))
        set(value) {
            prefs.edit().putString(KEY_ORIENTATION, value.key).apply()
            notifyChanged()
        }

    /** 主题（spec 故事 51）：跟随系统（默认）/ 深色 / 浅色 */
    var themeMode: ThemeMode
        get() = ThemeMode.fromKey(prefs.getString(KEY_THEME_MODE, null))
        set(value) {
            prefs.edit().putString(KEY_THEME_MODE, value.key).apply()
            notifyChanged()
        }

    /** 音量键翻页（spec 故事 39）：默认开、设置可关 */
    var volumeKeysEnabled: Boolean
        get() = prefs.getBoolean(KEY_VOLUME_KEYS, true)
        set(value) {
            prefs.edit().putBoolean(KEY_VOLUME_KEYS, value).apply()
            notifyChanged()
        }

    /**
     * 设置版本号：Compose 侧读取它即可在任一设置变化后重组
     * （主题、旋转等全局外观需要跨屏生效，而 AppSettings 本身不是可观察状态）。
     */
    var revision by mutableStateOf(0)
        private set

    /** 设置项写入后调用（各 setter 均已自动调用），版本号 +1 触发全局重组 */
    fun notifyChanged() {
        revision++
    }

    private const val KEY_ALWAYS_FIRST_PAGE = "always_open_first_page"
    private const val KEY_READING_MODE = "reading_mode"
    private const val KEY_PAGE_DIRECTION = "page_direction"
    private const val KEY_DOUBLE_TAP_SCALE = "double_tap_scale"
    private const val KEY_ORIENTATION = "orientation"
    private const val KEY_THEME_MODE = "theme_mode"
    private const val KEY_VOLUME_KEYS = "volume_keys_enabled"
}
