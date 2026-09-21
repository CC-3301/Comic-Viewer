package com.cc3301.comicviewer.ui

import android.widget.Toast
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
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
import com.cc3301.comicviewer.core.nav.BrowseHistory
import com.cc3301.comicviewer.core.nav.BrowseLocation
import com.cc3301.comicviewer.core.nav.LastBrowsing
import com.cc3301.comicviewer.core.nav.LastRead
import com.cc3301.comicviewer.core.nav.StartupTarget
import com.cc3301.comicviewer.core.nav.fallbackWhenConnectionMissing
import com.cc3301.comicviewer.core.source.PerfTiming
import com.cc3301.comicviewer.core.source.SortMode
import com.cc3301.comicviewer.core.source.Source
import com.cc3301.comicviewer.core.source.SourceType
import com.cc3301.comicviewer.core.source.isNotABook
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

    /** 书柜柜列表（票 17；票 #31 重定义为按连接分柜，spec 故事 43/44） */
    const val BOOKSHELF = "bookshelf"

    const val BROWSER = "browser/{connId}?container={container}"
    const val READER = "reader/{bookId}"

    fun browser(connId: Long, containerId: String?): String =
        "browser/$connId?container=${android.net.Uri.encode(containerId ?: "")}"

    /** 浏览根层（containerId 为空）：书柜点连接与首页点连接落到同一处（票 #49） */
    fun browserRoot(connId: Long): String = browser(connId, null)

    fun reader(bookId: String): String =
        "reader/${android.net.Uri.encode(bookId)}"
}

/**
 * 打开某本书（读内换书 / 抽屉「阅读器」入口）的导航选项（票 #68）：**换一条新的 back stack entry**，
 * 不沿用上一条（`launchSingleTop` 会沿用）。
 *
 * 阅读页的宿主态（页位/页边界表/按页缩放表/菜单/跨书确认条）与保存态（`rememberSaveable`）都挂在 entry 上：
 * 沿用同一条 entry 时（书 id 只存在于参数里），页位正确性只剩「Compose 分槽键 + 保存态桶」这一层兜底，
 * 而这层在真机上没能兜住（验收开着开关换书仍回到上一本页位）。这里改成结构上必然换 entry：
 * 新的 entry id → 新的组合槽位与 ViewModel/SaveableState 桶 → 页位/缩放/菜单随之整体重建。
 * 《谁是承重机制》的口径写在 `ReaderScreen` 的分槽注释里：这一层是承重的，`key(bookId)` 只是兜底。
 *
 * 导航语义不变：栈里仍只有一条阅读器 entry（换书不加深回退栈），回退仍落到浏览层。
 * 语义由 [ReaderSwapNavTest] 锁定（它用与生产同名的路由图跑真实的 `NavController`；**生产调用点本身**
 * 不被它覆盖——`AppNav` 里两处 `navigate(..., newReaderNavOptions())` 改回 `launchSingleTop` 时该用例仍绿，
 * 见该文件的 KDoc）。
 */
internal fun newReaderNavOptions(): NavOptions = navOptions { popUpTo(Routes.READER) { inclusive = true } }

/**
 * 全局页面过渡（票 #107）：四支全部显式声明为零时长、不绘制退场内容。
 *
 * `NavHost` 不声明过渡时走 navigation-compose 内建默认 `fadeIn(tween(700))` / `fadeOut(tween(700))`，
 * 本票两个现象都出在它：
 * - 「返回要等约 1s」（原 #98）：从阅读器返回时阅读器瞬间消失（READER 路由的 `popExitTransition`），
 *   被返回的浏览页却走默认 700ms 淡入 ⇒ 这 700ms 屏上没有内容。只在阅读器路径可感知，是因为其余页面之间
 *   返回时旧页淡出与新页淡入交叉，屏上始终有内容。
 * - 「返回后立刻点 B 却打开 A 的子文件夹」（原 #99）：被弹掉的浏览页在 `fadeOut(700)` 期间仍被绘制、
 *   **仍接收点击**，立刻点屏幕同坐标的 B 就落到旧页面的条目上。
 * 四支都设为零时长后过渡窗口消失（连带效果：全局导航不再有 700ms 淡入——本票选择时已说明，是有意为之）。
 *
 * 接缝：四支的值集中在这里，由 [NavTransitionsTest] 断言每条都是「无动画」值。路由级过渡属性在
 * navigation-compose 2.8.1 里是 `internal`（本模块读不到），而「`NavHost` 调用点是否真的接上本对象」属组合期
 * 行为、仓库无 Compose UI 测试基建——那条缝的真机判定方法写在 `NavTransitionsTest` 的 KDoc 里。
 */
internal object NavTransitions {
    val enter: EnterTransition = EnterTransition.None
    val exit: ExitTransition = ExitTransition.None
    val popEnter: EnterTransition = EnterTransition.None
    val popExit: ExitTransition = ExitTransition.None
}

/**
 * 启动落地要恢复的浏览路径（票 #70 r2 AC11，纯函数）：落盘路径与本次恢复到的位置**一致**时整条用，
 * 否则只恢复这一层。
 *
 * **术语**（票 #70 r2 评审 P2-3）：本处的「浏览路径」指**用户停留的层级链**（浏览页逐层下钻留下的那串位置）；
 * 与 `CONTEXT.md` 里连接的 `browsePath`（进连接后从哪一层开始，见 `KomgaConnectionConfig.browsePath`）同词不同义。
 *
 * 为什么不是无条件用落盘路径：路径只在**会话结束**（Activity finish）时落盘，而「上次停留的位置」是浏览页每次显示都写
 * （`StartupStore.recordBrowsing`）。两者不一致 = 上一会话之后又浏览到了别处（进程被杀、任务被划掉这类没有 finish 的退出），
 * 此时拿旧路径重建会恢复到一个用户早就不在的位置——宁可只恢复到落盘的那个位置。
 * 连接不同（手工改库/降级安装残留）同样不用：书与容器 id 只在各自连接内有效。
 */
internal fun startupBrowsePath(persisted: List<BrowseLocation>, target: BrowseLocation): List<BrowseLocation> =
    persisted.takeIf { it.isNotEmpty() && it.last() == target && it.all { level -> level.connId == target.connId } }
        ?: listOf(target)

/**
 * 启动落地的浏览历史重置（票 #70 AC6/AC7）：冷启动（含进程被杀后重建）里 NavController 的回退栈是全新的，
 * 落到某浏览层时栈里只有这条路径上的浏览页（根首页之下）——历史必须**重置为这条路径**（空路径表示本次落地没有
 * 浏览层，历史清空）。旧写法只在「连接 id 变了」时才清，同一连接的残留历史会让 `canGoBack` 为真，
 * 而返回处理器 `goBack()+popBackStack()` 于是落到一个不在回退栈上的层级（本票「一按返回就退出」的根因）。
 * 历史在已登记的路径上与回退栈里的浏览层保持一致（见 `docs/SPEC.md` 的 UI 骨架条「返回逐级」段，含所列未同步点），
 * 本函数是启动侧**唯一**的重置点（由 [BrowserBackStackSyncTest] 锁定）。
 */
internal fun resetBrowseHistoryForStartup(history: BrowseHistory, path: List<BrowseLocation>) {
    history.clear()
    path.forEach { history.record(it) }
}

/**
 * 把一条浏览路径逐层压到回退栈上（票 #70 r2）：路径上的层是**同一个 destination、只有 container 参数不同**，
 * 因此这里**不用** `launchSingleTop`——它按 destination 判重，会把整条路径塔成一条 entry（重启后返回仍是回首页）。
 * 已经在**栈顶**的那一层不重复压（评审 P2-5：抽屉「阅读器」入口传「当前浏览位置」单层时，与 `launchSingleTop`
 * 同效，但不会像它那样把参数不同的 entry 就地改写）；启动重建时栈顶是首页，这条跳过不会触发。
 */
internal fun pushBrowserPath(nav: NavHostController, path: List<BrowseLocation>) {
    path.forEach { level ->
        if (browseLocationOf(nav.currentBackStackEntry) == level) return@forEach
        nav.navigate(Routes.browser(level.connId, level.containerId))
    }
}

/**
 * 从浏览路由的参数里读出位置（票 #70 r2 评审 P2-4）：「按回退栈补齐历史」与浏览页目的地两处共用同一段解码
 * （参数键 `connId`/`container` 因此只留一处）。`connId` 解不出来（路由损坏）时返回 null；`container` 空串 = 根层。
 */
private fun browseLocationOf(entry: NavBackStackEntry?): BrowseLocation? {
    val connId = entry?.arguments?.getString("connId")?.toLongOrNull() ?: return null
    return BrowseLocation(connId, entry.arguments?.getString("container")?.takeIf { it.isNotEmpty() })
}

/**
 * 进程被杀后重建时按系统还原出来的回退栈补齐浏览历史（票 #70 r2 AC12）：回退栈由系统还原，
 * 而浏览历史是进程级的、随进程消失——不补齐时两者不同步（历史为空，返回处理器不接管；抽屉「阅读器」入口
 * 也拿不到「当前浏览位置」）。只在历史为空时补：旋转这类进程未死的重建里历史非空，本函数不动它。
 */
internal fun seedBrowseHistoryFromBackStack(history: BrowseHistory, nav: NavHostController) {
    if (history.current != null) return
    nav.currentBackStack.value
        .filter { it.destination.route == Routes.BROWSER }
        .mapNotNull { browseLocationOf(it) }
        .forEach { history.record(it) }
}

/** 抽屉顶层入口（票 #70 r2）：首页/书柜/设置。阅读器入口另有换 entry 的语义（[openReaderFromDrawer]） */
private val DRAWER_TOP_LEVEL_ROUTES = setOf(Routes.HOME, Routes.BOOKSHELF, Routes.SETTINGS)

/**
 * 抽屉顶层入口的导航（票 #70 r2 AC9/AC10）：**压在当前界面之上**，返回因此回到进入前的界面
 * （例如进入设置前的那个子文件夹），而不是把回退栈重置成 [首页, 入口]。
 *
 * **叠层收口**（票 #70 r2 评审 P1）：抽屉顶层入口可占用的区域 = 「进入抽屉前的那个界面」之上的部分
 * （[drawerRegionStart]），区域内**同一个入口最多一层**：
 * - 目标已在区域内 → 回到那一层（丢掉它之上的中间层），不新增重复层；
 * - 不在区域内（含栈底那个根首页实例）→ 先把栈顶连续的顶层入口层收掉
 *   （[revealBrowsingLayerBelowTopLevelEntries]），再压一层；`launchSingleTop` 另保证同一入口重复点不叠层。
 * 交替进入（首页→书柜→设置→书柜…）因此有界：顶层入口层数 ≤ 3（每个入口一层）。
 *
 * 本函数**不动浏览历史**：它只压/收顶层入口层，从不弹浏览层（区域下界严格在浏览层之上），
 * 历史与回退栈里的浏览层因此仍一一对应（由 [BrowserBackStackSyncTest] 锁定）。
 */
internal fun navigateTopLevel(nav: NavHostController, route: String) {
    val stack = nav.currentBackStack.value
    val start = drawerRegionStart(stack)
    val existing = stack.indices.lastOrNull { it >= start && stack[it].destination.route == route }
    if (existing != null) {
        // 已在区域内：回到那一层，把它之上的中间层丢掉（根首页在区域外，永远不会被弹掉）
        nav.navigate(route) {
            popUpTo(route) { inclusive = false }
            launchSingleTop = true
        }
        return
    }
    revealBrowsingLayerBelowTopLevelEntries(nav)
    nav.navigate(route) { launchSingleTop = true }
}

/**
 * 抽屉顶层入口可占用区域的下界（票 #70 r2 评审 P1）：取「栈里最后一个非顶层入口层」（浏览层 / 连接列表 /
 * 路由图入口）与「**栈底那个根首页**」的较大者再加一。
 *
 * 根首页也当下界，是因为它是应用栈底、不是抽屉压出来的——所以它不算「目标入口已在区域内」：
 * 从子文件夹点抽屉「首页」仍要压一层，返回才回得到进入前的界面（AC10）。
 * 区域下界严格在浏览层之上，所以「回到区域内那一层」永远弹不到浏览层与根首页。
 */
private fun drawerRegionStart(stack: List<NavBackStackEntry>): Int {
    val anchor = stack.indexOfLast { it.destination.route !in DRAWER_TOP_LEVEL_ROUTES }
    val rootHome = stack.indexOfFirst { it.destination.route == Routes.HOME }
    return maxOf(anchor, rootHome) + 1
}

/**
 * 收掉栈顶连续的抽屉顶层入口层（票 #70 r2），露出其下的浏览层——**只在露出的确实是浏览层时才收**：
 * 栈里没有浏览层时（如「书柜 → 抽屉阅读器」）不动栈，那一层要靠返回逐级回到。
 * 根首页（栈底）与浏览层都不动，因此历史无需同步。
 */
internal fun revealBrowsingLayerBelowTopLevelEntries(nav: NavHostController) {
    val stack = nav.currentBackStack.value
    val anchor = stack.indexOfLast { it.destination.route !in DRAWER_TOP_LEVEL_ROUTES }
    if (anchor < 0 || stack[anchor].destination.route != Routes.BROWSER) return
    repeat(stack.size - 1 - anchor) { nav.popBackStack() }
}

/**
 * 抽屉「阅读器」入口的导航（票 #70 r2 AC10）：先收掉栈顶的顶层入口层，再把本次浏览位置压到阅读器之下——
 * 返回因此落到**进入前的那个子文件夹**（而不是叠一层重复的浏览页或直接回首页）。
 * 「没选来源 / 没有阅读记录」的中文提示留在调用点（那里有 Context）。
 *
 * 与启动还原的 OpenReader 分支（`AppNav` 落地里的同名分支）形状相同、两处差异**有意保留**（评审 P2-5）：
 * - 浏览层来源：本处是**会话内的当前浏览位置**（单层，历史随进程存活），启动侧是**落盘的层级链**（整条）；
 * - 阅读器 entry：本处走 [newReaderNavOptions]（票 #68：换一条新 entry，与读内换书同一套语义），
 *   启动侧是 `launchSingleTop`——落地只在栈顶是中转页时跑，那时栈里不可能已有阅读器 entry，两者等价。
 * 两者都走 [pushBrowserPath]（已在栈顶的那一层不重复压），压浏览层的形状因此只有一处。
 */
internal fun openReaderFromDrawer(nav: NavHostController, history: BrowseHistory, last: LastRead) {
    revealBrowsingLayerBelowTopLevelEntries(nav)
    history.current?.takeIf { it.connId == last.connId }?.let { pushBrowserPath(nav, listOf(it)) }
    nav.navigate(Routes.reader(last.bookId), newReaderNavOptions())
}

/**
 * 真机排查「返回被扔回首页/直接退出」的观测点（票 #70；#98/#99 同一片根因）：一行给出
 * **回退栈深度 + 栈顶路由 + 浏览历史游标与能否后退**。默认关闭，开关与查看见 [PerfTiming]：
 * `adb shell setprop log.tag.ComicViewerPerf DEBUG` 后 `adb logcat -s ComicViewerPerf`。
 */
internal fun navObservation(nav: NavHostController, history: BrowseHistory): String {
    val cursor = history.current?.let { "${it.connId}/${it.containerId}" } ?: "null"
    return "route=${nav.currentDestination?.route} depth=${nav.currentBackStack.value.size} " +
        "historyCurrent=$cursor historyCanGoBack=${history.canGoBack}"
}

/**
 * 导航观测事件名（票 #70）：这四个名字是本票真机验收的**唯一证据通道**，收成常量免得四处字面量与
 * [PerfTiming] KDoc 清单漂移（由 [NavObservationTest] 锁形）。
 */
internal object NavEvent {
    const val STARTUP_SKIP = "nav startup skip"
    const val STARTUP_LAND = "nav startup land"
    const val STARTUP_FALLBACK = "nav startup fallback"
    const val BROWSE_BACK = "nav browseBack"
}

/** 一行导航观测日志（事件名 + [navObservation]）。事件名与字段名由 [NavObservationTest] 锁定。 */
internal fun navObservationLine(event: String, nav: NavHostController, history: BrowseHistory): String =
    "$event ${navObservation(nav, history)}"

/** 「上次退出时是否停在阅读器」的写点守卫（票 26 r3 修正 A，纯函数，由 [StartupReadingFlagTest] 锁定）：
 * 中转页（[Routes.STARTUP]）与路由未定（null）的那一帧返回 null = 本次不写。
 *
 * 启动判定读的是**上一会话**落盘的 `was_reading`，而本会话路由一变就会写它：慢来源冷启动期间若在中转页上
 * 写 false，随后的补跑（旋转屏幕/进程被杀后回到前台）就再也读不到「上次正在看书」，故事 47 直接打开那本书的
 * 语义丢失。把写点限定在已离开中转页的路由上，读与写的先后就成了结构性保证，不再依赖 effect 的启动顺序。
 */
internal fun readingFlagToRecord(route: String?): Boolean? = when (route) {
    null, Routes.STARTUP -> null
    Routes.READER -> true
    else -> false
}

/**
 * 启动还原「上次阅读的书」的结果（票 #97）：落地目的地 + 需要告知用户的一句中文提示（无需提示时为 null）。
 * 目的地与提示出自同一个判断点（[resolveStartupRead]），因此不会出现「回落了却没提示」（本票 AC「给中文提示」）。
 */
internal data class StartupReadOutcome(val target: StartupTarget, val notice: String? = null)

/** 回落提示（AC「给中文提示」）：只说发生了什么、现在在哪；不出现异常原文、路径或 id */
private const val NOTICE_BACK_TO_BROWSING = "上次阅读的书已不是一本书（目录结构可能已变化），已回到浏览列表"
private const val NOTICE_BACK_TO_HOME = "上次阅读的书已不是一个可读的书，已回到首页"

/**
 * 启动还原「上次阅读的书」前的可读性判定（票 #97 AC「升级路径」，由 [StartupReadFallbackTest] 锁定）。
 *
 * 为什么需要：本票把「本层有子目录/压缩包」的目录由书改判为容器——**上一版落盘的** `lastRead.bookId`
 * 完全可能正指向这样一个目录（或已被删除/改名的文件）。旧路径直接把它当书打开：`openBook` 抛
 * `IllegalArgumentException`，文本形如「不是一本书：<本机绝对路径>」，界面把这行原文显示给用户，
 * 人还停在阅读器里没有下一步。因此在**导航之前**先试开一次：
 * - 能开 → 照旧进阅读器。**0 页的书也算能开**（空/坏压缩包是书，件内确实没有图片）——界面按
 *   `pageCount == 0` 显示中文空态，不在这里拦；
 * - 抛 [IllegalArgumentException]（不是书 / 越界：目录已被改判为容器、文件已删）→ **回落到浏览层**：
 *   优先「上次停留的位置」（与阅读器入口一致：阅读器下面本来就压着它），没有可用位置就用这个 id 自己
 *   ——AC 场景里它正是那个「已变成容器的目录」，点开就是它的条目列表（只有它现在真能当容器列出来时才用它，
 *   否则回落到首页：把一个列不出来的层交给浏览页，只会再报一次错）；
 * - **其余失败不算「不是书」**（断链/超时这些暂时性失败）→ 照旧进阅读器，沿用票 #91 的
 *   「打开失败 + 点此重试」界面，本票不回退那个口径。
 *
 * 本票 AC「给中文提示」：两条回落分支都带上 [StartupReadOutcome.notice]（由启动 effect 用非阻塞 Toast 展示），
 * 用户能知道为何没回到上次那本书。
 *
 * 线程语义：**本接缝自己把来源调用切到 [Dispatchers.IO]**（调用方在启动 effect 的 Main 上）。
 * 仓库的通常规范是「调用方负责切 IO」（如 `ListComposition`），但这里是启动准备层的内部步骤、
 * 且两个调用都是可能阻塞的来源 I/O（本地 = provider IPC、SMB/WebDAV = 同步 socket、Komga = 同步 HTTP）——
 * 不切就会在主线程抛 `NetworkOnMainThreadException`，而它**不是** `IllegalArgumentException`，
 * 于是上面的回落判定在网络来源上会静默失效。切在接缝内而不是调用点，同时保证任何调用方都安全。
 * 取消语义不变：`withContext` 内的 [catchingNonCancellation] 把 `CancellationException` 原样抛出（票 #26）。
 *
 * 代价：多一次 `openBook`（回落路径上再多一次 `listEntries`）。压缩包包内条目按 id+mtime 有会话级缓存（票 #51），
 * 阅读器随后那次打开命中缓存；目录书那次是一次 `children()`。相对「用户看到绝对路径且卡在阅读器」这点代价是划算的。
 */
internal suspend fun resolveStartupRead(
    source: Source,
    lastRead: LastRead,
    lastBrowsing: LastBrowsing?,
): StartupReadOutcome {
    val attempt = withContext(Dispatchers.IO) { catchingNonCancellation { source.openBook(lastRead.bookId) } }
    if (attempt.isSuccess) return StartupReadOutcome(StartupTarget.OpenReader(lastRead))
    val failure = attempt.exceptionOrNull()
    // 只有「不是一本书」才回落（判据只有一处：[isNotABook]）：暂时性失败照旧交给阅读器的重试界面
    if (failure == null || !isNotABook(failure)) return StartupReadOutcome(StartupTarget.OpenReader(lastRead))
    lastBrowsing?.takeIf { it.connId == lastRead.connId }
        ?.let { return StartupReadOutcome(StartupTarget.OpenBrowser(it), NOTICE_BACK_TO_BROWSING) }
    // 没有可用的浏览位置：只有这个 id 现在真能列出来（= 它已变成一个容器）才拿它当落点
    val listable = withContext(Dispatchers.IO) {
        catchingNonCancellation { source.listEntries(lastRead.bookId, SortMode.NAME) }.isSuccess
    }
    return if (listable) {
        StartupReadOutcome(
            StartupTarget.OpenBrowser(LastBrowsing(lastRead.connId, lastRead.bookId)),
            NOTICE_BACK_TO_BROWSING,
        )
    } else {
        StartupReadOutcome(StartupTarget.OpenHome, NOTICE_BACK_TO_HOME)
    }
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
     *
     * 返回值带一句可展示的中文提示（票 #97：启动还原因「已不是一本书」而回落时必须告知用户，见 [StartupReadOutcome]）。
     */
    suspend fun prepareStartup(target: StartupTarget): StartupReadOutcome = when (target) {
        is StartupTarget.OpenBrowser -> {
            // 取库用的是 [catchingNonCancellation] 而不是裸 runCatching（票 #26 登记项）：它包在 withContext
            // 里层，裸 runCatching 会把取消当成「读库失败」，随后继续走导航与写历史。真正「包在 withContext
            // 外面」的是下面两处 browsingSourceFor 调用。
            val row = withContext(Dispatchers.IO) {
                catchingNonCancellation { ServiceLocator.db.connectionDao().byId(target.browsing.connId) }
            }
            val conn = row.getOrNull()
            // 与常规入口（本地根列表/连接列表）一致：先备会话来源，抽屉「阅读器」入口才能打开上次阅读的书；
            // 会话级实例（票 #30 P1）：随后的浏览页复用同一个，列表缓存跨页面存活
            if (conn == null) {
                // 这条「上次停留的位置」恢复不了了，顺手清掉（票 26 第 2 项）：留着只会让每次启动都重走一遍
                //「先导航到浏览页再弹回」。读库本身失败（isFailure）不清——那是暂时性故障，不等于连接被删
                if (row.isSuccess) StartupStore.clearBrowsing()
                StartupReadOutcome(fallbackWhenConnectionMissing(target))
            } else {
                // 会话建不起来不阻断——浏览页会按路由 connId 自行解析并显示重试
                catchingNonCancellation { withContext(Dispatchers.IO) { ServiceLocator.browsingSourceFor(conn) } }
                    .onSuccess {
                        ServiceLocator.currentSource = it
                        ServiceLocator.currentConnId = target.browsing.connId
                    }
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
                ServiceLocator.currentSource = source
                ServiceLocator.currentConnId = last.connId
                ServiceLocator.lastRead = last
                // 票 #97 AC「升级路径」：上一版落盘的 bookId 可能已被改判成容器（或被删）——先判定再落地，
                // 不是书就回落到浏览层，绝不把用户丢进一个只报错、还带绝对路径的阅读器（见 [resolveStartupRead]）
                resolveStartupRead(source, last, StartupStore.lastBrowsing())
            }
        }
        StartupTarget.OpenBookshelf, StartupTarget.OpenHome -> StartupReadOutcome(target)
    }

    // 只在真正的冷启动落地一次：配置变更/进程恢复时 NavController 会还原回退栈，不重复导航
    val startupDone = rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        // 落盘状态在**第一次挂起之前**同步读完（票 26 r2 修正 1），并保留在 effect 体第一句更稳：
        // 写点一侧另有结构性保证（票 26 r3 修正 A）——下面的 recordReading 只在离开中转页的路由才写，
        // 所以本会话的写不可能污染启动期的读。重活（按 id 取连接/建来源会话）照旧在 IO 上做。
        val startTarget = StartupStore.startupTarget()

        // 落地只做一次，但「做过」不能只看 startupDone（票 26 r2 修正 2）：rememberSaveable 只说明本会话
        // 标记过，不代表导航真的落地了——慢来源冷启动期间旋转屏幕、进程被杀后回到前台时，NavController 会还原出
        // 一个仍停在 STARTUP 的栈顶，必须补跑一次判定与导航，否则该页没有出口（抽屉手势已关、页上无控件）。
        // 只在**明确已落地**（栈顶是别的页）时才早退；栈顶是中转页、或尚不可知（currentDestination 为空，
        // 组合后理论上不会）都按「未落地」处理——宁可补跑一次，也不留死页。
        if (startupDone.value && nav.currentDestination?.route?.let { it != Routes.STARTUP } == true) {
            // 进程被杀后重建：回退栈由系统还原、浏览历史随进程消失——按还原出来的浏览层补齐历史（票 #70 r2）
            seedBrowseHistoryFromBackStack(history, nav)
            PerfTiming.log { navObservationLine(NavEvent.STARTUP_SKIP, nav, history) }
            return@LaunchedEffect
        }
        startupDone.value = true
        // 落地全过程兜底（票 26 r3 修正 C）：判定、建会话、导航任一步抛预期外异常时，用户会停在一个抽屉手势
        // 已关、页上无控件的中转页——那是应用内没有出口的死页（改前至少还能划开抽屉自救）。失败一律降级只落首页。
        // 取消（组合销毁/配置变更）必须照常传播、且不在取消后做任何导航：靠 [catchingNonCancellation] 而非
        // 事后在 onFailure 里补抛（票 #26 登记项——内层裸 runCatching 会在更早的地方就把取消吞掉）。
        catchingNonCancellation {
            val resolved = prepareStartup(startTarget)
            // 先把「首页」作为根，目的地压在其上：返回语义与常规导航一致
            // （launchSingleTop：补跑时若首页已在栈顶也不再叠第二层）
            nav.navigate(Routes.HOME) {
                popUpTo(Routes.STARTUP) { inclusive = true }
                launchSingleTop = true
            }
            // 票 #97 AC「给中文提示」：启动还原回落到浏览层/首页时告知用户为何没回到上次那本书（非阻塞，不改目的地）
            resolved.notice?.let { Toast.makeText(context, it, Toast.LENGTH_SHORT).show() }
            when (val target = resolved.target) {
                StartupTarget.OpenHome -> resetBrowseHistoryForStartup(history, emptyList())
                StartupTarget.OpenBookshelf -> {
                    resetBrowseHistoryForStartup(history, emptyList())
                    nav.navigate(Routes.BOOKSHELF) { launchSingleTop = true }
                }
                is StartupTarget.OpenBrowser -> {
                    // 与入口一致：把恢复到的位置作为当前浏览位置；票 #70 r2：**整条层级链**一起重建
                    //（只恢复一层的话，重启后返回只剩「回首页」一条路——追加口径的现象 A）。
                    // 只恢复目录层级；排序是全局设置本就保持，滚动位置不恢复，SPEC Out of Scope。
                    val browsing = target.browsing
                    val path = startupBrowsePath(
                        StartupStore.browsingPath(),
                        BrowseLocation(browsing.connId, browsing.containerId),
                    )
                    resetBrowseHistoryForStartup(history, path)
                    // 逐层压栈：路径上的层是**同一 destination、不同参数**（container），
                    // `launchSingleTop` 按 destination 判重，会把整条路径塔成一 entry——这里不能用它
                    pushBrowserPath(nav, path)
                }
                is StartupTarget.OpenReader -> {
                    // 返回手势落到浏览列表（与抽屉「阅读器」入口一致）：把上次停留位置及其上级压到阅读器之下
                    val browsing = StartupStore.lastBrowsing()?.takeIf { it.connId == target.lastRead.connId }
                    val path = browsing
                        ?.let { startupBrowsePath(StartupStore.browsingPath(), BrowseLocation(it.connId, it.containerId)) }
                        .orEmpty()
                    resetBrowseHistoryForStartup(history, path)
                    pushBrowserPath(nav, path)
                    nav.navigate(Routes.reader(target.lastRead.bookId)) { launchSingleTop = true }
                }
            }
            PerfTiming.log { navObservationLine(NavEvent.STARTUP_LAND, nav, history) }
        }.onFailure {
            // 降级落点只有首页（没有更好的地方可去）；兜底本身再失败也没有别的办法，不能让它把协程带崩
            runCatching {
                nav.navigate(Routes.HOME) {
                    popUpTo(Routes.STARTUP) { inclusive = true }
                    launchSingleTop = true
                }
            }
            PerfTiming.log { navObservationLine(NavEvent.STARTUP_FALLBACK, nav, history) }
        }
    }

    // 上次退出时是否正停在阅读器（spec 故事 47 的退化条件）：路由一变即落盘，进程被杀也留得住。
    // 中转页与路由未定的那一帧不写（票 26 r3 修正 A）：判定要读的正是上一会话落下的值。
    LaunchedEffect(currentRoute) {
        readingFlagToRecord(currentRoute)?.let { StartupStore.recordReading(it) }
    }

    // 阅读器沉浸（票 #61）：系统栏可见性只由「当前路由是不是阅读器」这一处事实决定——
    // 与阅读页的组合存活期解耦（换书时旧 entry 的销毁可能落在新 entry 之后，见 [ReaderImmersiveSystemBars]）。
    ReaderImmersiveSystemBars(immersive = currentRoute == Routes.READER)

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
    RegisterSlot(ServiceLocator.forwardHistorySlot, forwardHistory)

    AppDrawer(
        drawerState = drawerState,
        currentRoute = currentRoute,
        // 阅读器内禁用边缘手势：左缘滑动留给系统返回手势（spec 故事 38）。
        // 启动中转页同样禁用（票 26 第 1 项）：判定未完成时划出抽屉，「书柜/设置」会被随后的 popUpTo 吞掉、
        // 「阅读器」只会弹「请先选择一个来源」。
        gesturesEnabled = currentRoute != Routes.READER && currentRoute != Routes.STARTUP,
        onOpenHome = {
            closeDrawer()
            // 压在当前界面之上（票 #70 r2 AC9/AC10）：返回回到进入前的界面；重复点同一入口不叠层
            navigateTopLevel(nav, Routes.HOME)
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
                else -> openReaderFromDrawer(nav, history, last)
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
        NavHost(
            navController = nav,
            startDestination = Routes.STARTUP,
            // 四支过渡全部零时长（见 NavTransitions 的依据注释）：过渡窗口消失，返回不空等、旧页面也不再有
            // 「退场期间仍接收点击」的窗口
            enterTransition = { NavTransitions.enter },
            exitTransition = { NavTransitions.exit },
            popEnterTransition = { NavTransitions.popEnter },
            popExitTransition = { NavTransitions.popExit },
        ) {
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
            composable(Routes.BROWSER) { entry ->
                val location = browseLocationOf(entry)
                if (location == null) {
                    // 组合期不可直接导航：包装进 LaunchedEffect（review P2）
                    LaunchedEffect(Unit) { nav.popBackStack() }
                } else {
                    BrowserScreen(nav, location.connId, location.containerId, ::openDrawer)
                }
            }
            composable(
                route = Routes.READER,
                // 出场过渡（票 #68 AC3「换书瞬间不出现上一本页面」）：换书/打开都换 entry，被弹的那条在出场
                // 动画期间仍算「可见」——navigation 2.8.1 的 popWithTransition 把被弹 entry 放进
                // transitionsInProgress（maxLifecycle 停在 CREATED、不置 DESTROYED），
                // NavController.populateVisibleEntries 把它纳入 visibleEntries，NavHost 于是在旧 entry 上渲染
                // 退场内容；默认过渡是 fadeOut(tween(700))，上一本的页面会在换书后约 700ms 内继续被画在屏上。
                // 这里显式声明「旧内容立刻离场」（ExitTransition.None：零时长、不绘制退场内容）。
                // 两条出场路径都定死（`navigate()` 会把 isPop 复位为 false 走 exitTransition，版本差异下也可能走
                // popExitTransition）：连带效果是从阅读器返回时也不再淡出，换成版本无关的确定性。
                // 票 #107 起 NavHost 的全局四支过渡（[NavTransitions]）与这两条同值，路由级声明压过全局，语义不变；
                // 这两条按 #107 AC8 保持原样，是 #68「换书瞬间不出现上一本页面」的验收位。
                // 不可单测：过渡属性挂在 ComposeNavigator.Destination 上，而 navigation-compose 2.8.1 把它们声明为
                // internal（本仓库的模块读不到，用反射断言内部字段不值当），要真断言得上 Compose UI 测试基建；
                // 按 SPEC 的 Testing Decisions，UI 过渡走手动/真机验收（本票 AC6 两次开关验收时看是否露出上一本页面）。
                exitTransition = { ExitTransition.None },
                popExitTransition = { ExitTransition.None },
            ) { entry ->
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
                            nav.navigate(Routes.reader(newBookId), newReaderNavOptions())
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
            // 四个已实装来源（票 33 起 OPDS 已下线）：本地进根目录列表，其余进各自的连接列表
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

/** 首页的来源入口行（票 #33 起 OPDS 已下线，四个入口全可用：`enabled` 恒真的禁用分支已删） */
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
