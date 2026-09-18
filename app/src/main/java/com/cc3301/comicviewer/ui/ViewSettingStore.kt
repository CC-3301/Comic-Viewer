package com.cc3301.comicviewer.ui

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.cc3301.comicviewer.core.view.ViewMode

/**
 * 全局视图档位的落盘与可观察入口（票 #53，与 [SortSettingStore] 同一种设置外形）。
 *
 * 全 app 只有这一份：浏览页的条目形态与网格列数都读它，跨目录层级、跨连接、重启后都保持。
 * 落 SharedPreferences 且每次现读不缓存（与 [AppSettings]/[SortSettingStore] 同一手法）。
 */
object ViewSettingStore {

    private val prefs: android.content.SharedPreferences
        get() = ServiceLocator.context.getSharedPreferences(AppSettings.PREFS_NAME, Context.MODE_PRIVATE)

    /**
     * 设置版本号：任一写入 +1。组合期读它即可建立重组依赖（与 [SortSettingStore.revision] 同款），
     * 因此「在一屏切换」不需要额外的跨屏通知机制。
     */
    var revision by mutableStateOf(0)
        private set

    /** 视图档位：每次现读（跨重启读到的就是退出时那一档），写入即落盘并递增 [revision] */
    var setting: ViewMode
        get() = ViewMode.fromKey(prefs.getString(KEY_VIEW_MODE, null))
        set(value) {
            prefs.edit().putString(KEY_VIEW_MODE, value.name).apply()
            revision++
        }

    private const val KEY_VIEW_MODE = "view_mode"
}
