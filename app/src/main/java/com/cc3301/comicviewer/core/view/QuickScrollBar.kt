package com.cc3301.comicviewer.core.view

import kotlin.math.roundToInt

/**
 * 浏览页快速定位滑条（票 #60）的纯函数：滑条几何、「拖动位移 → 目标条目索引」与两条连续化读数。
 * 由 [QuickScrollBarTest] 锁定；Compose 接线在 `ui/QuickScrollBar.kt`。
 *
 * 背景：1000+ 条目的目录里只能靠反复拖动/滚轮移动，到列表中部与末尾非常慢。滑条提供两件事——
 * ① **看得见位置**：滑条长度与位置反映「当前视口 / 整份列表」的比例；
 * ② **一步到位**：拖到某个比例即定位到对应条目。
 *
 * 口径（两档共用，因此只写一份）：
 * - 进度 = `(行索引 + 行内比例) / 总行数`（[quickScrollBarProgress]）：把整份内容切成「总行数」份，行 `r` 占
 *   `[r/总行数, (r+1)/总行数)`，行内比例是首个可见行内部已滚过的分数位置。**按行不按条目**（批次 9 r2）：网格档
 *   一档多格，按条目算会让行内速度只有真实的一半、每跨一行边界补跳 1/总格数（真机「网格档滚动时滑条一格一格跳」）；
 *   列表档一行 = 一条（每行条目数 1），算式与改动前逐像素一致。位置取**连续值**（票 #60 r6）：
 *   真机反馈「移动的时候不连贯、看起来像抽搐」的根因就是这里只吃整数索引——滚动时滑条一格一格跳。
 * - 滑条长度 = 轨道长 × 连续可见条目数 / 总条目数（网格档里「可见格子数 / 总格子数」与「可见行数 / 总行数」
 *   同值，因此同一个公式两档通用），再夹到 `[最短长度, 轨道长]`；连续可见条目数由 [quickScrollBarVisibleItems]
 *   给出，**不是** `layoutInfo.visibleItemsInfo.size` 那个整数计数（新条目一露头它就 +1，长度每格抖一下）。
 * - 拖动按**滑条中部跟手**（[quickScrollBarIndexForDrag]：抓滑条中部拖，中部就在手指下），
 *   与几何互为逆映射（拿几何算出的滑条中部去拖，正好回到那个索引）：进度与行索引都以「一行」为单位
 *   （`进度 × 总行数 = 行索引`，网格档再乘每行条目数得到条目索引），这就是两者互为逆映射的原因。
 *
 * 不显示（返回 null）的三个前提：轨道还没量到长度、条目不足两条、**条目不足一屏**（没有可快速定位的余量）。
 */
internal data class QuickScrollBarGeometry(
    /** 滑条长度（px） */
    val thumbLengthPx: Float,
    /** 滑条首端距轨道首端（px） */
    val thumbOffsetPx: Float,
)

/**
 * 滑条几何：条目数 + 视口信息 → 滑条长度与位置。返回 null = 不显示（见文件头三条前提）。
 *
 * @param totalItems 本份列表的条目数（两档都取 `layoutInfo.totalItemsCount`；网格档是格子数）
 * @param visibleItems 连续可见条目数（见 [quickScrollBarVisibleItems]）：一屏装满 N 条时取值恒为 N，
 *   网格档是格子数（与「可见行数」同比例，所以长度比例仍然对）
 * @param itemsPerRow 本档每行的条目数（网格档 = 档位列数、列表档 = 1）：进度按**行**算的口径，
 *   见 [quickScrollBarProgress]；非正数当 1（不除零）
 * @param firstVisibleItemIndex 当前首个可见条目索引（= 几何要反映的位置）
 * @param firstVisibleItemScrollFraction 首个可见条目**内部**已滚过的比例（0 = 该条目正好贴视口上缘，
 *   见 [quickScrollBarItemScrollFraction]）：让位置在两条之间也连续推进
 * @param trackLengthPx 轨道长度（px）：调用方量到的滑条可用高度
 * @param minThumbLengthPx 滑条最短长度（px）：条目极多时比例算出的长度会小到抓不住
 */
internal fun quickScrollBarGeometry(
    totalItems: Int,
    visibleItems: Float,
    firstVisibleItemIndex: Int,
    firstVisibleItemScrollFraction: Float,
    trackLengthPx: Float,
    minThumbLengthPx: Float,
    itemsPerRow: Int,
): QuickScrollBarGeometry? {
    if (trackLengthPx <= 0f) return null
    if (totalItems <= 1) return null
    if (visibleItems >= totalItems) return null
    val floor = minThumbLengthPx.coerceIn(0f, trackLengthPx)
    val thumbLength = (trackLengthPx * visibleItems / totalItems).coerceIn(floor, trackLengthPx)
    val travel = trackLengthPx - thumbLength
    return QuickScrollBarGeometry(
        thumbLengthPx = thumbLength,
        thumbOffsetPx = travel *
            quickScrollBarProgress(
                index = firstVisibleItemIndex,
                scrollFraction = firstVisibleItemScrollFraction,
                totalItems = totalItems,
                itemsPerRow = itemsPerRow,
            ),
    )
}

/** 每行的条目数：非正数一律当 1（不除零、不产生越界行号） */
private fun itemsPerRowOrOne(itemsPerRow: Int): Int = if (itemsPerRow < 1) 1 else itemsPerRow

/** 总行数 = ⌈条目数 ÷ 每行条目数⌉（最后一行不满也占一行）：进度与拖动的分母 */
private fun rowCountOf(totalItems: Int, itemsPerRow: Int): Int =
    (totalItems + itemsPerRow - 1) / itemsPerRow

/**
 * 条目索引 + 行内比例 + 每行条目数 → 轨道进度（0 = 首行起点、1 = 整份内容末尾）：几何与拖动**共用同一个进度
 * 口径**，这就是两者互为逆映射的原因（`进度 × 总行数 = 行索引`、`行索引 × 每行条目数 = 条目索引`）。
 *
 * 进度按**行**算（票 #60 批次 9 r2）：网格档一档 [itemsPerRow] 个格子，而首个可见条目索引每跨一行边界只 +1 格
 * （2 列时 0 → 2）——按条目算的话行内速度只有真实的一半、每跨一行边界还补跳 1/总格数，真机反馈的
 * 「网格档滚动时滑条一格一格跳」正是它。列表档一行 = 一条（`itemsPerRow = 1`）⇒ 算式与改动前逐像素一致。
 *
 * 行内比例先夹到 `[0, 1]`（不产生越界进度）；条目极多时索引接近总数、加上行内比例后可能到 1，因此结果
 * 也夹一次。
 *
 * @param itemsPerRow 本档每行的条目数（网格档 = 档位列数、列表档 = 1）；非正数当 1
 */
internal fun quickScrollBarProgress(
    index: Int,
    scrollFraction: Float,
    totalItems: Int,
    itemsPerRow: Int,
): Float {
    if (totalItems <= 1) return 0f
    val perRow = itemsPerRowOrOne(itemsPerRow)
    val fraction = scrollFraction.coerceIn(0f, 1f)
    val row = index / perRow
    return ((row + fraction) / rowCountOf(totalItems, perRow)).coerceIn(0f, 1f)
}

/**
 * 首个可见条目**内部**已滚过的比例（0..1，票 #60 r6）：内偏移 ÷ 该条目自身高度。
 *
 * 条目高度取不到或 ≤0 时退回 0（不除零、不抛异常）——首帧布局未就绪时高度就是 0，此时按「刚贴到上缘」算，
 * 位置仍在正确量级上。
 *
 * @param firstVisibleItemScrollOffset 首个可见条目被滚过的高度（px，`LazyListState`/`LazyGridState` 同名字段）
 * @param firstVisibleItemExtentPx 该条目自身高度（px）：列表档取 `LazyListItemInfo.size`（主轴尺寸 = 条目高），
 *   网格档取 `LazyGridItemInfo.size.height`
 */
internal fun quickScrollBarItemScrollFraction(
    firstVisibleItemScrollOffset: Int,
    firstVisibleItemExtentPx: Int,
): Float {
    if (firstVisibleItemExtentPx <= 0) return 0f
    return (firstVisibleItemScrollOffset.toFloat() / firstVisibleItemExtentPx).coerceIn(0f, 1f)
}

/**
 * 单个可见条目「露出的比例」（0..1，票 #60 r6）：[quickScrollBarVisibleItems] 的加数。
 *
 * 条目在视口坐标系里由 `[itemOffsetPx, itemOffsetPx + itemExtentPx)` 给出（被滚出上半时 offset 为负），
 * 视口是 `[viewportStartPx, viewportEndPx)`（两者同原点：`LazyListLayoutInfo` 的 `viewportStartOffset`
 * 就是含 `beforeContentPadding` 的那个起点）。
 */
internal fun quickScrollBarItemVisibleFraction(
    itemOffsetPx: Float,
    itemExtentPx: Float,
    viewportStartPx: Float,
    viewportEndPx: Float,
): Float {
    if (itemExtentPx <= 0f) return 0f
    val visiblePx = minOf(itemOffsetPx + itemExtentPx, viewportEndPx) - maxOf(itemOffsetPx, viewportStartPx)
    return (visiblePx / itemExtentPx).coerceIn(0f, 1f)
}

/**
 * 连续可见条目数（票 #60 r6）= 各可见条目露出比例之和（[quickScrollBarItemVisibleFraction]）。
 *
 * 为什么不用 `layoutInfo.visibleItemsInfo.size`：那是**整数**计数，新条目刚露头就从 10 变 11，滑条长度
 * （轨道 × 可见 / 总数）随之每格抖一下——真机反馈的「胶囊长度在滚动时抖」。按露出比例求和则**连续**：
 * 一个条目滚出时它从 1 连续降到 0，同时下一个条目从 0 连续升到 1，总量守恒（无间距时恰好 =
 * 视口长 ÷ 条目高，见 `QuickScrollBarTest` 的用例）。
 */
internal fun quickScrollBarVisibleItems(itemVisibleFractions: List<Float>): Float =
    itemVisibleFractions.sum()

/**
 * 「拖动位移 → 目标条目索引」：把抓手点沿轨道的位置换算成进度，再取最近的条目。
 *
 * 抓手点按**滑条中部**算（`positionPx − 滑条长 / 2`）：按下滑条中部拖动时，中部就在手指下，
 * 滑条不会先跳半截；与 [quickScrollBarGeometry] 互为逆映射（拿几何算出的滑条中部去拖，正好回到那个索引）。
 *
 * 越界（手指滑出轨道两端、含负值）夹在首条与末条，不产生越界索引。
 *
 * 网格档的落点是该行的**首条**（`行索引 × 每行条目数`）：行内位置由拖动精度决定不了，落到行首才能与几何
 * 的逆映射对得上（几何给出的滑条中部是该行的位置，拖回它应回到同一行）。
 *
 * @param positionPx 抓手点沿轨道的位置（px）：Compose 手势里就是指针在该容器内的 y
 * @param totalItems 本份列表的条目数
 * @param trackLengthPx 轨道长度（px）
 * @param thumbLengthPx 当前滑条长度（px）：与几何同一来源
 * @param itemsPerRow 本档每行的条目数（网格档 = 档位列数、列表档 = 1）；非正数当 1
 */
internal fun quickScrollBarIndexForDrag(
    positionPx: Float,
    totalItems: Int,
    trackLengthPx: Float,
    thumbLengthPx: Float,
    itemsPerRow: Int,
): Int {
    if (totalItems <= 1) return 0
    val travel = trackLengthPx - thumbLengthPx
    // 行程为 0（轨道与滑条等长、条目极多）时没有可拖的余量：退回首条
    if (travel <= 0f) return 0
    val perRow = itemsPerRowOrOne(itemsPerRow)
    val progress = ((positionPx - thumbLengthPx / 2f) / travel).coerceIn(0f, 1f)
    // 与 [quickScrollBarProgress] 同口径的逆映射：进度以「一行」为单位 ⇒ 行索引 = 进度 × 总行数
    val rows = rowCountOf(totalItems, perRow)
    val row = (progress * rows).roundToInt().coerceIn(0, rows - 1)
    return (row * perRow).coerceIn(0, totalItems - 1)
}
