package com.cc3301.comicviewer.ui

import android.app.Activity
import android.os.SystemClock
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
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
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.IntOffset
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
import com.cc3301.comicviewer.core.view.NavTransitionProbe
import com.cc3301.comicviewer.core.view.pageDecodeWidthPx
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
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

    /**
     * 阅读器路由（票 #111 最终口径）：多带一条**方向通道** [ARG_READER_ENTER]——「上一本」与
     * `forward = false` 的跨书跳转给 [ReaderEnter.BACK]（新书从左滑入），其余入口给 [ReaderEnter.FORWARD]。
     * 方向因此到得了导航层（`NavHost` 的过渡 lambda 只能读 entry 的参数，读不到「按的是哪个按钮」），
     * 且用路由参数钉得住（[navTransitionDirection] 的用例）。
     */
    const val READER = "reader/{bookId}?$ARG_READER_ENTER={$ARG_READER_ENTER}"

    fun browser(connId: Long, containerId: String?): String =
        "browser/$connId?container=${android.net.Uri.encode(containerId ?: "")}"

    /** 浏览根层（containerId 为空）：书柜点连接与首页点连接落到同一处（票 #49） */
    fun browserRoot(connId: Long): String = browser(connId, null)

    fun reader(bookId: String, enter: String = ReaderEnter.FORWARD): String =
        "reader/${android.net.Uri.encode(bookId)}?$ARG_READER_ENTER=$enter"
}

/**
 * 阅读器路由的方向参数名（票 #111）：[Routes.READER] 的路由模板与读写两侧都只认这一个拼法
 * （`Routes.READER` 里的 `?enter={enter}` 与 [Routes.reader] 由它拼出，改一处会立刻编译不过/用例变红）。
 */
internal const val ARG_READER_ENTER = "enter"

/**
 * 阅读器方向参数的取值（票 #111 最终口径）：[FORWARD] = 新屏从右滑入（默认），[BACK] = 从左滑入，
 * [FADE] = 只淡入不滑（冷启动直接落进阅读器——那时旧屏是刚落盘的浏览层，不是中转页）。
 * 只有「上一本」与 `forward = false` 的跨书跳转给 [BACK]；**本对象是这两处入口与导航层之间的唯一契约**。
 */
internal object ReaderEnter {
    const val FORWARD = "forward"
    const val BACK = "back"

    /** 冷启动落地：只淡入、不滑（[navTransitionDirection] 的 Fade 分支） */
    const val FADE = "fade"

    /** 入口给的方向 → 路由参数值（只有这三个合法值） */
    fun of(direction: NavTransitionDirection): String = when (direction) {
        NavTransitionDirection.Forward -> FORWARD
        NavTransitionDirection.Back -> BACK
        NavTransitionDirection.Fade -> FADE
    }
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
 * 导航过渡的方向（票 #111 最终口径）：**由入口显式给出**，不允许「猜方向」。
 *
 * - [Forward]：新屏**从右**整屏滑入——进入子文件夹 / 抽屉入口进入 / 进入阅读器 / 「下一本」与
 *   `forward = true` 的跨书换书；
 * - [Back]：新屏**从左**整屏滑入——返回上一级 / 抽屉返回 / 退出阅读器 / 「上一本」与
 *   `forward = false` 的跨书换书；
 * - [Fade]：只淡入不滑——冷启动直接落进阅读器（那时**没有旧屏**可滑）。
 */
internal enum class NavTransitionDirection { Forward, Back, Fade }

/**
 * 一次导航的方向判定（纯函数，由 `NavTransitionsTest` 锁定；票 #111 最终口径的行为矩阵）。
 *
 * [push] = 这次导航是压栈（`NavHost` 的 `enterTransition`/`exitTransition` 那一对）还是弹栈
 * （`popEnterTransition`/`popExitTransition` 那一对）：**返回类操作固定反向**，因此弹栈一律 [Back]。
 *
 * 压栈时：进阅读器看入口——[enterHint] 是**目标阅读器 entry** 的路由参数（[ARG_READER_ENTER]）：
 * 冷启动落地给 [ReaderEnter.FADE]（只淡入）、「上一本」与 `forward = false` 的跨书跳转给 [ReaderEnter.BACK]，
 * 其余给 [ReaderEnter.FORWARD]；**冷启动落地**（旧屏是中转页）在入口没给方向时也按只淡入处理。
 * 其余（层级导航 / 抽屉入口）一律 [Forward]。
 *
 * 三个判不出方向的入口因此按票面矩阵处理：浏览页点书 / 抽屉「阅读器」= 进入阅读器（[Forward]），
 * 冷启动落地 = [Fade]——**显式给**而不是猜：落地顺序把浏览层先压在阅读器之下，旧屏是浏览层而不是中转页
 * （见 [navigateStartupReader]，由 `StartupReaderTransitionTest` 用真实落地顺序钉住）。
 */
internal fun navTransitionDirection(
    push: Boolean,
    initialRoute: String?,
    targetRoute: String?,
    enterHint: String?,
): NavTransitionDirection {
    if (!push) return NavTransitionDirection.Back
    if (targetRoute != Routes.READER) return NavTransitionDirection.Forward
    return when {
        // 入口显式给的方向优先（票面「方向由入口显式给出」）：冷启动落地那条给 FADE
        enterHint == ReaderEnter.FADE -> NavTransitionDirection.Fade
        initialRoute == Routes.READER ->
            if (enterHint == ReaderEnter.BACK) NavTransitionDirection.Back else NavTransitionDirection.Forward
        // 兑现不到的地方兜底：旧屏就是中转页时同样没有旧屏可滑
        initialRoute == Routes.STARTUP -> NavTransitionDirection.Fade
        else -> NavTransitionDirection.Forward
    }
}

/**
 * 全局页面过渡（票 #111）：**横向整屏滑入划出 400ms**。
 *
 * 口径：新屏滑入**整整一屏**、旧屏**沿同向滑出整整一屏**，两屏都**不做 alpha 变化**：
 * - 位移：新屏 `translateX` 从 **±[SLIDE_TRAVEL_PERCENT]%** → 0（整屏）；旧屏从 0 沿**同向**移出同一个
 *   [SLIDE_TRAVEL_PERCENT]%（整屏）——四支位移读同一个常量，两屏同幅；
 * - **没有亮度交叉**：旧口径（旧屏淡到 0.55 / 新屏从 0.55 淡到 1 的镜像）**整套推翻**。真机反馈「旧屏所有
 *   导航都有残影」的根因就是它：旧屏只移 30% 又停在 0.55，交叉期间一直半透明地留在屏内；
 * - 曲线：两屏位移共用一条 [TRANSITION_EASING] = `CubicBezier(0.2f, 0f, 0f, 1f)`（起步快、收尾慢）；
 * - 时长 [DURATION_MILLIS] = 400ms；方向不变（压栈从右、弹栈从左）；
 * - **不做错开**（两屏同时动）；驱动**交给系统**（`NavHost` 的 `EnterTransition` / `ExitTransition`），
 *   不自己用 `graphicsLayer` 挪；**冷启动的纯淡入支**（[fadeEnter] / [fadeExit]）不动（那时没有旧屏，起点 alpha 仍是 0）。
 *
 * 沿革：本票批次 8/9 曾是「纵向 8dp 纯交叉」，2026-09-22 改成「横向整屏滑入」，2026-09-23 把新屏收到与旧屏
 * 同幅（30%）并加亮度镜像，2026-09-24 真机验收未过（新屏「飞快、没有过渡」——行程从整屏收到 30% 后太短；
 * 旧屏「所有导航都有残影」——见上）⇒ 第 7 轮**整套推翻**：回到整屏滑入划出，时长 400ms、曲线
 * `CubicBezier(0.2, 0, 0, 1)`，亮度交叉整条拿掉。
 *
 * **单测钉不住清单（不要把「未守护」写成「已守护」）**：`NavHost` 的四支 lambda 是否真的接到本对象、
 * 每个方向挑的是哪一支、**四支的位移 lambda 是否真的按 [SLIDE_TRAVEL_PERCENT] 换算**（换成别的字面比例，
 * 如 `{ it / 2 }`，在单测里与 `{ it * SLIDE_TRAVEL_PERCENT / 100 }` 是同一个不透明过渡对象），以及
 * **有人把亮度交叉加回来**（`fadeIn` / `fadeOut` 的参数不可观测）——三者都读不到（`slideInHorizontally` 的
 * lambda、`fadeIn` 的 `initialAlpha` 与 `CubicBezierEasing` 对象都不可读，反射白名单为空，见 SPEC 的
 * Testing Decisions）。把 [SLIDE_TRAVEL_PERCENT] 改回 30 这类回归**能**被本文件的常量断言咬住；上面那三种
 * 咬不住，守护只有下面的真机目视项。`NavTransitionsTest` 钉的是 [DURATION_MILLIS] / [SLIDE_TRAVEL_PERCENT]
 * 两个常量值与两条曲线本身，以及「六支都不是 None」「三个方向各是一支」「每支只建一次（重组不重启动画）」
 * 这几条结构断言，不声称更多。
 *
 * 方向**由入口显式给出**（[navTransitionDirection]）：四个 lambda 每次导航只挑**同方向**那一对预先建好的
 * 实例（属性初始化，不是每次读取新建），因此 `AnimatedContent` 不会因重组重启动画。这一半由
 * `NavTransitionsTest` 用 `assertSame` 钉住；另一半（`NavHost` 调用点是否真的接上本对象）属组合期行为，
 * 仓库无 Compose UI 测试基建（SPEC 把 UI 层交给手动验收），靠该用例 KDoc 里的真机判定方法兜住。
 *
 * 已知代价（维护者已知并接受）：
 * - 位移走**布局阶段**（Compose 1.7.2 的 `EnterExitTransitionModifierNode` 每帧在 measure/placement 摆放
 *   整屏内容，只有 alpha/scale 走绘制层）⇒ 过渡期间每帧都在重新摆放两屏；整屏行程（本轮 100%，上一轮 30%）
 *   是每帧最重的组合，掉帧风险最高的就是它：先上线拿量化数据（`NavTransitionProbe`，挂在导航壳上、覆盖
 *   **所有**导航过渡），不过关不阻塞本票交付；
 * - **不做自动降级**：掉帧时不会自己退化成淡入；
 * - 过渡窗口（400ms）存在期间，正在退场的那一屏**仍接收点击**（原 #99 的机制，窗口由 #107 的 0ms 变回
 *   300ms、本轮又变 400ms）。拦截它需要「过渡期间不吃点击」的新机制，超出本票范围。
 */
internal class NavTransitions {

    /**
     * 位移规格：**新屏滑入与旧屏滑出共用这一条**（时长与曲线两屏一致），
     * 差别只在各支 lambda 给的位移量（同向、取反号）。
     */
    private val slideSpec = tween<IntOffset>(DURATION_MILLIS, easing = TRANSITION_EASING)

    /**
     * 冷启动纯淡入支的规格（[fadeEnter] / [fadeExit]）：端点 alpha 是 0 / 1（那时没有旧屏，不做半透明交叉）。
     * 滑入划出那四支**不再带亮度**（旧口径的 0.55 镜像整套推翻，见类 KDoc 的沿革）。
     */
    private val alphaSpec = tween<Float>(DURATION_MILLIS, easing = TRANSITION_EASING)

    /** 压栈：新屏从右**整屏**滑入（位移读两屏共用的 [SLIDE_TRAVEL_PERCENT]） */
    private val forwardEnter: EnterTransition =
        slideInHorizontally(slideSpec) { it * SLIDE_TRAVEL_PERCENT / 100 }

    /** 弹栈：新屏从左**整屏**滑入（与压栈取反号） */
    private val backwardEnter: EnterTransition =
        slideInHorizontally(slideSpec) { -it * SLIDE_TRAVEL_PERCENT / 100 }

    /** 冷启动落地：**没有旧屏**，只淡入（起点 alpha 仍是 0） */
    private val fadeEnter: EnterTransition = fadeIn(alphaSpec)

    /** 压栈的旧屏：**同向**（向左）整屏滑出——滑满一屏即完全出屏，不残留 */
    private val forwardExit: ExitTransition =
        slideOutHorizontally(slideSpec) { -it * SLIDE_TRAVEL_PERCENT / 100 }

    /** 弹栈的旧屏：同向（向右）整屏滑出 */
    private val backwardExit: ExitTransition =
        slideOutHorizontally(slideSpec) { it * SLIDE_TRAVEL_PERCENT / 100 }

    /** 冷启动落地的旧屏（中转页）：只淡出（终点 alpha 0） */
    private val fadeExit: ExitTransition = fadeOut(alphaSpec)

    fun enter(direction: NavTransitionDirection): EnterTransition = when (direction) {
        NavTransitionDirection.Forward -> forwardEnter
        NavTransitionDirection.Back -> backwardEnter
        NavTransitionDirection.Fade -> fadeEnter
    }

    fun exit(direction: NavTransitionDirection): ExitTransition = when (direction) {
        NavTransitionDirection.Forward -> forwardExit
        NavTransitionDirection.Back -> backwardExit
        NavTransitionDirection.Fade -> fadeExit
    }

    companion object {
        /** 过渡时长（毫秒）：票面 r7 口径 **400ms**（原 300ms；真机反馈新屏「飞快、没有过渡」） */
        const val DURATION_MILLIS: Int = 400

        /**
         * 两屏的位移比例（整屏宽度的百分数）：票面 r7 口径 **100%**（整屏）。
         * 四支位移同读这一个值：新屏从 ±100% → 0，旧屏 0 → ∓100%（完全出屏，不残留）。
         */
        const val SLIDE_TRAVEL_PERCENT: Int = 100

        /**
         * 过渡曲线（减速型）：票面 r7 口径 `CubicBezier(0.2f, 0f, 0f, 1f)`——**起步快、收尾慢**。
         *
         * **两屏位移四处全读这一条**（新屏 / 旧屏 × 压栈 / 弹栈）。
         * **冷启动的纯淡入支**（[fadeEnter] / [fadeExit]）经同一条 [alphaSpec] 也读它。
         * 上一轮的 `CubicBezier(0.05, 0.7, 0.1, 1)` 随「30% + 0.55 镜像」那套口径一起作废。
         */
        val TRANSITION_EASING: Easing = CubicBezierEasing(0.2f, 0f, 0f, 1f)

        /**
         * 加速曲线 `CubicBezier(0.3f, 0f, 0.8f, 0.15f)`。
         *
         * **剩余唯一调用方：阅读菜单面板的消失支**（`ui/ReaderMenuTransitions.kt` 的 `exit` 直接读它，
         * 不另起别名）。页面过渡的两屏都不再用它，保留是因为菜单收起仍需要一条「越走越快」的曲线。
         */
        val EXIT_EASING: Easing = CubicBezierEasing(0.3f, 0f, 0.8f, 0.15f)
    }
}

/**
 * 启动落地要恢复的浏览路径（票 #70 r2 AC11，纯函数）：落盘路径与本次恢复到的位置**一致**时整条用，
 * 否则只恢复这一层。
 *
 * **术语**（票 #70 r2 评审 P2-3）：本处的「浏览路径」指**用户停留的层级链**（浏览页逐层下钻留下的那串位置）；
 * 与 `CONTEXT.md` 里连接的 `browsePath`（进连接后从哪一层开始，见 `KomgaConnectionConfig.browsePath`）同词不同义。
 *
 * 为什么还需要这个判据（票 #70 r2 复审后缩窄）：路径与「上次停留的位置」在浏览页显示时由**同一次调用**写
 * （[StartupStore.recordBrowsePosition]），因此正常浏览下的两者总是一致；不一致只剩「路径不是本会话写的」那几种
 * （手工改库/降级安装残留、连接不同）——那时拿旧路径重建会恢复到一个用户早就不在的位置（甚至会压出容器不存在的层），
 * 宁可只恢复到落盘的那个位置。连接不同（书与容器 id 只在各自连接内有效）同样不用。
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
 * 已经在**栈里**的那一层不重复压（票 #70 r3：进程被杀后系统还原出来的回退栈里已有这些层，再压一遍会多出一段，
 * 历史镜像与回退栈于是不一致，返回决议落到别的层）；抽屉「阅读器」入口传「当前浏览位置」单层时这条也与
 * `launchSingleTop` 同效，但不会像它那样把参数不同的 entry 就地改写。启动重建时栈里只有首页，跳过不会触发。
 */
internal fun pushBrowserPath(nav: NavHostController, path: List<BrowseLocation>) {
    path.forEach { level ->
        if (level in browseLayersOnStack(nav)) return@forEach
        nav.navigate(Routes.browser(level.connId, level.containerId))
    }
}

/**
 * 浏览层落盘（票 #70 r2 复审，r3 改为以回退栈为准）：先把浏览历史对齐到**回退栈里实际的浏览层**
 * （[syncBrowseHistory]），再把「停留位置」与**整条路径**一次写入（[StartupStore.recordBrowsePosition]）。
 * 调用点两处：`BrowserScreen` 显示某层时（`LaunchedEffect(connId, containerId)`）与 [navigateToBrowseLocation] 结束时。
 *
 * 为什么是**这里**写而不再只靠会话结束那次写：真机上更常见的退出是任务被划掉 / 进程被杀——那时没有 Activity finish，
 * [ServiceLocator.closeSession] 不会跑，只有逐层写下的这份路径可用；不写它就只剩「一层」，重启后按返回直接跳回首页
 * （维护者真机反馈的现象 A）。
 *
 * 为什么 r3 要在写之前对齐栈：r2 写的是历史侧自己记下的路径，而历史是进程级单例、没有谁保证它与回退栈逐层对应；
 * 不对应时「位置」与「路径最后一层」会分家，启动侧 [startupBrowsePath] 的判据不成立→只恢复一层→返回直接回首页
 * （上一轮号称修好、真机仍复现的那条）。路径现在只有一个来源：回退栈。
 */
internal fun recordBrowsePosition(nav: NavHostController, history: BrowseHistory, location: BrowseLocation) {
    syncBrowseHistory(history, nav)
    StartupStore.recordBrowsePosition(
        LastBrowsing(location.connId, location.containerId),
        history.path(),
    )
}

/**
 * 回退栈里**实际的浏览层**（栈底 → 栈顶，票 #70 r3）：本文件里记录、返回决议、启动重建一律以它为准，
 * 浏览历史只是它的镜像（[syncBrowseHistory]）。不读历史侧的任何值。
 */
internal fun browseLayersOnStack(nav: NavHostController): List<BrowseLocation> =
    nav.currentBackStack.value.mapNotNull { browseLocationOf(it) }

/**
 * 把浏览历史重建为回退栈里实际的浏览层（票 #70 r3）。幂等：两者本就一致时什么都不变（前进栈保留）。
 *
 * 为什么要有它：浏览历史是**进程级单例**，而回退栈随 Activity/进程重建——两者一旦不一致，
 * 返回处理器就会拿着「历史里的层」去弹「回退栈上的层」，用户看到的是弹到别的层级（本票「一按返回就回首页」）。
 * 修法不是让两边各自记好（记不住的场合已真实存在于真机），而是让**回退栈成为唯一事实来源**：
 * 每次浏览层变化（导航、显示、重建）都按栈重建镜像，历史侧没有独立记录动作可漂移。
 */
internal fun syncBrowseHistory(history: BrowseHistory, nav: NavHostController) {
    history.syncPath(browseLayersOnStack(nav))
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
 * 用户点击驱动的浏览层导航入口（票 #70 r3/r4）：`BrowserScreen.openEntry` 点条目下钻与 `openConnectionRoot`
 * 进连接根层都走它。另有两处不经它的直接调用（都在本文件）：启动重建/抽屉阅读器入口（[pushBrowserPath]）
 * 与鼠标前进侧键（按历史前进，不重开路径）。
 *
 * 口径（补记 3 + 票 #70 r4 评审 P1 裁决②）：
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
internal fun navigateToBrowseLocation(nav: NavHostController, history: BrowseHistory, location: BrowseLocation) {
    val stack = nav.currentBackStack.value
    val layers = browseLayersOnStack(nav)
    val drillsDown = browseLocationOf(stack.lastOrNull())?.connId == location.connId && location !in layers
    if (drillsDown) {
        nav.navigate(Routes.browser(location.connId, location.containerId))
    } else {
        reopenBrowsingPath(nav, location, layers)
    }
    history.clearForward()
    recordBrowsePosition(nav, history, location)
}

/**
 * 重开一条浏览路径（票 #70 r4，评审 P1 裁决②）：收掉栈里**已离开的那一段浏览层**，把它之上的非浏览层
 * 按原顺序压回，最后压上目标层及其上级。
 *
 * 为什么非浏览层要重放而不能一并丢弃：NavController 只能从栈顶往下弹，而旧浏览层不在栈顶（用户是从侧滑菜单/
 * 来源列表进去的）——直接弹会把它上面的层（书柜、来源列表、抽屉压上的首页）一起弹掉，从浏览层返回就被扔回首页
 * （r3 的越界行为：子文件夹 → 侧滑书柜 → 点该连接 → 返回落首页）。重放后返回落到进入前的那个界面。
 *
 * [layers] 是调用点已取好的「栈里当前的浏览层」（栈底 → 栈顶）：目标层在其中时，它到那一段的底之间那几层
 * 是它的上级（返回要逐级回到它们），一并重建；目标层不在其中时只压它自己。
 */
private fun reopenBrowsingPath(nav: NavHostController, location: BrowseLocation, layers: List<BrowseLocation>) {
    val stack = nav.currentBackStack.value
    val first = stack.indexOfFirst { browseLocationOf(it) != null }
    if (first < 0) {
        nav.navigate(Routes.browser(location.connId, location.containerId))
        return
    }
    val above = stack.drop(first).filter { browseLocationOf(it) == null }
    popAbove(nav, first - 1)
    above.forEach { replayTopLevelEntry(nav, it) }
    val chain = if (location in layers) layers.take(layers.indexOf(location) + 1) else listOf(location)
    pushBrowserPath(nav, chain)
}

/**
 * 把一条非浏览层按原样压回（票 #70 r4）：目的地 id + 参数原封不动（连接列表这类带参数的路由不用再拼一遍 route 串）。
 * 栈里已有同目的地同参数的层时跳过（`launchSingleTop` 同效）：否则反复绕圈会在栈里堆出重复的首页/来源层，
 * 返回路径随绕圈变长（维护者反馈的那类「一直嵌套下去」的另一种形状）。
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
 * 弹掉 [index] **之上**的所有层（票 #70 r4 抽出一处）：浏览层导航重开路径时的收旧段、
 * 与抽屉顶层入口露浏览层（[revealBrowsingLayerBelowTopLevelEntries]）写的是同一段。
 */
private fun popAbove(nav: NavHostController, index: Int) {
    val stack = nav.currentBackStack.value
    if (index >= stack.size - 1) return
    repeat(stack.size - 1 - index) { nav.popBackStack() }
}

/**
 * 浏览页是否接管返回（票 #70 r3，由 [BrowserBackStackSyncTest] 锁定）：判据全部取自**实际回退栈**，
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
    popAbove(nav, anchor)
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
 * 冷启动直进阅读器的导航（票 #111 AC-2，修复轮）：**显式给 [ReaderEnter.FADE]**。
 *
 * 为什么不能靠 [navTransitionDirection] 猜：落地顺序是「根首页 → 落盘路径上的浏览层 → 阅读器」
 * （见 `AppNav` 启动落地的 OpenReader 分支：`resetBrowseHistoryForStartup` + `pushBrowserPath` 在导航之前），
 * 因此这一屏的旧屏是刚落地的**浏览层**，不是中转页——只认 `initialRoute == Routes.STARTUP` 时会判成
 * 「进入阅读器」而从右滑入（票面 AC-2 要的是只淡入）。方向由入口给，与票面「方向由入口显式给出」同一口径。
 * 由 `StartupReaderTransitionTest` 用**真实落地顺序**（栈里先有浏览层）钉住。
 */
internal fun navigateStartupReader(nav: NavHostController, bookId: String) {
    nav.navigate(Routes.reader(bookId, ReaderEnter.FADE)) { launchSingleTop = true }
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
    val currentEntry = nav.currentBackStackEntryAsState().value
    val currentRoute = currentEntry?.destination?.route
    val history = ServiceLocator.browseHistory

    // ---------- 页面过渡与前置（票 #111）----------
    // 全局过渡规格：一个实例里按方向预先建好各支（参见 [NavTransitions] 的「一次导航 = 一次过渡」），
    // `NavHost` 的四支 lambda 每次导航只挑同方向的那一支返回——重组因此拿到同一实例，动画不会被重启。
    val navTransitions = remember { NavTransitions() }
    // 过渡期帧时长探针（票 #111 AC-9）：开关打开才注册监听器（默认关，零开销，同 #109 的口径）。
    val navTransitionProbe = remember { NavTransitionProbe() }
    val pushDirection: AnimatedContentTransitionScope<NavBackStackEntry>.() -> NavTransitionDirection = {
        navTransitionDirection(
            push = true,
            initialRoute = initialState.destination.route,
            targetRoute = targetState.destination.route,
            enterHint = targetState.arguments?.getString(ARG_READER_ENTER),
        )
    }
    // 前置解码宽度（票 #111）：与浏览页点击同一条路（[pageDecodeWidthPx]），三条入口解出来的首帧才落在
    // 阅读页要取的那把缓存键上。留 view 本体而不是当前宽度：启动落地那条在**首帧布局之前**就会开跑，
    // 到那时再读一次宽度（先把首帧布局之前的值取下的话就是 0 宽）。
    val hostView = LocalView.current
    // 「不在浏览页点书」入口的请求判定（票 #111 r2/r3）：抽屉「阅读器」/ 读内换书 / 启动还原三条共用同一套——
    // 每点一次领一个单调 token，并记下发起时栈顶那一项（栈项身份，不是路由 pattern）；
    // 被顶替或用户已离开那一项都不再导航。
    val readerEntryRequest = remember { ReaderEntryRequest() }

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
                        ServiceLocator.adoptSessionSource(it, target.browsing.connId)
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
                ServiceLocator.adoptSessionSource(source, last.connId)
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
    // 启动 effect 的组合存活标志（票 #111，与浏览页点击路径同一手法）：前置等待的第二道守卫，
    // 防「取消还没送达、导航已经执行」的窄窗口（启动落地的那一次导航现在也走 [enterReaderThenPreload]）
    var startupEffectAlive by remember { mutableStateOf(true) }
    DisposableEffect(Unit) { onDispose { startupEffectAlive = false } }
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
            syncBrowseHistory(history, nav)
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
                    // 票 #111 / #122：冷启动直进阅读器也走同一条前置路——**导航立刻发生**（这一屏切进阅读器），
                    // 「打开书 + 首批解好」由 [enterReaderThenPreload] 在会话级作用域里继续跑，阅读页侧有界等它
                    // （≤1.5s，到点自己开书）。不再有「先把书打开、首批解好再切页」的等待。
                    // 修复轮（AC-2）：导航方向走 [navigateStartupReader] 显式给的 **FADE**（只淡入）——
                    // 这一屏的旧屏是刚落盘的浏览层，靠 [navTransitionDirection] 的 `initialRoute == STARTUP` 猜不出来。
                    // r3：守卫与另两条入口统一到同一套（[ReaderEntryRequest]）——发起时记下栈顶那一项
                    // （这里是刚压上的浏览层），等待窗口里用户走开（返回 / 切屏）就不再导航；
                    // 另外保留组合存活标志（这条等待挂在 `LaunchedEffect` 上，与浏览页点击路径同一手法）。
                    val request = readerEntryRequest.begin(ReaderEntryRequest.keyOf(nav))
                    enterReaderThenPreload(
                        workScope = ServiceLocator.appScope,
                        prelude = ServiceLocator.readerPrelude,
                        // 阅读器路由读的就是会话当前来源（见 prepareStartup 的 OpenReader 分支：先备好再导航）
                        source = ServiceLocator.currentSource,
                        connId = target.lastRead.connId,
                        bookId = target.lastRead.bookId,
                        targetWidthPx = { pageDecodeWidthPx(hostView.width.toFloat()) },
                        alwaysFirstPage = AppSettings.alwaysOpenFirstPage,
                        isRequestCurrent = {
                            startupEffectAlive && readerEntryRequest.isCurrent(request, ReaderEntryRequest.keyOf(nav))
                        },
                        enterReader = { navigateStartupReader(nav, target.lastRead.bookId) },
                    )
                }
            }
            // 落地完成后把历史镜像对齐到实际栈（票 #70 r3）：上面各支重建的层与实际压上的层一一对应，
            // 不一致（如栈里已有还原出来的层、[pushBrowserPath] 跳过了其中几层）时以栈为准。
            syncBrowseHistory(history, nav)
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

    // ---------- 根路由「再按一次退出」（票 #128）----------
    // 只在**真正停在首页根路由、且抽屉没开着**时接管返回：子层级（浏览页/阅读器/书柜/设置）各有自己的返回语义
    // （见 `BrowserScreen` / `ReaderScreen` 的 BackHandler），抽屉开着时返回归抽屉自己（关抽屉）。
    // 判据是纯函数 [atRootRoute]（读一次 currentEntry 建立重组依赖，其余两个事实取同一帧的快照；由 RootBackExitStateTest 锁定）。
    val atRoot = currentEntry != null && atRootRoute(nav)
    // 本 BackHandler 有意挂在 `AppDrawer` **之前**：返回回调按「后注册先派发」派发，抽屉自己的处理器因此排在它之后、
    // 抽屉开着时仍优先拿到返回（`enabled` 里的 `drawerState.isClosed` 是第二道保险）。
    // 状态机按「接管条件」重建（remember 的键）⇒ 离开根路由或抽屉开合即复位（票面「超时、或离开根路由 → 状态重置」）。
    val rootBackExit = remember(atRoot, drawerState.isClosed) { RootBackExitState() }
    BackHandler(enabled = atRoot && drawerState.isClosed) {
        when (rootBackExit.onBack(SystemClock.uptimeMillis())) {
            // 轻量提示、不打断操作（票面口径）；文案与 SPEC 的「返回逐级」段一致
            RootBackAction.PROMPT -> Toast.makeText(context, "再按一次退出", Toast.LENGTH_SHORT).show()
            // 退出走 Activity finish：会话级清理（`MainActivity.onDestroy` 的 `isFinishing` 分支）随之跑。
            // 拿不到 Activity（非 Activity 宿主/preview）时无处可退，保持不动作
            RootBackAction.EXIT -> (context as? Activity)?.finish()
        }
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
                else -> scope.launch {
                    // 票 #111 / #122：抽屉入口也走同一条前置路（同一套闸门）——**导航立刻发生**（滑入立刻开始），
                    // 「打开书 + 首批解好」在会话级作用域里继续跑并由阅读页侧有界等待；等页期间是主题背景色纯色。
                    // r2/r3 修复 P1：这条等待跑在 `AppNav` 的组合作用域上（只有整个 AppNav 离开组合才取消），
                    // 「用户已经走开」因此不会被取消观察到——守卫里除了「没被后一次点击顶替」，还要
                    // 「栈顶仍是发起时那一项」。用**栈项身份**而不是路由 pattern（r3）：浏览层级
                    // （子文件夹 ↔ 父目录）是同一个 pattern，只比 pattern 时「等待里按返回回到父目录」会被误判成没离开。
                    val request = readerEntryRequest.begin(ReaderEntryRequest.keyOf(nav))
                    enterReaderThenPreload(
                        workScope = ServiceLocator.appScope,
                        prelude = ServiceLocator.readerPrelude,
                        source = ServiceLocator.currentSource,
                        connId = connId,
                        bookId = last.bookId,
                        targetWidthPx = { pageDecodeWidthPx(hostView.width.toFloat()) },
                        alwaysFirstPage = AppSettings.alwaysOpenFirstPage,
                        isRequestCurrent = { readerEntryRequest.isCurrent(request, ReaderEntryRequest.keyOf(nav)) },
                        enterReader = { openReaderFromDrawer(nav, history, last) },
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
        // 过渡期的帧时长（票 #111 AC-9）：挂在导航壳上，因此**所有**导航过渡都进统计
        NavTransitionFrameMetrics(navTransitionProbe)
        NavHost(
            navController = nav,
            startDestination = Routes.STARTUP,
            // 四支过渡（票 #111）：横向整屏滑入划出、400ms、方向按入口（见 [navTransitionDirection]）；
            // 每支都返回**预先建好的同方向实例**（不在这里 new），重组因此不重启动画
            enterTransition = {
                beginNavTransitionProbe(navTransitionProbe, scope)
                navTransitions.enter(pushDirection())
            },
            exitTransition = { navTransitions.exit(pushDirection()) },
            popEnterTransition = {
                beginNavTransitionProbe(navTransitionProbe, scope)
                navTransitions.enter(NavTransitionDirection.Back)
            },
            popExitTransition = { navTransitions.exit(NavTransitionDirection.Back) },
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
                // 方向通道（票 #111 最终口径）：入口把方向写进路由参数，过渡 lambda 从这里读回来。
                // 默认 [ReaderEnter.FORWARD]（浏览页点书 / 抽屉「阅读器」/ 冷启动落地都走默认值——冷启动那次
                // 由 [navTransitionDirection] 按「旧屏是中转页」判成只淡入）。
                arguments = listOf(
                    navArgument(ARG_READER_ENTER) {
                        type = NavType.StringType
                        defaultValue = ReaderEnter.FORWARD
                    },
                ),
                // 本路由**不再单独声明过渡**（票 #111 最终口径）：进场/出场/退出阅读器三支都交给 `NavHost`
                // 的全局 lambda，方向由 [navTransitionDirection] 按入口给。历史条文（进场零时长、出场零时长、
                // 退出沿用全局 popExit）随之作废——它们要的「换书不残留上一本页面」由 400ms 的整屏同向滑出取代
                // （旧屏滑满一屏、不再带 alpha 交叉；旧口径的 30% + 0.55 已整套推翻）。
            ) { entry ->
                // navigation 已自动解码参数，不再手动 Uri.decode（review P1：双重解码损坏含 % 的 id）
                val bookId = entry.arguments?.getString("bookId")
                val source = ServiceLocator.currentSource
                if (bookId == null || source == null) {
                    LaunchedEffect(Unit) { nav.popBackStack() }
                } else {
                    // 读内换书的前置（票 #111 / #122）：**导航立刻发生**（换到新书的新 entry），当前这本的旧 entry
                    // 随之出场；「新书打开 + 首批解好」在会话级作用域里继续跑、由新阅读页有界等待。
                    // 守卫同抽屉入口那一套（[ReaderEntryRequest]）。
                    val swapScope = rememberCoroutineScope()
                    // 连点同一本不重启（值没变就不领新请求）；换点另一本才领
                    var swapBookId by remember { mutableStateOf<String?>(null) }
                    ReaderScreen(
                        bookId = bookId,
                        source = source,
                        // 前置槽的键（票 #110）：与写入口（浏览页点击路径）用的连接 id 同源
                        connId = ServiceLocator.currentConnId,
                        onOpenBook = { newBookId, direction ->
                            // 读内换书（菜单上一本/下一本、跨书确认条）：导航到新的阅读页 entry 立刻发生，
                            // 「开书 + 解首批」由 [enterReaderThenPreload] 在那之后继续跑；
                            // 「上次阅读位置」由那一页切进去时写（票 #110：全仓唯一写入点，不在这里写）
                            // r2 修复 P2：守卫改用单调 token（不再用值相等——A→B→A 三连点后值相等会让**旧** A 请求
                            // 重新算数，与新 A 请求各导航一次：同一本书被切两次、第二次取不到前置槽）。
                            if (swapBookId != newBookId) {
                                swapBookId = newBookId
                                val request = readerEntryRequest.begin(ReaderEntryRequest.keyOf(nav))
                                swapScope.launch {
                                    enterReaderThenPreload(
                                        workScope = ServiceLocator.appScope,
                                        prelude = ServiceLocator.readerPrelude,
                                        source = source,
                                        connId = ServiceLocator.currentConnId,
                                        bookId = newBookId,
                                        targetWidthPx = { pageDecodeWidthPx(hostView.width.toFloat()) },
                                        alwaysFirstPage = AppSettings.alwaysOpenFirstPage,
                                        isRequestCurrent = { readerEntryRequest.isCurrent(request, ReaderEntryRequest.keyOf(nav)) },
                                        enterReader = {
                                            nav.navigate(Routes.reader(newBookId, ReaderEnter.of(direction)), newReaderNavOptions())
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
