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

/**
 * 滚动恢复打点行的前缀（票 #142 取数）：`adb logcat -s ComicViewerPerf | grep browseRestore`，
 * 或设置页「诊断日志」开着时直接导出 .txt。
 *
 * **默认关**：三行都由调用点写在 `PerfTiming.log { ... }` 的 lambda 里（开关关着零开销、不拼字符串）。
 * 行格式的唯一出处是本文件的这三个拼行函数（与 `listEntries` / `coverBytes` 同一套 `key=value` 写法）。
 */
internal const val BROWSE_RESTORE_PREFIX: String = "browseRestore"

/**
 * 「本次该恢复到哪一条」那一行（`phase=read`，票 #142）。
 *
 * **首屏 effect 每跑一次产一行**（它的键含 `pager`，而来源是异步解析的 ⇒ 同一 `gen` 可能出多行）：
 * 所以它记的是「每次都算了什么」，不是「第一次的决定」。
 *
 * 四个字段（读数口径只写在这里，别处不复写）：
 * - `saved` = **离开这一屏那一刻**记下的项索引（`BrowserScreen` 里 `onDispose` 写的 `rememberSaveable`）。
 *   它是 0 就意味着位置在**离场那一刻**就已经没了（与恢复机制无关）；
 * - `now` = 这次 effect 里读到的**当下**索引——此时首帧那份短快照已测量过一次，可能已被夹小；
 * - `sent` = **这次算出、交给链的那个值**（代次 0 时是两者取大，见 [unclippedRestoredScrollIndex]）。
 *   同代重跑时链**可能仍用第一次记下的值**（[RestoredScrollIndex] 的同代只读一次）——
 *   真正当取数下限用的是哪个，看 `phase=apply` 行的 `restored`；
 * - `gen` = 代次（`reloadTick`）：非 0 = 下拉更新 / 重试，按口径不吃 `saved`。
 *
 * `container` 为 null（根层）时写 `<root>`：与 `listEntries` 行同一个写法，两根线才能按同一个键对齐。
 */
internal fun browseRestoreReadLine(container: String?, saved: Int, now: Int, sent: Int, generation: Int): String =
    BROWSE_RESTORE_PREFIX + " phase=read container=" + (container ?: "<root>") +
        " saved=" + saved + " now=" + now + " sent=" + sent + " gen=" + generation

/**
 * 「该不该把位置放回去」那一刻的一行（`phase=apply`，票 #142）。
 *
 * - `gen` = 这次取数的代次（与 `phase=read` 的同一个键，两行靠它配对）；
 * - `restored` = **本代真正当取数下限用的那个值**（[RestoredScrollIndex] 记住的，不一定是 `phase=read` 最后一次的 `sent`）；
 * - `now` = 取数落地后**当下**的首个可见项索引（短帧已把它夹到已加载末尾）；
 * - `loaded` = 这一层的 `Lazy` **项数**（条目 + 截断提示行 + 尾部触发件行）；
 * - `target` = 真正请求回到的项索引；`target=none` = 判据没成立、不动用户位置（判据见 [scrollRestoreTarget]）。
 *
 * **`target=none` 也要产行**（不能省成「没有这行」）：有这行才能把「机制跑到了但决定不放」与
 * 「首屏链根本没跑到这一步（反向档 / 取数失败 / 这一屏没重建）」分辨开。
 */
internal fun browseRestoreApplyLine(
    container: String?,
    generation: Int,
    restored: Int,
    now: Int,
    loaded: Int,
    target: Int?,
): String =
    BROWSE_RESTORE_PREFIX + " phase=apply container=" + (container ?: "<root>") +
        " gen=" + generation + " restored=" + restored + " now=" + now + " loaded=" + loaded +
        " target=" + (target?.toString() ?: "none")

/**
 * 「离开这一屏那一刻记下的值」那一行（`phase=leave`，票 #142 r2）。
 *
 * **它是 `phase=read` 里 `saved` 的来源**（同一个 `rememberSaveable`，写在 `BrowserScreen` 的 `onDispose`）。
 * 只有它能把下面三件事分开：
 * - `leave` 非 0、而返回时读到的 `saved=0` ⇒ 值丢在**保存 / 交回**这一段（saver 没交回来 / 键变了）；
 * - `leave=0` 而当时列表在中段 ⇒ 记录点本身取错了（`currentScrollItemIndex()` 取的不是可见项）；
 * - **整份日志里一条 `leave` 也没有** ⇒ 离场时 `onDispose` 根本没跑，这个值从没被写下。
 *
 * - `index` = 离场那一刻的**项索引**（与另两行同一套 `Lazy` 项坐标，见 [restoredScrollItemIndex]）；
 * - `mode` = 离场那一刻的档位（`list` / `grid`）：两档各有一份滚动状态、索引按档位取，
 *   没有这个字段就分不清这个索引是从哪一份状态里读出来的。
 */
internal fun browseRestoreLeaveLine(container: String?, index: Int, isGrid: Boolean): String =
    BROWSE_RESTORE_PREFIX + " phase=leave container=" + (container ?: "<root>") +
        " index=" + index + " mode=" + (if (isGrid) "grid" else "list")
