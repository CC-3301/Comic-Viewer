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
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import com.cc3301.comicviewer.core.input.WheelHandler
import com.cc3301.comicviewer.core.input.WheelSurface
import com.cc3301.comicviewer.core.nav.BrowseLocation
import com.cc3301.comicviewer.core.source.BrowseEntry
import com.cc3301.comicviewer.core.source.PerfTiming
import com.cc3301.comicviewer.core.source.ReadingProgress
import com.cc3301.comicviewer.core.source.SortMode
import com.cc3301.comicviewer.core.source.Source
import com.cc3301.comicviewer.core.source.SourceType
import com.cc3301.comicviewer.core.source.progressForEntry
import com.cc3301.comicviewer.core.view.CoverByteRequests
import com.cc3301.comicviewer.core.view.CoverDecode
import com.cc3301.comicviewer.core.view.CoverLayout
import com.cc3301.comicviewer.core.view.CoverPrefetch
import com.cc3301.comicviewer.core.view.CoverPrefetchLedger
import com.cc3301.comicviewer.core.view.CoverUriSource
import com.cc3301.comicviewer.core.view.ViewMode
import com.cc3301.comicviewer.core.view.gridCellMaxHeight
import com.cc3301.comicviewer.core.view.gridCellWidth
import com.cc3301.comicviewer.core.view.pageDecodeWidthPx
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.withContext

/** 列表档的封面列宽（票 #46：宽度保持现值，只有高度随封面比例变化） */
private val LIST_COVER_WIDTH = 56.dp

/**
 * 网格档的**水平**外边距（左右同值）。
 *
 * 值与来由：批次 6 定版 D7-A（票 #60）把 12dp → **20dp**——右留白要容下快速定位滑条的本体（离屏缘 7dp、
 * 宽 6dp，落在留白正中）并与封面留出 7dp 空隙；代价是每格封面变窄（手机竖屏 2 格约 8dp，票面 AC17 已接受）。
 * 抓取带（13dp = 离屏缘 7 + 本体 6）不侵入这条右留白由 `QuickScrollBarSizeTest` 钉住。
 *
 * 为什么与 [GRID_CONTENT_PADDING_VERTICAL] 拆成两个常量：批次 6 补记 #2 要求**留白只改水平方向、上下保持
 * 原值**——纵向留白直接决定格子槽高（[com.cc3301.comicviewer.core.view.gridCellMaxHeight] 扣它）与 #106
 * 已验收的格内几何，横竖共用一个常量会把纵向密度也拖走。票 #50 的「外边距 ≤12dp」在**水平轴**上由本票
 * 覆盖为 20dp，**纵向仍是 12dp**。
 */
internal val GRID_CONTENT_PADDING_HORIZONTAL = 20.dp

/** 网格档的**纵向**外边距（上下同值）：**保持 12dp 原值**（批次 6 补记 #2；与格子槽高同源，见上方 KDoc） */
internal val GRID_CONTENT_PADDING_VERTICAL = 12.dp

/**
 * 列表档每行的右留白（票 #60 批次 6 D7-A）：与网格档水平留白同为 20dp，滑条本体才落得进留白正中
 * （行左缘沿用旧值 16dp）；由 `QuickScrollBarSizeTest` 与 [GRID_CONTENT_PADDING_HORIZONTAL] 对齐。
 */
internal val LIST_ROW_END_PADDING = 20.dp
private val LIST_ROW_START_PADDING = 16.dp
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
    // 滚动量测（票 #109）：开关打开才注册帧监听器，关着什么都不做（见 [BrowseScrollFrameMetrics]）
    BrowseScrollFrameMetrics()
    // 上次停留的位置与**本次停留层的整条返回链**（票 20 故事 48 + 票 #70 r2/r3）：只记目录层级，不记排序
    // （排序属全局设置）与滚动位置（SPEC Out of Scope）。写之前先按**实际回退栈**重建浏览历史镜像——
    // 路径只有一个来源（回退栈），两个落盘键因此恒一致，启动侧的「最后一层 = 恢复位置」判据恒成立。
    LaunchedEffect(connId, containerId) {
        recordBrowsePosition(nav, ServiceLocator.browseHistory, BrowseLocation(connId, containerId))
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

    // 滚动活动登记（票 #109）：**可见区变化或滚动偏移变化**都算一次活动，帧量测据它开关统计窗口
    // （静止帧与空闲期事件都不进统计）。偏移必须一起进键：慢拖时可见区几乎不变，只按可见区登记
    // 窗口就会被静止判据在滚动中途切开（真机上表现为一段连续滚动落成多行、滚动期间的条目重组计不到）。
    // 与下方封面预取各用一条 snapshotFlow：量测只在开关打开时跑，且不参与预取的取消传播。
    // 键里带 [scrollResetKey]（与上面滑条的 `remember(listState)` 同一口径）：换排序会让两档滚动状态**换实例**，
    // 不跟着换键的话这个 effect 会一直盯着旧实例、`markScrollActivity` 不再被调 ⇒ 打点静默停摆。
    LaunchedEffect(PerfTiming.isOn, view.isGrid, scrollResetKey) {
        if (!PerfTiming.isOn) return@LaunchedEffect
        snapshotFlow { if (view.isGrid) gridState.scrollActivity else listState.scrollActivity }
            .collect { activity ->
                // `snapshotFlow` 总先发一次初值：首帧布局尚未就绪（以及空目录）时那是空集，不算滚动活动——
                // 否则会开一个空窗、落一行假摘要，取基线时与真实滚动混淆。
                if (activity.visible.isEmpty()) return@collect
                BrowseScroll.probe.markScrollActivity(System.nanoTime())
            }
    }

    // ---------- 打开前置（票 #108 E1-A）：点书不立刻切页 ----------
    // 点击后**留在书柜页**：先在这层把书打开、把首帧解码完（后台跑，不做任何提示条/toast/遮罩），
    // 就绪后**一次性**切到阅读页；前置由 [ServiceLocator.readerPrelude] 交给阅读页（它组合期同步取走，不再重开书）。
    // 连点同一本不重启（键不变）；换点另一本则取消前一次。
    // 等待有界（≤1.5s）且失败/超时都放行；**被取消时不导航**（与 `ui/Cancellation.kt` 票 #26 同口径），
    // 取消只可能来自「被后一次点击顶替」与「已离开组合」两处，都不该把人拉进阅读页——见 [awaitReaderPrelude]。
    var pendingOpenBookId by remember { mutableStateOf<String?>(null) }
    // 前置工作的作用域（票 #108 r6）：随本页销毁而取消（工作不会泄漏），但**不被 1.5s 上限取消**——
    // 到点后它继续把字节/位图填进缓存（Kotlin 侧阻塞的来源也能被放行，因为上限包的是等待侧）
    val preludeScope = rememberCoroutineScope()
    // 组合存活标志（第二道守卫）：离开浏览页时不导航（取消已经挡住绝大多数，这一道防「取消还没送达」的窄窗口）
    var openRequestAlive by remember { mutableStateOf(true) }
    DisposableEffect(Unit) { onDispose { openRequestAlive = false } }
    // 点书只登记「要开哪本」（条目点击路径调用）：切页在前置跑完后的那一个动作里，书柜页在这期间照常可见
    val beginBookOpen: (BrowseEntry) -> Unit = { pendingOpenBookId = it.id }
    // 阅读页目标宽度 px：与阅读页共用同一个纯函数（[pageDecodeWidthPx]）——前置解码宽度与阅读页取的那把
    // 解码缓存键必须一致，否则前置白解一张、进阅读页仍要重解（一致才能换来「切过去首帧就是图」）。
    // `LocalView.current.width`（整窗宽）与阅读页的 `maxWidth.toPx()` 在正常布局下是同一个宽度。
    val readerWidthPx = pageDecodeWidthPx(LocalView.current.width.toFloat())
    LaunchedEffect(pendingOpenBookId) {
        val bookId = pendingOpenBookId ?: return@LaunchedEffect
        val srcForOpen = source ?: sessionSource
        // 判据在**点击时刻**读一次，与落点同源（票 #110 r3）：它随前置槽一起带到落地那一刻，
        // 落地时不再重读设置（否则「落点按点击时刻算、写不写按落地时刻算」会不同源）。
        val preloadAlwaysFirstPage = AppSettings.alwaysOpenFirstPage
        awaitReaderPrelude(
            workScope = preludeScope,
            timeoutMillis = PRELUDE_TIMEOUT_MILLIS,
            preload = {
                srcForOpen?.let { src ->
                    withContext(Dispatchers.IO) {
                        preloadReaderOpening(src, bookId, preloadAlwaysFirstPage, readerWidthPx) { handle, index, width ->
                            PageDecoder.decodePage(handle, index, width) { PageDecoder.loadPageBytes(handle, index) }
                        }
                    }
                }
            },
            // 票 #110：键是**连接 id + 书 id**——书 id 只在对应连接内有效，只按书 id 认主会把本连接的前置换给别的连接的同 id 书
            onReady = { ServiceLocator.readerPrelude.put(connId, bookId, ReaderPreludeEntry(it, preloadAlwaysFirstPage)) },
            // 组合仍存活 + 仍是当前那次点击（被后一次点击顶替时新请求自己会导航）
            isRequestCurrent = { openRequestAlive && pendingOpenBookId == bookId },
            navigate = {
                pendingOpenBookId = null
                nav.navigate(Routes.reader(bookId))
            },
        )
    }

    // ---------- 封面预取（票 #108 E2-B）：可见区 ±1 屏 ----------
    // 口径与接线在下面**列表已就绪**那一支（预取要用那里的格宽/裁剪目标，见 [coverDecodeKeyOf]）：
    // 滚动带来新的可见区间时，把「±1 屏」**内、且可见行真的会走来源字节通路**的封面提前弄好
    // （判据 [CoverUriSource.viaSourceBytes]：本地/SAF 的 content://file:// 行由系统解 uri、从不调 coverBytes，
    // 预取它们只是白读整张图并挤占同一份字节缓存——票 #108 r3 评审 P1）。
    // 单批最多 [CoverPrefetch.MAX_CONCURRENT_LOADS] 张：快速滑动一屏一屏地撞出新窗口，不限并发会把内存/带宽拉爆。
    // 出屏**不**丢缓存：位图在 `PageDecoder` 的 `DecodedImageCache`（页面/封面各一份预算、按最旧淘汰），
    // 字节在来源的会话缓存（票 #108 起按上界**淘汰最旧**，不再是「越界就整仓清空」），滚回来不再重走整段加载。
    val prefetchSource = source ?: sessionSource
    // 同一 id 的封面字节**在飞合并**（票 #108 r6）：预取与可见行会同时要同一张，来源侧只有结果缓存，
    // 不合并就会在 SMB/WebDAV 上把同一张取两遍（慢来源上首屏反而更慢）。
    val coverRequests = remember(connId, containerId, reloadTick) { CoverByteRequests() }
    // 记帐本（票 #108 r2~r4）：只管**在飞**与**有界退避**。「已经有字节了」不进这里（r4）：那是来源字节缓存的
    // 状态（[Source.hasCachedCoverBytes]）——它是唯一真相，字节被上界淘汰后滚回来的条目因此会重新进窗口；
    // 退避则挡住「真没封面 / 这次失败」的条目被整窗反复重发（每 id 每会话 ≤ 3 次，下拉更新重建记帐本即重置）。
    val prefetchLedger = remember(connId, containerId, view.isGrid, reloadTick) { CoverPrefetchLedger() }

    // 系统返回手势 = 浏览历史后退（spec 故事 38）：历史是回退栈里浏览层的镜像，返回决议与栈一致时才接管。
    // 不一致（进程级单例漂移 / 上一会话残留 / 两段会话并存）时交回系统：系统照旧弹一层，仍是逐级返回，
    // 被弹出来的浏览页显示时按栈重建镜像（见 [browseBackInterception]）。
    BackHandler(enabled = browseBackInterception(nav, ServiceLocator.browseHistory)) {
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
            else -> BoxWithConstraints(Modifier.fillMaxSize()) {
                // 外层 Box **不**收横向 inset（票 #60 r4：滑条要以**屏幕**右缘为基准）⇒ 这里的 maxWidth 含左右 inset，
                // 而封面解码宽度要的是**内容区**宽度，因此手工扣掉（票 #108 r6 的格宽口径逐字不变）。
                val contentWidthDp = (maxWidth - (padding.calculateLeftPadding(LayoutDirection.Ltr) +
                    padding.calculateRightPadding(LayoutDirection.Ltr))).coerceAtLeast(0.dp)
                // 封面解码目标（票 #108 r6）：**可见行与预取共用这一份**。宽度与裁剪目标都进解码键
                // （[CoverDecode.key]），两处各算一次就会各解一张——预取解好的那张可见行命不中，等于白干。
                // 格宽在这里算、往下传给格子（[BrowserGrid] 不再自己算一份）：同一屏只有一个宽度来源。
                val coverWidthDp = if (view.isGrid) {
                    with(LocalDensity.current) {
                        gridCellWidth(
                            availableDp = contentWidthDp.value,
                            columns = view.columns ?: ViewMode.GRID_2.columns!!,
                            contentPaddingDp = GRID_CONTENT_PADDING_HORIZONTAL.value,
                            spacingDp = GRID_HORIZONTAL_SPACING.value,
                        ).dp
                    }
                } else {
                    LIST_COVER_WIDTH
                }
                // 解码参数与键都走 [CoverDecodeKeys.forPrefetch]（票 #108 r7）：可见行走 `forRow`，
                // 两边必须逐字相等，由 `CoverDecodeKeysTest` 钉住（漂移则预取静默白干）
                val coverDecodeParams = CoverDecodeKeys.forPrefetch(coverWidthDp.value, view.isGrid, LocalDensity.current.density)
                val coverDecodeWidthPx = coverDecodeParams.widthPx
                val coverCropTarget = coverDecodeParams.cropTarget
                /** 与 `CoverThumb` 同一把键（条目 id + 重取键 + 目标宽度 + 裁剪目标） */
                fun coverDecodeKeyOf(entryId: String) = coverDecodeParams.keyOf(entryId, reloadTick)

                // ---------- 封面预取（票 #108 E2-B + r6）：可见区 ±1 屏，取字节**并解码** ----------
                // 键里带 [scrollResetKey]（与上面量测 effect、滑条的 `remember(listState)` 同一口径）：换排序会换滚动状态
                // 实例，不跟着换键的话这个 effect 会一直盯着旧实例的 `visibleIndices`，预取静默失效。
                // 键里带解码宽度/裁剪目标：换档位（列数变 → 格宽变 → 桶变）就要按新键重解，不能拿上一档的位图。
                LaunchedEffect(
                    prefetchSource,
                    list,
                    view.isGrid,
                    reloadTick,
                    scrollResetKey,
                    coverDecodeWidthPx,
                    coverCropTarget,
                ) {
                    val candidates = list.map {
                        CoverPrefetch.Candidate(it.id, CoverUriSource.viaSourceBytes(it.coverUri))
                    }
                    snapshotFlow { if (view.isGrid) gridState.visibleIndices else listState.visibleIndices }
                        .collectLatest { visible ->
                            if (visible.isEmpty()) return@collectLatest
                            val window = CoverPrefetch.window(visible.first(), visible.last(), candidates.size) ?: return@collectLatest
                            val targets = prefetchLedger.begin(window, candidates) { candidate ->
                                // 已经有**位图**了 ⇒ 完全不用管（r6 起预取连解码一起做，位图在就是可见行能直接用的那张）；
                                // 只有**字节**在 ⇒ 不占预取名额：这一条不需要网络往返，可见行自己解一下就出来，
                                // 名额留给真要往返的条目（来源字节缓存是只读内存查询，不做 IO）
                                PageDecoder.cachedCover(coverDecodeKeyOf(candidate.id)) != null ||
                                    prefetchSource?.hasCachedCoverBytes(candidate.id) == true
                            }
                            try {
                                targets.chunked(CoverPrefetch.MAX_CONCURRENT_LOADS).forEach { batch ->
                                    val results = batch
                                        .map { id ->
                                            async(Dispatchers.IO) {
                                                id to catchingNonCancellation {
                                                    // 取字节（同一 id 与可见行在飞合并）+ 解码进封面分区：
                                                    // 可见行组合时 `PageDecoder.cachedCover` 直接命中，不再重解/重取
                                                    prefetchCoverBitmap(
                                                        entryId = id,
                                                        decodeKey = coverDecodeKeyOf(id),
                                                        targetWidthPx = coverDecodeWidthPx,
                                                        cropTarget = coverCropTarget,
                                                        requests = coverRequests,
                                                    ) { prefetchSource?.coverBytes(id) }
                                                }
                                            }
                                        }
                                        .awaitAll()
                                    results.forEach { (id, result) ->
                                        // 只分「拿到 / 没拿到」：来源区分不了「真没有」与「这次断了」，
                                        // null 一律可重试，由有界退避兜住重复（票 #108 r4）
                                        prefetchLedger.settle(id, loaded = result.getOrNull() == true)
                                    }
                                }
                            } finally {
                                // 取消/异常路径：本批在飞的全部放回（不计尝试次数：取消不是来源的答复）
                                prefetchLedger.release(targets)
                            }
                        }
                }

                PullToRefreshArea(
                    atTop = { if (view.isGrid) gridState.isAtTop else listState.isAtTop },
                    refreshing = refreshing,
                    onRefresh = ::refresh,
                    // Scaffold 内容 inset 收在这里（票 #60 r4：外层的 Box 不再收横向 inset，滑条才能以**屏幕**右缘为基准）
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
                            // 格宽与封面解码宽度同源（见上）：格子与预取不再各算一份
                            cellWidth = coverWidthDp,
                            coverRequests = coverRequests,
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
                                    coverRequests = coverRequests,
                                    onOpen = { openEntry(nav, connId, src, entry, onOpenBook = beginBookOpen) },
                                )
                            }
                        }
                    }
                }
                // 快速定位滑条（票 #60）：**兄弟层**，盖在 [PullToRefreshArea] 之上。
                // Compose 的命中选择最上层命中的子件，因此按下滑条时事件到不了下拉更新与条目点击——
                // 「拖滑条不触发下拉更新、不打开条目」是结构性保证（见 [QuickScrollBar] 的 KDoc）。
                // 横向基准是**屏幕**右缘（r4）：空档 = Scaffold 右缘 inset + 内容右留白，滑条本体居中于它
                // （系统右缘 inset 会把可见空档撑宽，按内容区右缘固定 7dp 会让滑条贴到封面上）；
                // 纵向用同一份 Scaffold inset 收成与原内容区一致的一条轨道，不压顶栏与系统栏。
                val endGap = padding.calculateRightPadding(LayoutDirection.Ltr) +
                    (if (view.isGrid) GRID_CONTENT_PADDING_HORIZONTAL else LIST_ROW_END_PADDING)
                QuickScrollBar(
                    state = if (view.isGrid) gridQuickScroll else listQuickScroll,
                    endGap = endGap,
                    modifier = Modifier
                        .align(Alignment.CenterEnd)
                        .padding(
                            top = padding.calculateTopPadding(),
                            bottom = padding.calculateBottomPadding(),
                        ),
                )
            }
        }
    }
}

/** 网格档容器（票 #50）：固定列数来自设置，格子宽度由调用方算出并传入（同一屏所有格子同宽同高） */
@Composable
private fun BrowserGrid(
    list: List<BrowseEntry>,
    columns: Int,
    state: LazyGridState,
    progressMap: Map<String, ReadingProgress>,
    source: Source,
    connId: Long,
    coverReloadKey: Any?,
    /**
     * 格宽（票 #108 r6 起由 [BrowserScreen] 的 `BoxWithConstraints` 算好传下来）：它同时是封面**解码宽度**的
     * 来源（宽度进解码键），预取要用同一个值 ⇒ 不能在这里再算一份。
     */
    cellWidth: Dp,
    /** 同一 id 的封面字节在飞合并（票 #108 r6）：格子与预取共用同一份，同一张不取两遍 */
    coverRequests: CoverByteRequests,
    nav: NavHostController,
    /** 点开一本书（票 #108 E1-A）：界面层只登记「要开这本」，切页由前置跑完后的那一个动作完成 */
    beginBookOpen: (BrowseEntry) -> Unit,
) {
    BoxWithConstraints {
        // 网格项高度上限（票 #106）：可视高度取格子真拿到的纵向约束（已扣掉顶栏与系统栏，不自己估摸屏幕
        // 高度），再扣掉上下 contentPadding。名字块与格子内间距**不在这里扣**——格子骨架 [GridCellFrame]
        // 会真量名字块（量高副本 `measurables`/`subcompose` 的那一份）后把剩下的高度留给封面，
        // 因此不靠「行高 × 行数」的字体度量推算。
        // 横屏 2 格时格宽大、格高超过这个上限（票 #106 的 bug），封面据此收窄并居中、两侧留白，名字行恒有位置。
        val cellMaxHeight = with(LocalDensity.current) {
            gridCellMaxHeight(
                visibleHeightDp = maxHeight.value,
                // 纵向分量：批次 6 只改水平留白，纵向保持 12dp（补记 #2）
                contentPaddingDp = GRID_CONTENT_PADDING_VERTICAL.value,
            ).dp
        }
        LazyVerticalGrid(
            columns = GridCells.Fixed(columns),
            state = state,
            // 鼠标左键按住拖动 = 上下滑动（票 #69）：与列表档同一份修饰符（网格档同样被内建 scrollable 拒绝）
            modifier = Modifier
                .fillMaxSize()
                .mouseDragScroll(state),
            // 横竖分量各一份常量：水平 20dp（滑条留白）、纵向 12dp（保持原值）
            contentPadding = PaddingValues(
                start = GRID_CONTENT_PADDING_HORIZONTAL,
                end = GRID_CONTENT_PADDING_HORIZONTAL,
                top = GRID_CONTENT_PADDING_VERTICAL,
                bottom = GRID_CONTENT_PADDING_VERTICAL,
            ),
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
                    coverRequests = coverRequests,
                    onOpen = { openEntry(nav, connId, source, entry, onOpenBook = beginBookOpen) },
                )
            }
        }
    }
}

/** 列表档条目（票 #45）：封面 + 名称（左对齐），已读的书在**名称正下方**有一条进度条（票 #92 需求 2）
 *
 * 可见性 `internal`（票 #109 r6）：条目体首的滚动量测计数接线要能被用例组合起来盯住
 * （`BrowseItemCountTest`）；本件其它接线不属于那个用例的范围。
 */
@Composable
internal fun BrowseRow(
    entry: BrowseEntry,
    progress: ReadingProgress?,
    source: Source,
    /** 封面字节的重取键（下拉更新 +1）：它一变，可见行重取封面（票 #30 F4） */
    coverReloadKey: Any?,
    /** 同一 id 的封面字节在飞合并（票 #108 r6）：与预取共用同一份（预取正在取的那张，这里直接等它） */
    coverRequests: CoverByteRequests,
    onOpen: () -> Unit,
) {
    // 滚动量测（票 #109）：本行 composable 体执行一次 = 条目层一次实际重组（Compose 跳过重组时不执行、不计数）
    if (PerfTiming.isOn) BrowseScroll.probe.onItemComposed()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onOpen)
            .padding(
                start = LIST_ROW_START_PADDING,
                end = LIST_ROW_END_PADDING,
                top = 8.dp,
                bottom = 8.dp,
            ),
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
            // 在飞合并（票 #108 r6）：预取正在取同一张时这里不另发一次往返
            loadBytes = { coverRequests.load(entry.id) { source.coverBytes(entry.id) } },
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
 * 名称行**宽度 = 封面宽、与封面同中线**（票 #106 r2 / 批次 6 定版 D6-A：名字在封面正下方；行内文字仍左对齐，
 * 票 #92 需求 1 口径不变）；封面与名称之间的间距收紧到 6dp；
 * 已读书目的进度条**压在封面下缘**（票 #92 需求 2：叠在封面上、不占布局），条下垫一层黑 45% 暗底
 * （票 #92 需求 5 方案 A：浅色/白色封面上轨道才看得清；**仅网格档**，列表档不铺）；
 * 名称块**固定两行高**（票 #94：1 行名也占满两行，因此同排格子等高、格底逐行对齐）；
 * 封面受**格子高度上限**（[cellMaxHeight]）约束：格高放不下时封面等高收缩、宽按格比例反算、水平居中，
 * 名字行因此恒有位置且与封面同宽同中线（横屏 2 格格宽大、格高超过可视高度是本票要修的 bug）——
 * 摆位全在 [GridCellFrame] 一处。
 *
 * 可见性 `internal`（票 #109 r6）：与 [BrowseRow] 同一理由——条目体首的计数接线要能被用例组合起来盯住。
 */
@Composable
internal fun BrowserGridCell(
    entry: BrowseEntry,
    progress: ReadingProgress?,
    source: Source,
    /** 格宽（格高由 [CoverLayout.GRID_CELL_ASPECT] 在封面里算出，不在这里再算一份） */
    cellWidth: Dp,
    /** 格子高度上限（票 #106）：= 可视高度 − 上下留白；封面放不下时按它收窄、名字行因此恒有位置 */
    cellMaxHeight: Dp,
    coverReloadKey: Any?,
    /** 同一 id 的封面字节在飞合并（票 #108 r6）：与预取共用同一份 */
    coverRequests: CoverByteRequests,
    onOpen: () -> Unit,
) {
    // 滚动量测（票 #109）：与列表档同一口径（本格 composable 体执行一次 = 条目层一次实际重组）
    if (PerfTiming.isOn) BrowseScroll.probe.onItemComposed()
    GridCellFrame(
        cellMaxHeight = cellMaxHeight,
        spacing = GRID_CELL_SPACING,
        modifier = Modifier.clickable(onClick = onOpen),
        // 封面槽（票 #106）：本帧给槽的固定尺寸就是**封面盒**（名字块高已被预算让出，见 [GridCellFrame]），
        // 槽内按同一份纯函数复算盒 ⇒ 两者恒等；未触发收缩时盒 = 格宽 × 格高（竖屏与 3/4 格逐像素同改动前）
        cover = {
            BoxWithConstraints(
                modifier = Modifier.fillMaxSize(),
                // 封面收缩时在槽内水平居中、两侧留白（票 #106 AC2）
                contentAlignment = Alignment.TopCenter,
            ) {
                val coverAvailableHeight = maxHeight
                val coverSize = CoverLayout.gridCellSize(cellWidth.value, coverAvailableHeight.value)
                // 封面 + 压在封面下缘的进度条（票 #92 需求 2）：条叠在封面上、不占布局，没读过的格子不留空白，
                // 读过的格子也不会比它高。盒子宽度取**封面宽**（票 #106 起收缩时窄于格宽）：条 fillMaxWidth
                // 因此恰好等于封面宽，收缩后仍与封面同宽
                Box(Modifier.width(coverSize.width.dp)) {
                    CoverThumb(
                        coverUri = entry.coverUri,
                        cacheKey = entry.id,
                        // 在飞合并（票 #108 r6）：预取正在取同一张时这里不另发一次往返
                        loadBytes = { coverRequests.load(entry.id) { source.coverBytes(entry.id) } },
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
        },
        // 名称在**名字行内**对齐（票 #92 需求 1 的口径 + 票 #106 批次 6 修 P2-1）：对齐由格子骨架给——
        // 收缩态居中（短书名的字形也落在封面中线上）、未收缩态左对齐（= 与列表档同一口径）；
        // 行宽 = 封面宽（票 #106 r2），断行宽度因此与封面同宽；断行口径与列表档共用（票 #47）；
        // 名称块固定两行高（票 #94）：1 行名也占两行，因此同排格子的高度只由「封面高 + 间距 + 两行名」
        // 决定，与名称行数无关。
        name = { nameAlign ->
            EntryNameText(
                name = entry.name,
                style = MaterialTheme.typography.labelLarge,
                minLines = entryNameMinLines(gridMode = true),
                textAlign = nameAlign,
                modifier = Modifier.fillMaxWidth(),
            )
        },
    )
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
        entry.isBook -> openBookFromBrowser(connId, source, entry, onOpenBook)
        else -> {
            // 子目录入浏览历史并压栈（spec 故事 37）：走唯一入口——同一层重复进入是**替换**而不是追加，
            // 已离开的那一段会话不会被新层级叠上去（票 #70 r3）
            navigateToBrowseLocation(nav, ServiceLocator.browseHistory, BrowseLocation(connId, entry.id))
        }
    }
}

/**
 * 浏览页点开一本书时要做的事（票 #110）：**只登记「要开哪本」**，外加把会话来源对齐到本页连接。
 *
 * 进度覆盖与「上次阅读位置」都**不**在这里写——两者都推迟到阅读页真正切进这本书那一刻
 * （见 `openAndLandReaderEntry` / `landReaderEntry`），于是「点了又取消 / 被后一次点击顶替」不留记录。
 *
 * 抽成非 Composable 的函数是为了让「点击路径到底做了什么」有自动化守护（`ReaderEntryLandingTest`）：
 * 把写加回这里，用例立刻变红；落在 `BrowserScreen` 的 Composable 里则守不住（本仓无 Compose UI 测试基建）。
 */
internal fun openBookFromBrowser(
    connId: Long,
    source: Source,
    entry: BrowseEntry,
    /** 书的打开（票 #108 E1-A）：调用方在这之后才切页（先把书打开、首帧解好） */
    onOpenBook: (BrowseEntry) -> Unit,
) {
    // 阅读器路由只认会话来源（AppNav）：跨来源后（打开过别的库的书）会话可能指向别的连接，
    // 此处必须对齐到本页的 connId，否则会用别的库的来源开本库的书 id、进度也写错库。
    // 只在点击路径写全局：组合期写会把回退栈下层带偏（r1 P1）
    if (ServiceLocator.currentConnId != connId) {
        ServiceLocator.currentSource = source
        ServiceLocator.currentConnId = connId
    }
    onOpenBook(entry)
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
 * 滚动活动键（票 #109 r5，量测窗口的开关信号）：可见区变化 **或** 滚动偏移变化都算一次活动——
 * 口径与理由在 [BrowseScrollActivity]。两档各写一份（与上面 [visibleIndices] 同一套理由），
 * **两处取值都受 `BrowseScrollTest` 盯着**（漏掉 `firstVisibleItemScrollOffset` 会红）。
 */
internal val LazyListState.scrollActivity: BrowseScrollActivity
    get() = BrowseScrollActivity(visible = visibleIndices, scrollOffset = firstVisibleItemScrollOffset)

internal val LazyGridState.scrollActivity: BrowseScrollActivity
    get() = BrowseScrollActivity(visible = visibleIndices, scrollOffset = firstVisibleItemScrollOffset)

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
