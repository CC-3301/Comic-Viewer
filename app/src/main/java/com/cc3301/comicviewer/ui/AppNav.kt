package com.cc3301.comicviewer.ui

import android.app.Activity
import android.os.SystemClock
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavHostController
import androidx.navigation.NavOptions
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import androidx.navigation.navOptions
import com.cc3301.comicviewer.R
import com.cc3301.comicviewer.core.data.ConnectionEntity
import com.cc3301.comicviewer.core.nav.BrowseHistory
import com.cc3301.comicviewer.core.nav.BrowseLocation
import com.cc3301.comicviewer.core.nav.LastBrowsing
import com.cc3301.comicviewer.core.nav.LastRead
import com.cc3301.comicviewer.core.nav.LastTopLevel
import com.cc3301.comicviewer.core.nav.StartupTarget
import com.cc3301.comicviewer.core.nav.fallbackWhenConnectionMissing
import com.cc3301.comicviewer.core.source.PerfTiming
import com.cc3301.comicviewer.core.source.SortMode
import com.cc3301.comicviewer.core.source.Source
import com.cc3301.comicviewer.core.source.SourceType
import com.cc3301.comicviewer.core.source.isNotABook
import com.cc3301.comicviewer.ui.nav.NavEvent
import com.cc3301.comicviewer.ui.nav.RouteLogMemo
import com.cc3301.comicviewer.ui.nav.StartupReadOutcome
import com.cc3301.comicviewer.ui.nav.adoptSessionSourceForBrowseChain
import com.cc3301.comicviewer.ui.nav.browseChainBelowTopLevel
import com.cc3301.comicviewer.ui.nav.browseLocationOf
import com.cc3301.comicviewer.ui.nav.landStartupBrowserLayer
import com.cc3301.comicviewer.ui.nav.landStartupTopLevel
import com.cc3301.comicviewer.ui.nav.navigateStartupReader
import com.cc3301.comicviewer.ui.nav.navigateTopLevel
import com.cc3301.comicviewer.ui.nav.navObservationLine
import com.cc3301.comicviewer.ui.nav.openReaderFromDrawer
import com.cc3301.comicviewer.ui.nav.pushBrowserPath
import com.cc3301.comicviewer.ui.nav.pushStartupRootHome
import com.cc3301.comicviewer.ui.nav.readingFlagToRecord
import com.cc3301.comicviewer.ui.nav.recordTopLevelForRoute
import com.cc3301.comicviewer.ui.nav.resetBrowseHistoryForStartup
import com.cc3301.comicviewer.ui.nav.resolveStartupRead
import com.cc3301.comicviewer.ui.nav.resolveTopLevelBrowseChain
import com.cc3301.comicviewer.ui.nav.startupBrowsePath
import com.cc3301.comicviewer.ui.nav.syncBrowseHistory
import com.cc3301.comicviewer.ui.nav.topLevelRouteOf
import com.cc3301.comicviewer.ui.nav.withPrimedLayer
import com.cc3301.comicviewer.ui.nav.NavSlideDirection
import com.cc3301.comicviewer.ui.nav.NavTransitionFrameMetrics
import com.cc3301.comicviewer.ui.nav.NavTransitionProbe
import com.cc3301.comicviewer.ui.nav.NavTransitionStyle
import com.cc3301.comicviewer.ui.nav.beginNavTransitionProbe
import com.cc3301.comicviewer.ui.nav.navSlideDirection
import com.cc3301.comicviewer.ui.nav.NavTransitionTimeline
import com.cc3301.comicviewer.ui.nav.navEnterMotion
import com.cc3301.comicviewer.ui.nav.navExitMotion
import com.cc3301.comicviewer.ui.nav.navTransitionDetail
import com.cc3301.comicviewer.ui.nav.navTransitionKind
import com.cc3301.comicviewer.ui.nav.navTransitionStyle
import com.cc3301.comicviewer.ui.nav.navTransitionWindowMillis
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

object Routes {
    /** 启动判定期间的中转页：解析完成后立即被 popUpTo 移除 */
    const val STARTUP = "startup"
    const val HOME = "home"
    const val LOCAL_ROOTS = "localRoots"

    /** 网络来源连接管理：按来源类型参数化，SMB 与 WebDAV 共用同一界面 */
    const val CONNS = "conns/{sourceType}"

    fun conns(type: SourceType): String = "conns/" + type.name

    const val SETTINGS = "settings"

    /** 书柜柜列表（按连接分柜，spec 故事 43/44） */
    const val BOOKSHELF = "bookshelf"

    /**
     * 浏览路由（带 `name` 通道）：`name` = 这一层的**条目名**（进目录那一刻的真实名字）。
     *
     * 为什么要随路由带（2026-09-27 定）：条目名原先只能从**会话内存缓存**
     * （`ServiceLocator.entryNames`）取，而它只在枚举某一层时回填——进程重建（退出 APP 再回来）后缓存是空的，
     * 这次恢复又不经过父层枚举，标题于是吃到 id 末段（Komga 的末段是服务端随机 id ⇒ 表现成「一串英文」）。
     * 名字进参数后，恢复路径（系统还原回退栈 / 启动按落盘路径重建）直接用它，不看缓存也不打网络。
     *
     * 没带名字（空串）时行为与改前一致：浏览页标题退到缓存、再退到 id 末段（见 [browserTitle]）。
     */
    const val BROWSER = "browser/{connId}?container={container}&name={name}"

    /**
     * 阅读器路由。
     *
     * 进 / 出的呈现方式由前后两屏的路由判定（[com.cc3301.comicviewer.ui.nav.navTransitionStyle]）：
     * 进阅读器一律从右滑入（冷启动落地同款），出阅读器是旧屏（阅读页）往右滑出。换书是硬切，与方向无关。
     */
    const val READER = "reader/{bookId}"

    /** 浏览子层目的地（[containerName] 就是随路由带走的条目名，没有名字时传 null ⇒ 参数为空串） */
    fun browser(connId: Long, containerId: String?, containerName: String? = null): String =
        "browser/$connId?container=${android.net.Uri.encode(containerId ?: "")}" +
            "&name=${android.net.Uri.encode(containerName ?: "")}"

    /** 浏览根层（containerId 为空）：书柜点连接与首页点连接落到同一处 */
    fun browserRoot(connId: Long): String = browser(connId, null)

    fun reader(bookId: String): String =
        "reader/" + android.net.Uri.encode(bookId)
}

/**
 * 打开某本书（读内换书 / 抽屉「阅读器」入口）的导航选项：**换一条新的 back stack entry**，
 * 不沿用上一条（`launchSingleTop` 会沿用）。
 *
 * 阅读页的宿主态（页位/页边界表/按页缩放表/菜单/跨书确认条）与保存态（`rememberSaveable`）都挂在 entry 上：
 * 沿用同一条 entry 时（书 id 只存在于参数里），页位正确性只剩「Compose 分槽键 + 保存态桶」这一层兜底，
 * 而这层在设备上没能兜住（验收开着开关换书仍回到上一本页位）。这里改成结构上必然换 entry：
 * 新的 entry id → 新的组合槽位与 ViewModel/SaveableState 桶 → 页位/缩放/菜单随之整体重建。
 * 《谁是承重机制》的口径写在 `ReaderScreen` 的分槽注释里：这一层是承重的，`key(bookId)` 只是兜底。
 *
 * 导航语义不变：栈里仍只有一条阅读器 entry（换书不加深回退栈），回退仍落到浏览层。
 * 语义由 [ReaderSwapNavTest] 锁定（它用与生产同名的路由图跑真实的 `NavController`；**生产调用点本身**
 * 不被它覆盖——`AppNav` 里两处 `navigate(..., newReaderNavOptions())` 改回 `launchSingleTop` 时该用例仍绿，
 * 见该文件的 KDoc）。
 */
internal fun newReaderNavOptions(): NavOptions = navOptions { popUpTo(Routes.READER) { inclusive = true } }

/** 导航壳：首页 → 本地根列表 → 浏览 → 条漫阅读器 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppNav() {
    val nav = rememberNavController()
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val currentEntry = nav.currentBackStackEntryAsState().value
    val currentRoute = currentEntry?.destination?.route
    val history = ServiceLocator.browseHistory

    // ---------- 页面过渡与前置 ----------
    // 过渡期帧时长探针：开关打开才注册监听器（默认关，零开销，与浏览页量测同一口径）。
    val navTransitionProbe = remember { NavTransitionProbe() }
    // 呈现方式与方向都在 `NavHost` 的过渡 lambda 里按 `initialState/targetState` 算
    //（同一套判据，见 `ui/nav/NavTransition.kt`）。
    // 「导航前先收系统栏」：进阅读器的入口在 `navigate` 之前调一次（理由见 `ui/ReaderSystemBars.kt`）。
    val hideReaderSystemBars = rememberReaderSystemBarsHider()
    // 开书入口：三条 AppNav 入口（启动还原 / 抽屉「阅读器」/ 读内换书）与浏览页点击共用同一条
    // 通道；接线（会话级作用域 / 前置槽 / 解码宽度 / 「始终从第一页打开」的判据）都在 [OpenBookEntry] 里，
    // 入口只交「哪本书 + 本入口自己那条守卫 + 怎么进阅读器」。
    val openBook = rememberOpenBookEntry()
    // 「不在浏览页点书」入口的请求判定：抽屉「阅读器」/ 读内换书 / 启动还原三条共用同一套——
    // 每点一次领一个单调 token，并记下发起时栈顶那一项（栈项身份，不是路由 pattern）；
    // 被顶替或用户已离开那一项都不再导航。
    val readerEntryRequest = remember { ReaderEntryRequest() }

    fun closeDrawer() {
        scope.launch { drawerState.close() }
    }

    fun openDrawer() {
        scope.launch { drawerState.open() }
    }

    // ---------- 启动页面（spec 故事 46-49）----------
    // 判定与落盘状态读取、来源解析、会话准备与导航都在下面的 LaunchedEffect 里：组合期不做同步
    // SharedPreferences 读，也绝不改写 ServiceLocator.currentSource/currentConnId。

    /**
     * 慢操作（按 id 取连接、建来源会话）在导航前完成，返回真正落地目的地——中转页期间不会先露出别的界面。
     * 阅读器建不起会话（连接已删/离线）时退化：上次停留的位置 → 首页。
     * 连接已不存在（OPDS 用户迁移后被清库、用户手工删连接）时回落首页——
     * 浏览页对不存在的连接只会停在「加载中…」。
     *
     * 返回值带一句可展示的中文提示（启动还原因「已不是一本书」而回落时必须告知用户，见 [StartupReadOutcome]）。
     */
    suspend fun prepareStartup(target: StartupTarget): StartupReadOutcome = when (target) {
        is StartupTarget.OpenBrowser -> {
            // 取库用的是 [catchingNonCancellation] 而不是裸 runCatching：它包在 withContext
            // 里层，裸 runCatching 会把取消当成「读库失败」，随后继续走导航与写历史。真正「包在 withContext
            // 外面」的是下面两处 browsingSourceFor 调用。
            val row = withContext(Dispatchers.IO) {
                catchingNonCancellation { ServiceLocator.db.connectionDao().byId(target.browsing.connId) }
            }
            val conn = row.getOrNull()
            // 与常规入口（本地根列表/连接列表）一致：先备会话来源，抽屉「阅读器」入口才能打开上次阅读的书；
            // 会话级实例：随后的浏览页复用同一个，列表缓存跨页面存活
            if (conn == null) {
                // 这条「上次停留的位置」恢复不了了，顺手清掉：留着只会让每次启动都重走一遍
                //「先导航到浏览页再弹回」。读库本身失败（isFailure）不清——那是暂时性故障，不等于连接被删
                if (row.isSuccess) StartupStore.clearBrowsing()
                StartupReadOutcome(fallbackWhenConnectionMissing(target))
            } else {
                // 会话建不起来不阻断——浏览页会按路由 connId 自行解析并显示重试（同一对调用也用于顶层落点重建的链）
                adoptSessionSourceForBrowseChain(conn, target.browsing.connId)
                StartupReadOutcome(target)
            }
        }
        is StartupTarget.OpenReader -> {
            val last = target.lastRead
            val conn = withContext(Dispatchers.IO) {
                catchingNonCancellation { ServiceLocator.db.connectionDao().byId(last.connId) }.getOrNull()
            }
            val source = conn?.let {
                catchingNonCancellation { withContext(Dispatchers.IO) { ServiceLocator.browsingSourceFor(it) } }
                    .getOrNull()
            }
            if (source == null) {
                val browsing = StartupStore.lastBrowsing()
                if (browsing != null) {
                    prepareStartup(StartupTarget.OpenBrowser(browsing))
                } else {
                    StartupReadOutcome(StartupTarget.OpenHome)
                }
            } else {
                // 阅读器路由只认会话来源 + lastRead（与柜页「打开书」同一手法）：先备好再导航
                ServiceLocator.adoptSessionSource(source, last.connId)
                ServiceLocator.lastRead = last
                // 升级路径：上一版落盘的 bookId 可能已被改判成容器（或被删）——先判定再落地，
                // 不是书就回落到浏览层，绝不把用户丢进一个只报错、还带绝对路径的阅读器（见 [com.cc3301.comicviewer.ui.nav.resolveStartupRead]）
                resolveStartupRead(source, last, StartupStore.lastBrowsing())
            }
        }
        StartupTarget.OpenBookshelf, StartupTarget.OpenHome, StartupTarget.OpenSettings -> {
            // 本次落点之下那段浏览链——只有落点正是记录的顶层路由时才用
            // （路由映射与落地那边同一处：[topLevelRouteOf]）
            val route = topLevelRouteOf(target)
            val candidate = browseChainBelowTopLevel(route, StartupStore.lastTopLevel(), StartupStore.topLevelBrowseChain())
            if (candidate.isEmpty()) {
                StartupReadOutcome(target)
            } else {
                // 链下面就是浏览页：连接还在不在得先问一次库（与浏览分支同一口径）。「第一次失败才重取、
                // 按哪一次结果算链」收在 [resolveTopLevelBrowseChain] 里（可单测，五支有用例）。
                val connId = candidate.first().connId
                // 取数只写一遍：接缝可能调它两次（不把表达式当场抄两遍）
                val fetchConn: suspend () -> Result<ConnectionEntity?> = {
                    withContext(Dispatchers.IO) {
                        catchingNonCancellation { ServiceLocator.db.connectionDao().byId(connId) }
                    }
                }
                val lookup = resolveTopLevelBrowseChain(candidate, fetchConn)
                // 链重建出来的是浏览页，会话来源要一并备好（否则随后点抽屉「阅读器」会弹「请先选择一个来源」）：
                // 与浏览分支同一对调用，实体取自上面那次取舍的结论。
                // 残余：两次都读不到实体时**不把暂时性故障变回丢链**
                //（链由 [usableTopLevelBrowseChain] 保留），只是少一个会话来源——浏览页仍按路由 connId
                // 自行解析并显示重试，用户至多多看到一次「请先选择一个来源」。
                lookup.connection?.let { adoptSessionSourceForBrowseChain(it, connId) }
                StartupReadOutcome(target, chain = lookup.chain)
            }
        }
    }

    // 只在真正的冷启动落地一次：配置变更/进程恢复时 NavController 会还原回退栈，不重复导航
    val startupDone = rememberSaveable { mutableStateOf(false) }
    // 启动 effect 的组合存活标志（与浏览页点击路径同一手法）：前置等待的第二道守卫，
    // 防「取消还没送达、导航已经执行」的窄窗口（启动落地的那一次导航现在也走 [OpenBookEntry]）
    var startupEffectAlive by remember { mutableStateOf(true) }
    DisposableEffect(Unit) { onDispose { startupEffectAlive = false } }
    LaunchedEffect(Unit) {
        // 落盘状态在**第一次挂起之前**同步读完，并保留在 effect 体第一句更稳：
        // 写点一侧另有结构性保证——下面的 recordReading 只在离开中转页的路由才写，
        // 所以本会话的写不可能污染启动期的读。重活（按 id 取连接/建来源会话）照旧在 IO 上做。
        val startTarget = StartupStore.startupTarget()

        // 落地只做一次，但「做过」不能只看 startupDone：rememberSaveable 只说明本会话
        // 标记过，不代表导航真的落地了——慢来源冷启动期间旋转屏幕、进程被杀后回到前台时，NavController 会还原出
        // 一个仍停在 STARTUP 的栈顶，必须补跑一次判定与导航，否则该页没有出口（抽屉手势已关、页上无控件）。
        // 只在**明确已落地**（栈顶是别的页）时才早退；栈顶是中转页、或尚不可知（currentDestination 为空，
        // 组合后理论上不会）都按「未落地」处理——宁可补跑一次，也不留死页。
        if (startupDone.value && nav.currentDestination?.route?.let { it != Routes.STARTUP } == true) {
            // 进程被杀后重建：回退栈由系统还原、浏览历史随进程消失——按还原出来的浏览层补齐历史
            // 这条早退支同样要**把落地层交回** `BrowseScrollPositions.position`——系统还原出来的栈顶就是本次
            // 的落地层（是浏览层时，那份落盘的位置记录正属于这一层）。真正的理由是**收口时机**：交回只决定收口
            // **能不能发生**，用掉 / 丢弃都发生在交回之后的**下一次**查询（本支的前提是界面已先组合、当帧问过一次，
            // 而 `startupLanding` 自身不触发查询）。不交回则记录停在「还没交回」那一态**保持不变**：`landingDecided`
            // 永远为假 ⇒ `landed` 不置位 ⇒ B 案永不生效，既不消费也不丢弃（不再销毁记录，见 `BrowseScrollPositions.position.consumeAtStartupLanding`）。
            // 栈顶不是浏览层（首页 / 书柜 / 设置 / 阅读器）时按「**已定的**非浏览层」交回 ⇒ 收口时当场丢弃。
            // 这一句与下面 `when` 块**同级**、不共用默认值：本支在进入下面那个块之前就 `return` 了。
            val restoredTop = browseLocationOf(nav.currentBackStackEntry)
            if (restoredTop != null) {
                BrowseScrollPositions.position.startupLanding(BrowseScrollLayer(restoredTop.connId, restoredTop.containerId))
            } else {
                BrowseScrollPositions.position.startupLanding(null)
            }
            syncBrowseHistory(history, nav)
            PerfTiming.log { navObservationLine(NavEvent.STARTUP_SKIP, nav, history) }
            return@LaunchedEffect
        }
        startupDone.value = true
        // 落地全过程兜底：判定、建会话、导航任一步抛预期外异常时，用户会停在一个抽屉手势
        // 已关、页上无控件的中转页——那是应用内没有出口的死页（改前至少还能划开抽屉自救）。失败一律降级只落首页。
        // 取消（组合销毁/配置变更）必须照常传播、且不在取消后做任何导航：靠 [catchingNonCancellation] 而非
        // 事后在 onFailure 里补抛（内层裸 runCatching 会在更早的地方就把取消吞掉）。
        catchingNonCancellation {
            // 本次「已定的落地层」**先按非浏览层**交回一句默认值。落到浏览层的那个支随后用
            // `startupLanding` 覆盖它（`startupLanding` 同时置 `landingDecided = true`）。这样「必须交回」的义务只剩
            // 块首这一处：新增非浏览落点支不必记得补一句，漏调即停在「还没交回」、静默失效的那种缺陷消失。
            // 边界：交回默认值之后、浏览支的 `startupLanding` 之前抛异常时，store 仍按非浏览层收口（正确）；
            // 而**浏览支交回浏览层之后**再抛异常（如 [landStartupBrowserLayer] 失败）时，下面 `onFailure` 会**回改**
            // 为非浏览层 ⇒ 那条记录照样按 B 案丢弃。代价是「该层已上屏之后才失败」时，它随后的
            // 查询按「非落地层」收口（位置丢掉）——取舍见 `onFailure` 处注释。
            BrowseScrollPositions.position.startupLanding(null)
            val resolved = prepareStartup(startTarget)
            // 启动还原回落到浏览层/首页时告知用户为何没回到上次那本书（非阻塞，不改目的地）
            resolved.notice?.let { Toast.makeText(context, it, Toast.LENGTH_SHORT).show() }
            when (val target = resolved.target) {
                // 三个顶层落点（首页/书柜/设置）走同一处落地：
                // 上次停在它**之下**的那段浏览链一并重建——从这条顶层路由返回因此先逐级回到那段链
                // （例如进入设置前的那个子文件夹），链走完才回到根首页、首页再返回才退出 APP。
                // 链为空（旧数据 / 本次落点不是上次停的那条顶层路由）时与改前口径逐字一致。
                // 路由从 [topLevelRouteOf] 取（与 `prepareStartup` 同一份映射；加入口时另需同步这里的枚举与
                // 写侧 `TOP_LEVEL_ROUTES`）。`?: Routes.HOME` 是**零成本地板**、不是可达路径：本支已吃掉
                // `OpenBrowser` / `OpenReader` 两支，[topLevelRouteOf] 对这三个目标必非 null（映射与枚举
                // 不同步的状态编译不过），留着只为「将来改错时宁可落首页，也不把用户留在抽屉手势已关、
                // 页上无控件的中转页（死页）」。
                StartupTarget.OpenHome, StartupTarget.OpenBookshelf, StartupTarget.OpenSettings -> {
                    // 本支落地层是非浏览层（顶层路由）：块首那句默认 `startupLanding(null)` 已按此交回，
                    // 这里不再重复。
                    // 先把「首页」作为根，目的地压在其上：返回语义与常规导航一致（按支调用）
                    pushStartupRootHome(nav)
                    landStartupTopLevel(nav, history, topLevelRouteOf(target) ?: Routes.HOME, resolved.chain)
                }
                is StartupTarget.OpenBrowser -> {
                    // 与入口一致：把恢复到的位置作为当前浏览位置；**整条层级链**一起重建
                    //（只恢复一层的话，重启后返回只剩「回首页」一条路——追加口径的现象 A）。
                    // 只恢复目录层级；排序是全局设置本就保持。滚动位置由 `ui/BrowseScrollPosition` 单独落盘一份，
                    // 重启落回**同一层**时恢复（`[BrowseLocation]` 仍不含位置——它只记目录层级）。
                    val browsing = target.browsing
                    val path = startupBrowsePath(
                        StartupStore.browsingPath(),
                        BrowseLocation(browsing.connId, browsing.containerId),
                    )
                    // 硬切先落快照：目标就是本次要显示的那个浏览层（同支上面的 [browsing]，直接值），
                    // 而冷启动会话内存是空的——不预置的话它头几帧渲染的是「加载中…」，
                    // 磁盘快照要等新屏自己的两段式 effect 才上屏（要治的就是这个空窗）。
                    // 不用 `path.lastOrNull()` 反推：那靠 [com.cc3301.comicviewer.ui.nav.startupBrowsePath] 的顺序不变量，而「预置键写错层」
                    // 是硬故障，能取直接值就不引这份隐式依赖。链里更下面的层不当帧组合（只栈顶那项组合），
                    // 它们回到屏上的路径是**系统返回**，不在这里。
                    // **压首页与压浏览链整段**在那一个临界区里（[landStartupBrowserLayer] 的
                    // 「预置与导航同一临界区」）——预置的挂起读因此不再夹在两跳之间，首页不会被组合出一帧。
                    // **落地层交回 store**。落到浏览层的入口有**两个**——本支，以及同一 effect 上面那条
                    // 「进程被杀后重建」早退支（回退栈由系统还原、还原出的那层当帧就是栈顶；它也在交回）。
                    // 本支吃掉正常「上次停留的位置」与启动链的两条退化支（「不是书」回落、连接来源拿不到回落）；
                    // 交接后 `BrowseScrollPositions.position` 不再自己按 `startupTarget()` 二次推导落地层
                    // （那条推导会把退化支的落地层误判为「非落地层」而销毁记录）。本句覆盖块首那句默认的
                    // `startupLanding(null)`——落地层默认按非浏览层交回，全块只此一处覆盖。
                    BrowseScrollPositions.position.startupLanding(BrowseScrollLayer(browsing.connId, browsing.containerId))
                    landStartupBrowserLayer(
                        nav = nav,
                        history = history,
                        path = path,
                        source = ServiceLocator.browsingSourceIfResolved(browsing.connId),
                        containerId = browsing.containerId,
                    )
                }
                is StartupTarget.OpenReader -> {
                    // 本次落地层是**阅读器**（非浏览层）：块首那句默认 `startupLanding(null)` 已按此交回
                    // 顺序仍成立——默认在块首，早于本支把恢复链里的浏览层压到栈上。
                    // 先把「首页」作为根（按支调用；这一支与顶层落点支都是同步连压，不闪）
                    pushStartupRootHome(nav)
                    // 返回手势落到浏览列表（与抽屉「阅读器」入口一致）：把上次停留位置及其上级压到阅读器之下
                    val browsing = StartupStore.lastBrowsing()?.takeIf { it.connId == target.lastRead.connId }
                    val path = browsing
                        ?.let { startupBrowsePath(StartupStore.browsingPath(), BrowseLocation(it.connId, it.containerId)) }
                        .orEmpty()
                    resetBrowseHistoryForStartup(history, path)
                    pushBrowserPath(nav, path)
                    // 冷启动直进阅读器也走同一条前置路——**导航立刻发生**（这一屏切进阅读器），
                    // 「打开书 + 首批解好」由 [OpenBookEntry] 在会话级作用域里继续跑，阅读页侧有界等它
                    // （≤1.5s，到点自己开书）。不再有「先把书打开、首批解好再切页」的等待。
                    // 冷启动直进阅读器与普通进档同款滑入（前后路由判，不需要入口传例外），
                    // 守卫与另两条入口统一到同一套（[ReaderEntryRequest]）——发起时记下栈顶那一项
                    // （这里是刚压上的浏览层），等待窗口里用户走开（返回 / 切屏）就不再导航；
                    // 另外保留组合存活标志（这条等待挂在 `LaunchedEffect` 上，与浏览页点击路径同一手法）。
                    val request = readerEntryRequest.beginGuard(nav, alsoAlive = { startupEffectAlive })
                    openBook.open(
                        target = OpenBookTarget(
                            // 阅读器路由读的就是会话当前来源（见 prepareStartup 的 OpenReader 分支：先备好再导航）
                            source = ServiceLocator.currentSource,
                            connId = target.lastRead.connId,
                            bookId = target.lastRead.bookId,
                        ),
                        guard = request,
                        enterReader = {
                            hideReaderSystemBars()
                            navigateStartupReader(nav, target.lastRead.bookId)
                        },
                    )
                }
            }
            // 落地完成后把历史镜像对齐到实际栈：上面各支重建的层与实际压上的层一一对应，
            // 不一致（如栈里已有还原出来的层、[pushBrowserPath] 跳过了其中几层）时以栈为准。
            syncBrowseHistory(history, nav)
            PerfTiming.log { navObservationLine(NavEvent.STARTUP_LAND, nav, history) }
        }.onFailure {
            // 降级落点只有首页（没有更好的地方可去）；兜底本身再失败也没有别的办法，不能让它把协程带崩。
            // 落地层在这里**再交回一次**（回到原先的行为）：本支的真实落点是首页（非浏览层），而 store
            // 可能已被浏览支那句 `startupLanding` 改成那个浏览层（`landStartupBrowserLayer` 压栈途中抛异常时）——不回改的话
            // 那条记录不会被丢弃，用户随后走进该层会恢复上一会话的位置，与 `docs/spec/browsing.md` 的
            // 「落地层不是记录那一层 ⇒ 当场丢弃」不符。异常若发生在浏览层**已上屏之后**，本句会让该层随后的查询
            // 按「非落地层」收口（记录位置丢掉）——取舍：让 B 案在失败支同样生效。
            BrowseScrollPositions.position.startupLanding(null)
            runCatching { pushStartupRootHome(nav) }
            PerfTiming.log { navObservationLine(NavEvent.STARTUP_FALLBACK, nav, history) }
        }
    }

    // 上次退出时是否正停在阅读器（spec 故事 47 的退化条件）：路由一变即落盘，进程被杀也留得住。
    // 中转页与路由未定的那一帧不写：判定要读的正是上一会话落下的值。
    // 同一帧顺手维护「顶层落点记录」：首页/书柜/设置记下、浏览层/阅读器清掉、其余不动——
    // 不记它的话，在首页退出后启动只会读到很久以前那个浏览目录。
    LaunchedEffect(currentRoute) {
        readingFlagToRecord(currentRoute)?.let { StartupStore.recordReading(it) }
        recordTopLevelForRoute(currentRoute, nav)
    }

    // 阅读器沉浸：系统栏可见性**只由当前路由这一处事实直接决定**。
    // 曾在这里多留一个过渡窗口（`ReaderImmersiveBarsState`）以避开「黑底阅读页还在往外滑、栏先冒出来」，
    // 设备验收把它否了：从阅读器返回时顶部系统 UI 会一直藏着、约 0.5s 后才突然蹦出来（比早出来更刺眼）。
    // 现在回到按路由直接判：返回动作一开始就恢复系统栏。
    ReaderImmersiveSystemBars(immersive = currentRoute == Routes.READER)

    // ---------- 根路由「再按一次退出」 ----------
    // 只在**真正停在首页根路由、且抽屉没开着**时接管返回：子层级（浏览页/阅读器/书柜/设置）各有自己的返回语义
    // （见 `BrowserScreen` / `ReaderScreen` 的 BackHandler），抽屉开着时返回归抽屉自己（关抽屉）。
    // 判据是纯函数 [atRootRoute]（读一次 currentEntry 建立重组依赖，其余两个事实取同一帧的快照；由 RootBackExitStateTest 锁定）。
    val atRoot = currentEntry != null && atRootRoute(nav)
    // 本 BackHandler 挂在 `AppDrawer` **之前**（抽屉自己的返回接管因此排在它后面——返回回调「后注册先派发」），
    // 但**注册顺序只解决「这个根处理器 vs 抽屉」这一段**：抽屉 content 槽里各屏的处理器注册得更晚，会抢在抽屉之前
    // 拿到返回（设备现象）。内容层那条口径不靠顺序，靠它们自己让位（`LocalDrawerIsClosed`，见 AppDrawer.kt）。
    // 状态机按「接管条件」重建（remember 的键）⇒ 离开根路由或抽屉开合即复位（「超时、或离开根路由 → 状态重置」）。
    val rootBackExit = remember(atRoot, drawerState.isClosed) { RootBackExitState() }
    BackHandler(enabled = atRoot && drawerState.isClosed) {
        when (rootBackExit.onBack(SystemClock.uptimeMillis())) {
            // 轻量提示、不打断操作；文案与 SPEC 的「返回逐级」段一致
            RootBackAction.PROMPT -> Toast.makeText(context, "再按一次退出", Toast.LENGTH_SHORT).show()
            // 退出走 Activity finish：会话级清理（`MainActivity.onDestroy` 的 `isFinishing` 分支）随之跑。
            // 拿不到 Activity（非 Activity 宿主/preview）时无处可退，保持不动作
            RootBackAction.EXIT -> (context as? Activity)?.finish()
        }
    }

    // 前进历史（spec 故事 37）：鼠标前进侧键专用实现（抽屉不再有前进入口）。
    // 目标固定由浏览历史给出（历史里没有阅读器），因此前进不会把用户带回阅读器（spec Out of Scope）。
    // 这一跳也可能是硬切（浏览层 → 浏览层）；**从阅读器过来时是滑动档（出阅读器）**，这条预置对两种都
    // 一样有用（都是「目标层得先有内容」），所以不按过渡档分支。先垫**目标层**（= 这次前进到的 containerId，不是当前层），
    // 而且与浏览页点容器共用同一把顺序锁（[withPrimedLayer]）：两次快速前进/点击按发起顺序落地；
    // 侧键处理器是同步签名（返回 true = 已消费），所以这一跳放进组合作用域里跑（否则等不了那次本地读）。
    val forwardHistory: () -> Boolean = remember(nav) {
        {
            history.goForward()?.let {
                scope.launch {
                    withPrimedLayer(ServiceLocator.browsingSourceIfResolved(it.connId), it.containerId) {
                        nav.navigate(Routes.browser(it.connId, it.containerId, it.containerName)) { launchSingleTop = true }
                    }
                }
                true
            } ?: false
        }
    }
    RegisterSlot(ServiceLocator.forwardHistorySlot, forwardHistory)

    AppDrawer(
        drawerState = drawerState,
        currentRoute = currentRoute,
        // 阅读器内禁用边缘手势：左缘滑动留给系统返回手势（spec 故事 38）。
        // 启动中转页同样禁用：判定未完成时划出抽屉，「书柜/设置」会被随后的 popUpTo 吞掉、
        // 「阅读器」只会弹「请先选择一个来源」。
        gesturesEnabled = currentRoute != Routes.READER && currentRoute != Routes.STARTUP,
        onOpenHome = {
            closeDrawer()
            // 压在当前界面之上：返回回到进入前的界面；重复点同一入口不叠层
            navigateTopLevel(nav, Routes.HOME)
        },
        onOpenReader = {
            closeDrawer()
            val connId = ServiceLocator.currentConnId
            val last = ServiceLocator.lastRead
            when {
                ServiceLocator.currentSource == null || connId == null ->
                    Toast.makeText(context, "请先选择一个来源", Toast.LENGTH_SHORT).show()
                // 书 id 只在各自连接内有效：跨连接直接打开会失败
                last == null || last.connId != connId ->
                    Toast.makeText(context, "还没有阅读记录", Toast.LENGTH_SHORT).show()
                else -> scope.launch {
                    // 抽屉入口也走同一条前置路（同一套闸门）——**导航立刻发生**（滑入立刻开始），
                    // 「打开书 + 首批解好」在会话级作用域里继续跑并由阅读页侧有界等待；等页期间是主题背景色纯色。
                    // 这条等待跑在 `AppNav` 的组合作用域上（只有整个 AppNav 离开组合才取消），
                    // 「用户已经走开」因此不会被取消观察到——守卫里除了「没被后一次点击顶替」，还要
                    // 「栈顶仍是发起时那一项」。用**栈项身份**而不是路由 pattern：浏览层级
                    // （子文件夹 ↔ 父目录）是同一个 pattern，只比 pattern 时「等待里按返回回到父目录」会被误判成没离开。
                    val request = readerEntryRequest.beginGuard(nav)
                    openBook.open(
                        target = OpenBookTarget(
                            source = ServiceLocator.currentSource,
                            connId = connId,
                            bookId = last.bookId,
                        ),
                        guard = request,
                        enterReader = {
                            hideReaderSystemBars()
                            openReaderFromDrawer(nav, history, last)
                        },
                    )
                }
            }
        },
        onOpenBookshelf = {
            closeDrawer()
            navigateTopLevel(nav, Routes.BOOKSHELF)
        },
        onOpenSettings = {
            closeDrawer()
            navigateTopLevel(nav, Routes.SETTINGS)
        },
    ) {
        // 过渡期的帧时长：挂在导航壳上，因此**所有**导航过渡都进统计
        NavTransitionFrameMetrics(navTransitionProbe)
        // 路由可见性打点（取数级，零行为变化）：**组合期同步打**。
        // 为什么不用 `LaunchedEffect`：只存在**一帧**的首帧（启动落地时首页那一帧）会在协程跑起来之前
        // 就被 key 变化取消，日志因此漏行 —— 设备取数（2026-09-28：看到首页闪，日志里却没有
        // `route=home`）。
        // 代价：组合被丢弃/重建时同一栈可能重复产行（按前后行与其 `depth` 对齐即可读）。
        val routeLogMemo = remember { RouteLogMemo() }
        val stackIds = nav.currentBackStack.value.map { it.id }
        if (routeLogMemo.ids != stackIds) {
            routeLogMemo.ids = stackIds
            PerfTiming.log { navObservationLine(NavEvent.ROUTE, nav, history) }
        }
        // 这一次过渡的两件事：呈现方式（两档）与滑动档的方向——四支 lambda 都从这两处读，
        // 两屏因此不可能各写一份判据。
        fun styleOf(from: NavBackStackEntry, to: NavBackStackEntry): NavTransitionStyle = navTransitionStyle(
            initialRoute = from.destination.route,
            targetRoute = to.destination.route,
        )

        fun directionOf(from: NavBackStackEntry, to: NavBackStackEntry): NavSlideDirection? = navSlideDirection(
            initialRoute = from.destination.route,
            targetRoute = to.destination.route,
        )

        // 取数与时刻线（形状收口）：两处（`enterTransition` / `popEnterTransition`）逐字相同，提成一个局部
        // 函数。量测窗口取**这一次过渡自己的时长**，与四支过渡走同一个纯函数（两处不会漂）。
        // 硬切不开窗：时长 0 ⇒ 没有滑动窗口，也就没有帧时长可量，时刻线同样不开。
        fun beginTransition(from: NavBackStackEntry, to: NavBackStackEntry) {
            val previousRoute = from.destination.route
            val enteringRoute = to.destination.route
            val windowMillis = navTransitionWindowMillis(
                previousRoute = previousRoute,
                enteringRoute = enteringRoute,
            )
            if (windowMillis == 0) return
            beginNavTransitionProbe(navTransitionProbe, scope, windowMillis)
            NavTransitionTimeline.begin(
                navTransitionKind(previousRoute = previousRoute, enteringRoute = enteringRoute),
            ) { navTransitionDetail(previousRoute, enteringRoute) }
        }

        NavHost(
            navController = nav,
            startDestination = Routes.STARTUP,
            // 四支过渡：滑动档由 `NavHost` 自己给（新屏滑入、旧屏 alpha 恒 1 的退场过渡）；
            // 硬切那一档直接是 `None`。时长 / 曲线 / 滑动方向都只在 `ui/nav/NavTransition.kt` 那一处读。
            enterTransition = {
                beginTransition(initialState, targetState)
                navEnterMotion(styleOf(initialState, targetState), directionOf(initialState, targetState))
            },
            exitTransition = {
                navExitMotion(styleOf(initialState, targetState), directionOf(initialState, targetState))
            },
            popEnterTransition = {
                beginTransition(initialState, targetState)
                navEnterMotion(styleOf(initialState, targetState), directionOf(initialState, targetState))
            },
            popExitTransition = {
                navExitMotion(styleOf(initialState, targetState), directionOf(initialState, targetState))
            },
        ) {
            // 启动中转页：异步解析（首次开库/建来源会话）期间不会先露出首页再跳走；
            // 给出进度指示而不是空屏，抽屉手势同时关闭（见 AppDrawer 的 gesturesEnabled）
            composable(Routes.STARTUP) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            }
            composable(Routes.HOME) {
                HomeScreen(nav, ::openDrawer)
            }
            composable(Routes.LOCAL_ROOTS) {
                LocalRootsScreen(nav, ::openDrawer)
            }
            composable(
                route = Routes.CONNS,
                arguments = listOf(navArgument("sourceType") { type = NavType.StringType }),
            ) { entry ->
                val name = entry.arguments?.getString("sourceType")
                val type = runCatching { SourceType.valueOf(name ?: "") }.getOrNull()
                if (type == null) {
                    LaunchedEffect(Unit) { nav.popBackStack() }
                } else {
                    when (type) {
                        // SMB 入口保留专名包装（README/日志好认），实现与 WebDAV 完全共用
                        SourceType.SMB -> SmbConnectionsScreen(nav, ::openDrawer)
                        else -> SourceConnectionsScreen(type, nav, ::openDrawer)
                    }
                }
            }
            composable(Routes.SETTINGS) {
                SettingsScreen(::openDrawer)
            }
            composable(Routes.BOOKSHELF) {
                BookshelfScreen(nav, ::openDrawer)
            }
            // `name` 是这一层的条目名：带默认值是为了让**没带名字**的旧数据/深链仍能匹配上本路由，
            // 匹配上之后标题退到会话缓存、再退到 id 末段（与改前一致，见 `browserTitle`）。
            composable(
                route = Routes.BROWSER,
                arguments = listOf(
                    navArgument("name") {
                        type = NavType.StringType
                        defaultValue = ""
                    },
                ),
            ) { entry ->
                val location = browseLocationOf(entry)
                if (location == null) {
                    // 组合期不可直接导航：包装进 LaunchedEffect
                    LaunchedEffect(Unit) { nav.popBackStack() }
                } else {
                    BrowserScreen(nav, location.connId, location.containerId, location.containerName, ::openDrawer)
                }
            }
            composable(Routes.READER) { entry ->
                // navigation 已自动解码参数，不再手动 Uri.decode（双重解码会损坏含 % 的 id）
                val bookId = entry.arguments?.getString("bookId")
                val source = ServiceLocator.currentSource
                if (bookId == null || source == null) {
                    LaunchedEffect(Unit) { nav.popBackStack() }
                } else {
                    // 读内换书的前置：**导航立刻发生**（换到新书的新 entry），当前这本的旧 entry
                    // 随之出场；「新书打开 + 首批解好」在会话级作用域里继续跑、由新阅读页有界等待。
                    // 守卫同抽屉入口那一套（[ReaderEntryRequest]）。
                    val swapScope = rememberCoroutineScope()
                    // 连点同一本不重启（值没变就不领新请求）；换点另一本才领
                    var swapBookId by remember { mutableStateOf<String?>(null) }
                    ReaderScreen(
                        bookId = bookId,
                        source = source,
                        // 前置槽的键：与写入口（浏览页点击路径）用的连接 id 同源
                        connId = ServiceLocator.currentConnId,
                        onOpenBook = { newBookId ->
                            // 读内换书（菜单上一本/下一本、跨书确认条）：导航到新的阅读页 entry 立刻发生，
                            // 「开书 + 解首批」由 [OpenBookEntry] 在那之后继续跑；
                            // 「上次阅读位置」由那一页切进去时写（全仓唯一写入点，不在这里写）
                            // 换书是**硬切**（没有滑动、也没有方向），入口因此不再传方向。
                            // 守卫改用单调 token（不再用值相等——A→B→A 三连点后值相等会让**旧** A 请求
                            // 重新算数，与新 A 请求各导航一次：同一本书被切两次、第二次取不到前置槽）。
                            if (swapBookId != newBookId) {
                                swapBookId = newBookId
                                val request = readerEntryRequest.beginGuard(nav)
                                swapScope.launch {
                                    openBook.open(
                                        target = OpenBookTarget(
                                            source = source,
                                            connId = ServiceLocator.currentConnId,
                                            bookId = newBookId,
                                        ),
                                        guard = request,
                                        enterReader = {
                                            nav.navigate(Routes.reader(newBookId), newReaderNavOptions())
                                        },
                                    )
                                }
                            }
                        },
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(nav: NavHostController, onOpenDrawer: () -> Unit) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { TopBarTitle(stringResource(R.string.app_name)) },
                navigationIcon = { DrawerMenuButton(onOpenDrawer) },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            // 四个已实装来源（OPDS 已下线）：本地进根目录列表，其余进各自的连接列表
            listOf(
                SourceType.LOCAL to "本地",
                SourceType.SMB to "SMB",
                SourceType.WEBDAV to "WebDAV",
                SourceType.KOMGA to "Komga",
            ).forEach { (type, label) ->
                SourceRow(label) {
                    when (type) {
                        SourceType.LOCAL -> nav.navigate(Routes.LOCAL_ROOTS)
                        SourceType.SMB, SourceType.WEBDAV, SourceType.KOMGA -> nav.navigate(Routes.conns(type))
                    }
                }
            }
        }
    }
}

/** 首页的来源入口行（OPDS 已下线，四个入口全可用：`enabled` 恒真的禁用分支已删） */
@Composable
private fun SourceRow(label: String, onClick: () -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(text = label, style = MaterialTheme.typography.bodyLarge)
        }
    }
}
