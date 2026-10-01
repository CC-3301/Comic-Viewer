package com.cc3301.comicviewer.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.remember
import com.cc3301.comicviewer.core.data.progressByBook
import com.cc3301.comicviewer.core.sort.SortSetting
import com.cc3301.comicviewer.core.sort.applySortDirection
import com.cc3301.comicviewer.core.source.BrowseEntry
import com.cc3301.comicviewer.core.source.ReadingProgress
import com.cc3301.comicviewer.core.source.SortMode
import com.cc3301.comicviewer.core.source.Source
import com.cc3301.comicviewer.core.view.ViewMode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

/**
 * 浏览列表与书柜柜内共用的组合期小件（票 41 收口）：全局排序设置的读法、条目枚举后的名字回填、
 * 方向在展示层的施加、进度表批量映射。这四段原先两屏各写一份（连注释都各一份），收在这里只留
 * 一处实现——改排序/条目/进度的口径时不会再漏掉一边。
 *
 * 两屏真正不同的部分留在各自页面里：加载/失败/空态块、以及一个用列表行、另一个用网格格子
 * （票 41 已声明为 Out of Scope）。
 */

/**
 * 当前全局排序设置（票 #29 裁决 1/4）：全 app 一份（排序方式 + 三个类别各自的方向），
 * 浏览列表与书柜柜内读的是它——跨目录层级、跨连接、重启都保持。
 *
 * 读一次 [SortSettingStore.revision] 建立重组依赖：任一处切换后另一处立即跟随
 * （两条路由各读一次同一份设置，靠版本号对齐）。
 */
@Composable
internal fun rememberSortSetting(): SortSetting {
    val revision = SortSettingStore.revision
    return remember(revision) { SortSettingStore.setting }
}

/**
 * 当前全局视图档位（票 #53 裁决，与 [rememberSortSetting] 对称）：全 app 一份（列表 / 网格 2·3·4 列），
 * 组成期读一次 [ViewModeStore.revision] 建立重组依赖——任一入口切换后其它页面立即跟随。
 */
@Composable
internal fun rememberViewMode(): ViewMode {
    val revision = ViewModeStore.revision
    return remember(revision) { ViewModeStore.setting }
}

/**
 * 列条目 + 回填条目名（票 13）：Komga 的系列/书 id 只有 UUID，标题只能靠列表见过一次，
 * 因此每次枚举都要把名字记进会话缓存（[ServiceLocator.entryNames]），供浏览页标题与阅读菜单标题用。
 *
 * 调用方自带 try/catch 与加载态：两屏的加载/失败块不属票 41 范围，各自的错误分支保持原样。
 *
 * 线程语义（票 41 评审）：两屏都在 `withContext(Dispatchers.IO)` 里调用本函数，因此**回填写点落在 IO 线程**
 * （改动前 `.also { … }` 在 `withContext` 之外，写点在主线程）。`entryNames` 是 `ConcurrentHashMap`，
 * 且写仍早于 `produceState` 的 `value = …`，观感无差异 —— 在此写明，避免日后被当成隐式线程假设。
 */
internal suspend fun listEntriesRememberingNames(
    source: Source,
    containerId: String?,
    sort: SortMode,
): List<BrowseEntry> = rememberEntryNames(source.listEntries(containerId, sort))

/** 条目名回填（票 13 的单一处实现）：列表见过一次就把名字记下，浏览页标题与阅读器标题靠它 */
internal fun rememberEntryNames(loaded: List<BrowseEntry>): List<BrowseEntry> = loaded.also { list ->
    list.forEach { ServiceLocator.entryNames[it.id] = it.name }
}

/**
 * 浏览页的两段式读取（票 #75 AC4）：**先**把已有快照交出去（[Source.snapshotEntries]：会话内存快照，
 * 没有就读落盘快照），**再**交新枚举结果（[listEntriesRememberingNames]）并把它作为返回值交给调用方。
 *
 * 为什么需要它：跨重启且目录 mtime 已变时，旧写法要等 1 次列目录 + 增量重探跑完才有一帧内容，
 * 界面上就是「加载中…」；现在落盘快照先上一帧、重列结果原地替换它。
 * 会话内进入时首帧已经由 `cachedEntries`（`BrowserScreen` 的 `preloaded`）落过同一份内容，
 * 两段口径相同 ⇒ 值相等 ⇒ `produceState` 不触发重组，因此这里不必特判。
 *
 * 线程语义：IO 调度在本函数内部（两段各自 `withContext(Dispatchers.IO)`），因此调用方在组合期直接调，
 * `onSnapshot` 回到**调用方的调度器**上执行（界面写入因此落在主线程）；两段的名字都回填会话缓存。
 * 顺序由本函数负责（先快照后新鲜），有单测钉住——界面侧只负责把两段分别落进 `value`。
 */
internal suspend fun listEntriesTwoPhaseRememberingNames(
    source: Source,
    containerId: String?,
    sort: SortMode,
    onSnapshot: (List<BrowseEntry>) -> Unit,
): List<BrowseEntry> {
    withContext(Dispatchers.IO) { source.snapshotEntries(containerId, sort) }
        ?.let { onSnapshot(rememberEntryNames(it)) }
    return withContext(Dispatchers.IO) { listEntriesRememberingNames(source, containerId, sort) }
}

/**
 * 方向只在展示层生效（票 #29 裁决 7）：来源接口只收排序方式、返回的恒是正向序，界面拿到后整份翻转。
 * 方向不进 `Source.listEntries` 契约，因此四个来源都只有一套排序语义。
 */
@Composable
internal fun <T> rememberShownEntries(entries: List<T>?, setting: SortSetting): List<T>? {
    val direction = setting.directionOf()
    return remember(entries, direction) { entries?.applySortDirection(direction) }
}

/**
 * 进度批量映射（票 05）：bookId → 阅读进度，浏览列表与柜内共用同一份取值通路；
 * Room Flow 跨重启存活，映射（[progressByBook]）下沉后台，列表条目的进度因此与阅读进度实时一致。
 */
@Composable
internal fun rememberProgressByBook(): Map<String, ReadingProgress> =
    remember {
        ServiceLocator.db.readingProgressDao().readAll()
            .map { list -> progressByBook(list) }
            .flowOn(Dispatchers.Default)
    }.collectAsState(initial = emptyMap()).value
