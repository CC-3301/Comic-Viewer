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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.rememberLazyListState
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import com.cc3301.comicviewer.core.input.WheelHandler
import com.cc3301.comicviewer.core.input.WheelSurface
import com.cc3301.comicviewer.core.nav.BrowseLocation
import com.cc3301.comicviewer.core.nav.LastBrowsing
import com.cc3301.comicviewer.core.nav.LastRead
import com.cc3301.comicviewer.core.source.BrowseEntry
import com.cc3301.comicviewer.core.source.ReadingProgress
import com.cc3301.comicviewer.core.source.Source
import com.cc3301.comicviewer.core.source.SourceType
import com.cc3301.comicviewer.core.source.progressForEntry
import com.cc3301.comicviewer.core.view.ViewMode
import com.cc3301.comicviewer.core.view.gridCellWidth
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** 列表档的封面列宽（票 #46：宽度保持现值，只有高度随封面比例变化） */
private val LIST_COVER_WIDTH = 56.dp

/** 网格档的排版常量（票 #50：外边距与条目间距 ≤12dp、格子内间距 ≤8dp） */
private val GRID_CONTENT_PADDING = 12.dp
private val GRID_HORIZONTAL_SPACING = 6.dp
private val GRID_VERTICAL_SPACING = 8.dp
private val GRID_CELL_SPACING = 6.dp

/**
 * 浏览页（票 04 + 票 05 进度条；票 #49 起是唯一的条目列表屏；票 #45/#50/#53 加形态与视图档位）：
 * 条目形态随全局视图档位切换——列表档 = 行（封面 + 名称两行 + 进度条），
 * 网格档 = 格子（封面占满格宽、名称居中 + 进度条），列数为设置值 2/3/4。
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

    // 鼠标滚轮（票 17，spec 故事 22）：列表滚轮交给 LazyColumn 自身滚动。注册声明界面类型，
    // 同时防止上一个界面的处理器（若未被清理）把列表滚轮误当成翻页。
    val wheelHandler = remember { WheelHandler(WheelSurface.LIST) { false } }
    RegisterSlot(ServiceLocator.wheelSlot, wheelHandler)
    var error by remember { mutableStateOf<String?>(null) }
    // 排序设置（票 #29，spec 故事 10-14）：全 app 一份；进子文件夹也保持同一份
    val setting = rememberSortSetting()
    // 视图档位（票 #53/#45）：全 app 一份（列表 / 网格 2·3·4 列），默认网格 2 列
    val view = rememberViewSetting()
    // 上次停留的位置（票 20，故事 48）：只记目录层级，不记排序（排序属全局设置）与滚动位置（SPEC Out of Scope）
    LaunchedEffect(connId, containerId) {
        StartupStore.recordBrowsing(LastBrowsing(connId, containerId))
    }
    // 列表按本页自己的来源取（source 就绪后自动重跑）
    val entries by produceState<List<BrowseEntry>?>(null, source, containerId, setting.mode, reloadTick) {
        val src = source ?: return@produceState
        error = null
        value = try {
            // 列条目同时回填条目名（票 13）：与柜内共用这一处（见 [listEntriesRememberingNames]）
            withContext(Dispatchers.IO) { listEntriesRememberingNames(src, containerId, setting.mode) }
        } catch (t: Throwable) {
            error = t.message ?: "加载失败"
            null
        }
        // 枚举结束（成功或失败）都复位下拉指示器：失败时页面上有内联重试，指示器不该一直转
        refreshing = false
    }

    // 方向只在展示层生效（票 #29 裁决 7）：与柜内共用整份翻转那一段
    val shown = rememberShownEntries(entries, setting)

    // 进度批量映射（票 05）：bookId → ReadingProgress；与柜内同一份取值通路（[rememberProgressByBook]）
    val progressMap = rememberProgressByBook()

    // 下拉更新（票 #53）：复用既有的显式失效入口——清当前层列表缓存 → 重新枚举 → 可见行重取封面（
    // 见下面的 coverReloadKey）。不是纯动画：能力与原刷新按钮完全一致。
    fun refresh() {
        source?.invalidateListCache(containerId)
        refreshing = true
        reloadTick++
    }

    // 两档各自的滚动状态：下拉只在"停在顶部"时接管（其余情况整段交回常规滚动）
    val listState = rememberLazyListState()
    val gridState = rememberLazyGridState()

    // 系统返回手势 = 浏览历史后退（spec 故事 38）：同步维护历史栈
    BackHandler(enabled = ServiceLocator.browseHistory.canGoBack) {
        ServiceLocator.browseHistory.goBack()
        nav.popBackStack()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    // 根层标题 = 连接显示名（票 #49）：书柜点连接与首页点连接落到同一屏、同一标题口径；
                    // 子层仍是条目名（会话内回填）→ id 末段兜底。规则收在 [browserTitle] 里（纯函数，有单测）
                    Text(
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
                    ViewMenuButton(view) { mode -> ViewSettingStore.setting = mode }
                    // 排序切换（spec 故事 14 + 票 #29）：名称 / 修改时间 / 发布时间三档，点当前档即反向
                    SortMenuButton(setting) { mode -> SortSettingStore.setting = setting.select(mode) }
                },
            )
        },
    ) { padding ->
        val list = shown
        val src = source
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
            else -> PullToRefreshArea(
                atTop = { if (view.isGrid) gridState.isAtTop else listState.isAtTop },
                refreshing = refreshing,
                onRefresh = ::refresh,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
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
                    )
                } else {
                    LazyColumn(
                        state = listState,
                        modifier = Modifier.fillMaxSize(),
                    ) {
                        items(list, key = { it.id }) { entry ->
                            BrowseRow(
                                entry = entry,
                                progress = progressForEntry(entry, progressMap[entry.id]),
                                source = src,
                                // 刷新真刷封面（票 #30 F4 / 票 #53 下拉更新）：reloadTick 变→CoverThumb 重取字节
                                coverReloadKey = reloadTick,
                                onOpen = { openEntry(nav, connId, src, entry) },
                            )
                        }
                    }
                }
            }
        }
    }
}

/** 网格档容器（票 #50）：固定列数来自设置，格子宽度由此算出并传给封面（封面必须占满格宽） */
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
        LazyVerticalGrid(
            columns = GridCells.Fixed(columns),
            state = state,
            modifier = Modifier.fillMaxSize(),
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
                    coverReloadKey = coverReloadKey,
                    onOpen = { openEntry(nav, connId, source, entry) },
                )
            }
        }
    }
}

/** 列表档条目（票 #45）：封面 + 名称两行（左对齐）+ 进度条；封面宽度不变、高度随比例（票 #46） */
@Composable
private fun BrowseRow(
    entry: BrowseEntry,
    progress: ReadingProgress?,
    source: Source,
    /** 封面字节的重取键（下拉更新 +1）：它一变，可见行重取封面（票 #30 F4） */
    coverReloadKey: Any?,
    onOpen: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onOpen)
            .padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // 缓存键用 entry.id：无 coverUri 的来源（Komga）若用 coverUri 做键，全列表会共用同一张封面
            CoverThumb(
                coverUri = entry.coverUri,
                cacheKey = entry.id,
                loadBytes = { source.coverBytes(entry.id) },
                width = LIST_COVER_WIDTH,
                reloadKey = coverReloadKey,
            )
            Text(
                entry.name,
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 2,
                modifier = Modifier.weight(1f),
            )
        }
        // 阅读进度条（票 05）：未读不显示；部分填充绿=进行中；满格红=读完（EntryProgressBar 两档复用）
        progress?.let {
            EntryProgressBar(
                progress = it,
                modifier = Modifier.padding(top = 6.dp),
            )
        }
    }
}

/**
 * 网格档格子（票 #45 形态 + 票 #50 视觉）：封面**占满格宽**（宽度=格子宽度，左中右不留白）、
 * 名称在格内**水平居中**（两行也整体居中）、封面与名称、名称与进度条之间的间距收紧到 6dp。
 */
@Composable
private fun BrowserGridCell(
    entry: BrowseEntry,
    progress: ReadingProgress?,
    source: Source,
    cellWidth: Dp,
    coverReloadKey: Any?,
    onOpen: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onOpen),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(GRID_CELL_SPACING),
    ) {
        // 可用宽度 = 格子宽度：高度由封面自身比例决定（票 #46），因此封面铺满格宽、无灰边
        CoverThumb(
            coverUri = entry.coverUri,
            cacheKey = entry.id,
            loadBytes = { source.coverBytes(entry.id) },
            width = cellWidth,
            reloadKey = coverReloadKey,
        )
        Text(
            entry.name,
            style = MaterialTheme.typography.labelLarge,
            maxLines = 2,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )
        // 进度条只对书条目显示（门控在 progressForEntry 里，文件夹与系列拿不到进度）
        progress?.let { EntryProgressBar(it) }
    }
}

/** 条目点击（票 #45 两档一致）：书→阅读器（并对齐会话来源与上次阅读），容器→下钻并入浏览历史 */
private fun openEntry(nav: NavHostController, connId: Long, source: Source, entry: BrowseEntry) {
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
            nav.navigate(Routes.reader(entry.id))
        }
        else -> {
            // 子目录入浏览历史（spec 故事 37）
            ServiceLocator.browseHistory.record(BrowseLocation(connId, entry.id))
            nav.navigate(Routes.browser(connId, entry.id))
        }
    }
}

/** 两档共用的「停在顶部」判定（票 #53）：下拉更新只在顶部接管，其余情况交回常规滚动 */
private val LazyListState.isAtTop: Boolean
    get() = firstVisibleItemIndex == 0 && firstVisibleItemScrollOffset == 0

private val LazyGridState.isAtTop: Boolean
    get() = firstVisibleItemIndex == 0 && firstVisibleItemScrollOffset == 0

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
