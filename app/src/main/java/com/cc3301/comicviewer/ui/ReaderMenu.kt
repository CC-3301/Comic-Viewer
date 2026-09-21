package com.cc3301.comicviewer.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.input.pointer.changedToUp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
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
 * 阅读菜单（票 07 / 票 28；票 #105 重定布局）：书名标题、页面预览条、跳页滑动条、当前页/总页数、上一本/下一本按钮。
 * 面板贴屏幕底部、半透明（不铺满全屏深色遮罩，当前页保持可见），高度由
 * [ReaderMenuLayout.panelHeightDp] 算：竖屏/平板恒为视口高度的 40%（票 #105 AC4），
 * **矮视口（横屏手机，可用高 < 480dp）按需抬高**（票 #105 批次 6 AC11 + 裁定 A：固定行按两行标题预算、
 * 预览条保底 80dp、面板上限 80% 屏高）：360dp 视口下算得面板 249.6dp（69.3%）、预览条 **80dp**；
 * fontScale 1.4 时面板 272.6dp（75.7%）、预览条仍 80dp。52% 只是公式起点
 * （在矮视口区间内不成为约束，见 `ReaderMenuLayout.panelHeightDp`）。
 *
 * 四行结构（票 #105 AC13 起）：标题 → 预览条（`weight(1f)`，吃剩下的高度）→ **跳页滑动条（独占一行）**
 * → 底部行。滑动条从「叠在预览条下缘」改为独占一行：改前它下缘 16–48dp 的阈值完全盖在缩略图上
 * （真机 `18.jpg`），改后**不遮挡任何缩略图**，整宽可点。
 * 预览条吃剩余高度、面板不再整体滚动，因此**页数与上/下一本按钮永远在面板里**（AC5；
 * 现状是面板上限 60% + 整体可滚动，横屏平板上这两行被挤到屏外）。
 *
 * 预览条（票 #105 AC1–AC3 + 批次 6 AC14）：横向滑动的 [LazyRow]，内容 = 全书页，打开/跳页后滚到目标页；
 * 单格高度撑满预览条、宽度按该页真实比例（[ReaderMenuLayout.previewItemWidth]），一屏几格由屏幕宽度决定；
 * 内容比面板窄时整条水平居中（`Arrangement.spacedBy` 的对齐参数），不靠左贴边。点某格 = 跳到该页，
 * **菜单保持打开**（票 #105 AC10，与滑动条跳页一致；现状是跳完即关）。
 *
 * 跳页滑动条：拖动中预览跟随目标页、抬手跳到该页；单击轨道与拖动等价（票 #63），
 * 任意按下位置都落到最近的页（票 #105 AC9，页数少的书同样如此）。
 *
 * 底部一行（票 #105 AC7/AC8 + 批次 6 AC15）：左/右两个等权槽位各放一个「上一本 / 下一本」，按钮占满整个槽位
 * （可点击区域 = 整份空白区）；文案为**透明底 + 橙色文字、无边框**，按下有水波纹，
 * 不可用态**不存在**（邻位查不到时点击弹提示「无上一本」/「无下一本」，SPEC 故事 28；票面 AC15 的「降透明」已由维护者撤回）。
 * 页码不参与权重、按自身宽度先量，因此严格居中。
 *
 * 无返回按钮、无模式切换、无设置入口（spec）。上一本/下一本按钮直接执行（相对：触摸区域跨书需两段式确认）。
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
    // 菜单打开即显示滑块目标页附近的预览（预览只看滑块目标页；跳页落地后它与当前页相等）
    val previewTarget = seekState.previewTarget
    // 显示页码与格内页码同一口径：0-based 页位 → 1-based 页码在阅读菜单内只有 previewPageLabel 一处换算
    // （阅读页另有同类换算：ReaderScreen 的加载失败提示与页 contentDescription）
    val displayPage = ReaderMenuLayout.previewPageLabel(previewTarget)

    // 菜单打开时用音量键/滚轮翻页：currentPage 变了滑块必须跟上，否则预览与滑块位置互相矛盾。
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
        // 矮视口（横屏手机，票 #105 批次 6 AC11）：面板按需抬高 + 固定行压扁；竖屏/平板走原尺寸
        val shortViewport = ReaderMenuLayout.isShortViewport(maxHeight.value)
        val density = LocalDensity.current
        val panelInsets = readerPanelInsets()
        // 横向 inset 只给预览条/进度条/底部行（票 #105 AC12：标题不吃）——横屏挖孔/侧边手势条下左右不等，
        // 给整块面板加会让内容盒中心偏离屏幕中心，标题居中也会看起来不居中（口径在 ReaderMenuLayout.PanelRow）
        val titleHorizontalInsets = panelRowHorizontalInsets(ReaderMenuLayout.PanelRow.TITLE, panelInsets)
        val previewHorizontalInsets = panelRowHorizontalInsets(ReaderMenuLayout.PanelRow.PREVIEW, panelInsets)
        val sliderHorizontalInsets = panelRowHorizontalInsets(ReaderMenuLayout.PanelRow.SLIDER, panelInsets)
        // 预览条宽度要按**面板内宽**算（扣掉左右内边距，但不扣横向 inset——inset 逐行加，见下）。
        val panelInnerWidth = (maxWidth - PANEL_HORIZONTAL_PADDING * 2).coerceAtLeast(0.dp)
        // 面板要避开的底部 inset（沉浸态由 MIN_BOTTOM_DP 兜底为 24dp）——它是固定行合计的一项，
        // 面板高度公式（[ReaderMenuLayout.panelHeightDp]）必须拿到真值才能算出预览条保底高度
        val panelBottomInsetDp = with(density) { panelInsets.getBottom(this).toDp().value }
        // 标题总高按 **dp** 传给几何口径（票 #105 标准轴 P2-5 + 裁定 A）：字号是 sp、随 fontScale 放大，
        // 把 sp 数值当 dp 用会把固定行算小、把「预览条保底」变成一句假承诺；行数按**两行**预算
        // （裁定 A：矮视口不截断、不省略号），两个因子都由这里显式乘进去
        val titleLineHeightDp = ReaderMenuLayout.titleLineHeightDp(panelInnerWidth.value, density.fontScale)
        val titleHeightDp = titleLineHeightDp * ENTRY_NAME_MAX_LINES
        // 面板高度：竖屏/平板恒为 40%；矮视口 max(52% 起点, 固定行 + 预览条保底 80dp) 夹 ≤80%
        val panelMaxHeight = with(density) {
            ReaderMenuLayout.panelHeightDp(maxHeight.value, titleHeightDp, panelBottomInsetDp).dp
        }
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = panelMaxHeight)
                .clip(RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp))
                // 半透明面板：底下的当前页仍看得清
                .background(Color(0xFF1E1E1E).copy(alpha = 0.85f))
                // 吞掉面板内点击，避免穿透关闭
                .pointerInput(Unit) { detectTapGestures { } }
                // **不在这里加 windowInsetsPadding**（票 #105 AC12）：横向 inset 左右不等时会把内容盒
                // 中心整体推离屏幕中心，标题居中也会看起来不居中——inset 改为逐行加（标题那一行不加，见下）。
                // 上侧内边距为 0：面板顶边 → 标题行顶的留白由标题自己带（票 #67）
                .padding(
                    start = PANEL_HORIZONTAL_PADDING,
                    end = PANEL_HORIZONTAL_PADDING,
                    bottom = PANEL_BOTTOM_PADDING,
                ),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(ReaderMenuLayout.panelRowGapDp(shortViewport).dp),
        ) {
            // 书名标题（票 #67 + 批次 6 AC12）：大字、水平居中，**不吃横向 inset**（盒子中心 = 屏幕中心）；
            // 断行口径三档一致（裁定 A）：最多两行、不省略号（矮视口也**不截断**，面板按两行预算抬高）
            ReaderMenuTitle(
                title = title,
                panelInnerWidth = panelInnerWidth,
                topPaddingDp = ReaderMenuLayout.panelTitleTopPaddingDp(shortViewport),
                modifier = Modifier.windowInsetsPadding(titleHorizontalInsets),
            )

            // 预览条（票 #105 AC1–AC3 + 批次 6 AC14）：横向滑动、单格高度撑满预览条、宽度按页面真实比例，
            // 页数显示在缩略图正下方；点某格（含页数行）= 跳该页 + **不关菜单**（AC10）
            PreviewStrip(
                handle = handle,
                bookId = bookId,
                target = previewTarget,
                pageCount = pageCount,
                panelInnerWidth = panelInnerWidth,
                previewAreaWidth = panelInnerWidth,
                modifier = Modifier.fillMaxWidth().weight(1f).windowInsetsPadding(previewHorizontalInsets),
                onTapPage = { page -> onSeek(page) },
            )

            // 跳页滑动条独占一行、在预览条下方（票 #105 批次 6 AC13）：不遮挡任何缩略图、整宽可点。
            // 行高恒为 SLIDER_BAND_HEIGHT_DP（48dp = 触摸目标下限）——Material3 的 Slider 最小高 44dp，
            // 方案图把这一行按 12dp 建模是错的（见 evidence-impl.md 的残余风险与 panelHeightDp 的说明）
            SeekSlider(
                seekState = seekState,
                lastPage = lastPage,
                onSeek = onSeek,
                modifier = Modifier.fillMaxWidth().windowInsetsPadding(sliderHorizontalInsets),
            )

            // 页码与上/下一本同一行（票 #66 + 票 #105 AC7/AC8；矮视口行高压到 36dp）。
            // 底部 inset 加在这一行：它就是「面板内容抬离手势导航带」的那一段（行高之外额外占位，
            // 与 ReaderMenuLayout.fixedRowsHeightDp 里的 bottomInsetDp 对应）
            ReaderMenuFooter(
                displayPage = displayPage,
                pageCount = pageCount,
                panelInnerWidth = panelInnerWidth,
                rowHeight = ReaderMenuLayout.panelFooterHeightDp(shortViewport).dp,
                onPrevBook = onPrevBook,
                onNextBook = onNextBook,
                modifier = Modifier.windowInsetsPadding(panelInsets),
            )
        }
    }
}

/** 面板左右内边距（预览条宽度按「面板内宽 − 两侧内边距」算，与 [ReaderMenuLayout] 同一个值） */
private val PANEL_HORIZONTAL_PADDING = ReaderMenuLayout.PANEL_HORIZONTAL_PADDING_DP.dp

/**
 * 某一行的**横向** inset（票 #105 AC12）：口径唯一一处在 [ReaderMenuLayout.rowConsumesHorizontalInsets]。
 * 标题那一行拿到的是空 inset（否则左右不等时标题会偏离屏幕中心）；其余行拿 [-Horizontal]。
 */
private fun panelRowHorizontalInsets(row: ReaderMenuLayout.PanelRow, insets: WindowInsets): WindowInsets =
    if (ReaderMenuLayout.rowConsumesHorizontalInsets(row)) {
        insets.only(WindowInsetsSides.Horizontal)
    } else {
        WindowInsets(left = 0, top = 0, right = 0, bottom = 0)
    }

/** 面板底部内边距（顶部留白由标题自己带，见 [ReaderMenuLayout.panelTitleTopPaddingDp]） */
private val PANEL_BOTTOM_PADDING = ReaderMenuLayout.PANEL_BOTTOM_PADDING_DP.dp

/** 叠在预览条正下方的滑动条那一行的高度（票 #105 批次 6 AC13）：48dp = 触摸目标下限 */
private val SLIDER_BAND_HEIGHT = ReaderMenuLayout.SLIDER_BAND_HEIGHT_DP.dp

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
 * 面板贴底、高度上限是视口 40%（票 #105），因此它的**顶边恒在屏幕 60% 以下**，与屏幕顶部的状态栏/挖孔永不相交
 * （横屏挖孔在左/右，那两侧照旧保留）。而 `windowInsetsPadding` 是无条件加内边距的，
 * 带着上边 inset 只会在面板顶部凭空多出一条状态栏高的空白——那正是维护者报的「上方留白太多」的一部分
 * （票 #67 要收掉的留白：状态栏 inset + 原 16dp 内边距）。
 */
@Composable
internal fun readerPanelInsets(): WindowInsets =
    readerOverlayInsets().only(WindowInsetsSides.Horizontal + WindowInsetsSides.Bottom)

/**
 * 菜单标题（票 #67 + 票 #105 批次 6 AC12）：书名大字、**水平居中** + 自有的面板顶部留白。
 *
 * - 字号随面板内宽放大（[ReaderMenuLayout.panelTitleSp]，票 #105 AC6 起夹 18–24sp）：字号/行高一起给
 *   （三者共用 [ReaderMenuLayout.PANEL_TEXT_LINE_HEIGHT_RATIO]），否则大字号会被 `titleMedium` 自带的行高压扁。
 * - 水平居中：`textAlign = TextAlign.Center` + 盒宽铺满面板内宽（两者缺一不可——只居中不铺满时
 *   盒宽 = 文字宽，居中没有可观测效果）；**不吃横向 inset**（票 #105 AC12：横屏左右 inset 不等时，
 *   吃 inset 会把盒子中心推离屏幕中心，看起来就是不居中）。
 * - 断行与浏览页条目名同一条路（[EntryNameText]，票 #47/#92）：零宽空格 + 贪心断行配置，
 *   **最多两行、不省略号**（裁定 A：三档一致，矮视口也不截断；面板的固定行按两行预算抬高）。
 * - 顶部留白挂在标题自己身上（[ReaderMenuLayout.panelTitleTopPaddingDp]）：面板 Column 的上侧内边距因此为 0，
 *   「面板顶边 → 标题行顶」的距离只有这一处来源，`ReaderMenuTitleTest` 直接在 Robolectric 里量它；
 *   矮视口（横屏手机）把这份留白去掉（票 #105 批次 6 AC11 的「标题行去掉上下留白」）。
 */
@Composable
internal fun ReaderMenuTitle(
    title: String,
    panelInnerWidth: Dp,
    modifier: Modifier = Modifier,
    topPaddingDp: Float = ReaderMenuLayout.PANEL_TITLE_TOP_PADDING_DP,
) {
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
            .padding(top = topPaddingDp.dp)
            .fillMaxWidth()
            .then(modifier),
    )
}

/**
 * 底部行（票 #105 AC7/AC8；批次 6 AC15）：上一本 / 页码 / 下一本 三槽。
 *
 * 结构保证页码严格居中：中央的 Text 不参与权重、按自身宽度先量，两侧槽位各 `weight(1f)`，
 * 余量因此被二等分（各 (行宽 − 页码宽) / 2），页码中点 = 行中点；万一余量像素除不尽，
 * Compose 只给靠前的那一槽多加 1px，偏差 ≤ 0.5dp。
 *
 * 按钮（AC7/AC8；批次 6 AC15 去掉配色的底与描边）：**整个槽位**是按钮（`clickable` 铺满槽宽与行高，
 * 可点击区域 = 页码左右两侧的整份空白区，不再是从前那个 32dp 高的文字按钮），文案为透明底 + 橙色文字、无边框——
 * 因此按钮不再贴屏幕左下/右下角。按下的水波纹由 `clickable` 的默认 indication（M3 的 ripple）提供。
 * 可点区域与位置由 `ReaderMenuFooterTest` 真发触摸事件验（左/右四分之一处分别触发上/下一本）。
 *
 * **邻位查不到时仍可点**（不置灰）：`ReaderScreen` 的做法是弹提示「无上一本」/「无下一本」——
 * SPEC 故事 28 明确要求「不置灰、弹提示」，票面 AC15 里那句「不可用态橙字降透明」与之冲突，
 * 已由维护者撤回（另开票处理），因此本组件**没有** enabled 参数。
 *
 * [rowHeight] 是行高：竖屏/平板 48dp（[ReaderMenuLayout.PANEL_FOOTER_HEIGHT_DP]），
 * 矮视口压到 36dp（[ReaderMenuLayout.PANEL_FOOTER_HEIGHT_SHORT_DP]，票 #105 批次 6 AC11）。
 * [modifier] 由调用方追加：生产传底部 inset（`windowInsetsPadding(readerPanelInsets())`），
 * 测试传测量钩子（量行高与宽度）。
 */
@Composable
internal fun ReaderMenuFooter(
    displayPage: Int,
    pageCount: Int,
    panelInnerWidth: Dp,
    onPrevBook: () -> Unit,
    onNextBook: () -> Unit,
    modifier: Modifier = Modifier,
    rowHeight: Dp = ReaderMenuLayout.PANEL_FOOTER_HEIGHT_DP.dp,
) {
    // 页码字号随面板内宽放大（票 #66 / 票 #105 AC6）
    val pageLabelSp = with(LocalDensity.current) {
        ReaderMenuLayout.panelPageLabelSp(panelInnerWidth.value).sp
    }
    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(rowHeight),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // 两侧槽位等权重（票 #66）：页码不被按钮文字挤离中线
        BookStepButton(text = "上一本", onClick = onPrevBook, modifier = Modifier.weight(1f))
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
        BookStepButton(text = "下一本", onClick = onNextBook, modifier = Modifier.weight(1f))
    }
}

/**
 * 上/下一本按钮（票 #105 AC7/AC8/AC15）：整份槽位可点，槽位里居中放一个**可见本体**（[BookStepLabel]）。
 *
 * 两层分开的理由：可点区域 = 整份空白区（AC7 后半句，靠外层 `clickable` 铺满槽宽与行高），
 * 可见尺寸 = 橙字本体（AC7 前半句「按钮加大」，靠 [ReaderMenuLayout.BOOK_STEP_MIN_WIDTH_DP] /
 * [ReaderMenuLayout.BOOK_STEP_MIN_HEIGHT_DP] 两个下限守住）——两层各自可验。
 */
@Composable
private fun BookStepButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .fillMaxHeight()
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        BookStepLabel(text = text)
    }
}

/**
 * 上/下一本按钮的**可见本体**（票 #105 批次 6 AC15）：**透明底 + 橙色文字、无边框**（按下有水波纹）。
 *
 * 改动前是「白边 + 灰底 + 黑字」的药丸（维护者真机反馈要改）；现在底色与描边都不要，只留文字，
 * 文字色取 [ACCENT_ORANGE]。**没有不可用态**：邻位查不到时点击弹提示（SPEC 故事 28），不置灰。
 *
 * 本体尺寸仍由两个下限守住（96 × 48dp，AC7「按钮加大」）——底色去掉后它就是一块透明的点击承接区，
 * 尺寸可量（`ReaderMenuFooterTest` 量它的放置框）。
 */
@Composable
internal fun BookStepLabel(text: String, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .widthIn(min = ReaderMenuLayout.BOOK_STEP_MIN_WIDTH_DP.dp)
            .heightIn(min = ReaderMenuLayout.BOOK_STEP_MIN_HEIGHT_DP.dp)
            .padding(horizontal = 20.dp, vertical = 9.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelLarge,
            color = ACCENT_ORANGE,
        )
    }
}

/**
 * 跳页滑动条（票 #63 + 票 #105 AC9；行位置见批次 6 AC13）：**独占一行、在预览条正下方**的 48dp 行。
 *
 * 行高 48dp = Material3 `Slider` 的触摸目标高度（它的 `minimumInteractiveComponentSize`），
 * 可点区域因此不会变小；底下垫一层**从透明到半透明黑的竖向渐变**（不是实心黑条）：亮色页面上轨道与滑块仍看得清，
 * 同时不把这一行画成一块死板的底。
 *
 * **点按归本组件，拖动归 Material3**（票 #105 AC9）：行上自己接一个点按手势，抬手时若没超过触摸阈值，
 * 就按**按下位置**算页（[SliderGestureState.onTapFraction]）。
 *
 * 为什么能确定「一次点按只跳一次」：Compose 的 Main pass 是**子先父后**，Material3 的滑块在同一次点按里
 * 会先跑一遍（它的 `onValueChangeFinished` 于是也调一次 `onSeek`）——本组件无法在它之前拦下。因此不靠
 * 「消费抬起阻止它」，而是靠 [SliderGestureState] 的两条幂等规则收口：**没挪动值的手势不发**（M3 退回
 * 当前位置的那种）＋**同一页本次手势只发一次**（两条通路算出同一页时只发一次）。消费抬起仍然保留，
 * 它只影响**祖先**（面板的 `detectTapGestures` 与背景的关闭手势）。拖动超过阈值时本手势不消费任何事件。
 */
@Composable
internal fun SeekSlider(
    seekState: SliderGestureState,
    lastPage: Int,
    onSeek: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(SLIDER_BAND_HEIGHT)
            .background(
                Brush.verticalGradient(
                    listOf(Color.Transparent, Color.Black.copy(alpha = 0.55f)),
                ),
            )
            // 点按手势（票 #105 AC9）：只在「没拖过阈值」的抬手上消费，见本函数 KDoc
            .pointerInput(seekState) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    val startX = down.position.x
                    var dragged = false
                    while (true) {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull { it.id == down.id } ?: break
                        if (!dragged &&
                            (change.position - down.position).getDistance() > viewConfiguration.touchSlop
                        ) {
                            dragged = true
                        }
                        if (change.changedToUp()) {
                            if (!dragged && size.width > 0) {
                                seekState.onTapFraction(startX / size.width)?.let(onSeek)
                                // 吃掉这次抬起：只影响祖先（面板的 detectTapGestures / 背景的关闭手势）；
                                // 与 Material3 那条通路的重叠由 state 的幂等规则收口（见 KDoc）
                                change.consume()
                            }
                            break
                        }
                        if (!change.pressed) break
                    }
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Slider(
            value = seekState.value,
            onValueChange = { seekState.onValueChange(it) },
            // 跳页目标当场按滑块最新值算（票 #63）：成因见 SliderGestureState 的说明
            // 没挪动值（M3 的失效点按）或该页本次已发过 → onGestureFinished 返回 null，不重复跳页
            onValueChangeFinished = { seekState.onGestureFinished()?.let(onSeek) },
            valueRange = 0f..lastPage.coerceAtLeast(1).toFloat(),
            // 撑满整行高（票 #105 AC13「整宽可点」）：不撑时 Material3 的滑块本体只有 44dp，
            // 行的上/下会各留一条点不中的死条（真机上就是「点了没反应」）
            modifier = Modifier.fillMaxWidth().fillMaxHeight(),
        )
    }
}

/**
 * 页面预览条（票 #105 AC1–AC3）：全书页横向滑动，单格高度撑满预览区、宽度按页面真实比例。
 *
 * - 内容 = `0 until pageCount` 的**全部页**（不再是从前那个固定 5 格的窗口）：一屏显示几格由
 *   屏幕宽度与页面比例自然决定（AC1），滑动可看到任意页（AC2）。
 * - 打开菜单或跳页后滚到目标页（[rememberLazyListState] + [LaunchedEffect]）：目标页始终在视口内。
 *   只在 `target` 变时滚，用户自己滑预览条不会被拽回去。
 * - 整条比面板窄时（1–2 页的书、全是竖版页时）水平居中，不靠左贴边（AC3）：
 *   `Arrangement.spacedBy` 的对齐参数负责这件事。
 * - 单格高度 = **预览条高 − 页数那一行**（票 #105 批次 6 AC14 把页数改到缩略图下方）；
 *   滑动条已经不在这条预览条上（批次 6 AC13 让它独占一行），因此预览条里没有任何遮挡。
 *
 * [panelInnerWidth] 是面板可用内宽（格内页码字号按它走）；[previewAreaWidth] 是预览区宽度
 * （= 面板内宽；超宽页按它收口宽度，见 [ReaderMenuLayout.previewItemHeight]）。
 * [onTapPage] 是某格被点击时给的回调（票 #105）：参数是**该格自己的 0-based 页位**。
 */
@Composable
private fun PreviewStrip(
    handle: BookHandle,
    bookId: String,
    target: Int,
    pageCount: Int,
    panelInnerWidth: Dp,
    previewAreaWidth: Dp,
    modifier: Modifier = Modifier,
    onTapPage: (Int) -> Unit,
) {
    val listState = rememberLazyListState()
    LaunchedEffect(target, pageCount) {
        if (target in 0 until pageCount) listState.scrollToItem(target)
    }
    LazyRow(
        state = listState,
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(
            space = ReaderMenuLayout.PREVIEW_GAP_DP.dp,
            alignment = Alignment.CenterHorizontally,
        ),
    ) {
        // key=页位：窗口滑动/复用时不丢每格的位图状态
        items(count = pageCount, key = { it }) { index ->
            PreviewItem(
                handle = handle,
                bookId = bookId,
                index = index,
                panelInnerWidth = panelInnerWidth,
                previewAreaWidth = previewAreaWidth,
                highlighted = index == target,
                onClick = { onTapPage(index) },
            )
        }
    }
}

/**
 * 单个预览格（票 #105 AC3；页数位置见批次 6 AC14）：高度撑满预览条、宽度 = 高度 × 该页真实比例；
 * 缩略图铺满格子（无留白、不裁切）；**页数在缩略图正下方居中**（不再叠在右上角）。
 *
 * 高度从 [BoxWithConstraints] 的 `maxHeight` 拿（= 预览条的高度 = 面板剩下的那部分），
 * 先扣掉页数那一行（[ReaderMenuLayout.previewLabelHeightSp] 算 sp、再用 `Density.toDp()` 换成 dp，
 * fontScale 因此被如实带入）再交给 [ReaderMenuLayout.previewImageHeightDp]：
 * 两个方向的尺寸都来自同一处，格子比例与图片比例一致。超宽页（宽 > 预览条宽）
 * 由 [ReaderMenuLayout.previewItemHeight] 按宽度收口、并在预览条里垂直居中：整页可见、不靠左贴边。
 *
 * 点击**整格**（缩略图 + 下方页数行）= 跳到该页（页位就是格位，预览条按 `items(count = pageCount)` 枚举
 * ⇒ 天然在界内，**不经过** [ReaderMenuLayout.clampPage]），菜单保持打开（票 #105 AC10）。
 * `clickable` 挂在外层 `Column`（而不是缩略图那个 `Box`）：页数那一行（约 15dp）也属于这一格的点击目标，
 * 否则那一行会变成“看得见但点不动”的真空带（评审 spec P2-4）。
 * 点击为何不会被父级抢走（**未真机复核**，依据 Compose 事件分发顺序推演）：
 * 整格上的 `clickable` 在 Main pass 里比祖先先拿到事件并消费 down，因此面板 Column 的
 * `detectTapGestures {}` 与背板 Box 的「点空白关菜单」用的 `awaitFirstDown(requireUnconsumed = true)` 都收不到这次按下。
 */
@Composable
private fun PreviewItem(
    handle: BookHandle,
    bookId: String,
    index: Int,
    panelInnerWidth: Dp,
    previewAreaWidth: Dp,
    highlighted: Boolean,
    onClick: () -> Unit,
) {
    BoxWithConstraints(modifier = Modifier.fillMaxHeight(), contentAlignment = Alignment.Center) {
        val stripHeight = maxHeight
        // 页数那一行的行高（AC14）：缩略图高度 = 预览条高 − 它
        val labelSp = with(LocalDensity.current) {
            ReaderMenuLayout.previewPageLabelSp(panelInnerWidth.value).sp
        }
        // 页数那一行的行高（AC14）：字号是 sp，先用 `Density.toDp()` 换成 dp（fontScale 如实带入）——
        // 直接把 sp 数值当 dp 用会在放大字体下把这一行算小、把页数压扁
        val labelHeightDp = with(LocalDensity.current) {
            ReaderMenuLayout.previewLabelHeightSp(labelSp.value).sp.toDp().value
        }
        // 解码目标高度按预览条高度分桶（票 #105）：格子变大后不会拿旧高度的位图拉伸变糊。
        // 用预览条高（而非扣掉页数行后的图片高）分桶：分桶只上取到 32px 的整数倍，多解的那一点保证
        // 解码高度恒 ≥ 图片高度（宁可多解不可拉伸）
        val decodeHeightPx = with(LocalDensity.current) {
            ReaderMenuLayout.previewDecodeHeightPx(stripHeight.toPx())
        }
        var bitmap by remember(bookId, index, decodeHeightPx) { mutableStateOf<ImageBitmap?>(null) }
        LaunchedEffect(handle, bookId, index, decodeHeightPx) {
            bitmap = withContext(Dispatchers.IO) {
                runCatching {
                    // 内存命中则不重新取图（票 07 AC：二次呼出不重新取图）
                    PageDecoder.decodePageByHeight(handle, index, decodeHeightPx) {
                        PageDecoder.loadPageBytes(handle, index)
                    }
                }.getOrNull()
            }
        }
        // 页面比例取解码出来的位图；还没解出来时用占位比例（出图后格子跟着变宽/变窄）
        val aspect = bitmap?.let { ReaderMenuLayout.previewItemAspect(it.width, it.height) }
            ?: ReaderMenuLayout.PREVIEW_PLACEHOLDER_ASPECT
        // 高度撑满「预览条 − 页数行」；超宽页按预览条宽度收口（比例不变），在预览条里垂直居中
        val itemHeight = with(LocalDensity.current) {
            ReaderMenuLayout.previewImageHeightDp(stripHeight.value, labelHeightDp, previewAreaWidth.value, aspect).dp
        }
        val itemWidth = with(LocalDensity.current) {
            ReaderMenuLayout.previewItemWidth(itemHeight.value, aspect).dp
        }
        Column(
            modifier = Modifier
                .fillMaxHeight()
                // 整格可点：缩略图 + 下方页数行（票 #105 评审 spec P2-4）
                .clickable(onClick = onClick),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Box(
                modifier = Modifier
                    .width(itemWidth)
                    .height(itemHeight)
                    .background(Color.DarkGray)
                    .border(
                        width = if (highlighted) 2.dp else 1.dp,
                        color = if (highlighted) ACCENT_ORANGE else Color.Gray,
                    ),
                contentAlignment = Alignment.Center,
            ) {
                bitmap?.let {
                    // 格子比例 = 图片比例，Fit 因此既不留白也不裁切（票 #105 AC3）
                    Image(it, contentDescription = null, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
                }
            }
            // 页码在缩略图正下方居中（票 #105 批次 6 AC14）：不再叠在图上，因此不需要半透明底
            Text(
                text = ReaderMenuLayout.previewPageLabel(index).toString(),
                style = MaterialTheme.typography.labelSmall,
                // 字号/行高一起给：字号大于 labelSmall 的 16sp 行高时数字不会被压（行高比例在 ReaderMenuLayout）
                fontSize = labelSp,
                lineHeight = labelSp * ReaderMenuLayout.PANEL_TEXT_LINE_HEIGHT_RATIO,
                color = if (highlighted) ACCENT_ORANGE else Color.LightGray,
                maxLines = 1,
                textAlign = TextAlign.Center,
                // 行高与 [ReaderMenuLayout.previewLabelHeightSp]（经 `toDp` 换算后）同值：
                // 缩略图尺寸就是按它算的，不能两处漂移
                modifier = Modifier.height(labelHeightDp.dp),
            )
        }
    }
}
