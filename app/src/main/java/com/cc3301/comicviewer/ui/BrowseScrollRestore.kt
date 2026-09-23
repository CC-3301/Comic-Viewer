package com.cc3301.comicviewer.ui

/**
 * 浏览页「从阅读器返回 / 界面重建时把滚动位置放回去」的接缝（票 #124 r2）。
 *
 * 为什么要它（机制，`BrowseScrollRestoreTest` 用 Robolectric 实测钉住）：直取档的会话内列表只含第 0 页，
 * 返回时首帧那份**短列表**先上屏，`Lazy` 列表按它测量一次，恢复的滚动索引那时就被夹到已加载末尾
 * （实测：恢复到 600、首帧 200 条 ⇒ 索引落到 184），此后列表涨长**不会**自己回到原索引。因此
 * 「首屏取够多少条」与「取够后把位置放回去」是同一件事的两半：前者保证有内容可落，后者保证真的落回去。
 *
 * 这里的函数与持有者都不碰 Compose 状态，取值/接线留在 `BrowserScreen`。
 */

/**
 * 「界面这次要恢复到哪一条」的持有者（票 #124 r2）：首屏 effect 每次运行都来读它。
 *
 * 两条规则（各对应一次真实事故面）：
 * - **同一次枚举里只读一次**：`source` 异步解析会让首屏 effect 重跑，第二次读到的索引已被首帧那份短列表
 *   夹过（实测 600 → 184），不能覆盖第一次的值；
 * - **每次重新枚举换一代（[valueFor] 的 `generation`），换代就重读当下索引**：下拉更新（与重试）会换 pager，
 *   若沿用旧索引，在顶部刷新会被拽回上次恢复的位置，且直取档首屏取数从 1 页变成 ⌈旧索引 / 每页⌉ 页
 *   （沿用旧索引就等于推翻票 #58 的「下拉更新仍照旧恢复 / 保持原位」）。
 *
 * 用「代次」而不是让调用方在刷新处手动复位：语义落在本类里，少一个会被漏掉的调用点。
 */
internal class RestoredScrollIndex {
    private var generation: Int = Int.MIN_VALUE
    private var remembered: Int = 0

    /** 本代第一次调用读 [restoredIndex]，同代内后续调用原样返回第一次的值 */
    fun valueFor(generation: Int, restoredIndex: Int): Int {
        if (generation != this.generation) {
            this.generation = generation
            remembered = restoredIndex
        }
        return remembered
    }
}

/**
 * 界面这次要恢复到的那一条的**项索引**（票 #124：`Lazy` 项坐标，与 [scrollRestoreTarget] 的 [loadedItems]
 * 同一套——条目 + 截断提示行 + 尾部触发件行）：两档的 `firstVisibleItemIndex` 都是项索引
 * （`LazyGridState` 给的是首个可见**行的首个格子**，行号 = 项索引 ÷ 列数，见 `core/view/QuickScrollBar.kt`
 * 的行号推导与 `QuickScrollBarTest` 的实测口径），因此这里**不做换算**——列数不参与。
 *
 * @param listIndex 列表档 `LazyListState.firstVisibleItemIndex`
 * @param gridIndex 网格档 `LazyGridState.firstVisibleItemIndex`
 * @param columns 当前视图档位的列数；null = 列表档（[com.cc3301.comicviewer.core.view.ViewMode.columns]）
 */
internal fun restoredScrollItemIndex(listIndex: Int, gridIndex: Int, columns: Int?): Int =
    if (columns == null) listIndex else gridIndex

/**
 * 取够页之后要不要把滚动位置放回去（票 #124 r2）：要放回时返回目标索引，不动时返回 null。
 *
 * 只在「确实有要恢复的位置（[restoredIndex] ≥ 1）」「这一层有这么多项（[restoredIndex] < [loadedItems]）」
 * 「当前位置确实退到了它前面（[currentIndex] < [restoredIndex]，即被短帧夹过）」三条同时成立时才放回——
 * 位置还在（含用户自己滚到恢复索引之后的场景）或这一层没那么长时都不动用户的位置。
 *
 * 三个索引都在**同一套坐标**里：`Lazy` 列表的项坐标（条目 + 截断提示行 + 尾部触发件行），
 * 与 `firstVisibleItemIndex` 同源——[loadedItems] 因此是项数，不是条目数。
 *
 * 有意接受：取数那一小段里用户自己往回滚时也会被放回（这一屏刚重建，位置恢复优先于这一次滚动）。
 */
internal fun scrollRestoreTarget(restoredIndex: Int, currentIndex: Int, loadedItems: Int): Int? =
    if (restoredIndex >= 1 && restoredIndex < loadedItems && currentIndex < restoredIndex) restoredIndex else null
