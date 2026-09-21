package com.cc3301.comicviewer.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import com.cc3301.comicviewer.core.input.WheelHandler
import com.cc3301.comicviewer.core.input.WheelSurface
import com.cc3301.comicviewer.core.nav.BrowseLocation
import com.cc3301.comicviewer.core.nav.LastRead
import com.cc3301.comicviewer.core.source.BrowseEntry
import com.cc3301.comicviewer.core.source.PerfTiming
import com.cc3301.comicviewer.core.source.ReadingProgress
import com.cc3301.comicviewer.core.source.SortMode
import com.cc3301.comicviewer.core.source.Source
import com.cc3301.comicviewer.core.source.SourceType
import com.cc3301.comicviewer.core.source.progressForEntry
import com.cc3301.comicviewer.core.view.CoverLayout
import com.cc3301.comicviewer.core.view.CoverPrefetch
import com.cc3301.comicviewer.core.view.ViewMode
import com.cc3301.comicviewer.core.view.gridCellMaxHeight
import com.cc3301.comicviewer.core.view.gridCellWidth
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.withContext

/** 列表档的封面列宽（票 #46：宽度保持现值，只有高度随封面比例变化） */
private val LIST_COVER_WIDTH = 56.dp

/** 网格档的排版常量（票 #50：外边距与条目间距 ≤12dp、格子内间距 ≤8dp）；抓取带不侵入这里的右留白由 `QuickScrollBarSizeTest` 钉住 */
internal val GRID_CONTENT_PADDING = 12.dp
private val GRID_HORIZONTAL_SPACING = 6.dp
private val GRID_VERTICAL_SPACING = 8.dp
private val GRID_CELL_SPACING = 6.dp

/**
 * 一次枚举的结果 + 它用的排序类别（票 #58）：换排序类别会重新枚举（异步），屏上会先停一帧旧顺序。
 * 浏览页据此判断「当前展示的是不是当前那档的产物」，复位键在这段窗口里把展示顺序也折进去。
 */
private data class ListedEntries(
    val mode: SortMode,
    val entries: List<BrowseEntry>,
)

/**
 * 浏览页（票 04 + 票 05 进度条；票 #49 起是唯一的条目列表屏；票 #45/#50/#53 加形态与视图档位）：
 * 条目形态随全局视图档位切换——列表档 = 行（封面 + 名称），
 * 网格档 = 格子（统一格子尺寸、封面裁剪填满、名称左对齐），列数为设置值 2/3/4；
 * 已读书目的进度条两档**位置不同**（票 #92 需求 2）：列表档在**名称正下方**（名称那一列内、与名称左缘对齐），
 * 网格档压在封面下缘（不占布局，条下垫黑 45% 暗底）。
 *
 * 两档共用同一套枚举 / 方向 / 名称回填 / 进度映射与点击语义（[ListComposition] 的小件），
 * 只有条目渲染层分叉，因此切档不改变条目顺序、方向与点击行为。
 * 手动刷新是**下拉更新**（[PullToRefreshArea]：只认拖拽、滚轮不触发），顶栏的刷新按钮已被「视图」菜单取代。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BrowserScreen(nav: NavHostController, connId: Long, containerId: String?, onOpenDrawer: () -> Unit) {
    // 重新枚举的计数（票 #30 的刷新通路；票 #53 起由下拉更新触发，顶栏不再有刷新按钮）
    var reloadTick by remember { mutableStateOf(0) }
    // 下拉指示器：本次枚举（成功或失败）结束即复位
    var refreshing by remember { mutableStateOf(false) }

    // 本页来源按自身路由的 connId 解析（票 17 AC2，spec 故事 44）：会话全局来源可能已被别的连接
    // 改写（打开别的书会切会话），跨来源页面若读全局来源，回退回来的浏览页会按别的库渲染。
    // 连接查询、会话级实例复用与「连接被删即退栈」都在 [rememberConnectionSource] 里。
    val connectionSource = rememberConnectionSource(nav, connId, reloadTick)
    val connection = connectionSource.connection
    val source = connectionSource.source
    val sourceError = connectionSource.error
    // 会话槽位里**已解析**的来源（票 #74 / 承办 #73 AC3）：同步可用，不必跟 [connectionSource] 的异步解析一起等；
    // **冷启动（进程重启）**时槽位为空 → 首帧仍可能短暂显示「加载中…」，但内容来自落盘快照、0 次列目录 0 次探测
    val sessionSource = remember(connId, reloadTick) { ServiceLocator.browsingSourceIfResolved(connId) }

    // 鼠标滚轮（票 17，spec 故事 22）：列表滚轮交给 LazyColumn 自身滚动。注册声明界面类型，
    // 同时防止上一个界面的处理器（若未被清理）把列表滚轮误当成翻页。
    val wheelHandler = remember { WheelHandler(WheelSurface.LIST) { false } }
    RegisterSlot(ServiceLocator.wheelSlot, wheelHandler)
    var error by remember { mutableStateOf<String?>(null) }
    // 排序设置（票 #29，spec 故事 10-14）：全 app 一份；进子文件夹也保持同一份
    val setting = rememberSortSetting()
    // 视图档位（票 #53/#45）：全 app 一份（列表 / 网格 2·3·4 列），默认网格 2 列
    val view = rememberViewMode()
    // 上次停留的位置与**本次停留层的整条返回链**（票 20 故事 48 + 票 #70 r2 复审）：只记目录层级，不记排序
    // （排序属全局设置）与滚动位置（SPEC Out of Scope）。位置与路径必须**一次写入**：启动侧按「路径最后一层 =
    // 恢复位置」判是否采用整条路径，分开写就会出现「路径还是上一会话的」那种错配（见 [recordBrowsePosition]）。
    LaunchedEffect(connId, containerId) {
        recordBrowsePosition(ServiceLocator.browseHistory, BrowseLocation(connId, containerId))
    }
    // 列表按本页自己的来源取（source 就绪后自动重跑）。值里带上「这次枚举用的排序类别」（票 #58）
    // 首帧直接落会话内快照（票 #74 / 承办 #73 AC3）：命中即立即出列表，不再先渲染「加载中…」；
    // 快照读取是**不做 IO** 的同步内存读（发布时间排序不读包），因此 [remember] 住排序结果，不做成每帧重排；
    // 冷启动槽位为空时这里为 null：首帧仍是异步路径（要等来源解析完，与 #74 同一句），随后走下面的两段式
    // （第一段落盘快照帧、第二段新枚举结果）
    val preloaded = remember(connId, containerId, setting.mode, reloadTick) {
        sessionSource?.cachedEntries(containerId, setting.mode)
    }
    val listed by produceState<ListedEntries?>(
        preloaded?.let { ListedEntries(setting.mode, it) },
        source,
        containerId,
        setting.mode,
        reloadTick,
    ) {
        val src = source ?: return@produceState
        error = null
        value = try {
            // 两段式（票 #75 AC4）：先落已有快照（会话内存快照 / 落盘快照，0 请求），再落新枚举结果。
            // 值替换即「静默刷新」：两段都带同一个排序类别，因此滚动复位键不变（见 [browseScrollResetKey]），
            // 滚动位置不跳、不闪空白。条目名回填也在小件里（见 [listEntriesTwoPhaseRememberingNames]）。
            val list = listEntriesTwoPhaseRememberingNames(src, containerId, setting.mode) { snapshot ->
                value = ListedEntries(mode = setting.mode, entries = snapshot)
            }
            ListedEntries(mode = setting.mode, entries = list)
        } catch (t: Throwable) {
            error = t.message ?: "加载失败"
            null
        }
        // 枚举结束（成功或失败）都复位下拉指示器：失败时页面上有内联重试，指示器不该一直转
        refreshing = false
    }
    val entries = listed?.entries

    // 方向只在展示层生效（票 #29 裁决 7）：与柜内共用整份翻转那一段
    val shown = rememberShownEntries(entries, setting)

    // 排序落地时两档滚动的复位键（票 #58）：键 =（排序类别 + 该类方向）+「旧序残留」。
    // 点排序那一刻换一次键（同类反向此刻就已同步重排）；换类别后新顺序异步落地那一帧再换一次——
    // 每次重排都在同一次重组里拿到一份全新的滚动状态（索引 0、没有可锚定的 key），
    // 因此不会先按新顺序（旧锚点）布局、再从另一端滑回来。键里不放「条目顺序」本身：进屏落地、
    // 下拉更新重列都没有旧序残留、键不跳，rememberSaveable 的位置恢复因此保住（理由见 [browseScrollResetKey]）。
    val displayedIds = remember(shown) { shown?.map { it.id } }
    val scrollResetKey = browseScrollResetKey(setting, listed?.mode, displayedIds)

    // 进度批量映射（票 05）：bookId → ReadingProgress；与柜内同一份取值通路（[rememberProgressByBook]）
    val progressMap = rememberProgressByBook()

    // 下拉更新（票 #53）：复用既有的显式失效入口——清当前层列表缓存 → 重新枚举 → 可见行重取封面（
    // 见下面的 coverReloadKey）。不是纯动画：能力与原刷新按钮完全一致。
    fun refresh() {
        (source ?: sessionSource)?.invalidateListCache(containerId)
        refreshing = true
        reloadTick++
    }

    // 两档各自的滚动状态：下拉只在"停在顶部"时接管（其余情况整段交回常规滚动）。
    // 用 rememberSaveable（与原来 rememberLazyListState/rememberLazyGridState 同一份 saver 语义）
    // 加一个复位键：排序设置变化（含换类别后新顺序落地那一帧）时换成新状态（回到顶部），
    // 其余一律键不变——旋转、从阅读器返回、进出子目录、下拉更新仍照旧恢复/保持原位。
    val listState = rememberSaveable(scrollResetKey, saver = LazyListState.Saver) { LazyListState() }
    val gridState = rememberSaveable(scrollResetKey, saver = LazyGridState.Saver) { LazyGridState() }
    // 快速定位滑条要读的滚动状态（票 #60）：两档各一条扩展函数构造同一个适配器（滚动状态随复位键重建，适配跟着重建）
    val listQuickScroll = remember(listState) { listState.quickScrollBarState() }
    val gridQuickScroll = remember(gridState) { gridState.quickScrollBarState() }

    // ---------- 打开前置（票 #108 E1-A）：点书不立刻切页 ----------
    // 点击后**留在书柜页**：先在这层把书打开、把首帧解码完（后台跑，不做任何提示条/toast/遮罩），
    // 就绪后**一次性**切到阅读页；前置由 [ServiceLocator.readerPrelude] 交给阅读页（它组合期同步取走，不再重开书）。
    // 连点同一本不重启（键不变）；换点另一本则取消前一次。
    var pendingOpenBookId by remember { mutableStateOf<String?>(null) }
    // 点书只登记「要开哪本」（条目点击路径调用）：切页在前置跑完后的那一个动作里，书柜页在这期间照常可见
    val beginBookOpen: (BrowseEntry) -> Unit = { pendingOpenBookId = it.id }
    // 阅读页目标宽度 px（= 整窗宽度，两屏都铺满整宽）：前置解码宽度与阅读页取的那把解码缓存键必须一致，
    // 否则前置白解一张、进阅读页仍要重解——一致才能换来「切过去首帧就是图」
    val readerWidthPx = LocalView.current.width
    LaunchedEffect(pendingOpenBookId) {
        val bookId = pendingOpenBookId ?: return@LaunchedEffect
        val srcForOpen = source ?: sessionSource
        if (srcForOpen != null) {
            catchingNonCancellation {
                withContext(Dispatchers.IO) {
                    preloadReaderOpening(srcForOpen, bookId, AppSettings.alwaysOpenFirstPage, readerWidthPx) { handle, index, width ->
                        PageDecoder.decodePage(handle, index, width) { PageDecoder.loadPageBytes(handle, index) }
                    }
                }
            }.onSuccess { ServiceLocator.readerPrelude.put(bookId, it) }
        }
        // 前置失败（拿不到页/来源断）照常进阅读页：那里有既有的失败提示与重试，书柜页没有任何提示位
        nav.navigate(Routes.reader(bookId))
        pendingOpenBookId = null
    }

    // ---------- 封面预取（票 #108 E2-B）：可见区 ±1 屏 ----------
    // 与阅读页前置无关的另一半：滚动带来新的可见区间时，把「±1 屏」内的封面**字节**提前向来源要一遍
    // （走的正是可见行自己用的 [Source.coverBytes]，因此命中的就是那次渲染要用的那份）。
    // 单批最多 [CoverPrefetch.MAX_CONCURRENT_LOADS] 张：快速滑动一屏一屏地撞出新窗口，不限并发会把内存/带宽拉爆。
    // 出屏**不**丢缓存：位图在 `PageDecoder` 的 LruCache（按内存上界淘汰），字节在来源的会话缓存
    // （票 #108 起按上界**淘汰最旧**，不再是「越界就整仓清空」），滚回来不再重走整段加载。
    val prefetchSource = source ?: sessionSource
    val prefetchedCovers = remember(connId, containerId, view.isGrid, reloadTick) { mutableSetOf<String>() }
    LaunchedEffect(prefetchSource, shown, view.isGrid, reloadTick) {
        val list = shown ?: return@LaunchedEffect
        val ids = list.map { it.id }
        snapshotFlow { if (view.isGrid) gridState.visibleIndices else listState.visibleIndices }
            .collectLatest { visible ->
                if (visible.isEmpty()) return@collectLatest
                val window = CoverPrefetch.window(visible.first(), visible.last(), ids.size) ?: return@collectLatest
                val targets = window.map { ids[it] }.filter { prefetchedCovers.add(it) }
                targets.chunked(CoverPrefetch.MAX_CONCURRENT_LOADS).forEach { batch ->
                    batch
                        .map { id ->
                            async(Dispatchers.IO) { catchingNonCancellation { prefetchSource?.coverBytes(id) } }
                        }
                        .awaitAll()
                }
            }
    }

    // 系统返回手势 = 浏览历史后退（spec 故事 38）：同步维护历史栈
    BackHandler(enabled = ServiceLocator.browseHistory.canGoBack) {
        // 票 #70 观测点（默认关闭）：回退栈深度 + 栈顶路由 + 历史游标，与 #98/#99 共用同一套打点
        PerfTiming.log { navObservationLine(NavEvent.BROWSE_BACK, nav, ServiceLocator.browseHistory) }
        ServiceLocator.browseHistory.goBack()
        nav.popBackStack()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    // 根层标题 = 连接显示名（票 #49）：书柜点连接与首页点连接落到同一屏、同一标题口径；
                    // 子层仍是条目名（会话内回填）→ id 末段兜底。规则收在 [browserTitle] 里（纯函数，有单测）；
                    // 渲染口径（恒单行 + 末尾省略，票 #79）收在 [TopBarTitle] 里
                    TopBarTitle(
                        browserTitle(
                            containerId = containerId,
                            // containerId 在根列表时为 null：ConcurrentHashMap 不接受 null 键（票 13 review P0）
                            containerName = containerId?.let { ServiceLocator.entryNames[it] },
                            connectionName = connection?.displayName,
                        ),
                    )
                },
                navigationIcon = { DrawerMenuButton(onOpenDrawer) },
                actions = {
                    // 视图菜单（票 #53）：四项 = 列表 / 网格 2·3·4 列，当前档位打勾；取代了原刷新按钮
                    ViewMenuButton(view) { mode -> ViewModeStore.setting = mode }
                    // 排序切换（spec 故事 14 + 票 #29/#80）：6 项 = 类别 × 方向，点一项即同时生效、当前项打勾
                    SortMenuButton(setting) { mode, direction -> SortSettingStore.setting = setting.select(mode, direction) }
                },
            )
        },
    ) { padding ->
        val list = shown
        // 来源还没解析完但会话槽位已有实例时（票 #74）：用那份实例渲染，列表立刻可见
        val src = source ?: sessionSource
        when {
            // 来源未就绪：解析中 → 加载中；解析失败 → 就地重试（票 11 AC4 同款）
            src == null -> Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    if (sourceError == null) {
                        Text("加载中…")
                    } else {
                        Text("加载失败：" + sourceError)
                        TextButton(onClick = { reloadTick++ }) { Text("重试") }
                    }
                }
            }
            error != null -> Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("加载失败：" + error)
                    Text(
                        loadFailureHint(src.type),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    TextButton(onClick = { reloadTick++ }) { Text("重试") }
                }
            }
            list == null -> Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                Text("加载中…")
            }
            list.isEmpty() -> Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                Text("此目录没有内容")
            }
            else -> Box(
                Modifier
                    .fillMaxSize()
                    .padding(padding),
            ) {
                PullToRefreshArea(
                    atTop = { if (view.isGrid) gridState.isAtTop else listState.isAtTop },
                    refreshing = refreshing,
                    onRefresh = ::refresh,
                    modifier = Modifier.fillMaxSize(),
                ) {
                    if (view.isGrid) {
                        BrowserGrid(
                            list = list,
                            columns = view.columns ?: ViewMode.GRID_2.columns!!,
                            state = gridState,
                            progressMap = progressMap,
                            source = src,
                            connId = connId,
                            coverReloadKey = reloadTick,
                            nav = nav,
                            beginBookOpen = beginBookOpen,
                        )
                    } else {
                        LazyColumn(
                            state = listState,
                            // 鼠标左键按住拖动 = 上下滑动（票 #69）：内建 scrollable 拒绝鼠标源拖动，
                            // 这段由 mouseDragScroll 补上；触摸仍走内建滚动
                            modifier = Modifier
                                .fillMaxSize()
                                .mouseDragScroll(listState),
                        ) {
                            items(list, key = { it.id }) { entry ->
                                BrowseRow(
                                    entry = entry,
                                    progress = progressForEntry(entry, progressMap[entry.id]),
                                    source = src,
                                    // 刷新真刷封面（票 #30 F4 / 票 #53 下拉更新）：reloadTick 变→CoverThumb 重取字节
                                    coverReloadKey = reloadTick,
                                    onOpen = { openEntry(nav, connId, src, entry, onOpenBook = beginBookOpen) },
                                )
                            }
                        }
                    }
                }
                // 快速定位滑条（票 #60）：**兄弟层**，盖在 [PullToRefreshArea] 之上。
                // Compose 的命中选择最上层命中的子件，因此按下滑条时事件到不了下拉更新与条目点击——
                // 「拖滑条不触发下拉更新、不打开条目」是结构性保证（见 [QuickScrollBar] 的 KDoc）。
                QuickScrollBar(
                    state = if (view.isGrid) gridQuickScroll else listQuickScroll,
                    modifier = Modifier.align(Alignment.CenterEnd),
                )
            }
        }
    }
}

/** 网格档容器（票 #50）：固定列数来自设置，格子宽度由此算出并传给封面（同一屏所有格子同宽同高） */
@Composable
private fun BrowserGrid(
    list: List<BrowseEntry>,
    columns: Int,
    state: LazyGridState,
    progressMap: Map<String, ReadingProgress>,
    source: Source,
    connId: Long,
    coverReloadKey: Any?,
    nav: NavHostController,
    /** 点开一本书（票 #108 E1-A）：界面层只登记「要开这本」，切页由前置跑完后的那一个动作完成 */
    beginBookOpen: (BrowseEntry) -> Unit,
) {
    BoxWithConstraints {
        // 格子宽度与下面 contentPadding/横向间距用同一份常量（两处各写一份会让封面与格子差几个 dp）
        val cellWidth = with(LocalDensity.current) {
            gridCellWidth(
                availableDp = maxWidth.value,
                columns = columns,
                contentPaddingDp = GRID_CONTENT_PADDING.value,
                spacingDp = GRID_HORIZONTAL_SPACING.value,
            ).dp
        }
        // 网格项高度上限（票 #106）：可视高度取格子真拿到的纵向约束（已扣掉顶栏与系统栏，不自己估摸屏幕
        // 高度），再扣掉上下 contentPadding。名字块与格子内间距**不在这里扣**——格子里的布局会真量名字块
        // 后把剩下的高度留给封面（见 [BrowserGridCell]），因此不靠「行高 × 行数」的字体度量推算。
        // 横屏 2 格时格宽大、格高超过这个上限（票 #106 的 bug），封面据此收窄并居中、两侧留白，名字行恒有位置。
        val cellMaxHeight = with(LocalDensity.current) {
            gridCellMaxHeight(
                visibleHeightDp = maxHeight.value,
                contentPaddingDp = GRID_CONTENT_PADDING.value,
            ).dp
        }
        LazyVerticalGrid(
            columns = GridCells.Fixed(columns),
            state = state,
            // 鼠标左键按住拖动 = 上下滑动（票 #69）：与列表档同一份修饰符（网格档同样被内建 scrollable 拒绝）
            modifier = Modifier
                .fillMaxSize()
                .mouseDragScroll(state),
            contentPadding = PaddingValues(GRID_CONTENT_PADDING),
            horizontalArrangement = Arrangement.spacedBy(GRID_HORIZONTAL_SPACING),
            verticalArrangement = Arrangement.spacedBy(GRID_VERTICAL_SPACING),
        ) {
            items(list, key = { it.id }) { entry ->
                BrowserGridCell(
                    entry = entry,
                    progress = progressForEntry(entry, progressMap[entry.id]),
                    source = source,
                    cellWidth = cellWidth,
                    cellMaxHeight = cellMaxHeight,
                    coverReloadKey = coverReloadKey,
                    onOpen = { openEntry(nav, connId, source, entry, onOpenBook = beginBookOpen) },
                )
            }
        }
    }
}

/** 列表档条目（票 #45）：封面 + 名称（左对齐），已读的书在**名称正下方**有一条进度条（票 #92 需求 2） */
@Composable
private fun BrowseRow(
    entry: BrowseEntry,
    progress: ReadingProgress?,
    source: Source,
    /** 封面字节的重取键（下拉更新 +1）：它一变，可见行重取封面（票 #30 F4） */
    coverReloadKey: Any?,
    onOpen: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onOpen)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        // 名称（+条）作为一个整体垂直居中于封面旁（票 #92 需求 2）：条因此落在封面高度范围内，不把行撑高。
        // 边界（按实现写）：条底边在封面内 ⟺ 名称块高（1 行 24dp / 2 行 48dp + 6dp 间距 + 6dp 条高）≤ 56dp × 封面高宽比
        // 即比例 ≥ 0.643（1 行名）/ ≥ 1.071（2 行名）；典型的竖版封面（比例 ~1.4）成立，
        // 而比例被夹到下限 0.6 的扁封面 + 2 行名时名称块本身就高于封面，条会落到封面下缘之外（既有行为，本票未动）
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        // 缓存键用 entry.id：无 coverUri 的来源（Komga）若用 coverUri 做键，全列表会共用同一张封面
        CoverThumb(
            coverUri = entry.coverUri,
            cacheKey = entry.id,
            loadBytes = { source.coverBytes(entry.id) },
            // 列表档口径不变（票 #46）：封面列宽 56dp、高随封面自身比例、完整显示
            sizing = CoverSizing.OwnAspect(LIST_COVER_WIDTH),
            reloadKey = coverReloadKey,
        )
        // 名称那一列（票 #92 需求 2）：名称与其正下方的进度条同属这一列——条左缘因此与名称左缘对齐
        // （横向不跨到封面那一列，占的是名称列自己的宽度）。
        Column(modifier = Modifier.weight(1f)) {
            // 名称渲染收在一处（票 #47）：两档断行口径因此一致。
            // 行数口径按档位取（票 #94）：列表档 1 行起（行高随名称行数变化是既有行为），
            // 网格档才固定两行——列表没有「同排对齐」诉求。
            // 名称占满名称列（换行宽度 = 文字盒宽）；条与它同列等宽（见下）。票 #92 r13：条不再内缩。
            EntryNameText(
                name = entry.name,
                style = MaterialTheme.typography.bodyLarge,
                minLines = entryNameMinLines(gridMode = false),
                modifier = Modifier.fillMaxWidth(),
            )
            // 进度条（票 #92 需求 2）：名称正下方、本列内；**条左右两端都与名称列对齐**
            // （左缘 = 名称左缘，右端到名称列右缘即行内容右缘，**不做内缩**——票 #92 r13 取消内缩口径）。
            // 未读不画、**不留空位**（列表仍是「有才画」）。top = 6dp 沿用 #92 之前那条的间距口径。
            // 只对书条目显示（门控在 progressForEntry 里，文件夹与系列拿不到进度）
            if (progress != null) {
                EntryProgressBar(
                    progress = progress,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 6.dp),
                )
            }
        }
    }
}

/**
 * 网格档格子（票 #45 形态 + 票 #50 视觉 + 票 #57 统一格子）：封面在**统一格子尺寸**里裁剪填满
 * （格高 = 格宽 × 固定格比例，短边铺满、长边裁掉），任何比例的封面都不改变格高、不留灰边、不出现"半截"；
 * 名称在格内**左对齐**（票 #92 需求 1：与列表档的默认口径一致）；封面与名称之间的间距收紧到 6dp；
 * 已读书目的进度条**压在封面下缘**（票 #92 需求 2：叠在封面上、不占布局），条下垫一层黑 45% 暗底
 * （票 #92 需求 5 方案 A：浅色/白色封面上轨道才看得清；**仅网格档**，列表档不铺）；
 * 名称块**固定两行高**（票 #94：1 行名也占满两行，因此同排格子等高、格底逐行对齐）；
 * 票 #106 起封面还受**格子高度上限**（[cellMaxHeight]）约束：格高放不下时封面等高收缩、宽按格比例反算，
 * 名字行因此恒有位置（横屏 2 格格宽大、格高超过可视高度是本票要修的 bug）。
 */
@Composable
private fun BrowserGridCell(
    entry: BrowseEntry,
    progress: ReadingProgress?,
    source: Source,
    /** 格宽（格高由 [CoverLayout.GRID_CELL_ASPECT] 在封面里算出，不在这里再算一份） */
    cellWidth: Dp,
    /** 格子高度上限（票 #106）：= 可视高度 − 上下留白；封面放不下时按它收窄、名字行因此恒有位置 */
    cellMaxHeight: Dp,
    coverReloadKey: Any?,
    onOpen: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(max = cellMaxHeight)
            .clickable(onClick = onOpen),
        // 封面收缩时水平居中、两侧留白（票 #106 AC2）
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(GRID_CELL_SPACING),
    ) {
        // 封面槽（票 #106）：名字块是**非权重子件**、先被测量，剩下的高度全给封面——名字块高因此取自
        // **真渲染**（与名称的字体/字号/字体缩放天然一致），不做「行高 × 行数」的字体度量推算。
        // 上面的 heightIn 上限保证「封面 + 间距 + 名字块」始终装得下，名字行因此不需要滚动就能看见。
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f, fill = false),
            // 封面收缩时在格子槽内水平居中、两侧留白（票 #106 AC2）
            contentAlignment = Alignment.TopCenter,
        ) {
            // 封面盒（票 #106）：高取「格高」与剩余高度中的较小者，宽按格比例反算（等比、不拉伸）；
            // 未触发收缩时宽 = 格宽、高 = 格高（竖屏与 3/4 格因此与改动前逐像素相同）。
            // 剩余高度 = 格子高度上限里，名字块与格子内间距已被权重分配让出的那部分；
            // 与 CoverThumb 的 GridCell 口径共用同一个纯函数——压条那一层（暗底 + 条）的宽度因此恒等于封面宽
            val coverAvailableHeight = maxHeight
            val coverSize = CoverLayout.gridCellSize(cellWidth.value, coverAvailableHeight.value)
            // 封面 + 压在封面下缘的进度条（票 #92 需求 2）：条叠在封面上、不占布局，没读过的格子不留空白，
            // 读过的格子也不会比它高。盒子宽度取**封面宽**（票 #106 起收缩时窄于格宽）：条 fillMaxWidth
            // 因此恰好等于封面宽，收缩后仍与封面同宽
            Box(Modifier.width(coverSize.width.dp)) {
                CoverThumb(
                    coverUri = entry.coverUri,
                    cacheKey = entry.id,
                    loadBytes = { source.coverBytes(entry.id) },
                    sizing = CoverSizing.GridCell(cellWidth, coverAvailableHeight),
                    reloadKey = coverReloadKey,
                )
                // 进度条只对书条目显示（门控在 progressForEntry 里，文件夹与系列拿不到进度，什么都不画）
                if (progress != null) {
                    // 先铺暗底、再画条（票 #92 需求 5 方案 A）：条压在浅色/白色封面上时轨道看不清；
                    // 暗底只网格档铺（列表档按维护者口径不动），未读时两者都不画、不留暗带。
                    // 两者同用 BottomCenter → 同宽（= 封面宽）同高（= 6dp）同一条带，条在暗底上面
                    GridProgressScrim(Modifier.align(Alignment.BottomCenter))
                    EntryProgressBar(progress, Modifier.align(Alignment.BottomCenter))
                }
            }
        }
        // 名称在格内左对齐（票 #92 需求 1：textAlign 取 [EntryNameText] 的默认值，与列表档同一口径）；
        // 断行口径与列表档共用（票 #47）；名称块固定两行高（票 #94）：1 行名也占两行，
        // 因此同排格子的高度只由「封面高 + 间距 + 两行名」决定，与名称行数无关。
        EntryNameText(
            name = entry.name,
            style = MaterialTheme.typography.labelLarge,
            minLines = entryNameMinLines(gridMode = true),
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

/** 条目点击（票 #45 两档一致）：书→阅读器（并对齐会话来源与上次阅读），容器→下钻并入浏览历史 */
private fun openEntry(
    nav: NavHostController,
    connId: Long,
    source: Source,
    entry: BrowseEntry,
    /** 书的打开（票 #108 E1-A）：调用方在这之后才切页（先把书打开、首帧解好） */
    onOpenBook: (BrowseEntry) -> Unit,
) {
    when {
        entry.isBook -> {
            // 阅读器路由只认会话来源（AppNav）：跨来源后（打开过别的库的书）会话可能指向别的连接，
            // 此处必须对齐到本页的 connId，否则会用别的库的来源开本库的书 id、进度也写错库。
            // 只在点击路径写全局：组合期写会把回退栈下层带偏（r1 P1）
            if (ServiceLocator.currentConnId != connId) {
                ServiceLocator.currentSource = source
                ServiceLocator.currentConnId = connId
            }
            // 抽屉「阅读器」入口打开该书（票 09）：带来源连接，跨连接时不误开
            ServiceLocator.lastRead = LastRead(connId, entry.id)
            // 票 #108 E1-A：不在这里导航——界面先跑打开前置，就绪后由它一次性切页
            onOpenBook(entry)
        }
        else -> {
            // 子目录入浏览历史（spec 故事 37）
            ServiceLocator.browseHistory.record(BrowseLocation(connId, entry.id))
            nav.navigate(Routes.browser(connId, entry.id))
        }
    }
}

/**
 * 两档共用的「停在顶部」判定（票 #53）：下拉更新只在顶部接管，其余情况交回常规滚动。
 *
 * 列表与网格的滚动状态是两个不同类型（`LazyListState` / `LazyGridState`），各自暴露同一对属性，
 * 因此这里写两份逐字相同的实现——不为此引入接口包装（取值语义就一行，包装反而要多一层转发）。
 */
private val LazyListState.isAtTop: Boolean
    get() = firstVisibleItemIndex == 0 && firstVisibleItemScrollOffset == 0

private val LazyGridState.isAtTop: Boolean
    get() = firstVisibleItemIndex == 0 && firstVisibleItemScrollOffset == 0

/**
 * 可见条目索引（票 #108 E2-B 预取窗口的输入）：两档的滚动状态是两个类型（`LazyListState` / `LazyGridState`），
 * 但「可见区」这一件事两边同义，因此各写一份逐字相同的取值（与上面 [isAtTop] 同一套理由：取值就一行，
 * 不为它引接口包装）。
 */
private val LazyListState.visibleIndices: List<Int>
    get() = layoutInfo.visibleItemsInfo.map { it.index }

private val LazyGridState.visibleIndices: List<Int>
    get() = layoutInfo.visibleItemsInfo.map { it.index }

/**
 * 列表失败提示（票 11；票 31 柜页同款）：本地是授权失效，网络来源是连接/认证问题，措辞不能混用。
 *
 * 本地那条（票 #40）必须给出**可执行的动作**：界面上的修法是「删掉这条连接再重新授权文件夹」
 * （删除入口在首页「本地」的根列表），只说「请重新添加」时用户找不到入口。
 */
internal fun loadFailureHint(type: SourceType): String = when (type) {
    SourceType.LOCAL -> "授权可能已失效，请在首页「本地」删除这条连接后重新添加文件夹"
    else -> "检查网络或服务器后重试"
}
