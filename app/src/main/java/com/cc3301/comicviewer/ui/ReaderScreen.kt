package com.cc3301.comicviewer.ui

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
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
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
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
import com.cc3301.comicviewer.core.reader.ReadingMode
import com.cc3301.comicviewer.core.reader.VolumeAction
import com.cc3301.comicviewer.core.reader.ZoomState
import com.cc3301.comicviewer.core.reader.clampPinchScale
import com.cc3301.comicviewer.core.reader.clampZoomOffset
import com.cc3301.comicviewer.core.reader.doubleTapZoom
import com.cc3301.comicviewer.core.source.BookHandle
import com.cc3301.comicviewer.core.source.Source
import com.cc3301.comicviewer.core.source.openStartIndex
import com.cc3301.comicviewer.core.touch.TapIntent
import com.cc3301.comicviewer.core.touch.pagedNextTarget
import com.cc3301.comicviewer.core.touch.pagedPrevTarget
import com.cc3301.comicviewer.core.touch.tapIntentAt
import com.cc3301.comicviewer.core.touch.webtoonNextTarget
import com.cc3301.comicviewer.core.touch.webtoonPrevTarget
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.abs

/** 打开书 + 起始页（局部 data class 不合法，提升至此） */
private data class Loaded(val handle: BookHandle, val startIndex: Int)

/** 跨书两段式确认状态（3.jpg 风格确认条） */
private data class CrossBookConfirm(
    val targetBookId: String,
    val currentLabel: String,
    val actionLabel: String,
)

/**
 * 阅读页宿主（票 07）：把条漫（LazyListState）与单页（PagerState）的差异收敛到这一层，
 * 使触摸区域、进度保存、跨书确认在两模式下共用同一份实现。
 */
private interface PageHost {
    /** 当前页（条漫 = 顶部可见页；单页 = 当前页） */
    fun currentPage(): Int

    /** 上一页/上一张；返回 false = 已在书首（交由跨书两段式确认） */
    suspend fun goPrev(): Boolean

    /** 下一页/下一张；返回 false = 已在书末（交由跨书两段式确认） */
    suspend fun goNext(): Boolean

    /** 直接定位（阅读菜单跳页） */
    suspend fun goTo(index: Int)

    /**
     * 音量键能否推进一屏（票 20，spec 故事 39）：单页=翻一页、条漫=滚动一屏。
     * 到书首/书末返回 false，交由 Activity 把按键交还系统（仍可调音量）。
     */
    fun canMoveByScreen(forward: Boolean): Boolean

    /** 执行一屏推进（调用前须先确认 [canMoveByScreen]） */
    suspend fun moveByScreen(forward: Boolean)
}

/** 条漫宿主：连续滚动，末页矮于视口时也能正确判定书末 */
private class WebtoonHost(
    private val state: LazyListState,
    private val pageCount: Int,
    /** 视口高度提供者：视口随布局/旋转变化，取当前值而非构造时快照 */
    private val viewportHeight: () -> Float,
) : PageHost {

    override fun currentPage(): Int = state.firstVisibleItemIndex

    override suspend fun goPrev(): Boolean {
        // 长图内部（首项在屏但已滚过其顶部）：先回到当前图起始，不算翻页
        if (state.firstVisibleItemIndex == 0 && state.firstVisibleItemScrollOffset > 0) {
            state.animateScrollToItem(0)
            return true
        }
        if (!state.canScrollBackward) return false
        state.animateScrollToItem(webtoonPrevTarget(state.firstVisibleItemIndex, pageCount))
        return true
    }

    override suspend fun goNext(): Boolean {
        if (!state.canScrollForward) return false
        state.animateScrollToItem(webtoonNextTarget(state.firstVisibleItemIndex, pageCount))
        return true
    }

    override suspend fun goTo(index: Int) {
        state.scrollToItem(index)
    }

    override fun canMoveByScreen(forward: Boolean): Boolean =
        if (forward) state.canScrollForward else state.canScrollBackward

    override suspend fun moveByScreen(forward: Boolean) {
        val delta = viewportHeight()
        if (delta > 0f) state.animateScrollBy(if (forward) delta else -delta)
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

    override fun canMoveByScreen(forward: Boolean): Boolean {
        val from = pendingPage ?: state.currentPage
        val target = if (forward) pagedNextTarget(from, pageCount) else pagedPrevTarget(from, pageCount)
        return target != null
    }

    override suspend fun moveByScreen(forward: Boolean) {
        if (forward) goNext() else goPrev()
    }
}

/**
 * 阅读器（票 04 基础 + 票 05 进度 + 票 06 触摸区域 + 票 07 菜单/跨书/单页模式）：
 * 黑底、无返回按钮；触摸区域类型 3 在两种模式下规则统一（左=上一页、中=菜单、右=下一页）。
 */
@Composable
fun ReaderScreen(bookId: String, source: Source, onOpenBook: (String) -> Unit) {
    var error by remember { mutableStateOf<String?>(null) }

    // 打开书 + 拉取起始页一次完成（「始终从第一页打开」语义统一走 openStartIndex，再按开关补覆盖写）
    val loaded = produceState<Loaded?>(initialValue = null, bookId) {
        value = try {
            withContext(Dispatchers.IO) {
                val h = source.openBook(bookId)
                val alwaysFirst = AppSettings.alwaysOpenFirstPage
                val start = openStartIndex(source.readProgress(bookId), alwaysFirst, h.pageCount)
                if (alwaysFirst) {
                    // 打开瞬间即覆盖进度为第 1 页（进入马上退出也只算读了 1 页）
                    source.writeProgress(bookId, 0, h.pageCount)
                }
                Loaded(h, start)
            }
        } catch (t: Throwable) {
            error = t.message ?: "打开失败"
            null
        }
    }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        when {
            error != null -> Text(
                "打开失败：$error",
                color = Color.White,
                modifier = Modifier.align(Alignment.Center).padding(24.dp),
            )
            loaded.value == null -> CircularProgressIndicator(
                modifier = Modifier.align(Alignment.Center),
                color = Color.White,
            )
            else -> {
                val (h, startIndex) = loaded.value!!
                if (h.pageCount == 0) {
                    Text("此书没有可显示的页面", color = Color.White, modifier = Modifier.align(Alignment.Center))
                } else {
                    ReaderContent(source, bookId, h, startIndex, onOpenBook)
                }
            }
        }
    }
}

@OptIn(FlowPreview::class, ExperimentalFoundationApi::class)
@Composable
private fun ReaderContent(
    source: Source,
    bookId: String,
    handle: BookHandle,
    startIndex: Int,
    onOpenBook: (String) -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    // 阅读模式在设置里切换（spec 故事 25）；回到阅读器时重新读取，全局生效
    val mode = remember(bookId) { AppSettings.readingMode }
    val direction = remember(bookId) { AppSettings.pageDirection }

    val listState = rememberLazyListState(initialFirstVisibleItemIndex = startIndex)
    val pagerState = rememberPagerState(initialPage = startIndex) { handle.pageCount }

    // 放大状态按页记忆（spec 故事 32）：翻页/回翻不复位；退出阅读器即丢弃（不持久化）
    val zoomByPage = remember(bookId) { mutableStateMapOf<Int, ZoomState>() }

    // 视口尺寸 + 页在窗口中的位置：把双击点换算成「页内坐标」需要（条漫长图节点远高于视口）
    var viewportW by remember { mutableStateOf(0f) }
    var viewportH by remember { mutableStateOf(0f) }
    var viewportLeft by remember { mutableStateOf(0f) }
    var viewportTop by remember { mutableStateOf(0f) }
    val pageBounds = remember(bookId) { mutableStateMapOf<Int, Rect>() }

    val host: PageHost = remember(mode, listState, pagerState, handle.pageCount) {
        when (mode) {
            ReadingMode.WEBTOON -> WebtoonHost(listState, handle.pageCount) { viewportH }
            ReadingMode.PAGED -> PagedHost(pagerState, handle.pageCount)
        }
    }
    // 模式切换后必须重新读取设置：本 destination 离开组合即丢弃普通 remember（见 issue #8 验收记录）
    var menuVisible by remember(bookId) { mutableStateOf(false) }
    var confirm by remember(bookId) { mutableStateOf<CrossBookConfirm?>(null) }

    val currentPage by remember(host) { derivedStateOf { host.currentPage() } }

    fun toast(message: String) {
        Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
    }

    // 相邻书查询：SAF provider IPC（listFiles/子目录探测）必须在 IO 线程（review P1）
    suspend fun neighborId(prev: Boolean): String? = withContext(Dispatchers.IO) {
        val neighbors = source.neighbors(bookId)
        if (prev) neighbors.prev else neighbors.next
    }

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

    // 触摸区域类型 3（spec 故事 26）：两模式、两方向统一——左=上一页、中=菜单、右=下一页
    fun onTapZone(x: Float, width: Float) {
        when (tapIntentAt(x, width)) {
            TapIntent.MENU -> menuVisible = true
            TapIntent.PREV_PAGE -> scope.launch {
                // 书首（或条漫首图内部已到顶）→ 跨书两段式确认
                if (!host.goPrev()) {
                    val prev = neighborId(prev = true)
                    if (prev == null) toast("无上一本") else confirm = CrossBookConfirm(prev, "第一页", "上一本书")
                }
            }
            TapIntent.NEXT_PAGE -> scope.launch {
                if (!host.goNext()) {
                    val next = neighborId(prev = false)
                    if (next == null) toast("无下一本") else confirm = CrossBookConfirm(next, "最后一页", "下一本书")
                }
            }
        }
    }

    // 音量键翻页（票 20，spec 故事 39）：单页=翻一页、条漫=滚一屏；总开关在设置页（AppSettings.volumeKeysEnabled）。
    // 推进动作统一走 PageHost seam（与触摸区共用同一份两模式差异实现）。
    // 到书首/书末返回 false → MainActivity 把按键交还系统（仍可调音量）；票面只要求翻页，不做跨书确认。
    val volumeHandler: (VolumeAction) -> Boolean = remember(host) {
        { action ->
            val forward = action == VolumeAction.NEXT
            if (!host.canMoveByScreen(forward)) {
                false
            } else {
                scope.launch { host.moveByScreen(forward) }
                true
            }
        }
    }
    DisposableEffect(volumeHandler) {
        ServiceLocator.volumeKeyHandler = volumeHandler
        onDispose {
            // 仅在仍挂着自己那份时清空（避免覆盖后继注册者）
            if (ServiceLocator.volumeKeyHandler === volumeHandler) ServiceLocator.volumeKeyHandler = null
        }
    }

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

    /** 统一写入：所有路径（双击/双指/视口变化）都过边界钳制，避免存下越界状态 */
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

    // 双指缩放 + 平移（spec 故事 33/34）：条漫只做水平平移（垂直留给列表滚动），单页双向限制在图片显示区域内
    val transformState = rememberTransformableState { zoomChange, panChange, _ ->
        val page = host.currentPage()
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
            detectTapGestures(
                // 双击放大（spec 故事 31）：以双击位置为中心；再次双击恢复适屏（故事 32）
                onDoubleTap = { pos ->
                    val page = pageIndexAt(pos)
                    if (zoomOf(page).isZoomed) {
                        // 再次双击恢复适屏（spec 故事 32）
                        zoomByPage[page] = ZoomState()
                    } else {
                        // 锚点用页内坐标（条漫长图节点中心 ≠ 视口中心，否则双击点会飞走）
                        val (pageW, pageH) = pageSizeOf(page)
                        val r = pageBounds[page]
                        val localX = if (r != null) pos.x - (r.left - viewportLeft) else pos.x
                        val localY = if (r != null) pos.y - (r.top - viewportTop) else pos.y
                        applyZoom(
                            page,
                            doubleTapZoom(localX, localY, pageW, pageH, AppSettings.doubleTapScale),
                        )
                    }
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

    when (mode) {
        // 条漫：黑底、全宽、垂直连续滚动
        ReadingMode.WEBTOON -> LazyColumn(modifier = gestureModifier, state = listState) {
            items(count = handle.pageCount, key = { it }) { index ->
                ReaderPage(handle, bookId, index, fitScreen = false, zoom = zoomOf(index)) { rect ->
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
            ReaderPage(handle, bookId, index, fitScreen = true, zoom = zoomOf(index)) { rect ->
                if (pageBounds[index] != rect) pageBounds[index] = rect
            }
        }
    }

    // 跨书两段式确认条（3.jpg）：左=当前位置灰字，右=橙色跳转按钮；点按钮才跳
    confirm?.let { state ->
        CrossBookBar(
            state = state,
            onConfirm = {
                confirm = null
                onOpenBook(state.targetBookId)
            },
            onDismiss = { confirm = null },
        )
    }

    if (menuVisible) {
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

/** 跨书确认条（两段式：首点区域弹出，点条内橙色按钮才跳转） */
@Composable
private fun CrossBookBar(
    state: CrossBookConfirm,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .pointerInput(Unit) { detectTapGestures { onDismiss() } },
    ) {
        Row(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .background(Color.Black.copy(alpha = 0.85f))
                .padding(horizontal = 28.dp, vertical = 22.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = state.currentLabel,
                style = MaterialTheme.typography.bodyLarge,
                color = Color.Gray,
            )
            Text(
                text = state.actionLabel,
                style = MaterialTheme.typography.bodyLarge,
                color = Color(0xFFFF9800),
                modifier = Modifier
                    .clickable { onConfirm() }
                    .padding(horizontal = 12.dp, vertical = 10.dp),  // 命中区≥48dp
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
    onBounds: (Rect) -> Unit,
) {
    BoxWithConstraints(
        modifier = if (fitScreen) {
            Modifier.fillMaxSize().background(Color.Black)
        } else {
            Modifier.fillMaxWidth().background(Color.Black)
        },
        contentAlignment = Alignment.Center,
    ) {
        val targetWidthPx = with(LocalDensity.current) { maxWidth.toPx().toInt() }
        var bitmap by remember(bookId, index, targetWidthPx) { mutableStateOf<ImageBitmap?>(null) }
        // 取图失败（票 11 AC4：SMB 断链/超时）：给出明确提示 + 就地重试，而不是永久转圈
        var failed by remember(bookId, index, targetWidthPx) { mutableStateOf(false) }
        var retryTick by remember(bookId, index, targetWidthPx) { mutableStateOf(0) }
        LaunchedEffect(handle, bookId, index, targetWidthPx, retryTick) {
            failed = false
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    // 缓存键含 bookId+宽度：跨书同字节数不碰撞（review P0）
                    PageDecoder.decodePage(handle, index, targetWidthPx) {
                        PageDecoder.loadPageBytes(handle, index)
                    }
                }
            }
            bitmap = result.getOrNull()
            failed = bitmap == null
        }

        val image = bitmap
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
                            color = Color(0xFFFF9800),
                            style = MaterialTheme.typography.bodyLarge,
                            modifier = Modifier
                                .clickable { retryTick++ }
                                .padding(horizontal = 12.dp, vertical = 10.dp),
                        )
                    }
                } else {
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
