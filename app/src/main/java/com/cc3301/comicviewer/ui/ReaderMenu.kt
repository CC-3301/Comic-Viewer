package com.cc3301.comicviewer.ui

import androidx.compose.foundation.Canvas
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
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredHeight
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.cc3301.comicviewer.core.source.BookHandle
import com.cc3301.comicviewer.core.view.ReaderMenuLayout
import com.cc3301.comicviewer.core.view.ReaderOverlayLayout
import androidx.compose.foundation.interaction.DragInteraction
import androidx.compose.runtime.derivedStateOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.withContext

/**
 * 阅读菜单（票 07 / 票 28；票 #105 重定布局）：书名标题、页面预览条、跳页滑动条、当前页/总页数、上一本/下一本按钮。
 * 面板贴屏幕底部、半透明（不铺满全屏深色遮罩，当前页保持可见），高度由
 * [ReaderMenuLayout.panelHeightDp] 算：`min(max(base, 固定行(实测行数) + 预览条目标高度), 屏高 × 80%)`，
 * `base` = 40%（常规视口）/ 52%（矮视口）；**预览条目标高度只由视口档位决定、与标题行数无关**（第 8 轮）：
 * **第 14 轮按档取值**（票面 AC19：**除手机竖屏外其它视口逐像素不变**）：
 * - **手机竖屏**（视口宽 < 600dp 且可用高 ≥ 480dp）：预览条保底 **201dp**（第 13 轮 AC19：224 → 201），
 *   实测目标 = 基础 40% 扣掉固定行后的余量（852dp 机 221.9dp、800dp 机 201.4dp），因此**面板仍是 40%**
 *   （不再是 43.8%）；滑条行 **28dp**、面板底部内边距 **18dp 且不消费底部 inset**（维护者裁决 C）
 *   ⇒ AC18 的两段各 36dp（一屏张数：405dp 机 2.58 张 / 363dp 机 2.52 张）。
 * - **其余视口**（平板竖屏/横屏、480–700dp 高横屏、矮视口）：取
 *   `max(80dp, 基础面板 − 一行标题后的余量)`，与改动前**逐像素相同**——平板竖屏 253.8dp、平板横屏 151.4dp、
 *   600dp 高横屏 84.2dp、480dp 高横屏与矮视口 80dp；一行标题时面板回到基础占比（平板竖屏/横屏 40%、
 *   矮视口 360dp 69.3%），滑条行仍 48dp、面板仍消费底部 inset。
 * 标题变 2/3 行只把面板往上长（预览条不变）。
 *
 * 四行结构（票 #105 AC13 起）：标题（第 6 轮起 1–3 行、动态加高面板）→ 预览条（`weight(1f)`，吃剩下的高度）
 * → **跳页滑动条（独占一行，自绘：2dp 细线 + 8dp 圆球）** → 底部行（上/下一本 + 页数同一行）。滑动条从「叠在预览条下缘」改为独占一行：改前它下缘 16–48dp 的阈值完全盖在缩略图上
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
 * 底部行（补记 8）是**三等分三列**：上一本 / 页数 / 下一本各占行宽的一份，**内容在各自列里居中**
 * （因此三个中心分别在行宽的 1/6、1/2、5/6；页数不再贴行右端——那是上一轮的口径，已被本段推翻），
 * 与 `docs/SPEC.md` 故事 27 的跨书确认条「三等分三列」同一套。可见行高 36dp（补记 8 ② 的 A 档），
 * 但上一本/下一本两列的**命中带仍是 48dp**，且**只向下挂**：命中带 = `[行顶, 行顶 + 48dp]`（向上溢出 0、
 * 向下溢出 `48 − 36 = 12dp`；做法见 [BookStepButton]）。
 * 按钮文案（批次 6 AC15）不变：**透明底 + 橙色文字、无边框**，按下有水波纹；不可用态**不存在**
 * （邻位查不到时点击弹提示「无上一本」/「无下一本」，SPEC 故事 28；票面 AC15 的「降透明」已由维护者撤回）。
 * 页数为**纯白**（补记 8 ④：维护者看到的橙色是编排者预览图画错，实现本来就白，现已收口到
 * [ReaderMenuLayout.PANEL_PAGE_LABEL_COLOR] 并由用例锁住）。
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
    // 菜单打开即显示滑块目标页附近的预览（预览只看滑块目标页；跳页落地后它与当前页相等）
    val previewTarget = seekState.previewTarget
    // 显示页码与格内页码同一口径：0-based 页位 → 1-based 页码在阅读菜单内只有 previewPageLabel 一处换算
    // （阅读页另有同类换算：ReaderScreen 的加载失败提示与页 contentDescription）
    val displayPage = ReaderMenuLayout.previewPageLabel(previewTarget)

    // 菜单打开时用音量键/滚轮翻页：currentPage 变了滑块必须跟上，否则预览与滑块位置互相矛盾。
    // 以 currentPage 为 key：只在页面变化时同步；手势中不回写（key 未变时不触发，跨页拖动时由 !gestureActive 挡住），
    // 松手瞬间不主动回写——避免 onSeek 的 goTo 落地前把滑块闪回旧页。
    LaunchedEffect(currentPage) { seekState.syncToPage(currentPage) }

    // 标题的**实测行数**（第 6 轮真机反馈第 ⑤ 条：短标题 1 行、超长最多 3 行、不省略号）：
    // 由 ReaderMenuTitle 的 onTextLayout 回传；行数只进「固定行合计」，因此标题变长 → 面板变高，预览条不变
    var titleLines by remember(title) { mutableStateOf(1) }

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            // 面板外任意空白处点击关闭菜单；不再铺满全屏深色遮罩（当前页保持可见）
            .pointerInput(Unit) { detectTapGestures { onDismiss() } },
        contentAlignment = Alignment.BottomCenter,
    ) {
        // 矮视口（横屏手机，票 #105 批次 6 AC11）：只影响 base（52% vs 40%）与固定行是否压扁；
        // 面板几何档（第 14 轮）：**只有手机竖屏**走 AC19/AC18 的新几何（滑条行 28dp、底部内边距 18dp
        // 且不消费底部 inset，维护者裁决 C）；其余视口（平板竖屏/横屏、480–700dp 高横屏、矮视口）
        // 保留改动前的固定行尺寸与 inset 消费，逐像素不变（票面 AC19 明文）
        val shortViewport = ReaderMenuLayout.isShortViewport(maxHeight.value)
        val phonePortrait = ReaderMenuLayout.isPhonePortrait(maxWidth.value, maxHeight.value)
        val density = LocalDensity.current
        val layoutDirection = LocalLayoutDirection.current
        // 面板整块消费这一份 inset（票 #105 AC12（r5 修订）：**四行一致**，标题也吃横向 inset，
        // 与 `docs/SPEC.md` 故事 28「贴底浮层显式消费挖孔 inset」同口径；真机是否居中由维护者目视）
        // **手机竖屏档只剩左/右**（裁决 C），其余档仍是左/右/下（改动前口径）
        val panelInsets = readerPanelInsets(phonePortrait)
        // 内容区宽度（生产唯一出处）：屏宽 − 两侧内边距 − 左右 inset。预览区宽度与超宽页收口都读它，
        // 否则侧边 inset 非 0 时会按大一圈的宽度收口
        val panelInnerWidthDp = with(density) {
            val horizontalInsets = panelInsets.getLeft(this, layoutDirection) + panelInsets.getRight(this, layoutDirection)
            ReaderMenuLayout.panelInnerWidthDp(maxWidth.value, horizontalInsets.toDp().value)
        }
        val panelInnerWidth = panelInnerWidthDp.dp
        // 面板要避开的底部 inset **真值**（沉浸态由 MIN_BOTTOM_DP 兜底 24dp）：它是几何函数的输入；
        // 是否真的消费按档决定（手机竖屏不消费，维护者裁决 C；其余档照旧消费）
        val panelBottomInsetDp = with(density) { readerOverlayInsets().getBottom(this).toDp().value }
        // 标题**一行**的高按 dp 传（票 #105 标准轴 P2-5）：字号是 sp、随 fontScale 放大，
        // 把 sp 数值当 dp 用会把固定行算小、把「预览条目标高度」变成一句假承诺；实测行数（1–3）另传，
        // 乘进固定行的是它们两个（乘在哪一处只有 ReaderMenuLayout 里的口径）
        val titleLineHeightDp = ReaderMenuLayout.titleLineHeightDp(panelInnerWidth.value, density.fontScale)
        // 面板高度（所有视口同一公式）：min(max(base, 固定行(实测行数) + 预览条目标高度), 屏高 × 80%)，
        // base = 40%（常规视口）/ 52%（矮视口）；预览条目标高度只由视口档位决定（第 8 轮：标题行数不改它），
        // 行数变多只把面板往上长
        val panelMaxHeight = with(density) {
            ReaderMenuLayout.panelHeightDp(
                viewportWidthDp = maxWidth.value,
                viewportHeightDp = maxHeight.value,
                titleLineHeightDp = titleLineHeightDp,
                titleLineCount = titleLines,
                bottomInsetDp = panelBottomInsetDp,
            ).dp
        }
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = panelMaxHeight)
                // 面板**直角**、整个预览菜单区域是一个矩形（第 6 轮真机反馈第 ⑥ 条：「预览菜单左上角和右上角
                // 做了个圆弧，改成直角」）：这里不再 clip 成圆角
                // 半透明面板：底下的当前页仍看得清
                .background(Color(0xFF1E1E1E).copy(alpha = 0.85f))
                // 吞掉面板内点击，避免穿透关闭
                .pointerInput(Unit) { detectTapGestures { } }
                // 贴底浮层显式消费系统栏/挖孔 inset（票 #44 + 票 #105 AC12（r5 修订））：四行（含标题）
                // 一致地贴在扣掉 inset 的内容区里。**手机竖屏档只取左/右**（裁决 C：面板底边贴屏幕下缘、
                // 底部只留 panelBottomPaddingDp 的 18dp ⇒ AC18 两段各 36dp）；其余档仍是左/右/下（逐像素不变）
                .windowInsetsPadding(panelInsets)
                // 上侧内边距为 0：面板顶边 → 标题行顶的留白由标题自己带（票 #67）
                // 面板底部内边距由纯函数**按档**算（补记 8 ③ + 裁决 C）：手机竖屏 = 滑条行半高 + 行距 = 18dp
                // （不扣 inset）⇒ 两段各 36dp；其余档 = 滑条行半高 24 + 行距 − 实际 inset（下限 4dp）⇒ 回到
                // 改动前的「两段相等」（46dp）。代价（维护者已知并拍板，只影响手机竖屏档）：底行连同其
                // 48dp 命中带的下缘落进底部 inset 区
                .padding(
                    start = PANEL_HORIZONTAL_PADDING,
                    end = PANEL_HORIZONTAL_PADDING,
                    bottom = with(density) {
                        ReaderMenuLayout.panelBottomPaddingDp(
                            rowGapDp = ReaderMenuLayout.panelRowGapDp(shortViewport),
                            bottomInsetDp = panelBottomInsetDp,
                            phonePortrait = phonePortrait,
                        ).dp
                    },
                ),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(ReaderMenuLayout.panelRowGapDp(shortViewport).dp),
        ) {
            // 书名标题（票 #67 + 批次 6 AC12）：大字、**在面板内容区里水平居中**（内容区已扣掉横向 inset，
            // 与其它三行同一口径）；行数 1–3（第 6 轮真机反馈第 ⑤ 条）：短书名 1 行、超长最多 3 行、不省略号
            ReaderMenuTitle(
                title = title,
                panelInnerWidth = panelInnerWidth,
                topPaddingDp = ReaderMenuLayout.panelTitleTopPaddingDp(shortViewport),
                onLineCount = { lines -> titleLines = lines },
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
                modifier = Modifier.fillMaxWidth().weight(1f),
                onTapPage = { page -> onSeek(page) },
            )

            // 跳页滑动条独占一行、在预览条下方（票 #105 批次 6 AC13）：不遮挡任何缩略图、整宽可点。
            // 行高按档取（票 #105 AC19 + 第 14 轮分档）：手机竖屏 28dp（可拖区随之变小属有意取舍）、
            // 其余视口 48dp（改动前口径，逐像素不变）。自绘轨道（2dp 线 + 8dp 圆球）后这一行的高度
            // 不受任何控件最小高牵制（第 6 轮已删掉 Material3 的 Slider）
            SeekSlider(
                seekState = seekState,
                onSeek = onSeek,
                bandHeight = ReaderMenuLayout.sliderBandHeightDp(phonePortrait).dp,
                modifier = Modifier.fillMaxWidth(),
            )

            // 页码与上/下一本同一行（票 #66 + 票 #105 AC7/AC8），**三等分三列**（补记 8 ①）：
            // 上一本 / 页数 / 下一本各占一份、内容在列里居中。横向 inset 由面板整块消费（四行一致）；
            // 底部 inset：手机竖屏档面板已不消费（裁决 C）⇒ 本行不追加；其余档保留改动前的追加
            // （`windowInsetsPadding(panelInsets)`，逐像素不变）
            ReaderMenuFooter(
                displayPage = displayPage,
                pageCount = pageCount,
                panelInnerWidth = panelInnerWidth,
                rowHeight = ReaderMenuLayout.PANEL_FOOTER_HEIGHT_DP.dp,
                onPrevBook = onPrevBook,
                onNextBook = onNextBook,
                modifier = if (phonePortrait) Modifier else Modifier.windowInsetsPadding(panelInsets),
            )
        }
    }
}

/** 面板左右内边距（预览条宽度按「面板内宽 − 两侧内边距」算，与 [ReaderMenuLayout] 同一个值） */
private val PANEL_HORIZONTAL_PADDING = ReaderMenuLayout.PANEL_HORIZONTAL_PADDING_DP.dp

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
 * 阅读菜单面板要避开的系统区域（票 #67 起从 [readerOverlayInsets] 里收窄；第 14 轮**按档**取值）：
 * **手机竖屏档只取左/右**（第 13 轮裁决 C），**其余档取左/右/下三边**（改动前口径，逐像素不变）。
 *
 * - 面板贴底、高度上限是视口 40%（票 #105），因此它的**顶边恒在屏幕 60% 以下**，与屏幕顶部的状态栏/挖孔永不相交
 *   （横屏挖孔在左/右，那两侧照旧保留）。而 `windowInsetsPadding` 是无条件加内边距的，
 *   带着上边 inset 只会在面板顶部凭空多出一条状态栏高的空白——那正是维护者报的「上方留白太多」的一部分
 *   （票 #67 要收掉的留白：状态栏 inset + 原 16dp 内边距）。
 * - **手机竖屏档的底部不取**（维护者 2026-09-22 裁决 C）：面板底只留 [ReaderMenuLayout.panelBottomPaddingDp]
 *   的 18dp，于是 AC18 的「上/下两段各 36dp」成立。代价（维护者已知并拍板）：底行连同其 48dp 命中带的下缘
 *   落进底部 inset 区，手势导航下差异小、三键导航下可能被导航栏区域压住（evidence-impl.md 第 13 轮残余风险）。
 * - **其余档照旧取底部**（第 14 轮按票面 AC19「逐像素不变」恢复）：底部那段留白仍把面板内容抬离手势带。
 *   跨书确认条**不走这份**（它仍用 [readerOverlayInsets]，底部照旧让开栏高/挖孔）。
 */
@Composable
internal fun readerPanelInsets(phonePortrait: Boolean): WindowInsets =
    if (phonePortrait) {
        // 手机竖屏档（第 13 轮裁决 C）：只剩左/右 —— 面板底边贴屏幕下缘，底部只留 panelBottomPaddingDp
        readerOverlayInsets().only(WindowInsetsSides.Horizontal)
    } else {
        // 其余档（第 14 轮按票面 AC19「逐像素不变」保留改动前口径）：左/右/下三边
        readerOverlayInsets().only(WindowInsetsSides.Horizontal + WindowInsetsSides.Bottom)
    }

/**
 * 菜单标题（票 #67 + 票 #105 批次 6 AC12）：书名大字、**水平居中** + 自有的面板顶部留白。
 *
 * - 字号随面板内宽放大（[ReaderMenuLayout.panelTitleSp]，票 #105 AC6 起夹 18–24sp）：字号/行高一起给
 *   （三者共用 [ReaderMenuLayout.PANEL_TEXT_LINE_HEIGHT_RATIO]），否则大字号会被 `titleMedium` 自带的行高压扁。
 * - 水平居中：`textAlign = TextAlign.Center` + 盒宽铺满面板内宽（两者缺一不可——只居中不铺满时
 *   盒宽 = 文字宽，居中没有可观测效果）；横向 inset 由面板整块消费（四行一致，见 `readerPanelInsets`
 *   的调用点），标题不额外处理。
 * - 断行与浏览页条目名同一条路（[EntryNameText]，票 #47/#92）：零宽空格 + 贪心断行配置，
 *   **1–3 行、不省略号**（第 6 轮真机反馈第 ⑤ 条：短书名 1 行、超长最多 3 行，上限见
 *   [ReaderMenuLayout.READER_MENU_TITLE_MAX_LINES]；实测行数回填给面板的固定行）。
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
    /** 实测行数回传（第 6 轮）：`ReaderMenu` 按它算面板高度；其它调用点不传 */
    onLineCount: ((Int) -> Unit)? = null,
) {
    val titleSp = with(LocalDensity.current) { ReaderMenuLayout.panelTitleSp(panelInnerWidth.value).sp }
    EntryNameText(
        name = title,
        style = MaterialTheme.typography.titleMedium.copy(
            color = Color.White,
            fontSize = titleSp,
            lineHeight = titleSp * ReaderMenuLayout.PANEL_TEXT_LINE_HEIGHT_RATIO,
        ),
        // 标题只占实际行数（列表档口径，取值来自 entryNameMinLines —— 行数下限口径只有那一处）
        minLines = entryNameMinLines(gridMode = false),
        // 行数上限 3（第 6 轮真机反馈第 ⑤ 条）——浏览页条目名不传这个参数，仍是最多两行
        maxLines = ReaderMenuLayout.READER_MENU_TITLE_MAX_LINES,
        onLineCount = onLineCount,
        textAlign = TextAlign.Center,
        modifier = Modifier
            .padding(top = topPaddingDp.dp)
            .fillMaxWidth()
            .then(modifier),
    )
}

/**
 * 底部行（票 #105 AC7/AC8；批次 6 AC15；**补记 8 ① 的 V1 三等分**）：**上一本 / 页数 / 下一本三列等宽**，
 * 每列的内容在**自己那一列里居中**——因此三个水平中心分别落在行宽的 1/6、1/2、5/6
 * （与 `docs/SPEC.md` 故事 27 的跨书确认条同一套三列口径）。它推翻了上一轮的「两个等权按钮 + 页数贴行右端」：
 * 那种排法把「下一本」顶到 61.9%（维护者实测 `21.jpg`），页数贴右端、下一本偏左。
 *
 * 按钮（AC7/AC8；批次 6 AC15 去掉配色的底与描边）：**整列**是按钮（`clickable` 铺满列宽与命中高，
 * 可点区不是文字大小），文案为透明底 + 橙色文字、无边框。按下的水波纹由 `clickable` 的默认 indication
 * （M3 的 ripple）提供。
 *
 * **可见矮 / 命中不矮，且命中带只向下挂**（补记 8 ② + ③；第 10 轮第 2 条）：[rowHeight] 是**可见**行高（36dp，已压到 A 档），
 * 两列的命中带是 [ReaderMenuLayout.PANEL_FOOTER_HIT_HEIGHT_DP]（48dp = 触摸目标下限），用 `requiredHeight` 量出 48dp，
 * 再与行同心后**下移** `(48 − 行高) / 2`（见 [BookStepButton]）：命中带 = `[行顶, 行顶 + 48dp]`，
 * **向上溢出 0、向下溢出 12dp**（`ReaderMenuFooterTest` 用真触摸逐点钉住）。
 * 为何不能在行里居中：居中时 48dp 命中带向上溢出 6dp，其中 4dp 落在行距上、**2dp 压进上方那行 48dp 的滑条行**
 * （矮视口行距 0 时 6dp 全压进去）——在那 2dp 里点滑动条会走「上一本/下一本」直接跳书
 * （菜单里的跳书按钮是直接执行、无确认），与「滑条整行可点」冲突。向下挂后两处不再重叠：
 * 向上溢出 0 ≤ 行距（含矮视口的 0），`ReaderMenuFooterTest` 对 4dp / 0dp 两种行距各验一次。
 * 向下的 12dp 落在面板底部内边距那一段上，**按档不同**（第 14 轮）：
 * - **手机竖屏档**：内边距 = 滑条行半高 + 行距 = **18dp**（面板不消费底部 inset，裁决 C）⇒ 12dp 全部落在
 *   内边距内、命中带不伸出面板底边；代价是面板底边贴屏幕下缘、底行连同命中带下缘整体落进底部 inset 区
 *   （维护者已知并拍板；evidence-impl.md 第 13 轮残余风险）。
 * - **其余档**：内边距 = 滑条行半高 24 + 行距 4 − 实际底部 inset（下限 4dp）⇒ inset 取沉浸态下限 24dp 时
 *   P = 4dp，12dp 里有 **8dp 伸进底部 inset（系统手势带）**（命中带高度不许缩，只影响点击、不抢上滑，
 *   由真机目视判；见 evidence-impl.md 第 11 轮残余风险）。
 * 可见本体（[BookStepLabel]）仍与行同心（文字中心不动），它只是透明的一层、不带手势。
 *
 * 页数为**纯白**（补记 8 ④）：色值收口在 [ReaderMenuLayout.PANEL_PAGE_LABEL_COLOR]（上/下一本用橙，见 [BookStepLabel]）。
 *
 * **邻位查不到时仍可点**（不置灰）：`ReaderScreen` 的做法是弹提示「无上一本」/「无下一本」——
 * SPEC 故事 28 明确要求「不置灰、弹提示」，票面 AC15 里那句「不可用态橙字降透明」与之冲突，
 * 已由维护者撤回（另开票处理），因此本组件**没有** enabled 参数。
 *
 * [modifier] 由调用方追加：生产只在**非手机竖屏档**追加底部 inset
 * （`windowInsetsPadding(readerPanelInsets(phonePortrait))`；手机竖屏档的面板已不消费底部 inset，不追加），
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
    // 页码文字（格式只有 ReaderMenuLayout.pageLabelText 一处）与字号：
    // 字号 = AC6 值（内宽 × 0.05 夹 16–24sp）**再按中列宽度收口**（第 10 轮 spec P2）：
    // 三等分把页数锁进 1/3 列宽，而它 maxLines = 1（常规档不省略号、极端档才省略号，见下面 overflow），
    // 字号只按比例算就会在窄屏 + 大字体下把整串截掉
    val pageText = ReaderMenuLayout.pageLabelText(displayPage, pageCount)
    val pageLabelSp = with(LocalDensity.current) {
        ReaderMenuLayout.pageLabelSp(
            displayPage = displayPage,
            pageCount = pageCount,
            panelInnerWidthDp = panelInnerWidth.value,
            fontScale = fontScale,
        ).sp
    }
    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(rowHeight),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // 三列等宽（补记 8 ①）：上一本 / 页数 / 下一本——每列内容在自己列里居中，
        // 因此三个中心就是 1/6、1/2、5/6（不再是「页数贴行右端、下一本被顶左」）
        BookStepButton(text = "上一本", onClick = onPrevBook, rowHeight = rowHeight, modifier = Modifier.weight(1f))
        Text(
            text = pageText,
            style = MaterialTheme.typography.bodyMedium,
            // 纯白（补记 8 ④）：色值只剩 ReaderMenuLayout 这一处
            color = Color(ReaderMenuLayout.PANEL_PAGE_LABEL_COLOR),
            textAlign = TextAlign.Center,
            fontSize = pageLabelSp,
            // 字号/行高一起给（票 #66）：放大后行高不能沿用 bodyMedium 的 20sp，否则数字被压；
            // 行高比例与格内页码、菜单标题共用一处口径（ReaderMenuLayout.PANEL_TEXT_LINE_HEIGHT_RATIO）
            lineHeight = pageLabelSp * ReaderMenuLayout.PANEL_TEXT_LINE_HEIGHT_RATIO,
            // 手机不得因放大而换行（票 #66 AC2）：页码恒为一行；`softWrap = false` 是不换行的落法。
            // 字号已按列宽收口（见 [ReaderMenuLayout.pageLabelSp]），常规场合放得下；
            // 只有「连层级下限（格内页码字号）都放不下」的极端档才截断，那时给省略号（比硬切更看得出是截断）
            maxLines = 1,
            softWrap = false,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        BookStepButton(text = "下一本", onClick = onNextBook, rowHeight = rowHeight, modifier = Modifier.weight(1f))
    }
}

/**
 * 上/下一本按钮（票 #105 AC7/AC8/AC15）：**整列可点**，列里居中放一个**可见本体**（[BookStepLabel]）。
 *
 * 两层分开的理由：可点一层 = 整列（AC7 后半句，靠外层 `clickable` 铺满列宽），
 * 可见一层 = 橙字本体（AC7 前半句「按钮加大」，靠 [ReaderMenuLayout.BOOK_STEP_MIN_WIDTH_DP] /
 * [ReaderMenuLayout.BOOK_STEP_MIN_HEIGHT_DP] 两个下限守住）——两层各自可验。
 *
 * 可点一条的**高度**用 `requiredHeight`（补记 8 ③）：行可见高已压到 36dp，
 * `height` 会被行的约束夹回 36dp，`requiredHeight` 忽略传入约束、量出 48dp（触摸目标下限）。
 *
 * **命中层只向下挂**（第 10 轮第 2 条）：命中层与行同心后再用 `offset` 下移 `(48 − 行高) / 2 = 6dp`，
 * 实测带 = `[行顶, 行顶 + 48]`（`ReaderMenuFooterTest` 的 5 个探点：行顶上方 5dp / 1dp 点不中，
 * 行顶下方 1dp / 44dp 点得中，行顶下方 52dp 点不中）——**向上溢出 0**，不抢上方 4dp 行距与滑条行下缘
 * （矮视口行距 0 时也不抢）。
 * 为何不用 `align(TopCenter)` 一步到位：实测它在「子项比容器高」时不生效（带会被居中），
 * 所以改成「先与行同心、再下移」这条可验证的路子。列容器同时用 `fillMaxHeight()` 钉在行高上，
 * 保证下移量是相对行（而不是相对一个被内容撑大的容器）。
 * 可见本体（[BookStepLabel]）仍与行同心（文字中心不动），它只是透明的一层、不带手势。
 */
@Composable
private fun BookStepButton(
    text: String,
    onClick: () -> Unit,
    rowHeight: Dp,
    modifier: Modifier = Modifier,
) {
    // 列容器的高度钉在行高上（fillMaxHeight）：命中层的下移量是相对**行**算的，
    // 容器若被内容撑大再被 Row 居中，下移基准就跟着漂
    Box(modifier = modifier.fillMaxHeight()) {
        // 命中层：48dp，与行同心后再**下移** (48 − 行高) / 2（⇒ 顶边落在行顶、只向下挂）；整列宽可点（AC7）
        Box(
            modifier = Modifier
                .align(Alignment.Center)
                .fillMaxWidth()
                .requiredHeight(ReaderMenuLayout.PANEL_FOOTER_HIT_HEIGHT_DP.dp)
                .offset(y = hitDropDp(rowHeight))
                .clickable(onClick = onClick),
        )
        // 可见本体：与行同心（文字中心 = 行中心），透明无手势；`requiredHeight` 让它保住 48dp 的可见尺寸（AC7）
        BookStepLabel(
            text = text,
            modifier = Modifier
                .align(Alignment.Center)
                .requiredHeight(ReaderMenuLayout.BOOK_STEP_MIN_HEIGHT_DP.dp),
        )
    }
}

/**
 * 命中层相对行中心要**下移**多少（dp）：`(命中高 − 行高) / 2`。
 *
 * 作用：把「与行同心」的 48dp 命中带推到「顶边与行顶齐平」（只向下挂）——向上溢出 0、向下 `48 − 行高`。
 * 行高 ≥ 命中高时回 0（不下移）。它只动命中层（空 Box），可见本体的位置不受影响。
 */
private fun hitDropDp(rowHeight: Dp): Dp =
    (((ReaderMenuLayout.PANEL_FOOTER_HIT_HEIGHT_DP - rowHeight.value) / 2f).coerceAtLeast(0f)).dp

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
 * 跳页滑动条（票 #63 + 票 #105 AC9；第 6 轮真机反馈第 ③④⑦ 条重做）。
 *
 * **整行只有一个手势主人**（本组件自己的 `pointerInput`），样式也自己画，不再用 Material3 的 `Slider`。
 *
 * 样式（第 ④ 条）：轨道是 [ReaderMenuLayout.SLIDER_TRACK_HEIGHT_DP]（2dp）的细线、拇指是
 * [ReaderMenuLayout.SLIDER_THUMB_DIAMETER_DP]（8dp）的圆球；**已划过**的线段与圆球用仓库既有强调色
 * [ACCENT_ORANGE]（黄）、未划过用灰（[ReaderMenuLayout.SLIDER_TRACK_REMAINDER_COLOR]）。
 * **不画任何底色/渐变**（第 ③ 条：上一轮那一层「更深的背景色块」删掉）。命中行高 = [bandHeight]，
 * 由调用方**按档**传（`ReaderMenuLayout.sliderBandHeightDp`：**手机竖屏 28dp** / 其余视口 48dp＝触摸目标下限），
 * 整宽可点。
 *
 * 手势（第 ⑦ 条「3 页书点进度条任意位置都能跳页」第三次返工的**真因**）：上一轮同行里叠了两条通路——
 * Material3 对按下位置做「扣掉拇指半宽」的换算，本组件自接的 `pointerInput` 另算「行上比例」；
 * 实测（`SeekSliderTapTest` 与一轮临时诊断用例）生效的是 Material3 那条、自接那条根本没触发：
 * 200 页书按下行宽 25% 处跳到第 50 页（行上比例口径应为第 51 页），3 页书按下行中点**什么都没发出**
 * （应为第 2 页）。真机现象因此是「只有个别位置有效」。现在按下 / 拖动 / 抬手全归本组件，
 * 比例 → 值/页只剩 [ReaderMenuLayout.seekTargetPageForFraction] 一个函数，不存在「哪条生效」的问题。
 *
 * 三条行为：
 * ① **拖动**（越过触摸阈值）→ 每一帧 `onValueChange`（连续值，拇指跟手；预览按页跟随）；
 * ② **抬手** → 拖动路径走 [SliderGestureState.onGestureFinished]（值没挪动就不发跳页）、
 *    点按路径走 [SliderGestureState.onTapFraction]（按**按下位置的比例**算页，与拖动同一映射）；
 * ③ **事件全消费**：祖先（面板的 `detectTapGestures`、背景的「点空白关菜单」）收不到这次点按，
 *    菜单因此**保持打开**（票 #105 AC10）。
 */
@Composable
internal fun SeekSlider(
    seekState: SliderGestureState,
    onSeek: (Int) -> Unit,
    bandHeight: Dp,
    modifier: Modifier = Modifier,
) {
    // 页数从状态回推（滑块值域就是 0..末页页位）：比例 → 页 与 值 → 比例 都读这一处，避免两处各写一份
    val pageCount = seekState.lastPage + 1
    val fraction = ReaderMenuLayout.sliderFraction(seekState.value, pageCount)
    val thumbDiameter = ReaderMenuLayout.SLIDER_THUMB_DIAMETER_DP.dp
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(bandHeight)
            .pointerInput(seekState) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    // 按下即消费：祖先的「点空白关菜单」与面板自身的点击都不起手（票 #105 AC10）
                    down.consume()
                    // 行宽**每次手势现读**（第 7 轮 standards P2）：`pointerInput` 块在 key 不变时不会重启，
                    // 起手读一次的写法会在「协程早于首帧布局启动」时把 rowWidth 永久钉在 0
                    // （startX / 0 = ∞ ⇒ 每次点按都跳末页）。宽度没量出来就整次手势不处理。
                    val rowWidth = size.width.toFloat()
                    val startX = down.position.x
                    var dragging = false
                    while (true) {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull { it.id == down.id } ?: break
                        change.consume()
                        if (!change.pressed) {
                            if (rowWidth > 0f) {
                                val page = if (dragging) {
                                    seekState.onGestureFinished()
                                } else {
                                    seekState.onTapFraction(startX / rowWidth)
                                }
                                page?.let(onSeek)
                            }
                            break
                        }
                        if (!dragging &&
                            (change.position - down.position).getDistance() > viewConfiguration.touchSlop
                        ) {
                            dragging = true
                        }
                        if (dragging && rowWidth > 0f) {
                            seekState.onValueChange(
                                ReaderMenuLayout.sliderValueForFraction(change.position.x / rowWidth, pageCount),
                            )
                        }
                    }
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Canvas(modifier = Modifier.fillMaxWidth().height(bandHeight)) {
            val radius = thumbDiameter.toPx() / 2f
            val centerY = size.height / 2f
            // 拇指圆心：**传整条轨道宽**给几何口径，半径的收口只在 [ReaderMenuLayout.sliderThumbCenterXPx] 里
            // 做一次（第 7 轮 standards P2：外面再减一次半径会让圆球行程比手势映射窄 2r）
            val centerX = ReaderMenuLayout.sliderThumbCenterXPx(
                fraction = fraction,
                trackWidthPx = size.width,
                thumbDiameterPx = thumbDiameter.toPx(),
            )
            // 轨道线画在 [radius, size.width − radius] 上：两端各留一个半径，圆球不出行两端
            val trackLeft = radius
            val trackRight = (size.width - radius).coerceAtLeast(trackLeft)
            val strokeWidth = ReaderMenuLayout.SLIDER_TRACK_HEIGHT_DP.dp.toPx()
            drawLine(
                color = Color(ReaderMenuLayout.SLIDER_TRACK_REMAINDER_COLOR),
                start = Offset(trackLeft, centerY),
                end = Offset(trackRight, centerY),
                strokeWidth = strokeWidth,
                cap = StrokeCap.Round,
            )
            drawLine(
                color = ACCENT_ORANGE,
                start = Offset(trackLeft, centerY),
                end = Offset(centerX.coerceIn(trackLeft, trackRight), centerY),
                strokeWidth = strokeWidth,
                cap = StrokeCap.Round,
            )
            drawCircle(color = ACCENT_ORANGE, radius = radius, center = Offset(centerX, centerY))
        }
    }
}

/**
 * 页面预览条（票 #105 AC1–AC3）：全书页横向滑动，单格高度撑满预览区、宽度按页面真实比例。
 *
 * - 内容 = `0 until pageCount` 的**全部页**（不再是从前那个固定 5 格的窗口）：一屏显示几格由
 *   屏幕宽度与页面比例自然决定（AC1），滑动可看到任意页（AC2）。
 * - 打开菜单或跳页后滚到目标页（[rememberLazyListState] + [LaunchedEffect]）：目标页始终在视口内，
 *   且**只要左右还有空间就落在预览区正中**（票 #105 AC17：先滚到该页，再按它自己的宽补一个居中偏移；
 *   首页贴左缘、末页贴右缘——两端的居中量由 `LazyList` 夹掉）。
 *   重算条件（第 15 轮）：`target`/`pageCount` 变化，**或目标项的实测宽变化**（位图到达 ⇒ 真实比例生效、
 *   标题行数回填 ⇒ 预览条高度变）——只按前者算一次会让真实比例 ≠ 占位比例的页偏 `|真实宽 − 占位宽| / 2`。
 *   用户**手指拖动**过预览条之后（`DragInteraction.Start`）本轮不再重算，不会与手指抢交互；
 *   跳页（`target` 变）重置该标志（票面 AC17 的「居中」针对目标页变化/跳页）。
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
    // 「用户已接管」（票 #105 AC17 的取舍，第 15 轮）：预览条被**手指拖动**过之后，本轮不再把当前页拉回中心
    //（否则会跟手指抢交互，且票面 AC17 的「居中」针对的是目标页变化/跳页）；跳页（`target` 变）是新一次
    //「把当前页摆正」的请求，因此会重置这个标志
    var userTookOver by remember { mutableStateOf(false) }
    LaunchedEffect(target) { userTookOver = false }
    LaunchedEffect(listState) {
        listState.interactionSource.interactions.collect { interaction ->
            if (interaction is DragInteraction.Start) userTookOver = true
        }
    }
    // 目标项的**实测宽**（px）：它是居中偏移的输入，而且**居中之后还会变**——位图到达 ⇒ 真实比例生效
    //（未解码时是占位比例 2:3），标题行数回填 ⇒ 预览条高度变 ⇒ 全格宽度变。只按 target/pageCount 算一次
    // 会让真实比例 ≠ 2:3 的页（封面、双页跨页）偏 `|真实宽 − 占位宽| / 2`，所以它必须进重算条件
    val targetItemWidthPx by remember(target, pageCount) {
        derivedStateOf { listState.layoutInfo.visibleItemsInfo.firstOrNull { it.index == target }?.size ?: 0 }
    }
    LaunchedEffect(target, pageCount, targetItemWidthPx, userTookOver) {
        if (target !in 0 until pageCount || userTookOver) return@LaunchedEffect
        // 两步走（票 #105 AC17）：① 先把目标项带进视口（它可能离得很远，那时量不到它的宽）；
        // ② 量出**目标项自己**的宽与视口宽，再按「条目居中」补一个偏移——首/末页不用特判：
        // 两头想推的方向正好是列表滚不动的那一侧，LazyList 自己把滚动量夹在界内（贴边）
        listState.scrollToItem(target)
        val info = listState.layoutInfo
        val item = info.visibleItemsInfo.firstOrNull { it.index == target } ?: return@LaunchedEffect
        val offset = ReaderMenuLayout.previewCenterScrollOffsetPx(item.size, info.viewportSize.width)
        if (offset != 0) listState.scrollToItem(target, scrollOffset = offset)
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
