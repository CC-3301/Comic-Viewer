package com.cc3301.comicviewer.ui.nav

import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavHostController
import com.cc3301.comicviewer.core.data.ConnectionEntity
import com.cc3301.comicviewer.core.nav.BrowseHistory
import com.cc3301.comicviewer.core.nav.BrowseLocation
import com.cc3301.comicviewer.core.nav.LastBrowsing
import com.cc3301.comicviewer.core.nav.LastRead
import com.cc3301.comicviewer.core.nav.LastTopLevel
import com.cc3301.comicviewer.core.nav.StartupTarget
import com.cc3301.comicviewer.core.source.Source
import com.cc3301.comicviewer.core.source.SortMode
import com.cc3301.comicviewer.core.source.isNotABook
import com.cc3301.comicviewer.ui.Routes
import com.cc3301.comicviewer.ui.ServiceLocator
import com.cc3301.comicviewer.ui.SortSettingStore
import com.cc3301.comicviewer.ui.StartupStore
import com.cc3301.comicviewer.ui.catchingNonCancellation
import com.cc3301.comicviewer.ui.newReaderNavOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * 浏览链与启动落地的导航操作层：**把导航操作应用到 `NavController` 上的那一层**（这一口径只在本文件声明），
 * 与 `core/nav/`（`BrowseHistory` / `StartupRouting`）配对——core 侧是纯数据，本文件把它落到回退栈上。
 * 过渡与动画数字不在这里（见 `NavTransition.kt`）；`AppNav` 组合体只接线，不直接持有这些操作
 * （由 `BrowseNavHomeGuardTest` 钉住）。
 */

/**
 * 启动落地要恢复的浏览路径（纯函数）：落盘路径与本次恢复到的位置**一致**时整条用，
 * 否则只恢复这一层。
 *
 * **术语**：本处的「浏览路径」指**用户停留的层级链**（浏览页逐层下钻留下的那串位置）；
 * 与 `GLOSSARY.md` 里连接的 `browsePath`（进连接后从哪一层开始，见 `KomgaConnectionConfig.browsePath`）同词不同义。
 *
 * 为什么还需要这个判据：路径与「上次停留的位置」在浏览页显示时由**同一次调用**写
 * （[StartupStore.recordBrowsePosition]），因此正常浏览下的两者总是一致；不一致只剩「路径不是本会话写的」那几种
 * （手工改库/降级安装残留、连接不同）——那时拿旧路径重建会恢复到一个用户早就不在的位置（甚至会压出容器不存在的层），
 * 宁可只恢复到落盘的那个位置。连接不同（书与容器 id 只在各自连接内有效）同样不用。
 */
internal fun startupBrowsePath(persisted: List<BrowseLocation>, target: BrowseLocation): List<BrowseLocation> =
    persisted.takeIf { it.isNotEmpty() && it.last() == target && it.all { level -> level.connId == target.connId } }
        ?: listOf(target)

/**
 * 启动落地的浏览历史重置：冷启动（含进程被杀后重建）里 NavController 的回退栈是全新的，
 * 落到某浏览层时栈里只有这条路径上的浏览页（根首页之下）——历史必须**重置为这条路径**（空路径表示本次落地没有
 * 浏览层，历史清空）。旧写法只在「连接 id 变了」时才清，同一连接的残留历史会让 `canGoBack` 为真，
 * 而返回处理器 `goBack()+popBackStack()` 于是落到一个不在回退栈上的层级（「一按返回就退出」的根因）。
 * 历史在已登记的路径上与回退栈里的浏览层保持一致（见 `docs/SPEC.md` 的 UI 骨架条「返回逐级」段，含所列未同步点），
 * 本函数是启动侧**唯一**的重置点（由 [BrowserBackStackSyncTest] 锁定）。
 */
internal fun resetBrowseHistoryForStartup(history: BrowseHistory, path: List<BrowseLocation>) {
    history.clear()
    path.forEach { history.record(it) }
}

/**
 * 把一条浏览路径逐层压到回退栈上：路径上的层是**同一个 destination、只有 container 参数不同**，
 * 因此这里**不用** `launchSingleTop`——它按 destination 判重，会把整条路径塔成一条 entry（重启后返回仍是回首页）。
 * 已经在**栈里**的那一层不重复压（进程被杀后系统还原出来的回退栈里已有这些层，再压一遍会多出一段，
 * 历史镜像与回退栈于是不一致，返回决议落到别的层）；抽屉「阅读器」入口传「当前浏览位置」单层时这条也与
 * `launchSingleTop` 同效，但不会像它那样把参数不同的 entry 就地改写。启动重建时栈里只有首页，跳过不会触发。
 */
internal fun pushBrowserPath(nav: NavHostController, path: List<BrowseLocation>) {
    path.forEach { level ->
        if (level in browseLayersOnStack(nav)) return@forEach
        // 名字随路由走：启动重建的层也要带名字，否则进程重建后标题退到 id 末段
        nav.navigate(Routes.browser(level.connId, level.containerId, level.containerName))
    }
}

/**
 * 浏览层落盘（以回退栈为准）：先把浏览历史对齐到**回退栈里实际的浏览层**
 * （[syncBrowseHistory]），再把「停留位置」与**整条路径**一次写入（[StartupStore.recordBrowsePosition]）。
 * 调用点两处：`BrowserScreen` 显示某层时（`LaunchedEffect(connId, containerId)`）与 [navigateToBrowseLocation] 结束时。
 *
 * 为什么是**这里**写而不再只靠会话结束那次写：设备上更常见的退出是任务被划掉 / 进程被杀——那时没有 Activity finish，
 * `ServiceLocator.session.end()` 不会跑，只有逐层写下的这份路径可用；不写它就只剩「一层」，重启后按返回直接跳回首页
 * （设备反馈的现象）。
 *
 * 为什么要在写之前对齐栈：原先写的是历史侧自己记下的路径，而历史是进程级单例、没有谁保证它与回退栈逐层对应；
 * 不对应时「位置」与「路径最后一层」会分家，启动侧 [startupBrowsePath] 的判据不成立→只恢复一层→返回直接回首页
 * （号称修好、设备上仍复现的那条）。路径现在只有一个来源：回退栈。
 */
internal fun recordBrowsePosition(nav: NavHostController, history: BrowseHistory, location: BrowseLocation) {
    syncBrowseHistory(history, nav)
    StartupStore.recordBrowsePosition(
        LastBrowsing(location.connId, location.containerId),
        history.path(),
    )
}

/**
 * 回退栈里**实际的浏览层**（栈底 → 栈顶）：本文件里记录、返回决议、启动重建一律以它为准，
 * 浏览历史只是它的镜像（[syncBrowseHistory]）。不读历史侧的任何值。
 */
internal fun browseLayersOnStack(nav: NavHostController): List<BrowseLocation> =
    nav.currentBackStack.value.mapNotNull { browseLocationOf(it) }

/**
 * 把浏览历史重建为回退栈里实际的浏览层。幂等：两者本就一致时什么都不变（前进栈保留）。
 *
 * 为什么要有它：浏览历史是**进程级单例**，而回退栈随 Activity/进程重建——两者一旦不一致，
 * 返回处理器就会拿着「历史里的层」去弹「回退栈上的层」，用户看到的是弹到别的层级（「一按返回就回首页」）。
 * 修法不是让两边各自记好（记不住的场合在设备上确实发生），而是让**回退栈成为唯一事实来源**：
 * 每次浏览层变化（导航、显示、重建）都按栈重建镜像，历史侧没有独立记录动作可漂移。
 */
internal fun syncBrowseHistory(history: BrowseHistory, nav: NavHostController) {
    history.syncPath(browseLayersOnStack(nav))
}

/**
 * 从浏览路由的参数里读出位置：「按回退栈补齐历史」与浏览页目的地两处共用同一段解码
 * （参数键 `connId`/`container` 因此只留一处）。`connId` 解不出来（路由损坏）时返回 null；`container` 空串 = 根层。
 *
 * `name` 同在这一处读：浏览历史是回退栈的镜像，名字跟着一起镜像后，「落盘路径 → 启动重建」
 * 天然带上名字（见 [BrowseLocation.containerName]）。
 *
 * `internal` 而非 `private`：用例要按**生产这份**核「导航目标 = 预置的目标层」，不另手抄解码
 *（生产侧就这一份；测试侧另有一处镜像 `BrowserBackStackSyncTest.locationOf`，两者同一套键，改键名要同时动这两处——
 * 分头漂 `connId` / `name` 会被那条接缝用例抓到）。
 * 同取舍的先例：`sliceEntryPage`。
 */
internal fun browseLocationOf(entry: NavBackStackEntry?): BrowseLocation? {
    val connId = entry?.arguments?.getString("connId")?.toLongOrNull() ?: return null
    return BrowseLocation(
        connId = connId,
        containerId = entry.arguments?.getString("container")?.takeIf { it.isNotEmpty() },
        containerName = entry.arguments?.getString("name")?.takeIf { it.isNotEmpty() },
    )
}

/**
 * 用户点击驱动的浏览层导航入口：`BrowserScreen.openEntry` 点条目下钻与 `openConnectionRoot`
 * 进连接根层都走它。另有两处不经它的直接调用（都在本文件）：启动重建/抽屉阅读器入口（[pushBrowserPath]）
 * 与鼠标前进侧键（按历史前进，不重开路径）。
 *
 * **本函数不会预置会话槽**：用户点击那两个入口走 [navigateToBrowseLocationPrimed]（它就是本函数
 * 外包一层「先落目标层快照再切」）；启动重建与前进侧键自己预置（它们不重写浏览历史）。
 *
 * 口径：
 * - 正常下钻（栈顶就是同一连接的浏览层，目标层不在栈里）→ 直接在它之上压一层；
 * - 否则（目标层已在栈里，或从侧滑菜单/别的连接重进来源）→ [reopenBrowsingPath]：收掉栈里已离开的那一段
 *   浏览层，把它**之上**的非浏览层（书柜 / 来源列表 / 抽屉压上的首页·设置）按原顺序压回，再压上目标层
 *   及其上级。同一层重复进入因此不追加新的一段（不无限嵌套），返回也落到进入前的那个界面而不是首页。
 *
 * 结束时把历史镜像与「停留位置 + 整条路径」一起对齐到实际栈（[recordBrowsePosition]）：两个落盘键因此永远一致，
 * 重启恢复不会因为「路径与位置不一致」而只恢复一层（那正是「重开后一按返回直接回首页」的形状）。
 * 前进栈在这里作废（[BrowseHistory.clearForward]）：导航到新位置清掉前进历史是标准浏览器语义（与 [BrowseHistory.record] 一致）；
 * 镜像同步本身（浏览页显示/返回后）不清它，鼠标前进侧键因此仍能用（spec 故事 37）。
 */
internal fun navigateToBrowseLocation(
    nav: NavHostController,
    history: BrowseHistory,
    location: BrowseLocation,
    /**
     * 目标不在当前浏览链上时，链必须钉在它（路径菜单传的是起点层）；`null` = 只压目标自己。
     * 目标已在链上时本参数不参与（链按链前缀截断）。
     */
    anchor: BrowseLocation? = null,
) {
    val stack = nav.currentBackStack.value
    val layers = browseLayersOnStack(nav)
    // 钉了链底的那一跳不是「下钻」：菜单跳兄弟目录时目标不是当前层的子层，
    // 走 [reopenBrowsingPath] 才能把它压在链底之上，而不是追加在当前层之下
    val drillsDown = anchor == null &&
        browseLocationOf(stack.lastOrNull())?.connId == location.connId &&
        location !in layers
    if (drillsDown) {
        nav.navigate(Routes.browser(location.connId, location.containerId, location.containerName))
    } else {
        reopenBrowsingPath(nav, location, layers, anchor)
    }
    history.clearForward()
    recordBrowsePosition(nav, history, location)
}

/**
 * 硬切档「先落快照再切」（2026-09-27 定走 B「预置会话槽」）：
 * **换屏之前**把目标层的列表快照垫进会话槽（[Source.primeCachedEntries]），于是新屏构造期那句同步读
 * （`BrowserScreen` 的 `preloaded` = [Source.cachedEntries]）当帧就命中——新屏「出生」当帧有内容，
 * 不再先空 1–3 帧「加载中…」。
 *
 * **形态**（为什么不走 A「延迟导航」）：A 要等目标层的**取数**回来（40–200ms）才换屏，慢源上就是「点了没反应」；
 * 本方法等的是**本地落盘快照读**（不列目录、不探测、0 次网络请求），且预置的层就是这次点击的目标层（没有「猜错」）。
 * 换屏仍是硬切：不加动画、不留重叠窗口。
 *
 * **写错层是硬故障**：`containerId` 必须是**目标层**的，写成当前层就会让新屏显示另一层的内容
 * （用例 `BrowseSnapshotPrimeTest` 钉住这一点）。
 *
 * 读不到（`source` 为 null = 会话槽里还没解析出这一连接的来源 / 没有落盘快照 / 读失败）就是空操作：
 * 新屏照旧走它自己的两段式（首帧「加载中…」、随后落快照帧）。
 *
 * 排序默认取**当下全局排序设置**（[SortSettingStore]）：新屏组合期用的是同一个值（`rememberSortSetting`），
 * 两处因此读的是同一档；排序不对就只是没命中（不会显示错层的内容）。
 */
internal suspend fun primeLayerSnapshot(
    source: Source?,
    containerId: String?,
    sort: SortMode = SortSettingStore.setting.mode,
) {
    source ?: return
    source.primeCachedEntries(containerId, sort)
}

/**
 * 「预置 → 导航」的顺序防线：把这二步串行化，**按发起顺序**落地。
 *
 * 要防的是：两次快速点击各起一个协程（[navigateToBrowseLocationPrimed] 与鼠标前进侧键都这么起），
 * 而预置那一步是挂起读（`Dispatchers.IO`）——两次读的完成顺序与点击顺序无关，**导航的落地顺序因此
 * 可能反掉**（上一次点击的层压在这一次之上，与「连续快速操作不叠加两层」那条红线冲突）。
 *
 * 为什么能保证顺序（两道机制叠一起）：
 * - 两次点击的 `launch` 落在同一个界面调度器上，协程**开始执行的顺序 = 发起顺序**；
 * - 临界区内的锁是 **FIFO 公平锁**（kotlinx `Mutex` 的契约），后到的等前者跑完；
 * - 「读快照」与「导航」在**同一个临界区**里，两次点击不会交错（不会出现后一次导航夹在前一次的读与导航之间）。
 *
 * 不选「取消尚在预置中的上一次点击」的原因：那会改掉旧行为（原来两次点击各落一层，取消后只剩一层），
 * 而红线要的是「顺序不乱」不是「丢弃前一次」；本函数逐字保留旧行为、只把顺序钉死。
 *
 * [navigate] 在锁内调用：它必须是**不挂起**的那一段（导航本身就是同步调用）；预置读在锁内完成。
 *
 * `internal` 而非 `private`：这条顺序保证由 `BrowseLayerNavigationOrderTest` 直接钉住。
 */
internal suspend fun withPrimedLayer(source: Source?, containerId: String?, navigate: () -> Unit) {
    browseLayerNavigationLock.withLock {
        primeLayerSnapshot(source, containerId)
        navigate()
    }
}

/** 浏览层导航的「预置 → 导航」单车道（见 [withPrimedLayer]）；进程内一把，四类入口共用 */
private val browseLayerNavigationLock: Mutex = Mutex()

/**
 * 用户点击驱动的浏览层导航入口：先落**目标层**的快照、再调 [navigateToBrowseLocation]。
 *
 * 为什么要单独一个入口（而不是让每个调用点各写两行）：预置的键必须是**目标层**（写错层是硬故障）、
 * 必须在导航**之前**、且两次快速点击要按发起顺序落地——这三件事都是跳不过去的不变量，
 * 收在这一处后，`BrowserScreen` 点容器与 `openConnectionRoot` 进根层就只需要喊这一声。
 *
 * `forwardHistory`（鼠标前进侧键）语义不同（它按历史前进、不重写浏览历史），因此只复用 [primeLayerSnapshot]
 * 与 [withPrimedLayer]；启动重建同理（[pushBrowserPath] 之前自己预置一层）。
 *
 * [source] 传调用点手上的**同一个会话来源实例**（新屏组合期读的 `SessionState.browsingSourceIfResolved` 就是它）：
 * null（会话未就绪）时预置是空操作，照旧导航。
 */
internal suspend fun navigateToBrowseLocationPrimed(
    nav: NavHostController,
    history: BrowseHistory,
    source: Source?,
    location: BrowseLocation,
    /** 见 [navigateToBrowseLocation]：路径菜单传起点层，普通下钻不传 */
    anchor: BrowseLocation? = null,
) {
    withPrimedLayer(source, location.containerId) {
        navigateToBrowseLocation(nav, history, location, anchor)
    }
}

/**
 * 重开一条浏览路径：收掉栈里**已离开的那一段浏览层**，把它之上的非浏览层
 * 按原顺序压回，最后压上目标层及其上级。
 *
 * 为什么非浏览层要重放而不能一并丢弃：NavController 只能从栈顶往下弹，而旧浏览层不在栈顶（用户是从侧滑菜单/
 * 来源列表进去的）——直接弹会把它上面的层（书柜、来源列表、抽屉压上的首页）一起弹掉，从浏览层返回就被扔回首页
 * （越界行为：子文件夹 → 侧滑书柜 → 点该连接 → 返回落首页）。重放后返回落到进入前的那个界面。
 *
 * [layers] 是调用点已取好的「栈里当前的浏览层」（栈底 → 栈顶）：目标层在其中时，它到那一段的底之间那几层
 * 是它的上级（返回要逐级回到它们），一并重建；目标层不在其中时只看 [anchor]——有它就把链钉成
 * 「[anchor] → 目标」（路径菜单跳兄弟目录 / Komga 四入口），没有就只压目标自己。
 */
private fun reopenBrowsingPath(
    nav: NavHostController,
    location: BrowseLocation,
    layers: List<BrowseLocation>,
    anchor: BrowseLocation? = null,
) {
    val stack = nav.currentBackStack.value
    val first = stack.indexOfFirst { browseLocationOf(it) != null }
    if (first < 0) {
        nav.navigate(Routes.browser(location.connId, location.containerId, location.containerName))
        return
    }
    val above = stack.drop(first).filter { browseLocationOf(it) == null }
    popAbove(nav, first - 1)
    above.forEach { replayTopLevelEntry(nav, it) }
    val chain = if (location in layers) {
        layers.take(layers.indexOf(location) + 1)
    } else {
        listOfNotNull(anchor, location)
    }
    pushBrowserPath(nav, chain)
}

/**
 * 把一条非浏览层按原样压回：目的地 id + 参数原封不动（连接列表这类带参数的路由不用再拼一遍 route 串）。
 * 栈里已有同目的地同参数的层时跳过（`launchSingleTop` 同效）：否则反复绕圈会在栈里堆出重复的首页/来源层，
 * 返回路径随绕圈变长（「一直嵌套下去」那类的另一种形状）。
 */
private fun replayTopLevelEntry(nav: NavHostController, entry: NavBackStackEntry) {
    val present = nav.currentBackStack.value.any { sameDestinationAndArgs(it, entry) }
    if (present) return
    nav.navigate(entry.destination.id, entry.arguments)
}

/**
 * 两条回退栈条目是否同目的地且**声明的参数**同值（[replayTopLevelEntry] 的去重判据）。
 *
 * 只比目的地自己声明的参数（连接列表的 `sourceType` 这类）：navigation 会把内部键（如
 * `android-support-nav:controller:deepLinkIntent`）塞进同一个 Bundle，而它只在部分条目上存在，
 * 整个 Bundle 比会把手柜/首页这类无参数层误判成不同层（去重失效，返回路径随绕圈变长）。
 */
private fun sameDestinationAndArgs(a: NavBackStackEntry, b: NavBackStackEntry): Boolean {
    if (a.destination.id != b.destination.id) return false
    return a.destination.arguments.keys.all { key ->
        a.arguments?.get(key)?.toString() == b.arguments?.get(key)?.toString()
    }
}

/**
 * 弹掉 [index] **之上**的所有层：浏览层导航重开路径时的收旧段、
 * 与抽屉顶层入口露浏览层（[revealBrowsingLayerBelowTopLevelEntries]）写的是同一段。
 */
private fun popAbove(nav: NavHostController, index: Int) {
    val stack = nav.currentBackStack.value
    if (index >= stack.size - 1) return
    repeat(stack.size - 1 - index) { nav.popBackStack() }
}

/**
 * 浏览页是否接管返回（由 [BrowserBackStackSyncTest] 锁定）：判据全部取自**实际回退栈**，
 * 历史只作一致性校验——`true` ⇔ 这次返回一定落到「历史里那一层」。
 * - 栈里当前页之下紧挨着的那一条也必须是浏览层（弹一层落到的是它，不是连接列表/首页）；
 * - 历史镜像必须与栈里的浏览层**逐层一致**（游标漂移、上一会话（Activity 会话）残留、两段浏览层并存时都会不一致）。
 *
 * 任一条不成立就交回系统（`enabled = false`）：系统照旧弹一层，用户看到的仍是逐级返回；
 * 被弹出来的浏览页显示时按栈重建镜像（[syncBrowseHistory]），漂移因此最多影响一次返回、不会弹到错误层级。
 */
internal fun browseBackInterception(nav: NavHostController, history: BrowseHistory): Boolean {
    val stack = nav.currentBackStack.value
    val layers = browseLayersOnStack(nav)
    if (layers.size < 2) return false
    if (browseLocationOf(stack[stack.size - 2]) != layers[layers.size - 2]) return false
    // 镜像逐层一致即蕴含 canGoBack（层数 ≥ 2），不再单独断言
    return history.path() == layers
}

/**
 * 路由 → 顶层落点映射：本表是**这份映射**的唯一来源——启动写点判定（[topLevelRecordFor]）
 * 与抽屉入口集合（[DRAWER_TOP_LEVEL_ROUTES]）都从它派生，两处不再各写一份。
 *
 * 注意本表同时是抽屉顶层集合的定义源：往表里加一条路由会**同时**改动 [drawerRegionStart] 的区域下界与
 * [revealBrowsingLayerBelowTopLevelEntries] 的 anchor（两者都按 [DRAWER_TOP_LEVEL_ROUTES] 判层）。
 * 因此 `TopLevelStopRecordTest` 把派生出的集合钉为「恰为 首页/书柜/设置」：加路由时先在那里撞红，
 * 逼一次「这真的是抽屉顶层入口吗」的判断，不再静默漂移。
 *
 * **阅读器不进本表**：它不是抽屉顶层入口，且它的写点是「清」而不是「记」（见 [topLevelRecordFor]）。
 */
private val TOP_LEVEL_ROUTES: Map<String, LastTopLevel> = mapOf(
    Routes.HOME to LastTopLevel.HOME,
    Routes.BOOKSHELF to LastTopLevel.BOOKSHELF,
    Routes.SETTINGS to LastTopLevel.SETTINGS,
)

/**
 * 抽屉顶层入口：首页/书柜/设置。阅读器入口另有换 entry 的语义（[openReaderFromDrawer]）。
 *
 * `internal` 而非 `private`：由 `TopLevelStopRecordTest` 直接断言集合内容（它同时是两处
 * 区域判据，加路由时不能静默漂移）。
 */
internal val DRAWER_TOP_LEVEL_ROUTES = TOP_LEVEL_ROUTES.keys

/**
 * 抽屉顶层入口的导航：**压在当前界面之上**，返回因此回到进入前的界面
 * （例如进入设置前的那个子文件夹），而不是把回退栈重置成 [首页, 入口]。
 *
 * **叠层收口**：抽屉顶层入口可占用的区域 = 「进入抽屉前的那个界面」之上的部分
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
 * 抽屉顶层入口可占用区域的下界：取「栈里最后一个非顶层入口层」（浏览层 / 连接列表 /
 * 路由图入口）与「**栈底那个根首页**」的较大者再加一。
 *
 * 根首页也当下界，是因为它是应用栈底、不是抽屉压出来的——所以它不算「目标入口已在区域内」：
 * 从子文件夹点抽屉「首页」仍要压一层，返回才回得到进入前的界面。
 * 区域下界严格在浏览层之上，所以「回到区域内那一层」永远弹不到浏览层与根首页。
 */
private fun drawerRegionStart(stack: List<NavBackStackEntry>): Int {
    val anchor = stack.indexOfLast { it.destination.route !in DRAWER_TOP_LEVEL_ROUTES }
    val rootHome = stack.indexOfFirst { it.destination.route == Routes.HOME }
    return maxOf(anchor, rootHome) + 1
}

/**
 * 收掉栈顶连续的抽屉顶层入口层，露出其下的浏览层——**只在露出的确实是浏览层时才收**：
 * 栈里没有浏览层时（如「书柜 → 抽屉阅读器」）不动栈，那一层要靠返回逐级回到。
 * 根首页（栈底）与浏览层都不动，因此历史无需同步。
 */
internal fun revealBrowsingLayerBelowTopLevelEntries(nav: NavHostController) {
    val stack = nav.currentBackStack.value
    val anchor = stack.indexOfLast { it.destination.route !in DRAWER_TOP_LEVEL_ROUTES }
    if (anchor < 0 || stack[anchor].destination.route != Routes.BROWSER) return
    popAbove(nav, anchor)
}

/**
 * 抽屉「阅读器」入口的导航：先收掉栈顶的顶层入口层，再把本次浏览位置压到阅读器之下——
 * 返回因此落到**进入前的那个子文件夹**（而不是叠一层重复的浏览页或直接回首页）。
 * 「没选来源 / 没有阅读记录」的中文提示留在调用点（那里有 Context）。
 *
 * 与启动还原的 OpenReader 分支（`AppNav` 落地里的同名分支）形状相同、两处差异**有意保留**：
 * - 浏览层来源：本处是**会话内的当前浏览位置**（单层，历史随进程存活），启动侧是**落盘的层级链**（整条）；
 * - 阅读器 entry：本处走 [newReaderNavOptions]（换一条新 entry，与读内换书同一套语义），
 *   启动侧是 `launchSingleTop`——落地只在栈顶是中转页时跑，那时栈里不可能已有阅读器 entry，两者等价。
 * 两者都走 [pushBrowserPath]（已在栈顶的那一层不重复压），压浏览层的形状因此只有一处。
 */
internal fun openReaderFromDrawer(nav: NavHostController, history: BrowseHistory, last: LastRead) {
    revealBrowsingLayerBelowTopLevelEntries(nav)
    history.current?.takeIf { it.connId == last.connId }?.let { pushBrowserPath(nav, listOf(it)) }
    nav.navigate(Routes.reader(last.bookId), newReaderNavOptions())
}

/**
 * 冷启动直进阅读器的导航：与普通进档同款滑入（呈现方式由前后路由判，不需要入口传例外）。
 * 落地顺序仍是「根首页 → 落盘路径上的浏览层 → 阅读器」（见 `AppNav` 启动落地的 OpenReader 分支：
 * `resetBrowseHistoryForStartup` + `pushBrowserPath` 在导航之前），因此这一屏的旧屏是刚落地的**浏览层**，
 * 滑动时它原地静止当背景（允许它还在加载：预置快照多数情况已就绪，没就绪时露「加载中…」可接受）。
 */
internal fun navigateStartupReader(nav: NavHostController, bookId: String) {
    nav.navigate(Routes.reader(bookId)) { launchSingleTop = true }
}

/**
 * 设备排查「返回被扔回首页/直接退出」的观测点（同一片根因）：一行给出
 * **回退栈深度 + 栈顶路由 + 浏览历史游标与能否后退**。默认关闭，开关与查看见 [PerfTiming]：
 * `adb shell setprop log.tag.ComicViewerPerf DEBUG` 后 `adb logcat -s ComicViewerPerf`。
 */
internal fun navObservation(nav: NavHostController, history: BrowseHistory): String {
    val cursor = history.current?.let { "${it.connId}/${it.containerId}" } ?: "null"
    return "route=${nav.currentDestination?.route} depth=${nav.currentBackStack.value.size} " +
        "historyCurrent=$cursor historyCanGoBack=${history.canGoBack}"
}

/**
 * 导航观测事件名：这些名字是设备验收的**唯一证据通道**，收成常量免得四处字面量与
 * [PerfTiming] KDoc 清单漂移（由 [NavObservationTest] 锁形）。
 */
internal object NavEvent {
    const val STARTUP_SKIP = "nav startup skip"
    const val STARTUP_LAND = "nav startup land"
    const val STARTUP_FALLBACK = "nav startup fallback"
    const val BROWSE_BACK = "nav browseBack"

    /**
     * 每次**被组合到的栈变化**都产一行（取数级，零行为变化）。
     *
     * 粒度 = **回退栈的栈项 id 列表**（与滑动动画同一处判据）：同一路由模板下的相邻两层
     *（文件夹→文件夹 / 返回上级 / 换书）**也算变化、也产行**；而同一帧内不挂起地连压的多层
     * **只产最后一行** —— 中间那几层根本没被组合过。**这不是本打点的缺口，正是它要报的事实**：
     * 首页那一行出现，就说明首页真的被组合过一帧。
     *
     * **组合期同步打**（不是 `LaunchedEffect`）：只存在**一帧**的首帧会在协程跑起来之前就被 key 变化
     * 取消，日志因此漏行 —— 设备取数（2026-09-28：看到首页闪，日志里却没有这一行）后改成现口径。
     * 代价：组合被丢弃/重建时同一栈可能重复产行。
     *
     * 与过渡时刻线（`NavTransitionTimeline`）**不同**：它不依赖过渡动画，因此**硬切落地**（首页 / 书柜 /
     * 设置 / 浏览层这些中间真的让出了一帧的跳）也看得见 —— 那些跳在时刻线上是空的：
     * 时刻线的闸门是过渡自己的时长（硬切时长 0，也就不 `begin`），
     * 帧时长探针 `navTransition` 通报的闸门则是 `beginProbe` 的 `windowMillis == 0`。
     * **口径边界**：这一行只证明这一栈状态**被组合过**；「真的被画到屏上」要另加绘制打点（本笔不做）。
     */
    const val ROUTE = "nav route"
}

/** 一行导航观测日志（事件名 + [navObservation]）。事件名与字段名由 [NavObservationTest] 锁定。 */
internal fun navObservationLine(event: String, nav: NavHostController, history: BrowseHistory): String =
    "$event ${navObservation(nav, history)}"

/**
 * 组合期同步打点用的「上一次栈项 id 列表」（取数级，见 `AppNav` 里 `routeLogMemo` 的调用点注释）：
 * 故意用**非快照**的可变持有对象 —— 打点不应因为自己而多订阅一次重组。
 */
internal class RouteLogMemo {
    var ids: List<String> = emptyList()
}

/** 「上次退出时是否停在阅读器」的写点守卫（纯函数，由 [StartupReadingFlagTest] 锁定）：
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
 * 「顶层落点记录」的写点判定（纯函数，由 [TopLevelStopRecordTest] 锁定）：写、清、不动三态。
 * 返回 null = 本次不写（路由未定/中转页/来源列表这类中层界面）。
 */
internal sealed interface TopLevelRecord {
    /** 停在顶层路由：记下它，启动时「上次停留的位置」落这里 */
    data class At(val top: LastTopLevel) : TopLevelRecord

    /** 离开顶层（停在浏览层 / 阅读器）：清掉记录，位置改由「上次停留的位置」说话 */
    data object Clear : TopLevelRecord
}

/**
 * 顶层落点记录的路由判定：抽屉的三个顶层入口（首页/书柜/设置）写它，浏览层与阅读器清它，其余不动。
 *
 * 为什么写点挂在路由上而不是各个界面的显示回调：抽屉顶层入口**压在当前界面之上**（见 `navigateTopLevel`），
 * 路由一变就是用户到了那一层；离开顶层回到浏览层同样是一次路由变化。两者靠同一个 `LaunchedEffect(currentRoute)`
 * 对齐，就不会出现「记了顶层却还停在浏览层」的半成品状态。
 *
 * 为什么进阅读器要**清**：阅读器可以从顶层路由经抽屉进入，
 * 那一帧已把记录写成该顶层路由；不清的话「上次停留的位置」在阅读器里退出就会落到那个顶层路由，
 * 而改前的口径是落回上次停留的浏览目录（期望「在阅读器退出 ⇒ 保持现状」）。
 * **清只清顶层键**：[StartupStore.clearTopLevel] 不碰 `lastBrowsing`——开书失败的兜底还要用它（见 [resolveStartupRead]）；
 * 阅读器里退出时的落点仍由 `was_reading` 那条链决定（[readingFlagToRecord]）。
 *
 * 顶层路由只有一份清单（[TOP_LEVEL_ROUTES]）：将来加顶层入口漏改一处不再会静默不记录。
 */
internal fun topLevelRecordFor(route: String?): TopLevelRecord? = when {
    route == null -> null
    route == Routes.READER || route == Routes.BROWSER -> TopLevelRecord.Clear
    else -> TOP_LEVEL_ROUTES[route]?.let { TopLevelRecord.At(it) }
}

/**
 * 把 [topLevelRecordFor] 的判定结果落到 [StartupStore]：`AppNav` 的 `LaunchedEffect(currentRoute)` 调它。
 *
 * 单独抽成函数是为了让「进阅读器要清顶层键」这类**跨键约束**（清它，但 `lastBrowsing` 与 `was_reading` 原封不动）
 * 能被用例直接钉住——写在 `LaunchedEffect` 里的那段 `when` 无法被单测覆盖（仓库无 Compose UI 测试基建）。
 *
 * `At` 分支同时把**这条顶层路由之下那段浏览链**落盘（[StartupStore.recordTopLevelBrowseChain]，
 * 链取自实际回退栈）——重启落在同一条顶层路由时靠它把层级重建在下面（从设置返回回到进入前的子文件夹）。
 * 两个键由这一处判据、这一帧一起写（写点唯一），因此「这段链真的压在这条顶层路由之下」是结构性事实；
 * 但两者**不同寿命**：`Clear` 分支只调 [StartupStore.clearTopLevel]，[StartupStore.clearBrowsing] 也不动这份链
 * ——读侧凭 `lastTopLevel` 相等才用它，留下的旧值不会被误用；措辞见 [StartupStore.recordTopLevelBrowseChain]。
 * 它与 [StartupStore.browsingPath] 的写点无关：那份是「上次停留的浏览路径」，
 * 用户在浏览层退回首页后它仍留着旧值，不能拿来当这条链用，见 [StartupStore.topLevelBrowseChain]。
 */
internal fun recordTopLevelForRoute(route: String?, nav: NavHostController) {
    when (val record = topLevelRecordFor(route)) {
        is TopLevelRecord.At -> {
            StartupStore.recordTopLevel(record.top)
            StartupStore.recordTopLevelBrowseChain(browseLayersOnStack(nav))
        }
        TopLevelRecord.Clear -> StartupStore.clearTopLevel()
        null -> Unit
    }
}

/**
 * 顶层落点对应的路由（纯函数）：`StartupTarget` → 路由这**一份映射只此一处**——
 * `prepareStartup`（取链、校验连接）与启动落地那一支都读它，两处不再各写一份 `when`。
 *
 * 但它**不是**「加第四个顶层入口只需改这一处」：两支的分支枚举（`prepareStartup` 的 `when` 与落地侧的 `when`）
 * 与写侧的表 [TOP_LEVEL_ROUTES] 仍各需同步一次；本函数只消除「目标 → 路由」这层重复。
 * `null` = 该目标不是顶层落点（浏览层 / 阅读器，两者各自的落地分支另有口径）。
 */
internal fun topLevelRouteOf(target: StartupTarget): String? = when (target) {
    StartupTarget.OpenHome -> Routes.HOME
    StartupTarget.OpenBookshelf -> Routes.BOOKSHELF
    StartupTarget.OpenSettings -> Routes.SETTINGS
    is StartupTarget.OpenBrowser, is StartupTarget.OpenReader -> null
}

/**
 * 重建了「顶层落点之下的浏览链」时把会话来源备好：与浏览落点那一支**同一对调用**
 * （`ServiceLocator.session.browsingSourceFor` + `ServiceLocator.session.adopt`，不另造第二条通道）——
 * 链重建出来的就是浏览页，不备来源的话随后点抽屉「阅读器」会命中 `currentSource == null` 守卫、弹
 * 「请先选择一个来源」。来源与连接 id 必须**一起**落槽（见 `ServiceLocator.session.adopt` 的 KDoc）。
 * 建不起来源不阻断（浏览页会按路由 connId 自行解析并显示重试）；会话级实例因此跨页面存活。
 */
internal suspend fun adoptSessionSourceForBrowseChain(conn: ConnectionEntity, connId: Long) {
    catchingNonCancellation { withContext(Dispatchers.IO) { ServiceLocator.session.browsingSourceFor(conn) } }
        .onSuccess { ServiceLocator.session.adopt(it, connId) }
}

/**
 * 顶层落点之下那段链能不能用（纯函数）：链为空时原样返回（本次落点下面本来就没有浏览层）。
 * [connectionPresent] 是「链指向的连接还在不在」的查库结果：
 * - 查到、但没有这一行 ⇒ 连接已被删：整条丢掉（不把用户扔进一条打不开的浏览层）；
 * - **读库失败**（`isFailure`，暂时性故障）⇒ **保留**链：读不到不等于连接被删，丢掉会让用户当场退回
 *   「设置返回 → 首页」（要消灭的现象，只是偶发一次）；与浏览分支同一口径（见 `prepareStartup`）。
 */
internal fun usableTopLevelBrowseChain(
    candidate: List<BrowseLocation>,
    connectionPresent: Result<Boolean>,
): List<BrowseLocation> = when {
    candidate.isEmpty() -> candidate
    connectionPresent.isFailure -> candidate
    connectionPresent.getOrNull() == true -> candidate
    else -> emptyList()
}

/**
 * 顶层落点分支的取数结论（[resolveTopLevelBrowseChain] 的产物）：[chain] = 该建在顶层落点之下的链
 * （空 = 不建）；[connection] = 能拿来备会话来源的连接实体（读到才有，读不到时为 null）。
 */
internal data class TopLevelBrowseChainResolution(
    val chain: List<BrowseLocation>,
    val connection: ConnectionEntity?,
)

/**
 * 顶层落点之下的浏览链：**两次取数的取舍 + 算链**（可单测接缝）。
 * 取舍规则只有一条——**只在第一次读失败时才采信重取结果**；[candidate] 非空（调用点已判过「链为空就不查库」，
 * 本函数不重复那个判据）：
 * - 第一次读到实体 ⇒ 链保留，实体随手带出（备会话来源），**不重取**；
 * - 第一次读到「没有这一行」（连接已删）⇒ 丢链，**也不重取**（结论已明确）；
 * - 第一次失败 + 重取读到实体 ⇒ 链保留；重取读到「没有这一行」⇒ **丢链**
 *   （连接确已删：一条连不上的浏览层不压在顶层落点之下）；
 * - 两次都失败 ⇒ 保留链（读不到 ≠ 连接被删，见 [usableTopLevelBrowseChain]）＋没有实体可备来源。
 *
 * [fetchConnection] 由调用点传入（它手里才有 connId）：本函数只决定**调几次**，因此不需要 Compose、可直接单测
 * （五支由 `BrowserBackStackSyncTest` 钉住）。
 */
internal suspend fun resolveTopLevelBrowseChain(
    candidate: List<BrowseLocation>,
    fetchConnection: suspend () -> Result<ConnectionEntity?>,
): TopLevelBrowseChainResolution {
    val first = fetchConnection()
    val effective = if (first.isFailure) fetchConnection() else first
    return TopLevelBrowseChainResolution(
        chain = usableTopLevelBrowseChain(candidate, effective.map { it != null }),
        connection = effective.getOrNull(),
    )
}

/**
 * 启动落在顶层入口（首页/书柜/设置）时该重建在它**之下**的那段浏览链（纯函数）：
 * 只有**顶层落点记录就是这条路由**时才用 [chain]——显式把启动页面设成首页/书柜、或兜底落首页时，
 * 本次落点并不是「上次停的那条顶层路由」，那份链与它无关（拿它重建只会平白多出一段返回路径）。
 *
 * 旧数据没有这份链（键缺失 ⇒ [chain] 为空）⇒ 不建链：升级后第一次启动的返回值与改前逐层一致
 * （拿 [StartupStore.browsingPath] 兜底会在首页下面接上一条陈旧路径，那正是要避开的现象）。
 * [route] 传 [topLevelRouteOf] 的结果：非顶层落点时同样不建链。
 */
internal fun browseChainBelowTopLevel(
    route: String?,
    recorded: LastTopLevel?,
    chain: List<BrowseLocation>,
): List<BrowseLocation> = chain.takeIf { route != null && TOP_LEVEL_ROUTES[route] == recorded }.orEmpty()

/**
 * 启动落地的**栈底**那一跳：压「首页」当根，并把中转页弹掉（改为**按支调用**）。
 *
 * 为什么要从「四支共用」改成「按支调用」（B 案）：落浏览层那一支要把它挪进 [withPrimedLayer] 的
 * 临界区（见 [landStartupBrowserLayer]）——原来它写在 `when` 之前，于是「压首页」与「预置浏览层」之间
 * 夹着预置那次挂起读，主线程在那一窗口里让出一次，首页被组合出一帧（设备上看到的「重启闪首页」）。
 * 其余两支（顶层落点支 / 阅读器支）逐字保持原来的「先压首页、再同步连压」顺序。
 *
 * `launchSingleTop`：补跑时首页已在栈顶也不再叠第二层；`popUpTo(STARTUP){inclusive}`：中转页不进返回链。
 * 启动落地的兜底分支（`onFailure` 落首页）也走这一处 —— 仓内「压启动栈底」只剩这一份 navigate。
 */
internal fun pushStartupRootHome(nav: NavHostController) {
    nav.navigate(Routes.HOME) {
        popUpTo(Routes.STARTUP) { inclusive = true }
        launchSingleTop = true
    }
}

/**
 * 启动落地「落浏览层」那一支（B 案）：
 * **压首页**与**压浏览链**整段圈进同一个预置临界区（[withPrimedLayer]）。
 *
 * 要治的是什么（设备上「重启闪首页」）：目标层是浏览层时，先把首页压成栈底、
 * 再预置目标层快照——而预置是挂起读（`Dispatchers.IO`），主线程在「压首页」与「压浏览链」之间让出一次，
 * 首页因此被组合并画出一帧。两跳挪进同一临界区后，两跳之间不再挂起（都在同一帧里同步压完）：首页虽然被
 * [pushStartupRootHome] 压成**当前目的地**，但同一帧内就被 [pushBrowserPath] 覆盖，因此**不会被组合到任何一帧**
 * —— 这才是打点判据「诊断日志里 `nav route route=home` 不出现」的意思（口径见 `StartupBrowserLandingTest` 同项注释）。
 *
 * 顺序与键的两条不变量不变：先压首页再压链（返回语义）；预置的键必须是**目标层**（[containerId] 由调用点
 * 从 `StartupTarget.OpenBrowser` 直接取，不用 `path.lastOrNull()` 反推——写错层是硬故障）。
 * 预置与导航仍在同一个临界区里跑，`BrowseLayerNavigationOrderTest` 钉的「预置与导航同一临界区」因此不变。
 */
internal suspend fun landStartupBrowserLayer(
    nav: NavHostController,
    history: BrowseHistory,
    path: List<BrowseLocation>,
    source: Source?,
    containerId: String?,
) {
    resetBrowseHistoryForStartup(history, path)
    withPrimedLayer(source, containerId) {
        pushStartupRootHome(nav)
        pushBrowserPath(nav, path)
    }
}

/**
 * 顶层落点的启动落地：先把**上次停在它之下**的那段浏览链逐层压在根首页之上，
 * 再压这条顶层路由本身。
 *
 * 顺序是承重的：链在下面 ⇒ 从这条顶层路由返回先逐级回到那段链（设置 → 子文件夹 → 上级 → 根层），
 * 链走完才回到栈底那个根首页，首页再返回才退出 APP。
 * [chain] 为空（旧数据没有这份记录、或本次落点不是上次停的那条顶层路由）时与改前口径逐字一致：
 * 首页不导航（它已是栈底），书柜/设置压一层。
 */
internal fun landStartupTopLevel(
    nav: NavHostController,
    history: BrowseHistory,
    route: String,
    chain: List<BrowseLocation>,
) {
    resetBrowseHistoryForStartup(history, chain)
    pushBrowserPath(nav, chain)
    // 首页且链为空：它就是栈底，绝不再压第二层（改前口径）；有链时压一层，
    // 复现「抽屉压在链之上的那个首页」（与线上回退栈同形，不额外多叠）
    if (chain.isNotEmpty() || route != Routes.HOME) nav.navigate(route) { launchSingleTop = true }
}

/**
 * 启动还原「上次阅读的书」的结果：落地目的地 + 需要告知用户的一句中文提示（无需提示时为 null）。
 * 目的地与提示出自同一个判断点（[resolveStartupRead]），因此不会出现「回落了却没提示」。
 */
internal data class StartupReadOutcome(
    val target: StartupTarget,
    val notice: String? = null,
    /**
     * 本次落点之下要重建的那段浏览链（只有顶层入口分支会带）：空 = 这条落点下面没有浏览层
     * （首页本就是栈底 / 旧数据没有这份记录 / 本次落点不是上次停的那条顶层路由）。
     */
    val chain: List<BrowseLocation> = emptyList(),
)

/** 回落提示（AC「给中文提示」）：只说发生了什么、现在在哪；不出现异常原文、路径或 id */
private const val NOTICE_BACK_TO_BROWSING = "上次阅读的书已不是一本书（目录结构可能已变化），已回到浏览列表"
private const val NOTICE_BACK_TO_HOME = "上次阅读的书已不是一个可读的书，已回到首页"

/**
 * 启动还原「上次阅读的书」前的可读性判定（由 [StartupReadFallbackTest] 锁定）。
 *
 * 为什么需要：把「本层有子目录/压缩包」的目录由书改判为容器——**上一版落盘的** `lastRead.bookId`
 * 完全可能正指向这样一个目录（或已被删除/改名的文件）。旧路径直接把它当书打开：`openBook` 抛
 * `IllegalArgumentException`，文本形如「不是一本书：<本机绝对路径>」，界面把这行原文显示给用户，
 * 人还停在阅读器里没有下一步。因此在**导航之前**先试开一次：
 * - 能开 → 照旧进阅读器。**0 页的书也算能开**（空/坏压缩包是书，件内确实没有图片）——界面按
 *   `pageCount == 0` 显示中文空态，不在这里拦；
 * - 抛 [IllegalArgumentException]（不是书 / 越界：目录已被改判为容器、文件已删）→ **回落到浏览层**：
 *   优先「上次停留的位置」（与阅读器入口一致：阅读器下面本来就压着它），没有可用位置就用这个 id 自己
 *   ——AC 场景里它正是那个「已变成容器的目录」，点开就是它的条目列表（只有它现在真能当容器列出来时才用它，
 *   否则回落到首页：把一个列不出来的层交给浏览页，只会再报一次错）；
 * - **其余失败不算「不是书」**（断链/超时这些暂时性失败）→ 照旧进阅读器，沿用
 *   「打开失败 + 点此重试」界面，不回退那个口径。
 *
 * 两条回落分支都带上 [StartupReadOutcome.notice]（由启动 effect 用非阻塞 Toast 展示），
 * 用户能知道为何没回到上次那本书。
 *
 * 线程语义：**本接缝自己把来源调用切到 [Dispatchers.IO]**（调用方在启动 effect 的 Main 上）。
 * 仓库的通常规范是「调用方负责切 IO」（如 `ListComposition`），但这里是启动准备层的内部步骤、
 * 且两个调用都是可能阻塞的来源 I/O（本地 = provider IPC、SMB/WebDAV = 同步 socket、Komga = 同步 HTTP）——
 * 不切就会在主线程抛 `NetworkOnMainThreadException`，而它**不是** `IllegalArgumentException`，
 * 于是上面的回落判定在网络来源上会静默失效。切在接缝内而不是调用点，同时保证任何调用方都安全。
 * 取消语义不变：`withContext` 内的 [catchingNonCancellation] 把 `CancellationException` 原样抛出。
 *
 * 代价：多一次 `openBook`（回落路径上再多一次 `listEntries`）。压缩包包内条目按 id+mtime 有会话级缓存，
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
