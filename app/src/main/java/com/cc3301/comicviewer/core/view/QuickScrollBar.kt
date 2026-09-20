package com.cc3301.comicviewer.core.view

import kotlin.math.roundToInt

/**
 * 浏览页快速定位滑条（票 #60）的两条纯函数：滑条几何与「拖动位移 → 目标条目索引」。
 * 由 [QuickScrollBarTest] 锁定；Compose 接线在 `ui/QuickScrollBar.kt`。
 *
 * 背景：1000+ 条目的目录里只能靠反复拖动/滚轮移动，到列表中部与末尾非常慢。滑条提供两件事——
 * ① **看得见位置**：滑条长度与位置反映「当前视口 / 整份列表」的比例；
 * ② **一步到位**：拖到某个比例即定位到对应条目。
 *
 * 口径（两档共用，因此只写一份）：
 * - 进度 = `首条索引 / (总条目数 − 1)`（[quickScrollBarProgress]），0 = 首条、1 = 末条；
 * - 滑条长度 = 轨道长 × 可见条目数 / 总条目数（网格档里「可见格子数 / 总格子数」与「可见行数 / 总行数」同值，
 *   因此同一个公式两档通用），再夹到 `[最短长度, 轨道长]`；
 * - 拖动按**滑条中部跟手**（[quickScrollBarIndexForDrag]：抓滑条中部拖，中部就在手指下），
 *   与几何互为逆映射，松手后的位置与拖动时看到的一致。
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
 * @param visibleItems 当前可见条目数（两档都取 `layoutInfo.visibleItemsInfo.size`；网格档是可见格子数，
 *   与「可见行数」同比例，所以长度比例仍然对）
 * @param firstVisibleItemIndex 当前首个可见条目索引（= 几何要反映的位置）
 * @param trackLengthPx 轨道长度（px）：调用方量到的滑条可用高度
 * @param minThumbLengthPx 滑条最短长度（px）：条目极多时比例算出的长度会小到抓不住
 */
internal fun quickScrollBarGeometry(
    totalItems: Int,
    visibleItems: Int,
    firstVisibleItemIndex: Int,
    trackLengthPx: Float,
    minThumbLengthPx: Float,
): QuickScrollBarGeometry? {
    if (trackLengthPx <= 0f) return null
    if (totalItems <= 1) return null
    if (visibleItems >= totalItems) return null
    val floor = minThumbLengthPx.coerceIn(0f, trackLengthPx)
    val thumbLength = (trackLengthPx * visibleItems / totalItems).coerceIn(floor, trackLengthPx)
    val travel = trackLengthPx - thumbLength
    return QuickScrollBarGeometry(
        thumbLengthPx = thumbLength,
        thumbOffsetPx = travel * quickScrollBarProgress(firstVisibleItemIndex, totalItems),
    )
}

/**
 * 条目索引 → 轨道进度（0 = 首条、1 = 末条）：几何与拖动**共用同一个进度口径**，这就是两者互为逆映射的原因。
 */
internal fun quickScrollBarProgress(index: Int, totalItems: Int): Float {
    if (totalItems <= 1) return 0f
    return (index.toFloat() / (totalItems - 1)).coerceIn(0f, 1f)
}

/**
 * 「拖动位移 → 目标条目索引」：把抓手点沿轨道的位置换算成进度，再取最近的条目。
 *
 * 抓手点按**滑条中部**算（`positionPx − 滑条长 / 2`）：按下滑条中部拖动时，中部就在手指下，
 * 滑条不会先跳半截；与 [quickScrollBarGeometry] 互为逆映射（拿几何算出的滑条中部去拖，正好回到那个索引）。
 *
 * 越界（手指滑出轨道两端、含负值）夹在首条与末条，不产生越界索引。
 *
 * @param positionPx 抓手点沿轨道的位置（px）：Compose 手势里就是指针在该容器内的 y
 * @param totalItems 本份列表的条目数
 * @param trackLengthPx 轨道长度（px）
 * @param thumbLengthPx 当前滑条长度（px）：与几何同一来源
 */
internal fun quickScrollBarIndexForDrag(
    positionPx: Float,
    totalItems: Int,
    trackLengthPx: Float,
    thumbLengthPx: Float,
): Int {
    if (totalItems <= 1) return 0
    val travel = trackLengthPx - thumbLengthPx
    // 行程为 0（轨道与滑条等长、条目极多）时没有可拖的余量：退回首条
    if (travel <= 0f) return 0
    val progress = ((positionPx - thumbLengthPx / 2f) / travel).coerceIn(0f, 1f)
    return (progress * (totalItems - 1)).roundToInt().coerceIn(0, totalItems - 1)
}
