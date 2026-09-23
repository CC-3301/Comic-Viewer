package com.cc3301.comicviewer.ui

/**
 * 浏览页「从阅读器返回 / 界面重建时把滚动位置放回去」的两个纯函数（票 #124 r2）。
 *
 * 为什么要它们（机制，`BrowseScrollRestoreTest` 用 Robolectric 实测钉住）：直取档的会话内列表只含第 0 页，
 * 返回时首帧那份**短列表**先上屏，`Lazy` 列表按它测量一次，恢复的滚动索引那时就被夹到已加载末尾
 * （实测：恢复到 600、首帧 200 条 ⇒ 索引落到 184），此后列表涨长**不会**自己回到原索引。因此
 * 「首屏取够多少条」与「取够后把位置放回去」是同一件事的两半：前者保证有内容可落，后者保证真的落回去。
 *
 * 两个函数都不碰 Compose 状态，取值/接线留在 `BrowserScreen`。
 */

/**
 * 界面这次要恢复到的那一条的**条目索引**（票 #124）：两档的 `firstVisibleItemIndex` 都是条目索引
 * （`LazyGridState` 给的是首个可见**行的首个格子**，行号 = 条目索引 ÷ 列数，见 `core/view/QuickScrollBar.kt`
 * 的行号推导与 `QuickScrollBarTest` 的实测口径），因此这里**不做换算**——列数不参与。
 *
 * @param listIndex 列表档 `LazyListState.firstVisibleItemIndex`
 * @param gridIndex 网格档 `LazyGridState.firstVisibleItemIndex`
 * @param columns 当前视图档位的列数；null = 列表档（[com.cc3301.comicviewer.core.view.ViewMode.columns]）
 */
internal fun restoredScrollItemIndex(listIndex: Int, gridIndex: Int, columns: Int?): Int =
    if (columns == null) listIndex else gridIndex

/**
 * 取够页之后要不要把滚动位置放回去（票 #124 r2）：要放回时返回目标**条目索引**，不动时返回 null。
 *
 * 只在「确实有要恢复的位置（[restoredIndex] ≥ 1）」「这一层有这么多条（[restoredIndex] < [loadedRows]）」
 * 「当前位置确实退到了它前面（[currentIndex] < [restoredIndex]，即被短帧夹过）」三条同时成立时才放回——
 * 位置还在（含用户自己滚到恢复索引之后的场景）或这一层没那么长时都不动用户的位置。
 *
 * 有意接受：取数那一小段里用户自己往回滚时也会被放回（这一屏刚重建，位置恢复优先于这一次滚动）。
 */
internal fun scrollRestoreTarget(restoredIndex: Int, currentIndex: Int, loadedRows: Int): Int? =
    if (restoredIndex >= 1 && restoredIndex < loadedRows && currentIndex < restoredIndex) restoredIndex else null
