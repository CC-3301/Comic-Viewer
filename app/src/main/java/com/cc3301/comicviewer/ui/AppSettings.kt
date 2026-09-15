package com.cc3301.comicviewer.ui

import android.content.Context

/**
 * 应用设置（票 05：SharedPreferences 最小实现；票 20 设置收口时统一演进）。
 */
object AppSettings {

    private val prefs: android.content.SharedPreferences by lazy {
        ServiceLocator.context.getSharedPreferences("settings", Context.MODE_PRIVATE)
    }

    /** 「始终从第一页打开」：打开定位第 1 页并立即覆盖进度 */
    var alwaysOpenFirstPage: Boolean
        get() = prefs.getBoolean(KEY_ALWAYS_FIRST_PAGE, false)
        set(value) = prefs.edit().putBoolean(KEY_ALWAYS_FIRST_PAGE, value).apply()

    private const val KEY_ALWAYS_FIRST_PAGE = "always_open_first_page"
}
