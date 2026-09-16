package com.cc3301.comicviewer.ui

import android.content.Context
import com.cc3301.comicviewer.core.reader.PageDirection
import com.cc3301.comicviewer.core.reader.ReadingMode

/**
 * 应用设置（票 05：SharedPreferences 最小实现；票 07 加阅读模式/单页方向；票 20 设置收口时统一演进）。
 */
object AppSettings {

    private val prefs: android.content.SharedPreferences by lazy {
        ServiceLocator.context.getSharedPreferences("settings", Context.MODE_PRIVATE)
    }

    /** 「始终从第一页打开」：打开定位第 1 页并立即覆盖进度 */
    var alwaysOpenFirstPage: Boolean
        get() = prefs.getBoolean(KEY_ALWAYS_FIRST_PAGE, false)
        set(value) = prefs.edit().putBoolean(KEY_ALWAYS_FIRST_PAGE, value).apply()

    /** 全局阅读模式：条漫 / 单页（只在此处切换，阅读菜单无入口） */
    var readingMode: ReadingMode
        get() = ReadingMode.fromKey(prefs.getString(KEY_READING_MODE, null))
        set(value) = prefs.edit().putString(KEY_READING_MODE, value.key).apply()

    /** 单页模式横向方向：左→右 / 右→左（仅单页模式生效） */
    var pageDirection: PageDirection
        get() = PageDirection.fromKey(prefs.getString(KEY_PAGE_DIRECTION, null))
        set(value) = prefs.edit().putString(KEY_PAGE_DIRECTION, value.key).apply()

    private const val KEY_ALWAYS_FIRST_PAGE = "always_open_first_page"
    private const val KEY_READING_MODE = "reading_mode"
    private const val KEY_PAGE_DIRECTION = "page_direction"
}
