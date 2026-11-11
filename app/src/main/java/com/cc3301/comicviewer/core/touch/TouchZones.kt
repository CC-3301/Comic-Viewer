package com.cc3301.comicviewer.core.touch

/**
 * 触摸区域类型 3（spec 硬约束，1.jpg 参考）：屏幕纵向三等分。
 * 纯几何判定 + 区域意图映射 + 条漫页位与条漫/单页翻页目标计算；菜单与跨书行为在 ReaderScreen。
 */
enum class TouchZone { LEFT, CENTER, RIGHT }

/** 触摸区域意图：两模式、两方向统一——左区恒上一页、中区菜单、右区下一页 */
enum class TapIntent { PREV_PAGE, MENU, NEXT_PAGE }

/** 横坐标 → 分区（竖向三等分；非法宽度防御返回 CENTER） */
fun touchZoneAt(x: Float, width: Float): TouchZone = when {
    width <= 0f -> TouchZone.CENTER
    x < width / 3f -> TouchZone.LEFT
    x < width * 2f / 3f -> TouchZone.CENTER
    else -> TouchZone.RIGHT
}

/**
 * 条漫模式左区目标：上一张图起始位置；已到首页即停（跨书跳转）。
 * cur = 当前页位（[webtoonCurrentPage] 的结果）。
 */
fun webtoonPrevTarget(cur: Int, pageCount: Int): Int = (cur - 1).coerceAtLeast(0)

/**
 * 条漫模式右区目标：下一张图起始位置；已到末页即停（跨书跳转）。
 */
fun webtoonNextTarget(cur: Int, pageCount: Int): Int = (cur + 1).coerceIn(0, (pageCount - 1).coerceAtLeast(0))

/**
 * 条漫模式一屏内的一页：页索引 + 本页顶边相对视口顶的纵向偏移（px，页已滚出视口顶时为负）。
 * 由 `LazyListState.visibleItemsInfo` 逐项映射，只收在屏的页。
 */
data class VisiblePage(val index: Int, val offset: Int)

/**
 * 条漫模式当前页位：**视口中线所在的页**；中线压在页与页的交界处时取后一页。
 *
 * 用中线而不是「顶部可见页」：交界处顶部正好是被切半的上一页，按顶部报必差一页。
 * 也不按「已滚到书末」特判末页：中线落在倒数第二页里就报倒数第二页。
 *
 * @param visiblePages 在屏的页，按索引升序（= `LazyListState.visibleItemsInfo` 的顺序）
 * @param viewportCenterPx 视口中线相对视口顶的纵向位置（px）
 * @return 页索引；中线在最后一页之下（页没有铺满视口）时报最靠后的那一页，一页都不在屏时报 0
 */
fun webtoonCurrentPage(visiblePages: List<VisiblePage>, viewportCenterPx: Float): Int =
    visiblePages.lastOrNull { it.offset <= viewportCenterPx }?.index ?: 0

/**
 * 条漫翻页的基准页位：把越界页位钳回页内（尺寸还没量完时页位可能越界），空书返回 null。
 * [webtoonTapTarget] 与 [webtoonVolumeTarget] 共用这一份夹取。
 */
private fun webtoonBasePage(page: Int, pageCount: Int): Int? =
    if (pageCount <= 0) null else page.coerceIn(0, pageCount - 1)

/**
 * 条漫模式音量键目标（spec 故事 39）：**一次按压 = 跳到下一页/上一页的页首**（`animateScrollToItem`
 * 会把目标页顶到视口顶端），不是按视口高度滚一屏——一屏装 2~3 页时后者会一次跳 2~3 页。
 *
 * **基准是页位**：两个方向都从 [webtoonCurrentPage]（全仓唯一的「当前页」口径）出发，
 * 一屏装多页时 ±1 页按中线所在页走，不按顶边索引走。
 *
 * 代价（见 `docs/SPEC.md` 故事 39）：
 * - 当前页尚未显示的部分会被跳过；要逐段细读用触摸区/手指滚动；
 * - 到书首/书末时返回 null：本方向没有可跳的页，阅读页据此就地弹**跨书确认**（与点左/右区同一动作）
 *   并消费按键——旧实现（按视口高滚一屏）在末页还能用音量键逐屏读完长末页，本口径下换成跨书确认；
 *   长末页的剩余部分用触摸区/手指滚动读完。
 *
 * @param page 当前页位（[webtoonCurrentPage] 的结果）
 * @param canScrollForward  列表还能否向前滚：滚到书末后它是 false，用它判定「无下一页可跳」
 * @param canScrollBackward 还能否向后滚：首页页首时为 false
 * @param forward true = 音量下（下一页）、false = 音量上（上一页）
 */
fun webtoonVolumeTarget(
    page: Int,
    pageCount: Int,
    canScrollForward: Boolean,
    canScrollBackward: Boolean,
    forward: Boolean,
): Int? {
    // 基准取页位：页位只有 webtoonCurrentPage 一个拼法
    val base = webtoonBasePage(page, pageCount) ?: return null
    return if (forward) {
        // 下一页必须真的存在（页位已是末页时没有）且本页之下确实还有内容
        if (!canScrollForward || base >= pageCount - 1) null else webtoonNextTarget(base, pageCount)
    } else {
        if (!canScrollBackward) null else webtoonPrevTarget(base, pageCount)
    }
}

/**
 * 条漫模式左/右区（触摸区）目标：**一次点击 = 从当前页位后退/前进一页**（跳到目标页页首）。
 *
 * **基准是页位**（与音量键 [webtoonVolumeTarget] 同一套口径；页位全仓只有 [webtoonCurrentPage] 一个拼法）：
 * 一屏装多页时 ±1 页按中线所在页走，不按顶边索引走——顶边索引在页被切半处与页位差一页。
 *
 * 与音量键的唯一差别：**到端点的判定条件不同**。
 * - 触摸区：本方向上「滚不动了」才算到端点（`!canScrollForward`/`!canScrollBackward`）；末页高过一屏、
 *   还没滚到底时右区照旧把读者带回末页页首（既有触摸语义），返回 null 才交跨书两段式确认；
 * - 音量键：页位已是末页时即便还能滚也返回 null（阅读页据此弹跨书确认），见 [webtoonVolumeTarget] 里那条额外判定。
 *
 * 页位是第 1 页时左区目标 = 钳在 0 的上一页页首 = 「回到当前图起始」。
 *
 * @param page 当前页位（[webtoonCurrentPage] 的结果）
 * @param canScrollForward  列表还能否向前滚；false = 已滚到书末
 * @param canScrollBackward 列表还能否向后滚；false = 已在书首
 * @param forward true = 右区（下一页）、false = 左区（上一页）
 * @return 目标页索引；null = 本方向上无可翻（书首/书末），由调用方走跨书确认
 */
fun webtoonTapTarget(
    page: Int,
    pageCount: Int,
    canScrollForward: Boolean,
    canScrollBackward: Boolean,
    forward: Boolean,
): Int? {
    // 基准取页位：越界页位由 [webtoonBasePage] 钳回页内
    val base = webtoonBasePage(page, pageCount) ?: return null
    return if (forward) {
        if (!canScrollForward) null else webtoonNextTarget(base, pageCount)
    } else {
        if (!canScrollBackward) null else webtoonPrevTarget(base, pageCount)
    }
}

/**
 * 触摸区域 → 意图（spec 故事 26）：条漫与单页、LTR 与 RTL 下语义完全一致。
 * 签名不含模式/方向参数即为契约：点击区不受阅读方向影响。
 */
fun tapIntentAt(x: Float, width: Float): TapIntent = when (touchZoneAt(x, width)) {
    TouchZone.LEFT -> TapIntent.PREV_PAGE
    TouchZone.CENTER -> TapIntent.MENU
    TouchZone.RIGHT -> TapIntent.NEXT_PAGE
}

/** 单页模式左区目标：上一页；已在书首返回 null（交由跨书两段式确认） */
fun pagedPrevTarget(cur: Int, pageCount: Int): Int? =
    if (pageCount <= 0 || cur <= 0) null else cur - 1

/** 单页模式右区目标：下一页；已在书末返回 null（交由跨书两段式确认） */
fun pagedNextTarget(cur: Int, pageCount: Int): Int? =
    if (pageCount <= 0 || cur >= pageCount - 1) null else cur + 1
