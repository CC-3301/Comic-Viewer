package com.cc3301.comicviewer.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.cc3301.comicviewer.core.source.BookHandle
import com.cc3301.comicviewer.core.view.ReaderMenuLayout
import com.cc3301.comicviewer.core.view.ReaderOverlayLayout
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 阅读菜单（票 07 / 票 28）：书名标题、跳页滑动条、当前页/总页数、上一本/下一本按钮。
 * 面板贴屏幕底部、半透明（不铺满全屏深色遮罩，当前页保持可见），高度不超过视口 60%（横屏也不遮没当前页）。
 * 打开即显示当前页 ±2 网格预览（带页码、当前页高亮；票 #65 起窗口整体平移，总页数 ≥ 5 时首末页也凑满 5 格）；**拖动滑块或单击轨道**时预览跟随目标页、
 * 抬手后跳到该页（票 #63：点击与拖动等价）；**点击任意预览格**跳到该页并关闭菜单（票 #64，与滑块跳页同一条落地路径）；
 * 预览在手势中或跳页未落地时跟滑块，落地后即当前页。
 * 无返回按钮、无模式切换、无设置入口（spec）。
 * 上一本/下一本按钮直接执行（相对：触摸区域跨书需两段式确认）。
 * 底部一行（票 #66）：上一本靠左、当前页/总页数居中、下一本靠右——三个槽位同高的 `Row`，页码字号随面板内宽放大。
 * 标题（票 #67）：字号随面板内宽放大（22–32sp，面板内三档字号里最大的一档）、紧贴面板顶部（上方留白由标题自己带），
 * 断行口径与浏览页条目名一致（[EntryNameText]）。
 */
@Composable
fun ReaderMenu(
    title: String,
    currentPage: Int,
    pageCount: Int,
    handle: BookHandle,
    bookId: String,
    onSeek: (Int) -> Unit,
    onPrevBook: () -> Unit,
    onNextBook: () -> Unit,
    onDismiss: () -> Unit,
) {
    // 跳页滑动条的手势状态（票 #63）：滑块值与「手势结束要跳的页」都从这一个持有者读，手势回调因此只看到当次最新值
    val seekState = remember(pageCount) { SliderGestureState(initialPage = currentPage, pageCount = pageCount) }
    val lastPage = seekState.lastPage
    // 菜单打开即显示当前页 ±2 预览：手势中、或跳页还没落地（目标页 ≠ 当前页）时跟滑块走，否则跟当前页
    val previewTarget = seekState.previewTarget(currentPage)
    // 显示页码与格内页码同一口径（票 #64）：0-based 页位 → 1-based 页码只有 previewPageLabel 一处换算
    val displayPage = ReaderMenuLayout.previewPageLabel(previewTarget)

    // 菜单打开时用音量键/滚轮翻页：currentPage 变了滑块必须跟上，否则常显预览与滑块位置互相矛盾。
    // 以 currentPage 为 key：只在页面变化时同步；手势中不回写（key 未变时不触发，跨页拖动时由 !gestureActive 挡住），
    // 松手瞬间不主动回写——避免 onSeek 的 goTo 落地前把滑块闪回旧页。
    LaunchedEffect(currentPage) { seekState.syncToPage(currentPage) }

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            // 面板外任意空白处点击关闭菜单；不再铺满全屏深色遮罩（当前页保持可见）
            .pointerInput(Unit) { detectTapGestures { onDismiss() } },
        contentAlignment = Alignment.BottomCenter,
    ) {
        // 面板高度上限 = 视口高度的 60%：SPEC 支持横屏，若不设上限，横屏（视口高约 360dp）下
        // 按内容排布会占 ~73%~92% 窗高，把本票要解决的「遮住当前页」重新引回来。
        // 取 60% 而非更小值：竖屏（视口高约 780dp）的 60% ≈ 468dp 仍大于面板内容高度
        // （标题+网格+滑块+页码+按钮，估算约 310dp），竖屏观感与 r1 一致、不被压小；
        // 横屏下上限约 216dp，剩余 ≥ 40% 屏高留给当前页（当前页顶部约 144dp 可见）；
        // 面板超出上限时整体可滚动，滑动条与页码始终可达、不被裁掉。
        // 票 #67 未动这个上限（AC2 仍要求 ≤ 视口 60%）。
        val layoutDirection = LocalLayoutDirection.current
        val panelMaxHeight = maxHeight * 0.6f
        // 预览格按面板**内宽**等分（票 #42）：扣掉左右 20dp 内边距，5 格 + 4 个间隙铺满。
        // 必须连**横向 inset**（手势导航栏在侧边、横屏挖孔）一起扣：面板内部的 windowInsetsPadding 会再吃
        // 掉那么多宽度，不扣的话横屏下 5 格总和会超出实际内宽（票 #42 × 票 #44 叠加）
        val sideInsets = with(LocalDensity.current) {
            val insets = readerPanelInsets()
            (insets.getLeft(this, layoutDirection) + insets.getRight(this, layoutDirection)).toDp()
        }
        val panelInnerWidth = (maxWidth - PANEL_HORIZONTAL_PADDING * 2 - sideInsets).coerceAtLeast(0.dp)
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = panelMaxHeight)
                .clip(RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp))
                // 半透明面板：底下的当前页仍看得清
                .background(Color(0xFF1E1E1E).copy(alpha = 0.85f))
                // 吞掉面板内点击，避免穿透关闭
                .pointerInput(Unit) { detectTapGestures { } }
                // 贴底浮层显式避开系统栏与挖孔（票 #44）：背景照旧铺到屏幕边，内容抬到手势导航条之上；
                // 横屏挖孔在左/右时也不会把面板内容切掉。放在 heightIn 之内，60% 上限照旧成立
                .windowInsetsPadding(readerPanelInsets())
                .verticalScroll(rememberScrollState())
                // 上侧内边距为 0：面板顶边 → 标题行顶的留白由标题自己带（票 #67）
                .padding(
                    start = PANEL_HORIZONTAL_PADDING,
                    end = PANEL_HORIZONTAL_PADDING,
                    bottom = PANEL_BOTTOM_PADDING,
                ),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // 书名标题（票 #67）：大字、紧贴面板顶部；字号与顶部留白都在 ReaderMenuTitle 里
            ReaderMenuTitle(title = title, panelInnerWidth = panelInnerWidth)

            // 常显当前页 ±2 网格预览（页码 + 当前/目标页高亮）；票 #65 起窗口整体平移凑满 5 格，
            // 总页数不足 5 时只渲染实际存在的页（票 #42：按面板内宽等分）
            // 点击某格 = 跳该页 + 关菜单（票 #64）：与滑动条跳页走同一条 onSeek（ReaderScreen 里 scope.launch { host.goTo }）
            PreviewGrid(handle, bookId, previewTarget, pageCount, panelInnerWidth) { page ->
                onSeek(page)
                onDismiss()
            }

            Slider(
                value = seekState.value,
                onValueChange = { seekState.onValueChange(it) },
                // 跳页目标当场按滑块最新值算（票 #63）：成因见 SliderGestureState 的说明
                onValueChangeFinished = { onSeek(seekState.onGestureFinished()) },
                valueRange = 0f..lastPage.coerceAtLeast(1).toFloat(),
            )

            // 页码与上/下一本同一行（票 #66）：上一本靠左、页码严格居中、下一本靠右。
            // 居中是结构保证的：中央的 Text 不参与权重、按自身宽度先量，两侧槽位各 weight(1f)，
            // 余量因此被二等分（各 (行宽 − 页码宽) / 2），页码中点 = 行中点；万一余量像素除不尽，
            // Compose 只给靠前的那一槽多加 1px，偏差 ≤ 0.5dp（远小于 AC 的 1dp）。
            // 页码字号随面板内宽放大（票 #66）：平板不再偏小，宽面板被上限夹住。
            // 两侧不会被挤小（AC「手机不换行、不挤压」）：360dp 屏内宽 320dp，页码 ≈ 90dp + 两个按钮 ≈ 74dp × 2
            // = 238dp，余 82dp；字号上限 28sp 只在宽面板上生效，那时行宽更大。
            val pageLabelSp = with(LocalDensity.current) {
                ReaderMenuLayout.panelPageLabelSp(panelInnerWidth.value).sp
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // 两侧槽位等权重（票 #66）：页码不被按钮文字挤离中线
                Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
                    BookStepButton(text = "上一本", onClick = onPrevBook)
                }
                Text(
                    text = "$displayPage / $pageCount",
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color.White,
                    textAlign = TextAlign.Center,
                    fontSize = pageLabelSp,
                    // 字号/行高一起给（票 #66）：放大后行高不能沿用 bodyMedium 的 20sp，否则数字被压；
                    // 行高比例与格内页码、菜单标题共用一处口径（ReaderMenuLayout.PANEL_TEXT_LINE_HEIGHT_RATIO）
                    lineHeight = pageLabelSp * ReaderMenuLayout.PANEL_TEXT_LINE_HEIGHT_RATIO,
                    // 手机不得因放大而换行（票 #66 AC2）：页码恒为一行
                    maxLines = 1,
                )
                Box(Modifier.weight(1f), contentAlignment = Alignment.CenterEnd) {
                    BookStepButton(text = "下一本", onClick = onNextBook)
                }
            }
        }
    }
}

/** 面板左右内边距（预览格按「面板内宽 − 两侧内边距」等分，两处必须用同一个值） */
private val PANEL_HORIZONTAL_PADDING = 20.dp

/** 面板底部内边距（票 #67 起与顶部解耦：顶部留白由标题自己带，见 [ReaderMenuLayout.PANEL_TITLE_TOP_PADDING_DP]） */
private val PANEL_BOTTOM_PADDING = 16.dp

/**
 * 贴底浮层要避开的系统区域（票 #44）：系统栏 + 挖孔，底部再按票 #61 的口径分两支处理。
 * **跨书确认条**用这一份（阅读菜单面板自票 #67 起改用 [readerPanelInsets]，见下）；横屏挖孔在左/右时同样不被切。
 *
 * 底部那一支的必要性：阅读器路由进入沉浸后系统栏被隐藏（见 `ui/ReaderSystemBars.kt`），
 * `WindowInsets.systemBars` 随之变成 0，这份 inset 在竖屏无挖孔时就只剩 0——确认条按钮
 * 会贴到屏幕下缘、落进手势导航的上滑带。判据（**栏占位时用真实 inset、栏缺席时用「挖孔底 ∪ 24dp」**）
 * 与常量都在 [ReaderOverlayLayout.overlayBottomPx]（一处口径），这里只做「读当前 inset → 交给它算 → 并进结果」。
 */
@Composable
internal fun readerOverlayInsets(): WindowInsets {
    val density = LocalDensity.current
    val bars = WindowInsets.systemBars
    val cutout = WindowInsets.displayCutout
    val bottomPx = ReaderOverlayLayout.overlayBottomPx(
        systemBarsBottomPx = bars.getBottom(density),
        cutoutBottomPx = cutout.getBottom(density),
        density = density.density,
    )
    return bars.union(cutout).union(WindowInsets(0, 0, 0, bottomPx))
}

/**
 * 阅读菜单面板要避开的系统区域（票 #67 起从 [readerOverlayInsets] 里收窄）：只取**左/右/下**三边。
 *
 * 面板贴底、高度上限是视口 60%，因此它的**顶边恒在屏幕 40% 以下**，与屏幕顶部的状态栏/挖孔永不相交
 * （横屏挖孔在左/右，那两侧照旧保留）。而 `windowInsetsPadding` 是无条件加内边距的，
 * 带着上边 inset 只会在面板顶部凭空多出一条状态栏高的空白——那正是维护者报的「上方留白太多」的一部分
 * （票 #67 要收掉的留白：状态栏 inset + 原 16dp 内边距）。
 */
@Composable
internal fun readerPanelInsets(): WindowInsets =
    readerOverlayInsets().only(WindowInsetsSides.Horizontal + WindowInsetsSides.Bottom)

/**
 * 菜单标题（票 #67）：书名大字 + 自有的面板顶部留白。
 *
 * - 字号随面板内宽放大（[ReaderMenuLayout.panelTitleSp]，夹 22–32sp）：360dp 屏 22.4sp 是原 `titleMedium`(16sp) 的 1.4 倍；
 *   字号/行高一起给（三者共用 [ReaderMenuLayout.PANEL_TEXT_LINE_HEIGHT_RATIO]），否则 32sp 会被 `titleMedium` 自带的 24sp 行高压扁。
 * - 断行与浏览页条目名同一条路（[EntryNameText]，票 #47/#92）：零宽空格 + 贪心断行配置，最多两行、不省略号。
 * - 顶部留白挂在标题自己身上（[ReaderMenuLayout.PANEL_TITLE_TOP_PADDING_DP]）：面板 Column 的上侧内边距因此为 0，
 *   「面板顶边 → 标题行顶」的距离只有这一处来源，`ReaderMenuTitleTest` 直接在 Robolectric 里量它。
 */
@Composable
internal fun ReaderMenuTitle(title: String, panelInnerWidth: Dp, modifier: Modifier = Modifier) {
    val titleSp = with(LocalDensity.current) { ReaderMenuLayout.panelTitleSp(panelInnerWidth.value).sp }
    EntryNameText(
        name = title,
        style = MaterialTheme.typography.titleMedium.copy(
            color = Color.White,
            fontSize = titleSp,
            lineHeight = titleSp * ReaderMenuLayout.PANEL_TEXT_LINE_HEIGHT_RATIO,
        ),
        // 标题只占实际行数（列表档口径，取值来自 entryNameMinLines —— 行数口径只有那一处）
        minLines = entryNameMinLines(gridMode = false),
        textAlign = TextAlign.Center,
        modifier = Modifier
            .padding(top = ReaderMenuLayout.PANEL_TITLE_TOP_PADDING_DP.dp)
            .fillMaxWidth()
            .then(modifier),
    )
}

/** 小号文字按钮（票 #42）：高度 32dp、无大色块填充，点击语义与文案不变 */
@Composable
private fun BookStepButton(text: String, onClick: () -> Unit) {
    TextButton(
        onClick = onClick,
        modifier = Modifier.height(32.dp),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp),
    ) {
        Text(text, style = MaterialTheme.typography.labelLarge)
    }
}

/**
 * 目标页 ±2 网格预览（票 #65 起窗口整体平移，总页数 ≥ 5 时恒为 5 格，不足 5 页时只画实际存在的页；
 * 目标页=当前页或拖动目标页）。
 * 窗口页码的判定在 [ReaderMenuLayout.previewWindow]——首页显示第 1–5 页、末页显示第 N−4–N 页，目标页（高亮格）始终在窗口内。
 *
 * [panelInnerWidth] 是面板可用内宽（票 #42）：5 格等分铺满，因此 360dp 屏上单格约 59×104dp（票 #62 把格比例从
 * 58:42 改成 7:4 的竖版格，比原来约 59×82dp 高 1.27 倍，缩略图因此按格宽铺满、不再被格高卡住）。
 * [onTapPage] 是某格被点击时给的回调（票 #64）：参数是**该格自己的 0-based 页位**。
 */
@Composable
private fun PreviewGrid(
    handle: BookHandle,
    bookId: String,
    target: Int,
    pageCount: Int,
    panelInnerWidth: Dp,
    onTapPage: (Int) -> Unit,
) {
    val cellWidth = with(LocalDensity.current) {
        ReaderMenuLayout.previewCellWidth(panelInnerWidth.value).dp
    }
    Row(horizontalArrangement = Arrangement.spacedBy(ReaderMenuLayout.PREVIEW_GAP_DP.dp)) {
        // 窗口页码由纯函数给出（票 #87 + 票 #65）：窗口整体平移到合法区间；高亮格 = 目标页自己
        for (index in ReaderMenuLayout.previewWindow(target, pageCount)) {
            // key=书+页：窗口每移一格时重叠格复用 remember 状态，拖动中预览不闪空
            key(bookId, index) {
                PreviewThumb(
                    handle = handle,
                    bookId = bookId,
                    index = index,
                    cellWidth = cellWidth,
                    highlighted = index == target,
                    // 跳页页位经 clampPage 夹取（票 #64）：不在这里直接传裸 index——窗口以后若产生越界格，
                    // 跳页也不会拿到非法页位（落地路径与滑动条跳页同一条）
                    onClick = { onTapPage(ReaderMenuLayout.clampPage(index, pageCount)) },
                )
            }
        }
    }
}

/**
 * 单格预览（票 #64 起可点）：缩略图 + 其下方页码整列是一个点击目标（命中区不小于缩略图本身、含页码文字区），
 * 点击回传这一格自己的页位。
 *
 * 点击为何不会被父级抢走（**未真机复核**，依据 Compose 事件分发顺序推演）：整列上的 `clickable` 在 Main pass 里
 * 比祖先先拿到事件并消费 down，因此面板 Column 的 `detectTapGestures {}` 与背板 Box 的「点空白关菜单」用的
 * `awaitFirstDown(requireUnconsumed = true)` 都收不到这次按下——不会只关菜单不跳页。
 */
@Composable
private fun PreviewThumb(
    handle: BookHandle,
    bookId: String,
    index: Int,
    cellWidth: Dp,
    highlighted: Boolean,
    onClick: () -> Unit,
) {
    val cellHeight = with(LocalDensity.current) {
        ReaderMenuLayout.previewCellHeight(cellWidth.value).dp
    }
    // 解码宽度按格宽像素向上分桶（票 #62）：格子变大后不会拿旧宽度的位图拉伸变糊
    val thumbWidthPx = with(LocalDensity.current) {
        ReaderMenuLayout.previewDecodeWidthPx(cellWidth.toPx())
    }
    // 格内页码字号随格宽走（票 #62；口径与票 #66 一致：明显放大、≥ 现值 1.3 倍），并夹到字号上下限里
    val labelSize = ReaderMenuLayout.previewPageLabelSp(cellWidth.value).sp
    var bitmap by remember(bookId, index, thumbWidthPx) { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(handle, bookId, index, thumbWidthPx) {
        bitmap = withContext(Dispatchers.IO) {
            runCatching {
                // 内存命中则不重新取图（票 07 AC：二次呼出不重新取图）
                PageDecoder.decodePage(handle, index, thumbWidthPx) {
                    PageDecoder.loadPageBytes(handle, index)
                }
            }.getOrNull()
        }
    }
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        // 整列可点（票 #64）：命中区 = 缩略图 + 其下方页码，且消费这次按下、不被父级「点空白关菜单」抢走
        modifier = Modifier.clickable(onClick = onClick),
    ) {
        Box(
            modifier = Modifier
                .width(cellWidth)
                .height(cellHeight)
                .background(Color.DarkGray)
                .border(
                    width = if (highlighted) 2.dp else 1.dp,
                    color = if (highlighted) Color(0xFFFF9800) else Color.Gray,
                ),
            contentAlignment = Alignment.Center,
        ) {
            bitmap?.let {
                // Fit（票 #34）：预览缩略图同样完整显示不裁剪，比例比格子长的页面两侧留白即可
                Image(it, contentDescription = null, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
            }
        }
        Spacer(Modifier.height(2.dp))
        Text(
            text = ReaderMenuLayout.previewPageLabel(index).toString(),
            style = MaterialTheme.typography.labelSmall,
            // 字号/行高一起给：字号大于 labelSmall 的 16sp 行高时数字不会被压（行高比例在 ReaderMenuLayout）
            fontSize = labelSize,
            lineHeight = labelSize * ReaderMenuLayout.PANEL_TEXT_LINE_HEIGHT_RATIO,
            color = if (highlighted) Color(0xFFFF9800) else Color.LightGray,
        )
    }
}
