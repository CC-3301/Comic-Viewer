package com.cc3301.comicviewer.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.ui.input.pointer.pointerInput
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
 * 阅读菜单（票 07 / 票 28；票 #105 重定布局）：书名标题、页面预览条、跳页滑动条、当前页/总页数、上一本/下一本按钮。
 * 面板贴屏幕底部、半透明（不铺满全屏深色遮罩，当前页保持可见），高度恒为视口高度的
 * [ReaderMenuLayout.PANEL_HEIGHT_FRACTION]（40%，票 #105 AC4：手机/平板/横屏统一）。
 *
 * 三行结构（票 #105 方案 B）：标题 → 预览区（`weight(1f)`，吃剩下的高度）→ 底部行。
 * **跳页滑动条叠在预览区下缘**（一条 48dp 高的半透明渐变带，[ReaderMenuLayout.SLIDER_BAND_HEIGHT_DP]）
 * 而不独占一行：它占掉的高度会直接从预览项里扣（实测两种摆法的单格尺寸差 ~30%），
 * 方案图给的尺寸（手机 160×240、平板 207×310）与 AC1 的 2.5 / 3.5 张只在叠放时成立。
 * 预览区吃剩余高度、面板不再整体滚动，因此**页数与上/下一本按钮永远在面板里**（AC5；
 * 现状是面板上限 60% + 整体可滚动，横屏平板上这两行被挤到屏外）。
 *
 * 预览条（票 #105 AC1–AC3）：横向滑动的 [LazyRow]，内容 = 全书页，打开/跳页后滚到目标页；
 * 单格高度撑满预览区、宽度按该页真实比例（[ReaderMenuLayout.previewItemWidth]），一屏几格由屏幕宽度决定；
 * 内容比面板窄时整条水平居中（`Arrangement.spacedBy` 的对齐参数），不靠左贴边。点某格 = 跳到该页，
 * **菜单保持打开**（票 #105 AC10，与滑动条跳页一致；现状是跳完即关）。
 *
 * 跳页滑动条：拖动中预览跟随目标页、抬手跳到该页；单击轨道与拖动等价（票 #63），
 * 任意按下位置都落到最近的页（票 #105 AC9，页数少的书同样如此）。
 *
 * 底部一行（票 #105 AC7/AC8）：左/右两个等权槽位各放一个「上一本 / 下一本」，按钮占满整个槽位
 * （可点击区域 = 整份空白区）、文字在槽位里居中；页码不参与权重、按自身宽度先量，因此严格居中。
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
        val layoutDirection = LocalLayoutDirection.current
        // 面板高度 = 视口 40%（票 #105 AC4），恒为这个值：`heightIn(max=)` 与 `height()` 在本处等效
        // （预览区 `weight(1f)` 会吃满剩余高度）。极矮视口（横屏手机）下固定行本身就可能越过这个高度，
        // 那时由预览区先被压扁（它只剩几 dp），固定行越界部分被下面的 clip 裁掉——
        // 真机观察项与两个候选方案见 evidence-impl.md。
        val panelMaxHeight = maxHeight * ReaderMenuLayout.PANEL_HEIGHT_FRACTION
        // 预览条宽度要按**面板内宽**算（扣掉左右内边距）。必须连**横向 inset**
        // （手势导航栏在侧边、横屏挖孔）一起扣：面板内部的 windowInsetsPadding 会再吃掉那么多宽度。
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
                // 横屏挖孔在左/右时也不会把面板内容切掉
                .windowInsetsPadding(readerPanelInsets())
                // 上侧内边距为 0：面板顶边 → 标题行顶的留白由标题自己带（票 #67）
                .padding(
                    start = PANEL_HORIZONTAL_PADDING,
                    end = PANEL_HORIZONTAL_PADDING,
                    bottom = PANEL_BOTTOM_PADDING,
                ),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(PANEL_ROW_GAP),
        ) {
            // 书名标题（票 #67）：大字、紧贴面板顶部；字号与顶部留白都在 ReaderMenuTitle 里
            ReaderMenuTitle(title = title, panelInnerWidth = panelInnerWidth)

            // 预览区 + 叠在它下缘的跳页滑动条（票 #105 方案 B）：预览项因此能占满预览区高度
            Box(modifier = Modifier.fillMaxWidth().weight(1f)) {
                // 预览条（票 #105 AC1–AC3）：横向滑动、单格高度撑满预览区、宽度按页面真实比例。
                // 点某格 = 跳该页 + **不关菜单**（AC10）：与滑动条跳页走同一条 onSeek（ReaderScreen 里 scope.launch { host.goTo }）
                PreviewStrip(
                    handle = handle,
                    bookId = bookId,
                    target = previewTarget,
                    pageCount = pageCount,
                    panelInnerWidth = panelInnerWidth,
                    previewAreaWidth = panelInnerWidth,
                    modifier = Modifier.fillMaxSize(),
                    onTapPage = { page -> onSeek(page) },
                )
                SeekSlider(
                    seekState = seekState,
                    lastPage = lastPage,
                    onSeek = onSeek,
                    modifier = Modifier.align(Alignment.BottomCenter),
                )
            }

            // 页码与上/下一本同一行（票 #66 + 票 #105 AC7/AC8）
            ReaderMenuFooter(
                displayPage = displayPage,
                pageCount = pageCount,
                panelInnerWidth = panelInnerWidth,
                onPrevBook = onPrevBook,
                onNextBook = onNextBook,
            )
        }
    }
}

/** 面板左右内边距（预览条宽度按「面板内宽 − 两侧内边距」算，与 [ReaderMenuLayout] 同一个值） */
private val PANEL_HORIZONTAL_PADDING = ReaderMenuLayout.PANEL_HORIZONTAL_PADDING_DP.dp

/** 面板底部内边距（顶部留白由标题自己带，见 [ReaderMenuLayout.PANEL_TITLE_TOP_PADDING_DP]） */
private val PANEL_BOTTOM_PADDING = ReaderMenuLayout.PANEL_BOTTOM_PADDING_DP.dp

/** 面板三行之间的行距 */
private val PANEL_ROW_GAP = ReaderMenuLayout.PANEL_ROW_GAP_DP.dp

/** 底部行高度（票 #105 AC7）：48dp = 触摸目标下限，两个按钮因此占满这一整行高 */
private val FOOTER_HEIGHT = ReaderMenuLayout.PANEL_FOOTER_HEIGHT_DP.dp

/** 叠在预览区下缘的滑动条那一条的高度（票 #105 方案 B）：48dp = 触摸目标下限，叠放不缩可点区域 */
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
 * 菜单标题（票 #67）：书名大字 + 自有的面板顶部留白。
 *
 * - 字号随面板内宽放大（[ReaderMenuLayout.panelTitleSp]，票 #105 AC6 起夹 18–24sp）：字号/行高一起给
 *   （三者共用 [ReaderMenuLayout.PANEL_TEXT_LINE_HEIGHT_RATIO]），否则大字号会被 `titleMedium` 自带的行高压扁。
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

/**
 * 底部行（票 #105 AC7/AC8）：上一本 / 页码 / 下一本 三槽。
 *
 * 结构保证页码严格居中：中央的 Text 不参与权重、按自身宽度先量，两侧槽位各 `weight(1f)`，
 * 余量因此被二等分（各 (行宽 − 页码宽) / 2），页码中点 = 行中点；万一余量像素除不尽，
 * Compose 只给靠前的那一槽多加 1px，偏差 ≤ 0.5dp。
 *
 * 按钮（AC7/AC8）：**整个槽位**是按钮（`clickable` 铺满槽宽与行高，可点击区域 = 页码左右两侧的整份空白区，
 * 不再是从前那个 32dp 高的文字按钮），文字在槽位里居中——因此按钮不再贴屏幕左下/右下角。
 * 可点区域与位置由 `ReaderMenuFooterTest` 真发触摸事件验（左/右四分之一处分别触发上/下一本）。
 *
 * [modifier] 供测量用（测试里量行高与宽度），生产调用不传。
 */
@Composable
internal fun ReaderMenuFooter(
    displayPage: Int,
    pageCount: Int,
    panelInnerWidth: Dp,
    onPrevBook: () -> Unit,
    onNextBook: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // 页码字号随面板内宽放大（票 #66 / 票 #105 AC6）
    val pageLabelSp = with(LocalDensity.current) {
        ReaderMenuLayout.panelPageLabelSp(panelInnerWidth.value).sp
    }
    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(FOOTER_HEIGHT),
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
 * 上/下一本按钮（票 #105 AC7/AC8）：整份槽位可点，槽位里居中放一个**可见本体**（[BookStepPill]）。
 *
 * 两层分开的理由：可点区域 = 整份空白区（AC7 后半句，靠外层 `clickable` 铺满槽宽与行高），
 * 可见尺寸 = 药丸（AC7 前半句「按钮加大」，靠 [ReaderMenuLayout.BOOK_STEP_MIN_WIDTH_DP] /
 * [ReaderMenuLayout.BOOK_STEP_MIN_HEIGHT_DP] 两个下限守住）——两层各自可验。
 */
@Composable
private fun BookStepButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .fillMaxHeight()
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        BookStepPill(text = text)
    }
}

/**
 * 上/下一本按钮的**可见本体**（票 #105 AC7）：带底/描边的药丸，最小 96×48dp，文字仍在 `labelLarge` 档
 * （面板的三档字号表只管标题/页码/格内页码，不动按钮文案）。
 *
 * 配色只取面板里已有的两个值（`Color.DarkGray` = 缩略图底、`Color.Gray` = 缩略图描边）——比面板底
 * `0xFF1E1E1E` 亮、与现有控件同族，不引入新配色；圆角 9dp 取自维护者方案图 §1。
 */
@Composable
internal fun BookStepPill(text: String, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .widthIn(min = ReaderMenuLayout.BOOK_STEP_MIN_WIDTH_DP.dp)
            .heightIn(min = ReaderMenuLayout.BOOK_STEP_MIN_HEIGHT_DP.dp)
            .background(Color.DarkGray, RoundedCornerShape(BOOK_STEP_PILL_CORNER))
            .border(1.dp, Color.Gray, RoundedCornerShape(BOOK_STEP_PILL_CORNER))
            .padding(horizontal = 20.dp, vertical = 9.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, style = MaterialTheme.typography.labelLarge)
    }
}

/** 按钮药丸的圆角（维护者方案图 §1 的 9px） */
private val BOOK_STEP_PILL_CORNER = 9.dp

/**
 * 跳页滑动条（票 #63 + 票 #105 AC9）：叠在预览区下缘的那一条。
 *
 * 叠放（票 #105 方案 B）而不是独占一行：它占掉的高度会直接从预览项里扣，方案图的尺寸与 AC1 的张数
 * 只在叠放时成立。底下垫一层**从透明到半透明黑的竖向渐变**（不是实心黑条）：亮色缩略图上轨道与滑块仍看得清，
 * 同时不把这一条画成一块死板的底。48dp 高 = Material3 `Slider` 的触摸目标高度，可点区域不因叠放变小。
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
            ),
        contentAlignment = Alignment.Center,
    ) {
        Slider(
            value = seekState.value,
            onValueChange = { seekState.onValueChange(it) },
            // 跳页目标当场按滑块最新值算（票 #63）：成因见 SliderGestureState 的说明
            onValueChangeFinished = { onSeek(seekState.onGestureFinished()) },
            valueRange = 0f..lastPage.coerceAtLeast(1).toFloat(),
            modifier = Modifier.fillMaxWidth(),
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
 * - 滑动条叠在预览区下缘（方案 B），因此预览项按**整个预览区**的高度铺满——遮挡的就是滑动条那一条。
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
 * 单格预览（票 #105 AC3）：高度撑满预览区、宽度 = 高度 × 该页真实比例；缩略图铺满格子（无留白、不裁切）；
 * 页码叠在格子右上角（不占高度，预览图因此能占满预览区高度）。
 *
 * 高度从 [BoxWithConstraints] 的 `maxHeight` 拿（= 预览条的高度 = 面板剩下的那部分），
 * 宽度随之算出——两个方向的尺寸都来自同一处，格子比例与图片比例一致。超宽页（宽 > 预览区宽）
 * 由 [ReaderMenuLayout.previewItemHeight] 按宽度收口、并在预览区里垂直居中：整页可见、不靠左贴边。
 *
 * 点击整格 = 跳到该页（页位就是格位，预览条按 `items(count = pageCount)` 枚举 ⇒ 天然在界内，
 * **不经过** [ReaderMenuLayout.clampPage]），菜单保持打开（票 #105 AC10）。点击为何不会被父级抢走
 * （**未真机复核**，依据 Compose 事件分发顺序推演）：
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
        // 解码目标高度按预览区高度分桶（票 #105）：格子变大后不会拿旧高度的位图拉伸变糊
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
        // 高度撑满预览区；超宽页按预览区宽度收口（比例不变），在预览区里垂直居中
        val itemHeight = with(LocalDensity.current) {
            ReaderMenuLayout.previewItemHeight(stripHeight.value, previewAreaWidth.value, aspect).dp
        }
        val itemWidth = with(LocalDensity.current) {
            ReaderMenuLayout.previewItemWidth(itemHeight.value, aspect).dp
        }
        // 格内页码字号随面板内宽走（票 #105 AC6：面板内三档字号里最小的一档）
        val labelSp = with(LocalDensity.current) {
            ReaderMenuLayout.previewPageLabelSp(panelInnerWidth.value).sp
        }
        Box(
            modifier = Modifier
                .width(itemWidth)
                .height(itemHeight)
                .background(Color.DarkGray)
                .border(
                    width = if (highlighted) 2.dp else 1.dp,
                    color = if (highlighted) Color(0xFFFF9800) else Color.Gray,
                )
                .clickable(onClick = onClick),
            contentAlignment = Alignment.Center,
        ) {
            bitmap?.let {
                // 格子比例 = 图片比例，Fit 因此既不留白也不裁切（票 #105 AC3）
                Image(it, contentDescription = null, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
            }
            Text(
                text = ReaderMenuLayout.previewPageLabel(index).toString(),
                style = MaterialTheme.typography.labelSmall,
                // 字号/行高一起给：字号大于 labelSmall 的 16sp 行高时数字不会被压（行高比例在 ReaderMenuLayout）
                fontSize = labelSp,
                lineHeight = labelSp * ReaderMenuLayout.PANEL_TEXT_LINE_HEIGHT_RATIO,
                color = if (highlighted) Color(0xFFFF9800) else Color.LightGray,
                maxLines = 1,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    // 页码叠在图上（不占高度）：垫一层半透明暗底，浅色页面上也读得清
                    .background(Color.Black.copy(alpha = 0.45f))
                    .padding(horizontal = 4.dp, vertical = 1.dp),
            )
        }
    }
}
