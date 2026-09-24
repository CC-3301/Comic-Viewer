package com.cc3301.comicviewer.ui

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.cc3301.comicviewer.core.input.MOUSE_BUTTON_SECONDARY
import com.cc3301.comicviewer.core.input.WheelAction
import com.cc3301.comicviewer.core.input.WheelHandler
import com.cc3301.comicviewer.core.input.mouseTapIntent
import com.cc3301.comicviewer.core.input.wheelAction
import com.cc3301.comicviewer.core.reader.ReadingMode
import com.cc3301.comicviewer.core.reader.VolumeAction
import com.cc3301.comicviewer.core.reader.ZOOM_ANIMATION_MILLIS
import com.cc3301.comicviewer.core.reader.ZoomState
import com.cc3301.comicviewer.core.reader.clampPinchScale
import com.cc3301.comicviewer.core.reader.clampZoomOffset
import com.cc3301.comicviewer.core.reader.doubleTapZoomTarget
import com.cc3301.comicviewer.core.reader.wheelSurface
import com.cc3301.comicviewer.core.reader.zoomTransition
import com.cc3301.comicviewer.core.reader.zoomTransitionFrame
import com.cc3301.comicviewer.core.source.BookHandle
import com.cc3301.comicviewer.core.source.BookOpening
import com.cc3301.comicviewer.core.source.PerfTiming
import com.cc3301.comicviewer.core.source.Source
import com.cc3301.comicviewer.core.source.isNotABook
import com.cc3301.comicviewer.core.touch.TapIntent
import com.cc3301.comicviewer.core.touch.pagedNextTarget
import com.cc3301.comicviewer.core.touch.pagedPrevTarget
import com.cc3301.comicviewer.core.touch.tapIntentAt
import com.cc3301.comicviewer.core.touch.webtoonCurrentPage
import com.cc3301.comicviewer.core.touch.webtoonTapTarget
import com.cc3301.comicviewer.core.touch.webtoonVolumeTarget
import com.cc3301.comicviewer.core.view.CrossBookBarLayout
import com.cc3301.comicviewer.core.view.pageDecodeWidthPx
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.abs

/**
 * 跨书两段式确认状态（3.jpg 风格确认条）。
 *
 * [forward] = 到边方向（true = 末页方向「下一本书」）：确认条的版式按它选按钮格——
 * 首页方向在左格、末页方向在右格（票 #100，见 [CrossBookBarLayout.actionZone]）。
 */
internal data class CrossBookConfirm(
    val targetBookId: String,
    val currentLabel: String,
    val actionLabel: String,
    val forward: Boolean,
)

/**
 * 阅读页宿主（票 07）：把条漫（LazyListState）与单页（PagerState）的差异收敛到这一层，
 * 使触摸区域、进度保存、跨书确认在两模式下共用同一份实现。
 */
private interface PageHost {
    /**
     * 当前页（条漫 = 顶部可见页；已滚到书末且内容超过一屏时 = 末页，见 [webtoonCurrentPage]；单页 = 当前页）。
     * 菜单预览/页码、进度写入、按页缩放都读这一份：页位只有一个拼法。
     */
    fun currentPage(): Int

    /** 上一页/上一张；返回 false = 已在书首（交由跨书两段式确认） */
    suspend fun goPrev(): Boolean

    /** 下一页/下一张；返回 false = 已在书末（交由跨书两段式确认） */
    suspend fun goNext(): Boolean

    /** 直接定位（阅读菜单跳页） */
    suspend fun goTo(index: Int)

    /**
     * 音量键能否翻一页（票 20，spec 故事 39；票 #89 起条漫的翻页 = 跳到下一页/上一页页首）。
     * 到书首/书末返回 false；阅读页据此就地弹跨书确认，且按键照旧被消费（票 #89 需求 2）。
     */
    fun canMoveOnePage(forward: Boolean): Boolean

    /** 执行一次翻页（调用前须先确认 [canMoveOnePage]） */
    suspend fun moveOnePage(forward: Boolean)
}

/** 条漫宿主：连续滚动，末页矮于视口时也能正确判定书末 */
private class WebtoonHost(
    private val state: LazyListState,
    private val pageCount: Int,
) : PageHost {

    override fun currentPage(): Int = webtoonCurrentPage(
        firstVisibleIndex = state.firstVisibleItemIndex,
        pageCount = pageCount,
        canScrollForward = state.canScrollForward,
        canScrollBackward = state.canScrollBackward,
    )

    override suspend fun goPrev(): Boolean {
        // 触摸区左区（票 #95）：目标跟音量键同一套**页位**口径（[webtoonTapTarget]）。
        // 旧实现在这里拿 `state.firstVisibleItemIndex`（顶边索引）当基准，并在「首页页内已滚过其顶部」时
        // 单写一个分支——页位口径下两者是同一个公式：顶边索引 0 + 偏移 > 0 时页位仍是第 1 页，
        // 目标 = 钳在 0 的上一页页首 = 回到当前图起始。
        // 书首（滚不动）= null → 跨书两段式确认（既有语义）。
        val target = tapTarget(forward = false) ?: return false
        state.animateScrollToItem(target)
        return true
    }

    override suspend fun goNext(): Boolean {
        // 触摸区右区（票 #95）：同上，基准是页位；书末（滚不动）= null → 跨书两段式确认
        val target = tapTarget(forward = true) ?: return false
        state.animateScrollToItem(target)
        return true
    }

    override suspend fun goTo(index: Int) {
        state.scrollToItem(index)
    }

    /**
     * 触摸区左/右区目标（票 #95）：与音量键共用**页位**口径（[webtoonTapTarget]，见那里为什么基准必须是页位）。
     * 两个方向共用一份判定，不在这里重算一遍页位。
     */
    private fun tapTarget(forward: Boolean): Int? = webtoonTapTarget(
        firstVisibleIndex = state.firstVisibleItemIndex,
        pageCount = pageCount,
        canScrollForward = state.canScrollForward,
        canScrollBackward = state.canScrollBackward,
        forward = forward,
    )

    /**
     * 音量键目标（票 #89，spec 故事 39）：下一页/上一页页首；null = 本方向上无页可翻（书首/书末）。
     * 两个方向共用 [webtoonVolumeTarget] 一份判定，不在这里重算一遍页位。
     */
    private fun volumeTarget(forward: Boolean): Int? = webtoonVolumeTarget(
        firstVisibleIndex = state.firstVisibleItemIndex,
        pageCount = pageCount,
        canScrollForward = state.canScrollForward,
        canScrollBackward = state.canScrollBackward,
        forward = forward,
    )

    override fun canMoveOnePage(forward: Boolean): Boolean = volumeTarget(forward) != null

    override suspend fun moveOnePage(forward: Boolean) {
        val target = volumeTarget(forward) ?: return
        state.animateScrollToItem(target)
    }
}

/** 单页宿主：一次一页横向翻页（方向由 Pager 的 reverseLayout 决定，不影响点击区语义） */
private class PagedHost(
    private val state: PagerState,
    private val pageCount: Int,
) : PageHost {

    /**
     * 在飞目标页（review P2）：animateScrollToPage 过半前 state.currentPage 仍是旧值，
     * 连点两次若都读 currentPage 会算出同一目标并互相取消（只翻一页）。动画结束清空。
     */
    private var pendingPage by mutableStateOf<Int?>(null)

    override fun currentPage(): Int = pendingPage ?: state.currentPage

    override suspend fun goPrev(): Boolean {
        val target = pagedPrevTarget(pendingPage ?: state.currentPage, pageCount) ?: return false
        pendingPage = target
        state.animateScrollToPage(target)
        pendingPage = null
        return true
    }

    override suspend fun goNext(): Boolean {
        val target = pagedNextTarget(pendingPage ?: state.currentPage, pageCount) ?: return false
        pendingPage = target
        state.animateScrollToPage(target)
        pendingPage = null
        return true
    }

    override suspend fun goTo(index: Int) {
        pendingPage = null
        state.scrollToPage(index)
    }

    override fun canMoveOnePage(forward: Boolean): Boolean {
        val from = pendingPage ?: state.currentPage
        val target = if (forward) pagedNextTarget(from, pageCount) else pagedPrevTarget(from, pageCount)
        return target != null
    }

    override suspend fun moveOnePage(forward: Boolean) {
        if (forward) goNext() else goPrev()
    }
}

/**
 * 阅读器进场后的邻位后台补齐（票 #93 修复轮）：让 `Source.warmNeighbors` 的调用**可单测**且行为固定：
 * - 只调一次 [Source.warmNeighbors]（不碰 `neighbors`/`listEntries`，因此不构成任何同步探测）；
 * - 失败只吞掉（离线/传输故障时邻位保持未知，与「确实到头」同一条提示），**不重试、不轮询**，也不给界面加转圈；
 * - 协程取消照常传播（[catchingNonCancellation]，票 #26 登记项：裸 `runCatching` 会把取消当失败）。
 *
 * 调用点在阅读页的 `LaunchedEffect(bookId)` 里：不阻塞打开书/首帧（与打开态是两个互不等待的协程），
 * 离开阅读页/换书随组合取消（不白列一层）。界面层不需要感知补齐有没有发生：`neighbors` 照旧瞬时返回。
 */
internal suspend fun warmNeighborsQuietly(source: Source, bookId: String) {
    withContext(Dispatchers.IO) {
        catchingNonCancellation { source.warmNeighbors(bookId) }
    }
}

/**
 * 阅读器打开失败的界面文案（票 #97，由 [ReaderOpenErrorTest] 锁定）：
 *
 * 「不是一本书」这类失败带**实现细节**——异常文本形如「不是一本书：<本机绝对路径>」/「无效或越界引用：<id>」，
 * 一旦原样展示，用户看到的是自己的磁盘路径和一句无行动含义的话。因此它们统一换成中文提示（含下一步：返回上一页）；
 * 其余失败沿用异常自带的中文 message（票 #91 的 `SourceReadTimeoutException` 就是设计成可直接展示的），
 * 无 message 时退回「打开失败」。
 *
 * 「是不是不是一本书」这个判据只有一处：[isNotABook]（启动还原侧 `resolveStartupRead` 用同一个判据决定回落）。
 * 启动还原那条路的同类问题已在导航层拦掉（见 `resolveStartupRead`）：这里兑的是其余入口
 * （抽屉「阅读器」、浏览列表里的陈旧行、菜单换书）。
 */
internal fun readerOpenErrorMessage(t: Throwable): String =
    if (isNotABook(t)) NOT_READABLE_HINT else t.message ?: "打开失败"

/** 不是一本书时的中文提示（带下一步）：不出现异常原文、绝对路径或 id */
private const val NOT_READABLE_HINT = "这本书已不是一个可读的书（目录结构可能已变化）；返回上一页可继续浏览"

/** 页就绪后整屏内容的淡入时长（毫秒，票 #111 AC-6）：**150ms** */
private const val CONTENT_FADE_MILLIS: Int = 150

/**
 * 阅读页根背景的判据（票 #111 AC-6；r10 b6/6 收成一个语义）：**屏上还没有正文可看**（[contentReady] 为假）
 * 且没有打开失败时用主题背景色，其余一律阅读器黑底。
 *
 * 「屏上还没有正文可看」= 书还没落地 **或** 首批页还没到位（`ReaderScreen` 的 `contentReady` 就是这一件事：
 * `opening != null && readiness.ready`）。**它有终点**：`readiness.ready` 由任一页到位触发，空书
 * （`pageCount == 0`）在 `ready` 里直接算就绪 ⇒ **空书不会落到主题色上**（空态文案与失败文案都是写死的白字，
 * 浅色主题下压在近白的主题背景上读不到，症状是「有重试按钮、没有失败原因」）。
 *
 * 纯函数：界面只引用它，用例钉住它。r10 b5/5 曾把它扩成「书没落地 **或** 首批窗口」——两个名字说同一件事、
 * 还会把空书那一支从视线里漏过（本票的 P1），故只留 [contentReady] 一个入口。
 */
internal fun readerShowsThemeBackground(hasError: Boolean, contentReady: Boolean): Boolean =
    !hasError && !contentReady

/**
 * 首批窗口里**这一页**是不是还在等（票 #111 r10 b5/5 + b6/6）：只有入口页在这一段里走「空占位 + 主题背景色」。
 *
 * 三条边界（逐条都用例钉住）：
 * - **空书**（[pageCount] == 0）不开窗：没有任何页会报到，[entryPageSettled] 恒假；
 * - **入口页自己到位**（可画**或**确定失败）即关窗——失败之后要回黑底、照旧画圈，白字文案才读得到；
 * - 其余页（`index != startIndex`）**永远不开窗**：入口页 effect 被取消（永不报到）时只影响它自己，
 *   别的未解码页照旧画进度圈（r10 b5/5 的 P1）。
 */
internal fun readerPageWaitsForFirstPaint(
    index: Int,
    startIndex: Int,
    pageCount: Int,
    entryPageSettled: Boolean,
): Boolean = pageCount > 0 && index == startIndex && !entryPageSettled

/**
 * 「整屏内容可以显示了吗」的状态（票 #111 r9 A2 + r10 b2/2 兜底）。
 *
 * 为什么要兜底：r9 的就绪信号只接在**首页**那一页，而回调在那一页自己的 `LaunchedEffect` 里——用户开屏
 * 就甩动 / 切阅读模式时那一页在解码完成前离开组合，effect 被取消 ⇒ 信号永不触发 ⇒ `contentAlpha` 恒 0
 * ⇒ 阅读器整屏（含失败文案与占位）不可见，只能退出重进。现在**任一页**到位（可画**或**失败）都算就绪。
 *
 * 两个事实分开记（两件事的后果不同，不能合成一个布尔）：`ready` 只看「有没有任何一页到位」（含失败页），
 * `settledWithImage` 只在真的画出位图时置真。
 *
 * 两套读法（同一个事实、两个粒度；r12 b1/3）：
 * - **整屏淡入读的那一个口**：[contentFadeMillisFor]——按**入口页那一页**（`opening.startIndex`）判，
 *   不按任意页聚合：一屏里可以同时有「首帧命中缓存、秒出」的页与「解码后才到、自己会淡」的页，
 *   按任意页聚合时后者会替前者决定，秒出的那一页（往往正是入口页）就变成硬切
 *   （见 [readerContentFadeMillis]）；
 * - **聚合读法**（**生产已不再读**，只被既有用例读；留着是为了不删既有断言）：[settledWithImage] /
 *   [imageFadesItself] = 「屏上有没有图 / 有没有一张自己会淡的图」。按页那两个事实
 *   （`showsImageAt` / `imageFadesItselfAt`）是私有实现（r12 b2/3 把这两处口径写准）。
 *
 * 重入路径（换书 / 重试 / 切模式）不靠本类复位：换书与重试由调用方的 `remember` 键换实例；切模式不换实例，
 * 因此已就绪的不会因「换了一批页去组合」而退回未就绪（这正是兜底要保住的）。
 */
internal class ReaderContentReadiness(private val pageCount: Int) {
    /**
     * 已到位的页（可画**或**确定失败）。用 `Set` 而不是计数器：同一页的 effect 重跑 / 切阅读模式重入
     * 会**重复上报**同一个 index，计数会失真，而本类只关心「有没有」（票 #111 r10 b4/4，评审 r10-b2 P2）。
     */
    var settledPages: Set<Int> by mutableStateOf(emptySet())
        private set

    /** 已**画出位图**的页（只累加事实、同页重复上报幂等） */
    private var pagesWithImage: Set<Int> by mutableStateOf(emptySet())

    /**
     * 其中**图是「刚到、自己会慢慢显」**的页（本页首帧无图、位图是后来解码到的，它自己有一条 150ms 淡入；
     * 首帧就命中解码缓存的那一页**不在**里面——它根本没有那条斜坡）。
     */
    private var pagesWithOwnFade: Set<Int> by mutableStateOf(emptySet())

    /**
     * 有没有任何一页到位（可画或确定失败）；**空书**（[pageCount] == 0）直接算就绪，否则那句中文空态永远浮不出来。
     *
     * **前置条件**：本类只在书已落地（调用方的 `opening != null`）时才被读——未落地时调用方根本不画内容分支，
     * `pageCount` 也还没得可读（构造时的 `?: 0` 不与「空书」同义，判定点见 `ReaderScreen` 的 `contentReady`）。
     */
    val ready: Boolean get() = settledPages.isNotEmpty() || pageCount == 0

    /**
     * 到位的页里**有没有真的画出图**（聚合读法：屏上到底有没有图）。
     *
     * **生产已不再读它**（整屏淡入走 [contentFadeMillisFor]，按入口页那一页判）；目前只被既有用例读，
     * 留着是为了不删既有断言（r12 b2/3 登记）。
     */
    val settledWithImage: Boolean get() = pagesWithImage.isNotEmpty()

    /**
     * 有没有哪一页的图是「自己会淡」的（聚合读法）。
     *
     * **生产已不再读它**（同 [settledWithImage]：整屏淡入走 [contentFadeMillisFor]）；目前只被既有用例读，
     * 留着是为了不删既有断言（r12 b2/3 登记）。
     */
    val imageFadesItself: Boolean get() = pagesWithOwnFade.isNotEmpty()

    /** [index] 那一页的图到位了吗（整屏淡入的判据之一：看的是**入口页**那一页） */
    private fun showsImageAt(index: Int): Boolean = index in pagesWithImage

    /** [index] 那一页的图是不是「刚到、自己会慢慢显」（解码后才到 ⇒ 会；首帧命中解码缓存 ⇒ 不会） */
    private fun imageFadesItselfAt(index: Int): Boolean = index in pagesWithOwnFade

    /**
     * 整屏淡入的时长（毫秒）：**判据取入口页那一页的图**——[entryIndex] = 调用方给的 `opening.startIndex`；
     * 书还没落地 / 还没有入口页时传 null ⇒ 按「入口页还没有图」那一档取 150ms。
     *
     * **生产接线只此一处**（`ReaderScreen` 的 `animateFloatAsState`）；纯函数本身仍由 `ReaderBackgroundTest`
     * 直钉（它直接调 [readerContentFadeMillis] 的三档）。判据不按任意页聚合；为什么必须按入口页：见
     * [readerContentFadeMillis] 的 KDoc（并存场景会硬切）。
     */
    fun contentFadeMillisFor(entryIndex: Int?): Int = readerContentFadeMillis(
        settledWithImage = entryIndex != null && showsImageAt(entryIndex),
        imageFadesItself = entryIndex != null && imageFadesItselfAt(entryIndex),
    )

    /**
     * 一页到位：[index] = 页号、[hasImage] = 本页真的画出了位图、[hasOwnFade] = 本页那张图是后来解码到的
     * （它自己有一条淡入；首帧就命中解码缓存时为假）。
     * 可重复调用：同页重报与切模式重入都幂等，不会把「一页到位」算成多页。
     */
    fun onPageSettled(index: Int, hasImage: Boolean, hasOwnFade: Boolean) {
        settledPages = settledPages + index
        if (hasImage) {
            pagesWithImage = pagesWithImage + index
            if (hasOwnFade) pagesWithOwnFade = pagesWithOwnFade + index
        }
    }
}

/**
 * 整屏内容淡入的时长（毫秒，票 #111 r11 §4 + r12 b1/3）：屏幕从主题背景色切到正文那一刻，**只有一条斜坡**。
 *
 * **两个入参说的是同一页：入口页**（用户进屏看到的那一页 = `opening.startIndex`）的图——
 * [settledWithImage] = 那一页画出图了吗、[imageFadesItself] = 那一页那张图会不会自己淡。
 * **不是「任意已到位页」的聚合**（r12 b1/3 修的就是这条）：一屏里可以同时有「首帧命中解码缓存、秒出」的页与
 * 「解码后才到、自己会淡」的页，两页在同一帧报到时后者会替前者决定 ⇒ 取 0ms ⇒ 那张**秒出的页**（往往正是
 * 入口页、屏幕上正对着用户的那一张）在一帧内全亮 = 硬切，正是本口径要消掉的东西。
 *
 * 三档（分档本身与 r11 一致，只把「**谁**的事实」改成入口页）：
 * - 入口页的图**已经在首帧就到手**（命中解码缓存）⇒ 图片那条 `animateFloatAsState` 的初值就是目标值、
 *   **不会自己淡**（`hasOwnFade` = 假）⇒ 整屏补一条 [CONTENT_FADE_MILLIS]；
 * - 入口页的图**是刚到**（解码后置入，自己有一条同长的淡入）⇒ 整屏保持**立即**（0ms），只要两条斜坡同时
 *   起跑就会 alpha 相乘、「先暗后亮」的二段式；
 * - 入口页**还没有图**（失败文案 / 空书 / 首图还没到）⇒ 文案也走 [CONTENT_FADE_MILLIS]，不让它硬切。
 *   **这一档不看别的页**：别的页有图、别的页会自己淡，都不算（它们不在用户看的地方）；入口页在整屏淡入
 *   **启动那一下**可能还没报到（它的图比别的页晚到），那时按这一档取 150ms——这是安全的一边
 *   （宁可多一条斜坡，也不要硬切）。
 *
 * 沿革：r10 b2/2 只看「有没有图」⇒ 命中缓存那一屏被当作「有图可画、让位给图片自己那条」⇒ 整屏 0ms，
 * 而那屏的图片根本没有斜坡（它就是秒出的）；r11 补上「图片到底会不会自己淡」这个事实，但按**任意页**聚合；
 * r12 b1/3 把这两个事实收到**入口页那一页**（判据的唯一入口是
 * [ReaderContentReadiness.contentFadeMillisFor]）。图片自己那条 150ms（`imageAlpha`）从头到尾未动。
 */
internal fun readerContentFadeMillis(settledWithImage: Boolean, imageFadesItself: Boolean): Int = when {
    settledWithImage && !imageFadesItself -> CONTENT_FADE_MILLIS
    settledWithImage -> 0
    else -> CONTENT_FADE_MILLIS
}

/**
 * 阅读器（票 04 基础 + 票 05 进度 + 票 06 触摸区域 + 票 07 菜单/跨书/单页模式）：
 * 黑底、无返回按钮；触摸区域类型 3 在两种模式下规则统一（左=上一页、中=菜单、右=下一页）。
 *
 * 换书（票 #68）＝按新书重新定位：打开态与宿主态都按书 id 分槽重建，上一本的页位/缩放/菜单一律不带过来。
 */
@Composable
internal fun ReaderScreen(bookId: String, source: Source, connId: Long?, onOpenBook: (String) -> Unit) {
    var error by remember(bookId) { mutableStateOf<String?>(null) }
    // 打开失败的重试（票 11：断链/超时后不必退出重进）
    var reloadTick by remember(bookId) { mutableStateOf(0) }

    // 打开态按书分槽（票 #68）：换书 = 换一本书的打开态，上一本的句柄与落点一律不带过来，
    // 新书打开完成前停在「准备打开…」（spinner + 文案，不残留上一本页面；四条入口共用这一分支）。
    // 承重机制在**导航层**：换书/打开某本书都走 `newReaderNavOptions()` 换一条 back stack entry
    // （书 id 变了、entry id 也变），本 destination 整棵子树连同保存态桶一起重建。
    // 这里的 bookId 槽位是兜底（同一 destination 内书 id 再变：同书重开等），reloadTick 也在这一槽上承接
    // 「打开失败重试」——重试是同书重开，不能靠换 entry。
    // 票 #108 E1-A / 票 #122：发起那一屏在点击时就开始把书打开、首批也解好（[ReaderPrelude]），但**导航已提前到
    // 点击那一帧**，因此这里组合期取到的通常是「还没到货」——取到就直接用（首帧命中解码缓存，见 PageImage 的初始值），
    // 取不到就在下面的效果里有界等它（到点自己开书，见 [openReaderForLanding]）。
    // 票 #110：前置槽的键是「连接 id + 书 id」，因此取用也带连接 id（[connId] 由导航层从会话来源取）；
    // 取到的那份连同**点击时刻**的判据一起交给下面的落地（判据不在落地时重读，见 [ReaderPreludeEntry]）。
    val prelude = remember(bookId, reloadTick) { connId?.let { ServiceLocator.readerPrelude.take(it, bookId) } }
    var loaded by remember(bookId, reloadTick) { mutableStateOf(prelude?.opening) }

    // 页就绪后的淡入（票 #111 AC-6 + r9 ② + r10 b2/2 + r11 §4）。
    // 起点是「**任一页就绪**」（可画或失败），不再是「书打开完成」——书先淡进来（占位框）、图晚到再硬切
    // 一下，真机反馈的「一整套下来感觉不连贯」就是这么来的；而 r9 只把信号接在首页那一页上，开屏就甩动 /
    // 切模式时那一页提前离开组合 ⇒ 永远不就绪、**整屏不可见**（只能退出重进），所以现在任一页都算。
    // 淡入只有**一条**斜坡（r11 §4 + r12 b1/3）：整屏要不要自己补一条，看**入口页那一页**（用户进屏看到的
    // 第一张图）有没有一条自己的斜坡可以躲——图已经在首帧就到手（命中解码缓存）时它自己不会淡 ⇒ 整屏补
    // 150ms；刚到（自己会淡）⇒ 整屏立即。**不按「任意已到位页」聚合**：同一屏里可以同时有秒出的页与
    // 自己会淡的页，聚合时后者会替前者决定，秒出的那一页（往往正是入口页）就变成硬切。判据只在
    // [ReaderContentReadiness.contentFadeMillisFor] 一处（见 [readerContentFadeMillis]）。
    val opening = loaded
    val readiness = remember(bookId, reloadTick, opening?.handle?.pageCount) {
        ReaderContentReadiness(pageCount = opening?.handle?.pageCount ?: 0)
    }
    val contentReady = opening != null && readiness.ready
    val entryIndex = opening?.startIndex
    val contentAlpha: State<Float> = animateFloatAsState(
        targetValue = if (contentReady) 1f else 0f,
        animationSpec = tween(readiness.contentFadeMillisFor(entryIndex)),
        label = "readerContentFade",
    )
    // 首批窗口（票 #111 r10 b4/4 + b5/5 + b6/6）：兜底就绪与「用户正看的那一页」是**解耦**的（别的页先到位
    // ⇒ 整屏 0ms 亮起，而入口那一页还在解码）。那个窗口里**只有入口页**走「空占位 + 主题背景色」、不画进度圈
    //（AC-6 的「不出现加载指示」「不出现黑底」），其余页照旧画进度圈——抑制不能用一个全局布尔洩到每一页：
    // 入口页自己的 effect 可能在解码完成前被取消（永不报到），那个布尔会恒假，整场会话所有未解码页都会
    // 失去指示（r10 b5/5 的 P1）。判定收在纯函数 [readerPageWaitsForFirstPaint] 里（含空书与两条终点）。
    // 根背景不读它：那一支由 [readerShowsThemeBackground] 按「屏上还没有正文可看」判（r10 b6/6 收掉冗余析取，
    // 也正是它把空书从视线里漏过——空书 `readiness.ready` 直接为真 ⇒ 不会停在主题色上）。
    val entryPageSettled = entryIndex?.let { it in readiness.settledPages } == true

    // 打开 + 落地（票 #110）：前置在手就用它（#108），否则自己开书（#68 的落点口径）；两条分支都在
    // [openReaderForLanding] 里一次走完（取前置 → 开书 → 落地），阅读页只调它一次（票 #132 步骤②）。
    // 票 #122：导航已经在点击那一帧发生，前置常常**还在飞**——那一处用 [ReaderPrelude.await] 有界等它
    // （≤1.5s，与 #108 的闸门同一个上限；没有在飞的前置则立即不等），等的过程中本页仍是主题背景色纯色、
    // 不显示加载指示（#111 的呈现侧）；到点/没有前置就走兜底分支自己开书。落地仍只发生在本页在屏幕上时
    // （阅读页离开/换书 → 本效果取消，不落地）——这就是 #108「取消不导航」在新形状下的对应。
    // 打开失败照旧显示失败提示与重试；落地写失败在那一处被吞掉，不影响打开。
    LaunchedEffect(bookId, reloadTick) {
        try {
            // 票 #122 r3 + 票 #132 步骤②：取前置（拿不到就退役这次打开、本页自己开书）→ 开书 → 落地，
            // 全在 [openReaderForLanding] 这一次调用里（票号、判据读取、与阅读中节流写的关系都在那一处）。
            loaded = openReaderForLanding(
                source = source,
                preludeSlot = ServiceLocator.readerPrelude,
                connId = connId,
                bookId = bookId,
                taken = prelude,
            )
        } catch (c: CancellationException) {
            throw c // 换书取消上一本的加载：不是打开失败
        } catch (t: Throwable) {
            error = readerOpenErrorMessage(t)
        }
    }

    // 根背景：**屏上还没有正文可看**（且没失败）= 主题背景色（票 #111 AC-6「新屏先是一张主题背景色纯色、
    // 不出现黑底」）；**书就绪之后**（含空书与入口页到位）回到阅读器的黑底（文案是白字，
    // 见 [readerShowsThemeBackground]）。
    Box(
        Modifier
            .fillMaxSize()
            .background(
                // 单语义、有终点：`contentReady` 为假（书没落地或首批没到位）才用主题背景色；
                // 空书在 `readiness.ready` 里直接算就绪 ⇒ 这里为真、走黑底，白字空态文案读得到。
                if (readerShowsThemeBackground(hasError = error != null, contentReady = contentReady)) {
                    MaterialTheme.colorScheme.background
                } else {
                    Color.Black
                },
            ),
    ) {
        when {
            error != null -> Column(
                modifier = Modifier.align(Alignment.Center).padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text("打开失败：$error", color = Color.White)
                Text(
                    "点此重试",
                    color = ACCENT_ORANGE,
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier
                        .clickable {
                            error = null
                            reloadTick++
                        }
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                )
            }
            // 打开中（票 #111 AC-6）：本分支是**四条入口共用**（浏览页点击 / 启动还原 / 抽屉「阅读器」/
            // 读内换书，调用点 `AppNav`）。滑入期间新屏就是一张**主题背景色纯色**——不出现黑底、
            // **不显示任何加载指示**（维护者选择）；页就绪后整屏内容淡入 150ms（见上面的 contentAlpha）。
            // #108 的闸门与超时兜底未改（1.5s 上限 / 到点放行 / 失败放行 / 取消不导航）：它决定的是
            // 「前置结果是否入槽、何时导航」，不再是本屏的加载指示。
            loaded == null -> Unit
            else -> {
                val opening = loaded!!
                if (opening.handle.pageCount == 0) {
                    // 空书：整屏就这一句白字，淡入那条斜坡由这里给（不经过页内容那一层）
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .graphicsLayer { alpha = contentAlpha.value },
                    ) {
                        Text("此书没有可显示的页面", color = Color.White, modifier = Modifier.align(Alignment.Center))
                    }
                } else {
                    // 宿主态的随书重建在 ReaderContent 内部（那里是页位/缩放/菜单的家，分槽点只有一处）。
                    // 淡入那一层**只包页内容**（r11 §5）：菜单、跨书条在它之外，否则页面还没出来时点中区
                    // 呼出菜单会「什么都没发生」，等页到位又多出来一个东西。
                    ReaderContent(
                        source,
                        bookId,
                        opening.handle,
                        opening.startIndex,
                        onOpenBook,
                        contentAlpha = contentAlpha,
                        onPageSettled = readiness::onPageSettled,
                        entryPageSettled = entryPageSettled,
                    )
                }
            }
        }
    }
}

/**
 * 阅读页宿主态（票 04 基础 + 票 05 进度 + 票 06 触摸区域 + 票 07 菜单/跨书/单页模式）：
 * 页位、页边界表、按页缩放表、菜单与跨书确认条都在这里。
 *
 * 整棵子树按书 id 分槽（票 #68）：**承重机制在导航层**——换书（菜单上/下一本、跨书确认条）与抽屉入口打开
 * 某本书都走 `newReaderNavOptions()` 换一条 back stack entry，新 entry id ⇒ 新组合槽位 + 新保存态桶，
 * 宿主态因此整体重建，不依赖 Compose 分槽键。这里的 `key(bookId)` 是同一 destination 内书 id 再变时的**兜底**
 * （同书重开等路径），不是「唯一的保证」。
 * 分槽点按状态归属各一处、不重叠也不嵌套：宿主态全在这里（`key(bookId)` 之内），
 * 打开态（loaded/error，必须先于本子树存在）在调用方。
 */
@Composable
private fun ReaderContent(
    source: Source,
    bookId: String,
    handle: BookHandle,
    startIndex: Int,
    onOpenBook: (String) -> Unit,
    /** 整屏内容的淡入进度（只包页内容，见 `ReaderScreen` 的调用点；r11 §5） */
    contentAlpha: State<Float>,
    /**
     * 任一页到位（可画**或**失败）时回调一次（页号 + 本页是否画出图 + 那张图是不是「自己会淡」的）
     * （票 #111 r9 ② + r10 b2/2 兜底 + r11 §4）
     */
    onPageSettled: (Int, Boolean, Boolean) -> Unit,
    /** 入口页（= 下面那个 [startIndex]）到位了吗：与 [startIndex] 一起构成**每页**自己的判据（b5/5） */
    entryPageSettled: Boolean,
) {
    key(bookId) {
        ReaderSessionContent(source, bookId, handle, startIndex, onOpenBook, contentAlpha, onPageSettled, entryPageSettled)
    }
}

@OptIn(FlowPreview::class, ExperimentalFoundationApi::class)
@Composable
private fun ReaderSessionContent(
    source: Source,
    bookId: String,
    handle: BookHandle,
    startIndex: Int,
    onOpenBook: (String) -> Unit,
    /** 整屏内容的淡入进度：**只包页内容**（菜单与跨书条在外），见下面的那层 `graphicsLayer` */
    contentAlpha: State<Float>,
    /**
     * 任一页到位（可画**或**失败）时回调一次（页号 + 本页是否画出图 + 图是否自己会淡），**每一页都接**
     * （不再只接首页）
     */
    onPageSettled: (Int, Boolean, Boolean) -> Unit,
    /** 入口页（= 上面的 [startIndex]）到位了吗（首批窗口里只有它走空占位，票 #111 r10 b5/5） */
    entryPageSettled: Boolean,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    // 阅读模式在设置里切换（spec 故事 25）；回到阅读器时重新读取，全局生效
    val mode = remember { AppSettings.readingMode }
    val direction = remember { AppSettings.pageDirection }

    // 换书必须重建这两处（票 #68）：否则 B 会沿用 A 的页位（本票的串页）。保证机制 = 导航层每次打开某本书都换
    // 新 entry（`newReaderNavOptions()`），整棵子树随之重建；外层的 key(bookId) 是同一 destination 内书 id 再变
    // 时的兜底 —— 书 id 一变，本子树全部 remember（含页位、页边界表、按页缩放表、菜单）同样作废重建。
    val listState = rememberLazyListState(initialFirstVisibleItemIndex = startIndex)
    val pagerState = rememberPagerState(initialPage = startIndex) { handle.pageCount }

    // 放大状态按页记忆（spec 故事 32）：翻页/回翻不复位；退出阅读器即丢弃（不持久化）
    val zoomByPage = remember { mutableStateMapOf<Int, ZoomState>() }

    // 在飞的双击缩放过渡，按页一份（票 #59）：手势写入时取消 → 动画让位于实时状态
    val zoomAnimationJobs = remember { mutableMapOf<Int, Job>() }

    // 视口尺寸 + 页在窗口中的位置：把双击点换算成「页内坐标」需要（条漫长图节点远高于视口）
    var viewportW by remember { mutableStateOf(0f) }
    var viewportH by remember { mutableStateOf(0f) }
    var viewportLeft by remember { mutableStateOf(0f) }
    var viewportTop by remember { mutableStateOf(0f) }
    val pageBounds = remember { mutableStateMapOf<Int, Rect>() }

    val host: PageHost = remember(mode, listState, pagerState, handle.pageCount) {
        when (mode) {
            ReadingMode.WEBTOON -> WebtoonHost(listState, handle.pageCount)
            ReadingMode.PAGED -> PagedHost(pagerState, handle.pageCount)
        }
    }
    // 模式切换后必须重新读取设置：本 destination 离开组合即丢弃普通 remember（见 issue #8 验收记录）
    var menuVisible by remember { mutableStateOf(false) }
    var confirm by remember { mutableStateOf<CrossBookConfirm?>(null) }

    val currentPage by remember(host) { derivedStateOf { host.currentPage() } }

    fun toast(message: String) {
        Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
    }

    // 相邻书查询：票 #93 起只读会话快照（不再列目录/探测子目录），但仍要按 id 取一次节点
    // （SAF = provider IPC、SMB/WebDAV = 一次 stat），因此照旧留在 IO 线程（review P1）
    suspend fun neighborId(prev: Boolean): String? = withContext(Dispatchers.IO) {
        val neighbors = source.neighbors(bookId)
        if (prev) neighbors.prev else neighbors.next
    }

    // 邻位后台补齐（票 #93 修复轮）：启动页「上次阅读的位置」与抽屉「阅读器」入口直接进来时，
    // 这一层本会话从未被列过 → 邻位未知。这里在**后台**补一次（见 [warmNeighborsQuietly]）。
    // 与上面的打开态是两个互不等待的协程：本补齐再慢/再失败也不阻塞打开、首帧与翻页；
    // 书 id 一变（换书）本效果重跑，离开本页随组合一起取消（不白列一层）。
    LaunchedEffect(bookId) { warmNeighborsQuietly(source, bookId) }

    // 统一进度写入：APP 级域 fire-and-forget（协程不随组合取消）
    val savePage = remember(source, bookId, handle) {
        { page: Int ->
            ServiceLocator.appScope.launch {
                runCatching { source.writeProgress(bookId, page, handle.pageCount) }
            }
            Unit
        }
    }

    // 阅读中节流保存（spec）：400ms 时间窗合并快速翻动，停顿后落盘最新页
    LaunchedEffect(host, bookId) {
        snapshotFlow { host.currentPage() }
            .drop(1)                 // 起始页已由打开逻辑写入/定位，不重写
            .debounce(400)
            .collect { page -> savePage(page) }
    }

    // 退出兜底写（节流尾窗内的停留页由此补上）
    DisposableEffect(host, bookId) {
        onDispose { savePage(host.currentPage()) }
    }

    // 菜单开启时系统返回优先关菜单
    BackHandler(enabled = menuVisible) { menuVisible = false }

    // 到边（书首/书末）已请求过跨书确认的**方向**；null = 下一次到边按键可以再请求。
    // 去重的目的：长按连发停在边上时，不要每一发都重弹提示 / 重查邻居。
    // 复位口径（r3 评审 P1：不复位会把该方向的按键变成永久静默键——条被点掉后再按就没反应了）：
    // ① 真的翻了一页（离开边）② 确认条被点掉 ③ 任何一次触摸/左键点击（走 [onTapIntent]，含呼出菜单）。
    // 无邻书时只弹提示、没有条可点掉，所以靠 ③ + ① 复位：点过屏或翻过页，下一次到边照弹提示。
    var edgeConfirmSent by remember(host, bookId) { mutableStateOf<Boolean?>(null) }

    // 跨书两段式确认请求（票 06/07；票 #89 需求 2 起音量键与触摸区共用这一份）：
    // 无邻书 → 提示；有邻书 → 弹出确认条（按条内按钮才真换书）
    suspend fun requestCrossBook(forward: Boolean) {
        val target = neighborId(prev = !forward)
        if (target == null) {
            toast(if (forward) "无下一本" else "无上一本")
        } else {
            confirm = CrossBookConfirm(
                targetBookId = target,
                currentLabel = if (forward) "最后一页" else "第一页",
                actionLabel = if (forward) "下一本书" else "上一本书",
                forward = forward,
            )
        }
    }

    // 触摸区域类型 3（spec 故事 26）：两模式、两方向统一——左=上一页、中=菜单、右=下一页
    fun onTapIntent(intent: TapIntent) {
        // 任何一次点击都解除「到边已请求过」的去重：下一次到边的音量键因此必重新请求（r3 评审 P1）
        edgeConfirmSent = null
        when (intent) {
            TapIntent.MENU -> menuVisible = true
            // 书首（或条漫首图内部已到顶）→ 跨书两段式确认
            TapIntent.PREV_PAGE -> scope.launch { if (!host.goPrev()) requestCrossBook(forward = false) }
            TapIntent.NEXT_PAGE -> scope.launch { if (!host.goNext()) requestCrossBook(forward = true) }
        }
    }

    // 触摸输入与鼠标左键（Compose 点击）走这里；鼠标右键走 mouseTapIntent → onTapIntent（两者共用分区判定）
    fun onTapZone(x: Float, width: Float) = onTapIntent(tapIntentAt(x, width))

    // 音量键翻页（票 20，spec 故事 39；票 #89 需求 2/3）：单页=翻一页、条漫=跳到下一页/上一页页首；
    // **长按连发每一发都翻一页**（MainActivity 把每一发 DOWN 都送进来，它不自己判发数）。
    // 首/末页没有可翻的页时，触发与触摸区（首页左区 / 末页右区）**同一份** [requestCrossBook]，
    // 并**始终消费**按键——阅读器内不得改系统音量（需求 2；MainActivity 据此不再把按键交回系统）。
    // 连发停在首/末页会反复进来：同一方向只请求一次（见 [edgeConfirmSent] 的复位口径），
    // 一旦真的翻了一页就复位，下次到边重新请求。
    val volumeHandler: (VolumeAction) -> Boolean = remember(host, bookId) {
        { action ->
            val forward = action == VolumeAction.NEXT
            if (host.canMoveOnePage(forward)) {
                edgeConfirmSent = null
                scope.launch { host.moveOnePage(forward) }
            } else if (edgeConfirmSent != forward) {
                edgeConfirmSent = forward
                scope.launch { requestCrossBook(forward) }
            }
            true
        }
    }
    RegisterSlot(ServiceLocator.volumeKeySlot, volumeHandler)

    // 鼠标接入（票 17，spec 故事 22/35/36）：滚轮与右键与音量键同一手法，注册给 MainActivity 的分发入口。
    // 滚轮：单页模式一格=翻一页（条漫交给列表自身滚动）；右键：等价左键，走同一份触摸区域处理。
    val wheelHandler = remember(host, mode) {
        WheelHandler(mode.wheelSurface) { forward ->
            if (!host.canMoveOnePage(forward)) {
                false
            } else {
                scope.launch { host.moveOnePage(forward) }
                true
            }
        }
    }
    RegisterSlot(ServiceLocator.wheelSlot, wheelHandler)

    // 右键处理器（spec 故事 36）：与左键等价——按键映射复用 mouseTapIntent，动作落在同一份 onTapIntent。
    // MainActivity 给出的是窗口坐标，减掉视口左边即与触摸/点击的节点坐标对齐（同 contains 的换算）。
    val secondaryTapHandler: (Float) -> Unit = remember(host, bookId) {
        { windowX -> mouseTapIntent(MOUSE_BUTTON_SECONDARY, windowX - viewportLeft, viewportW)?.let { onTapIntent(it) } }
    }
    RegisterSlot(ServiceLocator.mouseSecondaryTapSlot, secondaryTapHandler)

    fun zoomOf(index: Int): ZoomState = zoomByPage[index] ?: ZoomState()

    /** 页显示尺寸（未测量时回退视口尺寸，保证公式有安全输入） */
    fun pageSizeOf(index: Int): Pair<Float, Float> {
        val r = pageBounds[index]
        return if (r != null && r.width > 0f && r.height > 0f) {
            r.width.toFloat() to r.height.toFloat()
        } else {
            viewportW to viewportH
        }
    }

    /** 双击点落在哪一页：条漫可能同时可见多页；先看当前页，再回退到矩形命中（review P2） */
    fun contains(rect: Rect, pos: Offset): Boolean =
        pos.x >= rect.left - viewportLeft && pos.x <= rect.right - viewportLeft &&
            pos.y >= rect.top - viewportTop && pos.y <= rect.bottom - viewportTop

    fun pageIndexAt(pos: Offset): Int {
        val current = host.currentPage()
        pageBounds[current]?.let { if (contains(it, pos)) return current }
        return pageBounds.entries.firstOrNull { (_, r) -> contains(r, pos) }?.key ?: current
    }

    /** 统一写入：所有路径（双击过渡每帧/双指/视口变化）都过边界钳制，避免存下越界状态 */
    fun applyZoom(index: Int, state: ZoomState) {
        val (pageW, pageH) = pageSizeOf(index)
        zoomByPage[index] = clampZoomOffset(
            state,
            viewportW = viewportW,
            viewportH = viewportH,
            pageW = pageW,
            pageH = pageH,
            constrainVertical = mode == ReadingMode.PAGED,
        )
    }

    /** 取消某页在飞的双击过渡（手势接管、再次双击重算时调） */
    fun cancelZoomAnimation(page: Int) {
        zoomAnimationJobs.remove(page)?.cancel()
    }

    /**
     * 双击缩放过渡（票 #59）：逐帧把插值写进 `zoomByPage`（它同时是渲染的唯一来源），
     * 因此起点就是**当前显示状态**——动画中再次双击或手势接管都从眼下这一帧接上，不回跳、不错位。
     * 帧循环跟着渲染节拍走（[withFrameNanos]），时长 [ZOOM_ANIMATION_MILLIS]；进度到底即停。
     */
    fun animateZoomTo(page: Int, target: ZoomState) {
        cancelZoomAnimation(page)
        val transition = zoomTransition(zoomOf(page), target)
        zoomAnimationJobs[page] = scope.launch {
            val startNanos = withFrameNanos { it }
            var elapsedNanos = 0L
            while (elapsedNanos < ZOOM_ANIMATION_MILLIS * 1_000_000L) {
                elapsedNanos = withFrameNanos { it } - startNanos
                applyZoom(page, zoomTransitionFrame(transition, elapsedNanos))
            }
            zoomAnimationJobs.remove(page)
        }
    }

    // 双指缩放 + 平移（spec 故事 33/34）：条漫只做水平平移（垂直留给列表滚动），单页双向限制在图片显示区域内
    val transformState = rememberTransformableState { zoomChange, panChange, _ ->
        val page = host.currentPage()
        // 手势驱动要逐帧跟手（票 #59）：先让在飞的过渡动画让位，再按实时状态算
        cancelZoomAnimation(page)
        val cur = zoomOf(page)
        val newScale = clampPinchScale(cur.scale * zoomChange)
        val rect = pageBounds[page]
        val next = if (rect == null || newScale == cur.scale) {
            cur.copy(
                scale = newScale,
                offsetX = cur.offsetX + panChange.x,
                offsetY = cur.offsetY + panChange.y,
            )
        } else {
            // 锚点取「视口中心」（review P1-2）：节点中心在条漫下位于长图中央，直接用它会让视口瞬移。
            // 把视口中心折算成页内比例作为新锚点，并补偿平移，使视口中心内容在缩放前后不动。
            val pageW = rect.width.toFloat()
            val pageH = rect.height.toFloat()
            val vx = viewportW / 2f - (rect.left - viewportLeft)
            val vy = viewportH / 2f - (rect.top - viewportTop)
            val cx = (vx - cur.originX * pageW * (1f - cur.scale) - cur.offsetX) / cur.scale
            val cy = (vy - cur.originY * pageH * (1f - cur.scale) - cur.offsetY) / cur.scale
            ZoomState(
                scale = newScale,
                originX = (cx / pageW).coerceIn(0f, 1f),
                originY = (cy / pageH).coerceIn(0f, 1f),
                offsetX = vx - cx + panChange.x,
                offsetY = vy - cy + panChange.y,
            )
        }
        applyZoom(page, next)
    }

    val gestureModifier = Modifier
        .fillMaxSize()
        .onGloballyPositioned { coords ->
            val bounds = coords.boundsInWindow()
            viewportLeft = bounds.left
            viewportTop = bounds.top
        }
        .onSizeChanged {
            viewportW = it.width.toFloat()
            viewportH = it.height.toFloat()
        }
        .pointerInput(host, bookId, viewportW, viewportH) {
            // 单击 / 双击（票 #129 r4）：用 [detectReaderTapGestures] 替掉 `detectTapGestures` ——
            // 它把双击等待窗口从平台默认的 300ms 钉到 150ms（见 [ReaderTapGesture]），
            // 单击因此早 150ms 唤出菜单；「单击立即响应 + 双击第二下撤销」会让菜单在双击时闪一下，已否决。
            detectReaderTapGestures(
                // 双击放大（spec 故事 31）：以双击位置为中心；再次双击恢复适屏（故事 32）
                onDoubleTap = { pos ->
                    val page = pageIndexAt(pos)
                    // 锚点用页内坐标（条漫长图节点中心 ≠ 视口中心，否则双击点会飞走）
                    val (pageW, pageH) = pageSizeOf(page)
                    val r = pageBounds[page]
                    val localX = if (r != null) pos.x - (r.left - viewportLeft) else pos.x
                    val localY = if (r != null) pos.y - (r.top - viewportTop) else pos.y
                    // 双击路径是「目标值变化」→ 走过渡动画（票 #59）；手势路径直接写实时状态（见 [transformState]）
                    animateZoomTo(
                        page,
                        doubleTapZoomTarget(
                            current = zoomOf(page),
                            localX = localX,
                            localY = localY,
                            pageW = pageW,
                            pageH = pageH,
                            scale = AppSettings.doubleTapScale,
                        ),
                    )
                },
                onTap = { pos -> onTapZone(pos.x, size.width.toFloat()) },
            )
        }
        .transformable(
            state = transformState,
            // 单页：放大后上下左右都可平移；条漫：仅水平位移为主时消费 → 垂直拖动仍交给列表滚动（spec 故事 34）
            canPan = { offset ->
                zoomOf(host.currentPage()).isZoomed &&
                    (mode == ReadingMode.PAGED || abs(offset.x) > abs(offset.y))
            },
            lockRotationOnZoomPan = true,
        )

    // 视口变化（旋转/多窗口）后重新钳制已有放大状态：延到布局完成，否则读到的 pageBounds 还是旧尺寸（review P2-1）
    LaunchedEffect(viewportW, viewportH) {
        if (viewportW > 0f && viewportH > 0f) {
            zoomByPage.keys.toList().forEach { index -> applyZoom(index, zoomOf(index)) }
        }
    }

    // 页内容那一层（票 #111 r11 §5）：整屏淡入**只包页内容**，菜单与跨书条都在它之外——页面还没出来时
    // 点中区呼出的菜单因此**立刻看得见**（旧写法把菜单与页内容一起门控在 alpha 0 上，页到位才一起冒出来）。
    // alpha 在 `graphicsLayer` 的 block 里读（不在组合期读）：淡入期间只失效图层，不重组这两屏
    //（与 `AppNav` 的 [NavSlideFrame] 同一手法）。
    Box(
        Modifier
            .fillMaxSize()
            .graphicsLayer { alpha = contentAlpha.value },
    ) {
        when (mode) {
            // 条漫：黑底、全宽、垂直连续滚动
            // 鼠标左键按住拖动 = 上下滑动（票 #69）：内建 scrollable 拒绝鼠标源拖动，这段由 mouseDragScroll 补上
            // （单页模式不加：单页不做纵向拖动翻页，保持「滚轮/音量键翻页」的语义）
            ReadingMode.WEBTOON -> LazyColumn(
                modifier = gestureModifier.mouseDragScroll(listState),
                state = listState,
            ) {
                items(count = handle.pageCount, key = { it }) { index ->
                    ReaderPage(
                        handle,
                        bookId,
                        index,
                        fitScreen = false,
                        zoom = zoomOf(index),
                        onSettled = onPageSettled,
                        // 首批窗口里这一页还在等 = 本页是**入口页**（= `startIndex`）且还没到位：
                        // 只影响这一格，其余页照旧画圈（判据见 [readerPageWaitsForFirstPaint]）
                        waitingFirstPaint = readerPageWaitsForFirstPaint(index, startIndex, handle.pageCount, entryPageSettled),
                    ) { rect ->
                        if (pageBounds[index] != rect) pageBounds[index] = rect
                    }
                }
            }

            // 单页：一次一页、横向翻页（RTL 反转布局方向）
            ReadingMode.PAGED -> HorizontalPager(
                state = pagerState,
                modifier = gestureModifier,
                reverseLayout = direction.reverseLayout,
                key = { it },
            ) { index ->
                ReaderPage(
                    handle,
                    bookId,
                    index,
                    fitScreen = true,
                    zoom = zoomOf(index),
                    onSettled = onPageSettled,
                    waitingFirstPaint = readerPageWaitsForFirstPaint(index, startIndex, handle.pageCount, entryPageSettled),
                ) { rect ->
                    if (pageBounds[index] != rect) pageBounds[index] = rect
                }
            }
        }
    }

    // 跨书两段式确认条（3.jpg）：中格=当前位置白字（只提示），按钮格（按方向取左/右一格）=橙色跳转按钮；点按钮格才跳
    confirm?.let { state ->
        CrossBookBar(
            state = state,
            onConfirm = {
                confirm = null
                // r11 §1：换书的方向不再分「上一本 / 下一本」（滑动统一为新屏从右进）——入口不再传方向。
                onOpenBook(state.targetBookId)
            },
            onDismiss = {
                // 条被点掉 → 一并解除去重：同方向再按音量键要能重新弹条（r3 评审 P1）
                confirm = null
                edgeConfirmSent = null
            },
        )
    }

    // 菜单显隐过渡（票 #129）：面板从屏幕下缘滑入、沿来路滑回，出现 120ms / 消失 100ms（口径与可钉的部分见 [ReaderMenuTransitions]）。
    // 过渡对象只建一次（`remember`）：`AnimatedVisibility` 每次重组拿到的是同一对实例，动画不被重组重启。
    // 显隐的来源一律未动：点屏幕中区 `menuVisible = true`、点空白 `onDismiss`、`BackHandler` 关菜单三处照旧。
    val menuTransitions = remember { ReaderMenuTransitions() }
    AnimatedVisibility(
        visible = menuVisible,
        enter = menuTransitions.enter,
        exit = menuTransitions.exit,
    ) {
        ReaderMenu(
            title = ServiceLocator.entryNames[bookId] ?: displayNameOf(bookId) ?: "阅读",
            currentPage = currentPage,
            pageCount = handle.pageCount,
            handle = handle,
            bookId = bookId,
            onSeek = { target -> scope.launch { host.goTo(target) } },
            onPrevBook = {
                scope.launch {
                    val prev = neighborId(prev = true)
                    if (prev == null) toast("无上一本") else onOpenBook(prev)
                }
            },
            onNextBook = {
                scope.launch {
                    val next = neighborId(prev = false)
                    if (next == null) toast("无下一本") else onOpenBook(next)
                }
            },
            onDismiss = { menuVisible = false },
        )
    }
}

/**
 * 跨书确认条（票 #100 版式：整宽黑七成八条 + 三等分三列；两段式确认不变——首点区域弹条，点动作格才跳转）。
 *
 * 版式口径在 [CrossBookBarLayout]（列与触摸区分区同源）：中格恒为「第一页」/「最后一页」，
 * 动作格按方向取左/右一格；**两块文字两种色**（r3）：中格位置标签 = 白 [CROSS_BOOK_LABEL_COLOR]，
 * 两侧按钮格 = 强调橙 [CROSS_BOOK_ACTION_COLOR]，配色判据见 `CrossBookBarStyle`。四处承重细节：
 * - **条面的底色挂在内容之前**（`background` 先于任何内边距）：整宽铺到屏幕左右边与底边，
 *   底部那段就是留给系统栏/手势带的（内容在其中居中，而不是贴它下缘）；
 *   底色 = 黑 [CrossBookBarLayout.BAR_ALPHA]（r2 = 0.78），无圆角、无胶囊、无边框（没有任何 `clip`）；
 * - **条面高 = 内容带 + 底部避让**（[CrossBookBarLayout.bandHeightDp]），**文案在整块条面里垂直居中**
 *   （批次 6 AC14）：内容那一层 `fillMaxSize()` 铺满条面、三格各自居中，因此上下留白一致
 *   ——不是居中在 64dp 的内容带里（那会让文字看上去偏上、下方空一大截）；
 * - **命中层铺满整块条面**（r2 根因修正）：它就是**视觉格**本身，按横坐标复用触摸区的三等分
 *   （[CrossBookBarLayout.confirmsAt]）——按钮就在触发区正下方，且**按钮格整格（含底部避让那一截）**
 *   都能点。原先命中层只铺到 64dp 内容带，按钮格内部因此留出一截死区（沉浸态 24dp），真机就是
 *   「点击左右选区有时候无效」。中格与反向空白格在整块条面上仍一概不动作，也不关条
 *   （维护者口径「提示格不做点击触发」）；
 * - **关条只由条外那层点击负责**（最外层 `fillMaxSize` 的 Box 仍在，两段式语义不变）。
 */
@Composable
internal fun CrossBookBar(
    state: CrossBookConfirm,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    val overlayInsets = readerOverlayInsets()
    val density = LocalDensity.current
    val bottomInsetDp = with(density) { overlayInsets.getBottom(this).toDp() }
    Box(
        modifier = Modifier
            .fillMaxSize()
            .pointerInput(Unit) { detectTapGestures { onDismiss() } },
    ) {
        Box(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .height(CrossBookBarLayout.bandHeightDp(bottomInsetDp.value).dp)
                .background(Color.Black.copy(alpha = CrossBookBarLayout.BAR_ALPHA)),
        ) {
            // 内容层：铺满整个条面 → 三格文案在条面里垂直居中（含底部避让那一截），上下留白一致；
            // 两块文字两种色（r3）：中格位置标签白（只提示）、两侧按钮格橙（可点）
            Row(
                modifier = Modifier.fillMaxSize(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                CrossBookCell(
                    text = if (state.forward) null else state.actionLabel,
                    color = CROSS_BOOK_ACTION_COLOR,
                    contentInsets = overlayInsets.only(WindowInsetsSides.Left),
                    modifier = Modifier.weight(1f),
                )
                CrossBookCell(
                    text = state.currentLabel,
                    color = CROSS_BOOK_LABEL_COLOR,
                    // 中格恒为屏幕水平正中：不吃横向 inset（挖孔只可能在屏幕边缘，扰不到正中）
                    contentInsets = WindowInsets(0, 0, 0, 0),
                    modifier = Modifier.weight(1f),
                )
                CrossBookCell(
                    text = if (state.forward) state.actionLabel else null,
                    color = CROSS_BOOK_ACTION_COLOR,
                    contentInsets = overlayInsets.only(WindowInsetsSides.Right),
                    modifier = Modifier.weight(1f),
                )
            }
            // 命中层：铺满**整块条面**（= 视觉格），无内容。
            // 尺寸必须与条面高一致：只铺到 64dp 内容带会在按钮格底部留出一截死区
            //（沉浸态 24dp），r2 真机就是「点击左右选区有时候无效」。
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .pointerInput(state.forward) {
                        detectTapGestures { pos ->
                            if (CrossBookBarLayout.confirmsAt(pos.x, size.width.toFloat(), state.forward)) onConfirm()
                        }
                    },
            ) {
                // 命中层没有内容：它只负责接住点击（文案与底色都在上面那层）
            }
        }
    }
}

/**
 * 跨书条里的一格（票 #100）：三等分三列之一，文案居中；[text] 为 null 时该格是空的
 * （没轮到这个方向、因此不显示按钮的那一格）。
 *
 * 命中判定不挂在格里（见 [CrossBookBar] 里铺满整块条面的命中层）：命中层按横坐标复用触摸区的三等分，
 * 因此格自己不需要 `clickable` 也能「整格都能点」，且不会与触摸区分区漂移。格的**高**就是条面高
 * （[CrossBookBarLayout.bandHeightDp]），文案在其中垂直居中。
 *
 * [contentInsets] 只用来让文案避开系统栏/挖孔（左右两端的两格各吃自己那一侧；中格传 0）。
 * 它是**格内内边距**，不影响格的宽度（宽度由调用方的 `weight(1f)` 定）——命中区因此仍与触摸区逐像素对齐。
 */
@Composable
private fun CrossBookCell(
    text: String?,
    color: Color,
    contentInsets: WindowInsets,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier.fillMaxHeight().windowInsetsPadding(contentInsets),
        contentAlignment = Alignment.Center,
    ) {
        if (text != null) {
            Text(
                text = text,
                style = MaterialTheme.typography.bodyLarge,
                fontSize = CrossBookBarLayout.LABEL_SP.sp,
                color = color,
            )
        }
    }
}

/**
 * 阅读页（票 07）：条漫 = 全宽 FillWidth；单页 = 适屏 Fit 居中（解码宽度仍取屏宽，共用同一份缓存键）。
 */
@Composable
private fun ReaderPage(
    handle: BookHandle,
    bookId: String,
    index: Int,
    fitScreen: Boolean,
    zoom: ZoomState,
    /**
     * 本页到位（可画**或**失败）后回调一次（页号 + 本页是否画出图 + 那张图是不是「自己会淡」的）：
     * **每一页都接**，任一页到位即算就绪。
     */
    onSettled: (index: Int, hasImage: Boolean, hasOwnFade: Boolean) -> Unit,
    /**
     * 首批窗口里这一页还在等（= 本页是入口页且入口页还没到位，票 #111 r10 b5/5）：这一格走
     * **空占位 + 主题背景色**（AC-6：不出现加载指示、不出现黑底）；**其余页照旧画进度圈**。
     */
    waitingFirstPaint: Boolean,
    onBounds: (Rect) -> Unit,
) {
    // 首批窗口里的入口页用**主题背景色**（AC-6「不出现黑底」，SPEC 导航段的「等页期间新屏是主题背景色纯色」）；
    // 其余情况仍是阅读器黑底（失败文案是白字，浅色主题下压在主题背景上读不到，见 [readerShowsThemeBackground]）。
    val pageBackdrop = if (waitingFirstPaint) MaterialTheme.colorScheme.background else Color.Black
    BoxWithConstraints(
        modifier = if (fitScreen) {
            Modifier.fillMaxSize().background(pageBackdrop)
        } else {
            Modifier.fillMaxWidth().background(pageBackdrop)
        },
        contentAlignment = Alignment.Center,
    ) {
        // 页面解码宽度（票 #108 E1-A/E2-B）：与浏览页的前置共用同一个纯函数，两处不得各自 toInt()
        // （宽度写进解码缓存键，差 1px 前置那张图就白解了）
        val targetWidthPx = pageDecodeWidthPx(with(LocalDensity.current) { maxWidth.toPx() })
        // 首帧初值同步查解码缓存（票 #108 E1-A）：书柜页预解码过的那张就在里面，因此本页**首帧**就是图片，
        // 不是「先黑一帧再出图」（查不到时照旧为 null，仍走下面的异步取解）
        var bitmap by remember(bookId, index, targetWidthPx) {
            mutableStateOf(PageDecoder.cachedPage(bookId, index, targetWidthPx))
        }
        // 本页**首次组合**时图就已经在手吗（首帧命中解码缓存，票 #108 E1-A）？是的话图片那条
        // `animateFloatAsState` 的初值即目标值、**不会自己淡**（r11 §4：整屏那一条就得自己补上）；否的话
        // 位图是后来解码到的、它自己有一条 [CONTENT_FADE_MILLIS] 的淡入，整屏不必再盖一条
        //（两条同时起跑会 alpha 相乘、「先暗后亮」）。
        val imageOnFirstComposition = remember(bookId, index, targetWidthPx) { bitmap != null }
        // 取图失败（票 11 AC4：SMB 断链/超时）：给出明确提示 + 就地重试，而不是永久转圈
        var failed by remember(bookId, index, targetWidthPx) { mutableStateOf(false) }
        var retryTick by remember(bookId, index, targetWidthPx) { mutableStateOf(0) }
        LaunchedEffect(handle, bookId, index, targetWidthPx, retryTick) {
            failed = false
            val startedNanos = System.nanoTime()
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    // 缓存键含 bookId+宽度：跨书同字节数不碰撞（review P0）
                    PageDecoder.decodePage(handle, index, targetWidthPx) {
                        PageDecoder.loadPageBytes(handle, index)
                    }
                }
            }
            bitmap = result.getOrNull()
            // 真机打点（票 #73 诊断协议）：单页从「开始取」到「可以画」的总耗时——尖峰归属看同一次
            // 会话里的 pageBytes（磁盘/来源）与 pageDecode（解码）两条
            PerfTiming.log {
                "pageShown book=" + bookId + " index=" + index + " ok=" + (bitmap != null) +
                    " ms=" + ((System.nanoTime() - startedNanos) / 1_000_000)
            }
            failed = bitmap == null
            // 就绪信号（票 #111 r9 ② + r10 b2/2 + r11 §4）：图到位或确定失败都算——失败不放行的话，本页的失败
            // 文案会一直压在 alpha 0 上；第三个分量告诉整屏「本页的图会不会自己淡」（首帧命中缓存时不会）。
            val pageHasImage = bitmap != null
            onSettled(index, pageHasImage, pageHasImage && !imageOnFirstComposition)
        }

        val image = bitmap
        // 图片到货的淡入（票 #111 r9 ①）：位图从 null 变 Bitmap 原来是一帧内全亮（真机反馈「图片加载没有
        // 淡出淡入」）。图片自己走一条与内容淡入同长的 alpha；组合期就在解码缓存里命中的那一页不发虚——
        // `animateFloatAsState` 的初值就是目标值（首帧已有图 = 不淡）。
        val imageAlpha by animateFloatAsState(
            targetValue = if (image == null) 0f else 1f,
            animationSpec = tween(CONTENT_FADE_MILLIS),
            label = "readerPageImageFade",
        )
        if (image == null) {
            // 未解码完成时不上报 bounds：占位高度不是真实页高，上报会让双击锚点算错（review P2-3）
            Box(Modifier.fillMaxWidth().height(400.dp), contentAlignment = Alignment.Center) {
                if (failed) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            "第 ${index + 1} 页加载失败",
                            color = Color.White,
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        Text(
                            "点此重试",
                            color = ACCENT_ORANGE,
                            style = MaterialTheme.typography.bodyLarge,
                            modifier = Modifier
                                .clickable { retryTick++ }
                                .padding(horizontal = 12.dp, vertical = 10.dp),
                        )
                    }
                } else if (!waitingFirstPaint) {
                    // 首批窗口里的**入口页**不画进度圈（AC-6「不出现加载指示」）：那一格是主题背景色的空占位
                    //（底色由上面的 `pageBackdrop` 给，也不是「不出现黑底」的反例）。**只抑制入口页**——
                    // 入口页可能在解码完成前被取消、永不报到，全局布尔会让整场会话的未解码页都失去指示
                    //（票 #111 r10 b5/5，评审 P1）。其余页照旧画圈。
                    CircularProgressIndicator(color = Color.White)
                }
            }
        } else {
            val aspect = if (image.height > 0) image.width.toFloat() / image.height else 1f
            // 缩放层节点 = 图片实际显示区域：单页在视口内按比例最大化、条漫满宽。
            // 节点尺寸等于显示区域后，双击锚点与平移边界公式才成立（review P1-1：Fit 的 letterbox 不能算进页尺寸）
            val displayW = if (fitScreen) minOf(maxWidth, maxHeight * aspect) else maxWidth
            val displayH = if (aspect > 0f) displayW / aspect else maxHeight
            Box(
                modifier = Modifier
                    .size(displayW, displayH)
                    .onGloballyPositioned { onBounds(it.boundsInWindow()) }
                    .graphicsLayer(
                        alpha = imageAlpha,
                        // 以页内比例锚点为不动点缩放（双击位置 / 捏合的视口中心）
                        scaleX = zoom.scale,
                        scaleY = zoom.scale,
                        translationX = zoom.offsetX,
                        translationY = zoom.offsetY,
                        transformOrigin = TransformOrigin(zoom.originX, zoom.originY),
                    ),
            ) {
                Image(
                    bitmap = image,
                    contentDescription = "第 ${index + 1} 页",
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.FillBounds,
                )
            }
        }
    }
}
