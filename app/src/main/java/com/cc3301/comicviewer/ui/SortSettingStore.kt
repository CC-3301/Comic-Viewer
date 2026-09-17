package com.cc3301.comicviewer.ui

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.cc3301.comicviewer.core.sort.SortDirection
import com.cc3301.comicviewer.core.sort.SortSetting
import com.cc3301.comicviewer.core.source.SortMode

/**
 * 全局排序设置的落盘与可观察入口（票 #29，spec「排序设置」）。
 *
 * 全 app 只有这一份（排序方式 + 三个类别各自的方向）：浏览列表与书柜柜内读写的都是它，
 * 任一处切换即全局生效，跨目录层级、跨连接、重启后都保持（进子文件夹不再退回名称排序）。
 * 与「上次停留的位置」（[StartupStore]）互不相干——柜页不是浏览位置，柜内切排序不改写它（票 #29 评论）。
 * 落 SharedPreferences 且每次现读不缓存：与 [AppSettings] 同一手法。
 */
object SortSettingStore {

    private val prefs: android.content.SharedPreferences
        get() = ServiceLocator.context.getSharedPreferences(AppSettings.PREFS_NAME, Context.MODE_PRIVATE)

    /**
     * 设置版本号：任一写入 +1。浏览列表与柜页是两条路由、各自读一次同一份设置，
     * Compose 侧读它即可建立重组依赖，一处切换另一处立即跟随（与 [AppSettings.revision] 同款）。
     */
    var revision by mutableStateOf(0)
        private set

    /** 全局排序设置：每次现读（跨重启读到的就是退出时那一份），写入即落盘并递增 [revision] */
    var setting: SortSetting
        get() = SortSetting(
            mode = SortMode.entries.firstOrNull { it.name == prefs.getString(KEY_SORT_MODE, null) }
                ?: SortMode.NAME,
            nameDirection = SortDirection.fromKey(prefs.getString(KEY_DIRECTION_NAME, null)),
            modifiedDirection = SortDirection.fromKey(prefs.getString(KEY_DIRECTION_MODIFIED, null)),
            releaseDirection = SortDirection.fromKey(prefs.getString(KEY_DIRECTION_RELEASE, null)),
        )
        set(value) {
            prefs.edit()
                .putString(KEY_SORT_MODE, value.mode.name)
                .putString(KEY_DIRECTION_NAME, value.nameDirection.name)
                .putString(KEY_DIRECTION_MODIFIED, value.modifiedDirection.name)
                .putString(KEY_DIRECTION_RELEASE, value.releaseDirection.name)
                .apply()
            revision++
        }

    private const val KEY_SORT_MODE = "sort_mode"
    private const val KEY_DIRECTION_NAME = "sort_direction_name"
    private const val KEY_DIRECTION_MODIFIED = "sort_direction_modified"
    private const val KEY_DIRECTION_RELEASE = "sort_direction_release"
}
