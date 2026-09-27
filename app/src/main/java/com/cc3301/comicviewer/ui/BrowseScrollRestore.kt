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
 * 记录键（票 #142）：**一层**（连接 + 容器）在**一个复位代次**里的一条位置记录。
 *
 * 三个字段都参与相等性：
 * - [connId]：根层（[containerId] 为 null）在不同连接上同名，不带它就互相串位；
 * - [containerId]：同一连接下的不同层各记各的；
 * - [generation]：与两档滚动状态的复位键同源（换排序就换代次）⇒ 换排序后进屏读不到旧代次的记录，
 *   回到顶部（票 #58 的承诺不破）。
 */
internal data class BrowseScrollRecordKey(
    val connId: Long,
    val containerId: String?,
    val generation: BrowseScrollResetKey,
)

/**
 * 「离开这一屏那一刻记下的位置」的持有者（票 #142 换机制）：**活在界面之外**，不押 `rememberSaveable`
 * 的交回。
 *
 * 为什么不能只押 saved state：真机诊断日志（`references/` 里那份导出）里，离场那一刻确实记下了
 * `leave index=18`，而返回后读到的是 `saved=0 now=0`——**整屏 saved state 都是 0**（两档滚动状态的
 * `Saver` 与那个 `rememberSaveable` 一起丢）。上四轮的修法逐条枚举「用户主动定位」的输入路径
 * （触摸拖动 / 滑条 / 带内滚轮 / 鼠标拖动带惯性 / 列表本体滚轮）没有收敛（票面 2026-09-27 决定换机制），
 * 因此本轮改成：**只在离场那一刻记一次**（不枚举输入路径），记进这个界面之外的记录里。
 *
 * 两条口径：
 * - **按（层，复位代次）记**（见 [BrowseScrollRecordKey]）：不同层、不同代次互不干扰；
 * - **这一屏读数没动过的那一次离场不算数**（[record] 的判据）：真机日志里同一次过渡里会换一份滚动状态，
 *   新那份进屏读到 0（实测形态也会读到被短帧夹小的 184），90 ms 后又 `leave index=0`；
 *   那个值不是用户停留的位置，不能覆盖记录。
 *
 * 内存记录、不落盘（滚动位置仍属 SPEC Out of Scope），进程重启即空。
 */
internal object BrowseScrollIndexStore {
    /** 键 → 离场那一刻记下的项索引（[record] 没写过就不在表里，[valueFor] 给 0） */
    private val recorded = mutableMapOf<BrowseScrollRecordKey, Int>()

    /**
     * 键 → 这一屏**进屏那一刻**读到的当下索引（[noteEntered] 写，[record] 收下一次离场读数时清）。
     *
     * 它只回答一个问题：**这一屏自己的读数动过没有**——动过 = 这一屏自己（用户滚动 / 恢复链把位置放回）
     * 定过位置，那次离场读数算数；没动过 = 离场读到的还是进屏那一下的残留（被系统夹小 / 根本没交回），
     * 不能用它把记录改小。
     */
    private val entered = mutableMapOf<BrowseScrollRecordKey, Int>()

    /**
     * 进屏那一刻读到的当下索引（`BrowserScreen` 的 `LaunchedEffect` 里、首屏链跑之前那次读）。
     *
     * **每屏只记第一次**（`putIfAbsent`）：来源异步解析会让这条 effect 重跑，第二次读到的可能已经是
     * 被放回去 / 被夹过的值，拿它当基准就把「进屏那一下」丢了。
     *
     * 基准是「进屏那一下读了什么」，不是「读到 0 没有」：短帧把 600 夹到 **184** 时读数非 0，
     * 它照样不是用户的位置（评审 r1 P1-2）。
     */
    fun noteEntered(key: BrowseScrollRecordKey, readNow: Int) {
        entered.putIfAbsent(key, readNow)
    }

    /**
     * 离场那一刻记一次（`BrowserScreen` 的 `onDispose`）。
     *
     * 一条例外：**这一屏自己的读数没动过**（离场读数 = 进屏那一下读到的值）**而它比记录小** ⇒ 不收。
     * 那正是「被系统弄丢 / 夹小之后读到的那一下」：真机日志里同一次过渡会换一份滚动状态，新那份
     * 进屏读到 0（实测形态也会读到夹小的 184），90 ms 后又 `leave index=0`——不是用户停留的位置，
     * 不能覆盖记录（票面「系统夹索引不写」）。
     *
     * 反过来**不能一律拒小值**：恢复链把位置放回之后这一屏的读数就变了（用户此后滚到哪就是哪），
     * 因此「600 → 开书 → 返回 → 上移到 5 → 再开书 → 返回」落到 5；少了这道门时，5 会被记录里的 600
     * 一直挡掉、丢失整屏（评审 r1 P1-1）。
     */
    fun record(key: BrowseScrollRecordKey, indexAtLeave: Int) {
        val existing = recorded[key] ?: 0
        val fromEntry = entered.remove(key)
        val screenMoved = fromEntry == null || indexAtLeave != fromEntry
        if (!screenMoved && indexAtLeave < existing) return
        recorded[key] = indexAtLeave
    }

    /** 本代次要恢复到哪一条：没记过就是 0（首屏在顶部） */
    fun valueFor(key: BrowseScrollRecordKey): Int = recorded[key] ?: 0

    /** 单测用：对象是进程级单例，用例之间要互不串味 */
    internal fun clearForTest() {
        recorded.clear()
        entered.clear()
    }
}

/**
 * 「本次该恢复到哪一条」那一行（`phase=read`，票 #142）。
 *
 * **首屏 effect 每跑一次产一行**（它的键含 `pager`，而来源是异步解析的 ⇒ 同一 `gen` 可能出多行）：
 * 所以它记的是「每次都算了什么」，不是「第一次的决定」。
 *
 * 四个字段（读数口径只写在这里，别处不复写）：
 * - `saved` = **离开这一屏那一刻**记下的项索引（票 #142 换机制后 = [BrowseScrollIndexStore] 里这一层的记录，
 *   不再是 `onDispose` 写的 `rememberSaveable`——那份在真机日志里返回时读到 0）。
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
 * 「离开这一屏那一刻读到的候选值」那一行（`phase=leave`，票 #142 r2）。
 *
 * **它是 `phase=read` 里 `saved` 的候选来源**（票 #142 换机制后 `saved` 读自记录，见下行）。这一行打的是
 * **候选值**：[BrowseScrollIndexStore.record] 收下它，下一次 `read` 的 `saved` 才是这个值；**被拒写时**
 * （判据见 `record`）记录仍是**旧值**，两行这时对不上是正常的。只有它能把下面几件事分开：
 * - `leave` 非 0、而接下来 `phase=read` 的 `saved` 也随之回升 ⇒ 真记进了记录、交得回来（机制在工作）；
 * - `leave` 非 0、而 `saved` 仍是 0 / 旧值 ⇒ **键没命中**（层或代次对不上）或那次离场**被拒写**了；
 * - `leave=0` 而当时列表在中段 ⇒ 记录点本身取错了（`currentScrollItemIndex()` 取的不是可见项）；
 * - **整份日志里一条 `leave` 也没有** ⇒ 离场时 `onDispose` 根本没跑，这个值从没被写下。
 *
 * （r2 判读表里「`leave` 非 0 而 `saved=0` ⇒ 丢在**保存 / 交回**这一段」那条随换机制作废：`saved` 不再来自
 * `rememberSaveable`，交不交回不再是这条线的分辨对象——那个 0 现在是「没写进记录 / 没读到记录」。）
 *
 * **多行是正常的**：这条 effect 的键是两份滚动状态，而排序落地 / 下拉更新会换 `scrollResetKey` ⇒ 换键那次也会
 * dispose、也产一行（那是「重置到顶部」的既定行为，不是离场）。因此判读要按**时间戳**把 `leave` 与它前后的
 * `read` / `apply` 配对；`leave` 行本身只说明「那一刻记了一次值」。
 *
 * - `index` = 离场那一刻的**项索引**（与另两行同一套 `Lazy` 项坐标，见 [restoredScrollItemIndex]）；
 * - `mode` = 离场那一刻的档位（`list` / `grid`）：两档各有一份滚动状态、索引按档位取，
 *   没有这个字段就分不清这个索引是从哪一份状态里读出来的。
 */
internal fun browseRestoreLeaveLine(container: String?, index: Int, isGrid: Boolean): String =
    BROWSE_RESTORE_PREFIX + " phase=leave container=" + (container ?: "<root>") +
        " index=" + index + " mode=" + (if (isGrid) "grid" else "list")
