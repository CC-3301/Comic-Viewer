package com.cc3301.comicviewer.ui

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.cc3301.comicviewer.core.sort.SortDirection
import com.cc3301.comicviewer.core.sort.SortSetting
import com.cc3301.comicviewer.core.source.SortMode

/**
 * 全局排序设置的落盘与可观察入口（spec「排序设置」）。
 *
 * 全 app 只有这一份（排序方式 + 三个类别各自的方向）：浏览列表与书柜柜内读写的都是它，
 * 任一处切换即全局生效，跨目录层级、跨连接、重启后都保持（进子文件夹不再退回名称排序）。
 * 与「上次停留的位置」（[StartupStore]）互不相干——柜页不是浏览位置，柜内切排序不改写它。
 * 落 SharedPreferences 且每次现读不缓存：与 [AppSettings] 同一手法。
 */
object SortSettingStore {

    private val prefs: android.content.SharedPreferences
        get() = ServiceLocator.context.getSharedPreferences(AppSettings.PREFS_NAME, Context.MODE_PRIVATE)

    /**
     * 设置版本号：任一写入 +1；**长按排序按钮的跳顶请求**（[requestScrollReset]）也只动它。
     * 浏览列表与柜页是两条路由、各自读一次同一份设置，
     * Compose 侧读它即可建立重组依赖，一处切换另一处立即跟随（与 [AppSettings.revision] 同款）。
     */
    var revision by mutableStateOf(0)
        private set

    /**
     * 请求浏览页两档滚动回顶部（长按顶栏「排序」按钮）。
     *
     * 走的是**复位键换代次**那条路（复位键含 [revision]，见 `browseScrollResetKey`），**不是**裸 `scrollToItem(0)`：
     * 换代次 ⇒ `rememberSaveable` 按新键重建两档滚动状态（回顶部），且 `BrowseScrollIndexStore` 里这一层的
     * 旧代次位置记录同时作废。裸跳顶只挪视口、不发新代次，那条记录还是跳顶**前**的位置，
     * 「长按跳顶 → 进子目录 → 返回」就会回到跳顶前的位置（观感矛盾）。
     *
     * **不改设置本身**：排序方式与三个类别各自的方向都逐字不变，跳顶不是一次排序切换。
     */
    fun requestScrollReset() {
        revision++
    }

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
