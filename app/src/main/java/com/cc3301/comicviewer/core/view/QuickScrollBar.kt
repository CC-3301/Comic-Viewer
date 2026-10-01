package com.cc3301.comicviewer.core.view

import kotlin.math.roundToInt

/**
 * 浏览页快速定位滑条的纯函数：滑条几何、「拖动位移 → 目标落点」与两条连续化读数。
 * 由 [QuickScrollBarTest] 锁定；Compose 接线在 `ui/QuickScrollBar.kt`。
 *
 * 背景：1000+ 条目的目录里只能靠反复拖动/滚轮移动，到列表中部与末尾非常慢。滑条提供两件事——
 * ① **看得见位置**：滑条长度与位置反映「当前视口 / 整份列表」的比例；
 * ② **一步到位**：拖到某个比例即定位到对应条目。
 *
 * 口径（两档共用，因此只写一份）：
 * - 进度 = `(行索引 + 行内比例) / 可滚动行数`（[quickScrollBarProgress]、 ①）：分母是**可滚动行数**
 *   = 总行数 − 可见行数（下限 1），行内比例是首个可见行内部已滚过的分数位置。**按行不按条目**（批次 9 r2）：网格档
 *   一档多格，按条目算会让行内速度只有真实的一半、每跨一行边界补跳 1/总格数（真机「网格档滚动时滑条一格一格跳」）；
 *   列表档一行 = 一条（每行条目数 1），算式与改动前逐像素一致。位置取**连续值**：
 *   真机反馈「移动的时候不连贯、看起来像抽搐」的根因就是这里只吃整数索引——滚动时滑条一格一格跳。
 * - 滑条长度 = 轨道长 × 连续可见条目数 / 总条目数（网格档里「可见格子数 / 总格子数」与「可见行数 / 总行数」
 *   同值，因此同一个公式两档通用），再夹到 `[最短长度, 轨道长]`；连续可见条目数由 [quickScrollBarVisibleItems]
 *   给出，**不是** `layoutInfo.visibleItemsInfo.size` 那个整数计数（新条目一露头它就 +1，长度每格抖一下）。
 * - 拖动按**滑条中部跟手**（[quickScrollBarTargetForDrag]：抓滑条中部拖，中部就在手指下），
 *   与几何互为逆映射（拿几何算出的滑条中部去拖，正好回到同一个行内位置）：进度与行内位置都以「一行」为单位
 *   （`进度 × 可滚动行数 = 行索引 + 行内偏移`，网格档再乘每行条目数得到条目索引），这就是两者互为逆映射的原因。
 *   反解**保留行内偏移**：取整到整行会让网格档一次跳一整行（真机「一排排封面滚」）。
 *   两条路共用同一个分母函数（[scrollableRowCount]）⇒ 分母口径只能改一处，不会一边改一边忘。
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
 *   网格档是格子数（与「可见行数」同比例，所以长度比例仍然对）。**浮点**，进度分母与长度比例都读它
 *   这**同一个量**（①：各算一次就会在比例尺上错开）
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
                visibleItems = visibleItems,
            ),
    )
}

/** 每行的条目数：非正数一律当 1（不除零、不产生越界行号） */
private fun itemsPerRowOrOne(itemsPerRow: Int): Int = if (itemsPerRow < 1) 1 else itemsPerRow

/** 总行数 = ⌈条目数 ÷ 每行条目数⌉（最后一行不满也占一行）：可滚动行数的被减数 */
private fun rowCountOf(totalItems: Int, itemsPerRow: Int): Int =
    (totalItems + itemsPerRow - 1) / itemsPerRow

/**
 * **可滚动行数** = 总行数 − 可见行数（下限 1）：进度与拖动**共用**的分母（①）。
 *
 * 测试反查取样点时调的就是这个函数（不再自己抄一份算式）⇒ 分母口径只此一处。
 *
 * 为什么不是总行数（原式）：滚到底时首个可见行就是「总行数 − 可见行数」那一行、行内比例为 0 ⇒
 * 分子上界恰好是**可滚动行数**，分母取总行数时进度恒 < 1，滑条永远差一截到不了底（十几条的目录里停在轨道
 * 约 77%，维护者 2026-09-30 一眼看出；1000 条的目录里差 0.9%，当初因此登记为「肉眼基本不可感」）。
 * 分母取可滚动行数后：到顶进度 = 0、到底进度 = 1，滑条分别贴轨道两端。
 *
 * 可见行数是**浮点**（[quickScrollBarVisibleItems] 按露出比例求和、再 ÷ 每行条目数）⇒ 与几何的长度比例
 * **读同一个量**：两处各算一次会在比例尺上错开（① 点明的坑）。
 *
 * 下限 1 只为不除零：可见条目数与总条目数相等（不足一屏，几何那时返回 null）时没有可滚动的余量。
 */
internal fun scrollableRowCount(totalItems: Int, itemsPerRow: Int, visibleItems: Float): Float =
    (rowCountOf(totalItems, itemsPerRowOrOne(itemsPerRow)) - visibleItems / itemsPerRowOrOne(itemsPerRow))
        .coerceAtLeast(1f)

/**
 * 条目索引 + 行内比例 + 每行条目数 + 可见条目数 → 轨道进度（0 = 首行起点、1 = 到底）：几何与拖动**共用同一个
 * 进度口径**，这就是两者互为逆映射的原因（`进度 × 可滚动行数 = 行索引`、`行索引 × 每行条目数 = 条目索引`）。
 *
 * 进度按**行**算（批次 9 r2）：网格档一档 [itemsPerRow] 个格子，而首个可见条目索引每跨一行边界只 +1 格
 * （2 列时 0 → 2）——按条目算的话行内速度只有真实的一半、每跨一行边界还补跳 1/总格数，真机反馈的
 * 「网格档滚动时滑条一格一格跳」正是它。列表档一行 = 一条（`itemsPerRow = 1`）⇒ 算式与改动前逐像素一致。
 *
 * 行内比例先夹到 `[0, 1]`（不产生越界进度）；条目极多时索引接近总数、加上行内比例后可能到 1，因此结果
 * 也夹一次。
 *
 * @param itemsPerRow 本档每行的条目数（网格档 = 档位列数、列表档 = 1）；非正数当 1
 * @param visibleItems 连续可见条目数（与几何的长度比例同源、同一份读数，见 [scrollableRowCount]）
 */
internal fun quickScrollBarProgress(
    index: Int,
    scrollFraction: Float,
    totalItems: Int,
    itemsPerRow: Int,
    visibleItems: Float,
): Float {
    if (totalItems <= 1) return 0f
    val perRow = itemsPerRowOrOne(itemsPerRow)
    val fraction = scrollFraction.coerceIn(0f, 1f)
    val row = index / perRow
    return ((row + fraction) / scrollableRowCount(totalItems, perRow, visibleItems)).coerceIn(0f, 1f)
}

/**
 * 首个可见条目**内部**已滚过的比例（0..1）：内偏移 ÷ 该条目自身高度。
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
 * 单个可见条目「露出的比例」（0..1）：[quickScrollBarVisibleItems] 的加数。
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
 * 连续可见条目数= 各可见条目露出比例之和（[quickScrollBarItemVisibleFraction]）。
 *
 * 为什么不用 `layoutInfo.visibleItemsInfo.size`：那是**整数**计数，新条目刚露头就从 10 变 11，滑条长度
 * （轨道 × 可见 / 总数）随之每格抖一下——真机反馈的「胶囊长度在滚动时抖」。按露出比例求和则**连续**：
 * 一个条目滚出时它从 1 连续降到 0，同时下一个条目从 0 连续升到 1，总量守恒（无间距时恰好 =
 * 视口长 ÷ 条目高，见 `QuickScrollBarTest` 的用例）。
 */
internal fun quickScrollBarVisibleItems(itemVisibleFractions: List<Float>): Float =
    itemVisibleFractions.sum()

/** 拖动落点：目标条目索引 + 该行内的纵向偏移（px） */
internal data class QuickScrollBarDragTarget(
    /** 目标条目索引：网格档是该行的**首条**（同一行的条目竖向位置相同，行内位置分不出条目） */
    val index: Int,
    /**
     * 行内偏移（px）：0 = 该行上沿停在「顶部内容留白已被吃掉」的位置，越大 = 该行滚得越高。
     * **不含**顶部内容留白那一段——那一层按停位口径加在落位处（`ui/QuickScrollBar.kt` 的
     * `quickScrollBarLandingOffsetPx`），两档因此在纯函数这一层不必知道留白。
     */
    val rowOffsetPx: Int,
)

/**
 * 「拖动位移 → 目标落点（行首条目索引 + 行内偏移）」：把抓手点沿轨道的位置换算成**行内位置**，
 * 再拆成行索引与行内偏移。
 *
 * 抓手点按**滑条中部**算（`positionPx − 滑条长 / 2`）：按下滑条中部拖动时，中部就在手指下，
 * 滑条不会先跳半截；与 [quickScrollBarGeometry] 互为逆映射（拿几何算出的滑条中部去拖，正好回到同一个
 * 行内位置——几何的分子是「行索引 + 行内比例」，这里原样解回来）。
 *
 * **行内偏移为什么不能省**（修法）：反解只取行索引（取整到整行）时，网格档拖动不足一行的位移不改变
 * 落点、跨过半行就跳下一整行（真机「一排排封面滚」）；几何给出的位置是连续的，落点也必须是连续值。
 *
 * 行内偏移按**行距** [rowExtentPx] 折算，且必须与正向 [quickScrollBarItemScrollFraction] 用的是**同一个量**
 *（那里是「首个可见条目自身高度」）：两处各算一次会在比例尺上错开 ⇒ 松手后的滑条位置与拖动时看到的不一致。
 *
 * 越界（手指滑出轨道两端、含负值）夹在**可达范围** `[0, 可滚动行数]` 内（①）：轨道底端 = 列表底，
 * 反解出的就是「滚到底时首个可见的那一行」，因此不会比它更靠后（更靠后那几行滑条位置已经与它重合）；
 * 再夹一层「不超过末行」——可见条目数读数极小（甚至为 0）时可滚动行数会超过「总行数 − 1」。
 * 行内偏移**取整到 px 后正好凑满一行时进位到下一行**：行边界上的浮点误差（形如 0.9999996 行）不这么算
 * 就会给出「行号少一行 + 偏移 = 整行」的非规范落点——行号与偏移都不再是它们该有的值。
 * 网格档乘每行条目数回到条目索引，并夹在 `[0, 总条目数 − 1]`。
 *
 * @param positionPx 抓手点沿轨道的位置（px）：Compose 手势里就是指针在该容器内的 y
 * @param totalItems 本份列表的条目数
 * @param trackLengthPx 轨道长度（px）
 * @param thumbLengthPx 当前滑条长度（px）：与几何同一来源
 * @param itemsPerRow 本档每行的条目数（网格档 = 档位列数、列表档 = 1）；非正数当 1
 * @param visibleItems 连续可见条目数：与几何的长度比例同一份读数（同一个量才能互逆，见 [scrollableRowCount]）
 * @param rowExtentPx 行距（px）：与 [quickScrollBarItemScrollFraction] 的条目高度同一份读数
 */
internal fun quickScrollBarTargetForDrag(
    positionPx: Float,
    totalItems: Int,
    trackLengthPx: Float,
    thumbLengthPx: Float,
    itemsPerRow: Int,
    visibleItems: Float,
    rowExtentPx: Int,
): QuickScrollBarDragTarget {
    if (totalItems <= 1) return QuickScrollBarDragTarget(index = 0, rowOffsetPx = 0)
    val travel = trackLengthPx - thumbLengthPx
    // 行程为 0（轨道与滑条等长、条目极多）时没有可拖的余量：退回首行
    if (travel <= 0f) return QuickScrollBarDragTarget(index = 0, rowOffsetPx = 0)
    val perRow = itemsPerRowOrOne(itemsPerRow)
    val progress = ((positionPx - thumbLengthPx / 2f) / travel).coerceIn(0f, 1f)
    // 与 [quickScrollBarProgress] 同口径的逆映射（共用同一个分母函数）：进度以「一行」为单位
    // ⇒ 行内位置 = 进度 × 可滚动行数；上界是「滚到底时首个可见的那一行」（末行是兜底，正常够不到）
    val rows = rowCountOf(totalItems, perRow)
    val rowPosition = (progress * scrollableRowCount(totalItems, perRow, visibleItems))
        .coerceIn(0f, (rows - 1).toFloat())
    // 整数部分是行索引（`toInt` 对非负值即下取整）、小数部分是行内偏移的比例
    val row = rowPosition.toInt()
    val extent = rowExtentPx.coerceAtLeast(0)
    val rowOffsetPx = ((rowPosition - row) * extent).roundToInt()
    // 取整到 px 后正好凑满一行时**进位到下一行的行首**：几何给的滑条中部拿回来算时，正好落在行边界上的
    // 浮点误差（形如 0.9999996 行）会在取整后凑成整行——不进位就会给出「行号少一行 + 偏移 = 整行」
    // 这种非规范落点（行号与偏移都不再是它们该有的值）
    return if (extent > 0 && rowOffsetPx >= extent) {
        QuickScrollBarDragTarget(index = ((row + 1) * perRow).coerceIn(0, totalItems - 1), rowOffsetPx = 0)
    } else {
        QuickScrollBarDragTarget(
            index = (row * perRow).coerceIn(0, totalItems - 1),
            rowOffsetPx = rowOffsetPx,
        )
    }
}
