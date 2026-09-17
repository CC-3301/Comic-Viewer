package com.cc3301.comicviewer.ui

import android.widget.Toast
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.cc3301.comicviewer.R
import com.cc3301.comicviewer.core.nav.BrowseLocation
import com.cc3301.comicviewer.core.nav.LastRead
import com.cc3301.comicviewer.core.nav.StartupTarget
import com.cc3301.comicviewer.core.nav.fallbackWhenConnectionMissing
import com.cc3301.comicviewer.core.source.SourceType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

object Routes {
    /** 启动判定期间的中转页（票 20）：解析完成后立即被 popUpTo 移除 */
    const val STARTUP = "startup"
    const val HOME = "home"
    const val LOCAL_ROOTS = "localRoots"

    /** 网络来源连接管理（票 11/12）：按来源类型参数化，SMB 与 WebDAV 共用同一界面 */
    const val CONNS = "conns/{sourceType}"

    fun conns(type: SourceType): String = "conns/" + type.name

    const val SETTINGS = "settings"

    /** 书柜柜列表与单个柜（票 17，spec 故事 43/44） */
    const val BOOKSHELF = "bookshelf"
    const val SHELF = "shelf/{connId}"

    fun shelf(connId: Long): String = "shelf/$connId"

    const val BROWSER = "browser/{connId}?container={container}"
    const val READER = "reader/{bookId}"

    fun browser(connId: Long, containerId: String?): String =
        "browser/$connId?container=${android.net.Uri.encode(containerId ?: "")}"

    fun reader(bookId: String): String =
        "reader/${android.net.Uri.encode(bookId)}"
}

/** 导航壳（票 04）：首页 → 本地根列表 → 浏览 → 条漫阅读器 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppNav() {
    val nav = rememberNavController()
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val currentRoute = nav.currentBackStackEntryAsState().value?.destination?.route
    val history = ServiceLocator.browseHistory

    fun closeDrawer() {
        scope.launch { drawerState.close() }
    }

    fun openDrawer() {
        scope.launch { drawerState.open() }
    }

    // ---------- 启动页面（票 20，spec 故事 46-49）----------
    // 判定与落盘状态读取、来源解析、会话准备与导航都在下面的 LaunchedEffect 里：组合期不做同步
    // SharedPreferences 读（票 26 第 6 项），也绝不改写 ServiceLocator.currentSource/currentConnId（#18 复核纪律）。

    /**
     * 慢操作（按 id 取连接、建来源会话）在导航前完成，返回真正落地目的地——中转页期间不会先露出别的界面。
     * 阅读器建不起会话（连接已删/离线）时退化：上次停留的位置 → 首页。
     * 连接已不存在（票 33：OPDS 用户迁移后被清库、用户手工删连接）时回落首页——
     * 浏览页对不存在的连接只会停在「加载中…」。
     */
    suspend fun prepareStartup(target: StartupTarget): StartupTarget = when (target) {
        is StartupTarget.OpenBrowser -> {
            val row = withContext(Dispatchers.IO) {
                runCatching { ServiceLocator.db.connectionDao().byId(target.browsing.connId) }
            }
            val conn = row.getOrNull()
            // 与常规入口（本地根列表/连接列表）一致：先备会话来源，抽屉「阅读器」入口才能打开上次阅读的书；
            // 会话级实例（票 #30 P1）：随后的浏览页复用同一个，列表缓存跨页面存活
            if (conn == null) {
                // 这条「上次停留的位置」恢复不了了，顺手清掉（票 26 第 2 项）：留着只会让每次启动都重走一遍
                //「先导航到浏览页再弹回」。读库本身失败（isFailure）不清——那是暂时性故障，不等于连接被删
                if (row.isSuccess) StartupStore.clearBrowsing()
                fallbackWhenConnectionMissing(target)
            } else {
                // 会话建不起来不阻断——浏览页会按路由 connId 自行解析并显示重试
                runCatching { withContext(Dispatchers.IO) { ServiceLocator.browsingSourceFor(conn) } }
                    .onSuccess {
                        ServiceLocator.currentSource = it
                        ServiceLocator.currentConnId = target.browsing.connId
                    }
                target
            }
        }
        is StartupTarget.OpenReader -> {
            val last = target.lastRead
            val conn = withContext(Dispatchers.IO) {
                runCatching { ServiceLocator.db.connectionDao().byId(last.connId) }.getOrNull()
            }
            val source = conn?.let {
                runCatching { withContext(Dispatchers.IO) { ServiceLocator.browsingSourceFor(it) } }.getOrNull()
            }
            if (source == null) {
                val browsing = StartupStore.lastBrowsing()
                if (browsing != null) prepareStartup(StartupTarget.OpenBrowser(browsing)) else StartupTarget.OpenHome
            } else {
                // 阅读器路由只认会话来源 + lastRead（与柜页「打开书」同一手法）：先备好再导航
                ServiceLocator.currentSource = source
                ServiceLocator.currentConnId = last.connId
                ServiceLocator.lastRead = last
                target
            }
        }
        StartupTarget.OpenBookshelf, StartupTarget.OpenHome -> target
    }

    // 只在真正的冷启动落地一次：配置变更/进程恢复时 NavController 会还原回退栈，不重复导航
    val startupDone = rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        // 落盘状态必须在**第一次挂起之前**同步读完（票 26 r2 修正 1）：下面那个
        // LaunchedEffect(currentRoute) { StartupStore.recordReading(...) } 会在 STARTUP 上写 was_reading=false，
        // 两个 effect 同在主线程、按源码顺序启动——只有「先同步读、再挂起」才能保证本会话的读一定早于本会话的任何写。
        // 否则在阅读器里退出（已落盘 was_reading=true）后冷启动可能把状态读成 false，故事 47「直接打开上次那本书」
        // 就丢了（并且 ServiceLocator.lastRead = last 也不会执行）。重活（按 id 取连接/建来源会话）照旧在 IO 上做。
        val startTarget = StartupStore.startupTarget()

        // 落地只做一次，但「做过」不能只看 startupDone（票 26 r2 修正 2）：rememberSaveable 只说明本会话
        // 标记过，不代表导航真的落地了——慢来源冷启动期间旋转屏幕、进程被杀后回到前台时，NavController 会还原出
        // 一个仍停在 STARTUP 的栈顶，必须补跑一次判定与导航，否则该页没有出口（抽屉手势已关、页上无控件）。
        // 组合后 currentDestination 必然已就绪；万一为空按「未落地」处理——宁可补跑一次，也不留死页。
        if (startupDone.value && nav.currentDestination?.route != Routes.STARTUP) return@LaunchedEffect
        startupDone.value = true
        val target = prepareStartup(startTarget)
        // 先把「首页」作为根，目的地压在其上：返回语义与常规导航一致
        nav.navigate(Routes.HOME) { popUpTo(Routes.STARTUP) { inclusive = true } }
        when (target) {
            StartupTarget.OpenHome -> Unit
            StartupTarget.OpenBookshelf -> nav.navigate(Routes.BOOKSHELF) { launchSingleTop = true }
            is StartupTarget.OpenBrowser -> {
                // 与入口一致：换连接先清历史，再把恢复到的位置作为当前浏览位置
                // （只恢复目录层级；排序是全局设置本就保持，滚动位置不恢复，SPEC Out of Scope）
                val browsing = target.browsing
                if (ServiceLocator.browseHistory.current?.connId != browsing.connId) {
                    ServiceLocator.browseHistory.clear()
                }
                ServiceLocator.browseHistory.record(
                    BrowseLocation(browsing.connId, browsing.containerId),
                )
                nav.navigate(Routes.browser(browsing.connId, browsing.containerId)) { launchSingleTop = true }
            }
            is StartupTarget.OpenReader -> {
                // 返回手势落到浏览列表（与抽屉「阅读器」入口一致）：把上次停留位置压在阅读器下面
                val browsing = StartupStore.lastBrowsing()?.takeIf { it.connId == target.lastRead.connId }
                if (browsing != null) {
                    ServiceLocator.browseHistory.record(
                        BrowseLocation(browsing.connId, browsing.containerId),
                    )
                    nav.navigate(Routes.browser(browsing.connId, browsing.containerId)) { launchSingleTop = true }
                }
                nav.navigate(Routes.reader(target.lastRead.bookId)) { launchSingleTop = true }
            }
        }
    }

    // 上次退出时是否正停在阅读器（spec 故事 47 的退化条件）：路由一变即落盘，进程被杀也留得住
    LaunchedEffect(currentRoute) {
        StartupStore.recordReading(currentRoute == Routes.READER)
    }

    // 前进历史（票 17，spec 故事 37）：鼠标前进侧键专用实现（票 32 起抽屉不再有前进入口）。
    // 目标固定由浏览历史给出（历史里没有阅读器），因此前进不会把用户带回阅读器（spec Out of Scope）。
    val forwardHistory: () -> Boolean = remember(nav) {
        {
            history.goForward()?.let {
                nav.navigate(Routes.browser(it.connId, it.containerId)) { launchSingleTop = true }
                true
            } ?: false
        }
    }
    DisposableEffect(forwardHistory) {
        ServiceLocator.forwardHistoryHandler = forwardHistory
        onDispose {
            if (ServiceLocator.forwardHistoryHandler === forwardHistory) ServiceLocator.forwardHistoryHandler = null
        }
    }

    AppDrawer(
        drawerState = drawerState,
        currentRoute = currentRoute,
        // 阅读器内禁用边缘手势：左缘滑动留给系统返回手势（spec 故事 38）。
        // 启动中转页同样禁用（票 26 第 1 项）：判定未完成时划出抽屉，「书柜/设置」会被随后的 popUpTo 吞掉、
        // 「阅读器」只会弹「请先选择一个来源」。
        gesturesEnabled = currentRoute != Routes.READER && currentRoute != Routes.STARTUP,
        onOpenHome = {
            closeDrawer()
            // 单实例语义（票 32）：已在首页时重复点不再压一层
            nav.navigate(Routes.HOME) { launchSingleTop = true }
        },
        onOpenReader = {
            closeDrawer()
            val connId = ServiceLocator.currentConnId
            val last = ServiceLocator.lastRead
            when {
                ServiceLocator.currentSource == null || connId == null ->
                    Toast.makeText(context, "请先选择一个来源", Toast.LENGTH_SHORT).show()
                // 书 id 只在各自连接内有效：跨连接直接打开会失败（review P1-1）
                last == null || last.connId != connId ->
                    Toast.makeText(context, "还没有阅读记录", Toast.LENGTH_SHORT).show()
                else -> {
                    // 返回手势要落到浏览列表（AC3）：先把当前浏览位置压到其下
                    history.current?.takeIf { it.connId == last.connId }?.let {
                        nav.navigate(Routes.browser(it.connId, it.containerId)) { launchSingleTop = true }
                    }
                    nav.navigate(Routes.reader(last.bookId)) { launchSingleTop = true }
                }
            }
        },
        onOpenBookshelf = {
            closeDrawer()
            nav.navigate(Routes.BOOKSHELF) { launchSingleTop = true }
        },
        onOpenSettings = {
            closeDrawer()
            nav.navigate(Routes.SETTINGS) { launchSingleTop = true }
        },
    ) {
        NavHost(navController = nav, startDestination = Routes.STARTUP) {
            // 启动中转页：异步解析（首次开库/建来源会话）期间不会先露出首页再跳走；
            // 给出进度指示而不是空屏（票 26 第 1 项），抽屉手势同时关闭（见 AppDrawer 的 gesturesEnabled）
            composable(Routes.STARTUP) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            }
            composable(Routes.HOME) { HomeScreen(nav, ::openDrawer) }
            composable(Routes.LOCAL_ROOTS) { LocalRootsScreen(nav, ::openDrawer) }
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
            composable(Routes.SETTINGS) { SettingsScreen(::openDrawer) }
            composable(Routes.BOOKSHELF) { BookshelfScreen(nav, ::openDrawer) }
            composable(Routes.SHELF) { entry ->
                val connId = entry.arguments?.getString("connId")?.toLongOrNull()
                if (connId == null) {
                    LaunchedEffect(Unit) { nav.popBackStack() }
                } else {
                    CabinetScreen(nav, connId, ::openDrawer)
                }
            }
            composable(Routes.BROWSER) { entry ->
                val connId = entry.arguments?.getString("connId")?.toLongOrNull()
                val container = entry.arguments?.getString("container")?.takeIf { it.isNotEmpty() }
                if (connId == null) {
                    // 组合期不可直接导航：包装进 LaunchedEffect（review P2）
                    LaunchedEffect(Unit) { nav.popBackStack() }
                } else {
                    BrowserScreen(nav, connId, container, ::openDrawer)
                }
            }
            composable(Routes.READER) { entry ->
                // navigation 已自动解码参数，不再手动 Uri.decode（review P1：双重解码损坏含 % 的 id）
                val bookId = entry.arguments?.getString("bookId")
                val source = ServiceLocator.currentSource
                if (bookId == null || source == null) {
                    LaunchedEffect(Unit) { nav.popBackStack() }
                } else {
                    ReaderScreen(
                        bookId = bookId,
                        source = source,
                        onOpenBook = { newBookId ->
                            // 读内换书（菜单上一本/下一本、跨书确认条）也要更新上次阅读的位置（review P1-1）
                            ServiceLocator.currentConnId?.let { ServiceLocator.lastRead = LastRead(it, newBookId) }
                            nav.navigate(Routes.reader(newBookId)) { launchSingleTop = true }
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
                title = { Text(stringResource(R.string.app_name)) },
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
            // 四个已实装来源（票 33 起 OPDS 已下线）：本地进根目录列表，其余进各自的连接列表
            listOf(
                SourceType.LOCAL to "本地",
                SourceType.SMB to "SMB",
                SourceType.WEBDAV to "WebDAV",
                SourceType.KOMGA to "Komga",
            ).forEach { (type, label) ->
                SourceRow(label, enabled = true) {
                    when (type) {
                        SourceType.LOCAL -> nav.navigate(Routes.LOCAL_ROOTS)
                        SourceType.SMB, SourceType.WEBDAV, SourceType.KOMGA -> nav.navigate(Routes.conns(type))
                    }
                }
            }
        }
    }
}

@Composable
private fun SourceRow(label: String, enabled: Boolean, onClick: () -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled, onClick = onClick),
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(text = label, style = MaterialTheme.typography.bodyLarge)
        }
    }
}
