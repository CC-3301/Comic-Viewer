package com.cc3301.comicviewer.ui

/**
 * 浏览页 「从阅读器返回 / 界面重建时把滚动位置放回去」的接缝(票 #124 r2)。
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
 * 两档滚动状态的**初值**（票 #146 ④，方案见票面 2026-09-28 评论）：`min(本代次的位置记录, 首帧项数 - 1)`，下界 0。
 *
 * 为什么放在组合期（而不是沿用「先组在 0、取够页后再 `requestScrollToItem` 跳过去」）：真机读数里
 * `browseRestore phase=read` 那一刻 `now=0` —— 列表**已经组在顶部**，记录里的位置要等 `phase=apply`
 * 才跳过去（两条相差 3–4 帧），那一下「先到顶部再跳」就是维护者看到的闪。给了初值，首帧本来就落在原位。
 *
 * 为什么必须取 `min`（两半各对应一次真实形态）：
 * - **首帧可能短于记录**：直取档的会话内列表只含第 0 页（`docs/spec/browsing.md`「直取档的取数下限」），
 *   记录 600 配 200 项的首帧，原样塞进去就是越界初值；
 * - 记录落在首帧范围内（文件源的会话快照 = 上次上屏的那份整表）时 `min` 不夹任何东西，初值就是记录本身。
 *
 * **它治不了什么**：记录比首帧还长时首帧仍到不了原位（初值被夹到首帧末项），那一段照旧由首屏链的
 * 「取够页后把位置放回去」接手（[scrollRestoreTarget]）；两者是同一件事的两半，不互相替代。
 *
 * 两档（列表 / 网格）共用这一个值，不做换算：两档的 `firstVisibleItemIndex` 都是 `Lazy` 项坐标
 * （网格档是首个可见行的首个格子，见 [restoredScrollItemIndex]）。
 *
 * @param recordedIndex **本代次**的位置记录：**启动那一代 = 盘上那条一次性记录**（[BrowseScrollDiskStore]，由
 * [restoredIndexOnLeaveFor] 在「当前复位键 = 启动那一代」时吃到，盘上没有时退回内存记录）；其余代次 = 内存记录
 *（[BrowseScrollIndexStore.valueFor]；没记过 = 0）
 * @param firstFrameItemCount 首帧那份列表的长度（界面传 `pager.entries.size`，`Lazy` 项坐标）。它是**上界**
 * 而不是精确项数：附加行（截断提示 / 尾部触发件）不参与——初值只需落在首帧范围内，附加行只会把范围放大
 * @return 不小于 0 的项索引：首帧还没有列表（冷启动没落过帧）时是 0
 */
internal fun initialScrollItemIndex(recordedIndex: Int, firstFrameItemCount: Int): Int =
    minOf(recordedIndex, firstFrameItemCount - 1).coerceAtLeast(0)

/**
 * [ancestorContainerId] 是不是 [containerId] 这一层的**上级层**（票 #142 口径 ② 的「返回上一级」判据）。
 *
 * 文档树来源（SMB / 本地）的容器 id 是**路径**形态（`smb://<host>/<share>/<库名>/<目录名>`，父子以 `/` 相接，
 * 见 `DocumentTreeSource.snapshotKeyOf`），因此判的是路径前缀；**根层**（`null`，容器 id 为 null 的那一层）
 * 是任何层的上级。
 *
 * **它只是口径 ② 判据的一半**：服务端 id 形态的来源（容器 id 不是路径）判不出前后关系 ⇒ 一律 false，
 * 那种来源靠 [BrowseScrollIndexStore.noteLayerWritten] 的第二个输入（落地层还在不在浏览链上）判方向。
 *
 * 已知限制（与 id 形态无关、覆盖不掉的两种）：落地层是**根层**（`containerId == null`）时没有上级 ⇒
 * 这一条永不触发（根层落地是受支持的输入，见 `BrowseScrollRestoreTest` 的「落盘记录根层也能往返」用例）；
 * 用户离开浏览区（如去书柜）也会让落地层离开浏览链 ⇒ 登记同样成立（那一种去向不在授权的 ② 之内）。
 */
internal fun isAncestorContainer(ancestorContainerId: String?, containerId: String?): Boolean {
    // 根层没有上级
    if (containerId == null) return false
    // 根层是任何层的上级
    if (ancestorContainerId == null) return true
    // 同层不是上下级（`/` 相接才算父子：`a/b` 不会把 `a/bc` 当子层）
    return containerId.startsWith(ancestorContainerId + "/")
}

/**
 * 「离开这一屏那一刻」这次要用的位置记录（票 #142 b10）：盘上那条启动恢复值**只属于启动那一代**。
 *
 * 为什么必须这么判（P1）：盘上那份一次性记录（[BrowseScrollDiskStore]）**不带代次**，而 `BrowserScreen`
 * 把它记住整个屏期（那边的 `diskRestoredIndex`，评审 spec-r2 P2 的 `remember`）⇒ 换排序换代次、两档滚动状态
 * 按新键重建时，初值与首屏链的取数下限照旧取它，**压过「本代次内存记录 = 0」**：在启动恢复命中的那一层上
 * 换排序（含重选当前排序）不回顶部，违反 `docs/spec/browsing.md`「排序在展示层翻转 / 滚动复位」。
 * 判据因此是「当前复位键是不是启动那一代」：是 ⇒ 吃盘上那条（盘上没有就退回本代次内存记录）；否 ⇒ 只吃
 * 本代次内存记录。「启动那一代」由调用方在**本屏第一次组合**时记下（`BrowserScreen` 的 `startupScrollGeneration`）。
 *
 * 换键之后还会不会再回到启动那一代：不会。`BrowseScrollResetKey` 含 [SortSettingStore.revision]，
 * 每次排序写入 +1 ⇒ A→B→A 也是新键，盘上那条不会在之后再被吃到。
 *
 * @param diskAtStartup 启动落地已定时收下的那条一次性落盘记录（不是落地层时为 null）
 * @param startupGeneration 本屏第一次组合时看到的复位键 = 「启动那一代」
 * @param currentGeneration 这次组合的复位键
 * @param inMemoryIndex 本代次的内存记录（[BrowseScrollIndexStore.valueFor]，没记过 = 0）
 */
internal fun restoredIndexOnLeaveFor(
    diskAtStartup: Int?,
    startupGeneration: BrowseScrollResetKey,
    currentGeneration: BrowseScrollResetKey,
    inMemoryIndex: Int,
): Int =
    if (currentGeneration == startupGeneration) diskAtStartup ?: inMemoryIndex else inMemoryIndex

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
 *   回到顶部（票 #58 的承诺不破）。复位键里已有 [SortSettingStore.revision]（每次排序写入 +1）
 *   ⇒ 排序 A→B→A **不再**回到同一个键；换代时 [BrowseScrollIndexStore.beginGeneration] 仍丢掉该层
 *   **其他代次**的旧记录（那些键不会再被读，留着只是堆内存记录）。
 */
internal data class BrowseScrollRecordKey(
    val connId: Long,
    val containerId: String?,
    val generation: BrowseScrollResetKey,
)

/** 一层（连接 + 容器）：[BrowseScrollIndexStore] 按它给位次记录分组（换代只清该层的旧代次） */
private data class BrowseScrollLayerKey(val connId: Long, val containerId: String?)

/** [BrowseScrollRecordKey] 的层部分（去掉代次）：换代清理与 [BrowseScrollIndexStore.record] 的代次判据按它分组 */
private val BrowseScrollRecordKey.layer: BrowseScrollLayerKey
    get() = BrowseScrollLayerKey(connId, containerId)

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
 * 三条口径：
 * - **按（层，复位代次）记**（见 [BrowseScrollRecordKey]）：不同层、不同代次互不干扰；
 * - **换代丢掉该层其他代次的记录**（[beginGeneration]，票 #142 代次口径收口）：复位键里含
 *   [SortSettingStore.revision]（每次排序写入 +1）⇒ A→B→A 不再回到同一个键；换代丢掉的是该层用不上的
 *   旧代次记录（`docs/spec/browsing.md`「排序在展示层翻转 / 滚动复位」）；
 * - **这一屏读数没动过、也没放过回的那一次离场，且它比记录小** ⇒ 不算数（[record] 的判据，三个合取项）：
 *   真机日志里同一次过渡里会换一份滚动状态，新那份进屏读到 0（实测形态也会读到被短帧夹小的 184），
 *   90 ms 后又 `leave index=0`；那个值不是用户停留的位置，不能覆盖记录。位置**请求放回那一刻**
 *   （[notePlaced]）起窗口关闭——放回之后用户滚到哪就是哪（含再滚回 0 离场）。
 *
 * 本 store 是**进程内**记录（进程重启即空）；跨重启那一份在 [BrowseScrollDiskStore]——「上次停留那一层 + 位置」
 * 单条落盘、**用掉即清**（票 #142 现行口径第 2/3 条）。那份记录用掉后只有「离开落地层、再从上一级进来」
 * 那一种去向才作废该层的位置记录（[resetOnReentryFromParent]）——「用掉即清」因此延伸到那一种离场之后；
 *「从阅读器返回」「进 / 出子目录」照旧保持原位（票面现行口径第 1 条）。
 */
internal object BrowseScrollIndexStore {
    /** 键 → 离场那一刻记下的项索引（[record] 没写过就不在表里，[valueFor] 给 0） */
    private val recorded = mutableMapOf<BrowseScrollRecordKey, Int>()

    /**
     * 键 → 这一屏**进屏那一刻**读到的当下索引（[noteEntered] 写，[record] **收下**离场读数时消费、
     * [notePlaced] 请求放回时清）。
     *
     * 它只回答一个问题：**这一屏自己的读数动过没有**——动过 = 这一屏自己（用户滚动 / 恢复链把位置放回）
     * 定过位置，那次离场读数算数；没动过 = 离场读到的还是进屏那一下的残留（被系统夹小 / 根本没交回），
     * 不能用它把记录改小。
     *
     * **「拒写就不消费基准」这条收口的边界**（评审 spec-r3-b3 P2-1，本批登记、本票不修）：拒写那一支不
     * `entered.remove` ⇒ 基准停在**进屏那一下的旧值**上 ⇒ 同键的**兄弟组合**（真机过渡里出现过两份组合，
     * 见 [notePlaced]）下一次 [noteEntered] 的 `putIfAbsent` 返回非 null ⇒ **既不重立基准、也不清 [placed]**。
     * 于是那份组合只要进屏读到的是**与旧基准不同的值**（同一份残留被夹到别的数、或状态这次真的交回来了），
     * 它离场时就会被判成「这一屏读数动过」而写进记录——一次丢态残留因此可以覆盖记录。触发条件是
     * 「本屏被拒写过 + 同键兄弟组合进屏读出另一个值」，窗口只有一帧宽；它的反面（无条件消费基准）正是
     * r3-b7（`1d45814`）修掉的那个窗口（同屏第二个写点失去基准、必然放行）。两个窗口互斥，关一个就开另一个，
     * 只能由真机时序定（与 [notePlaced] KDoc 里那对边界同一性质）。
     */
    private val entered = mutableMapOf<BrowseScrollRecordKey, Int>()

    /**
     * 键 → 这一屏**有没有请求过把位置放回**（[notePlaced] 写；[noteEntered] 重立基准时清）。
     *
     * 它是拒写窗口的开关：放回请求之前，离场读数可能还是进屏那一下的残留（被夹小 / 没交回）；
     * 请求放回之后这一屏的位置就由用户接管了。
     */
    private val placed = mutableSetOf<BrowseScrollRecordKey>()

    /**
     * 启动那次**真正落地**的那一层（盘侧收口那一刻登记，见 [noteStartupLanding]）：只有它吃
     * 「离开这一层、再从上一级进来 ⇒ 回顶部」那一条（现行口径第 2 条新写法）。其它层一律不受影响。
     */
    private var startupLandingLayer: BrowseScrollLayerKey? = null

    /**
     * 层 → 「用户已经走到这一层的**上级**去了，下一次从上一级进这一层要回顶部」
     * （[noteLayerWritten] 写；[resetOnReentryFromParent] 取用一次即消）。
     */
    private val resetOnReentry = mutableSetOf<BrowseScrollLayerKey>()

    /**
     * 每层**当下**登记的复位代次（[beginGeneration] 写）：换代时据此丢掉该层其他代次的记录，[record] 也据此
     * 拒收「代次已过」的离场读数。按「层」而不是「代次」分组——同一层同时只会有一个当下代次。
     */
    private val currentGenerations = mutableMapOf<BrowseScrollLayerKey, BrowseScrollResetKey>()

    /**
     * 进屏那一刻读到的当下索引（`BrowserScreen` 的 `LaunchedEffect` 里、首屏链跑之前那次读）。
     *
     * **同一份基准只由第一次读立起来**（`putIfAbsent`）：来源异步解析会让这条 effect 重跑，第二次读到的可能已经是
     * 被放回去 / 被夹过的值，拿它当基准就把「进屏那一下」丢了。基准被 [record]（**收下**读数那一刻）/
     * [notePlaced] 消费之后，同一屏里 effect 再重跑会重立一次基准（同时重开拒写窗口，见 [notePlaced] 的边界口径）；
     * **被拒写时不消费**（[record]），同一屏里 effect 再重跑因此不会拿更小的残留读数把基准换掉；
     * 这条收口的边界（同键兄弟组合不再重立基准）见 [entered] 的 KDoc。
     *
     * 基准是「进屏那一下读了什么」，不是「读到 0 没有」：短帧把 600 夹到 **184** 时读数非 0，
     * 它照样不是用户的位置（评审 r1 P1-2）。
     */
    fun noteEntered(key: BrowseScrollRecordKey, readNow: Int) {
        // 基准缺失时重建它、并清 [placed]（拒写窗口重新打开）。**基准缺失 ≠ 这是新的一屏**：
        // [notePlaced] 会一并消费基准，因此同一屏里「请求放回」之后 effect 再重跑，也会走到这里重立基准、
        // 清掉标记 ⇒ 拒写窗口重新打开（早一帧的窗口，见 [notePlaced] 的边界口径）。
        // 不这么做时，同键的兄弟组合会继承上一份组合「已请求放回」的标记。
        if (entered.putIfAbsent(key, readNow) == null) placed.remove(key)
    }

    /**
     * 恢复链把位置**请求**放回那一刻（`BrowseFirstScreenChainPorts.requestScrollTo` 以 `target != null`
     * 请求的那一处，票 #142 r2 b2/2）。本屏从此不再拒写：请求之后这一屏的读数就是用户的。
     *
     * **同时把这一屏的基准一并消费掉**（与 [record] 一样 `entered.remove`）：**标记之后才进屏**的同键兄弟组合
     * （真机过渡里出现过两份组合）下一次 [noteEntered] 的 `putIfAbsent` 因此返回 null ⇒ 它会重立基准、清 `placed`、
     * 重新处于「丢态」口径（评审 r2-b2 P2-1）；不消费基准时，那个标记会被兄弟组合继承。
     *
     * **这道收口是有边界的**（评审 r2-b3 P2-1，两条都已登记、本票不修）：① **标记之前**就已经进屏的同键
     * 兄弟组合不在收口范围内——它的基准还在，会继续把丢态残留写进记录；② 标记之后的同键重进屏会按住
     * 合法的「回到顶部离场」（这一屏不再重建基准时）。两条触发窗口都只有一帧、且互相打架（关 ① 就开 ②），
     * 只能由真机时序定。
     *
     * 时点是**请求**而不是「真落到屏上」：`requestScrollToItem` 非挂起，真正落地在下一帧测量时，
     * 因此本标记比实际落地早一帧（口径如实写在这里，不硬做落地观测）。
     */
    fun notePlaced(key: BrowseScrollRecordKey) {
        placed.add(key)
        entered.remove(key)
    }

    /**
     * 登记「这一层此刻的复位代次」（票 #142 代次口径收口）：**换代时丢掉该层其他代次的记录**。
     *
     * 为什么必须丢：`BrowseScrollResetKey` 现含（类别, 方向, 旧序残留, [SortSettingStore.revision]）——
     * revision 每次排序写入 +1 ⇒ A→B→A **不再**回到同一个键；换代仍要丢掉该层**其他代次**的旧记录：
     * 那些键不会再被读，留着只会堆内存记录（`docs/spec/browsing.md`「排序在展示层翻转 / 滚动复位」
     * 要求排序设置变化即**回顶部**）。调用点见 `BrowserScreen`（组合期同步登记）。
     *
     * **只丢其他代次，不清本代次**：同一代次里离开 / 返回（从阅读器返回、进出子目录）照旧恢复位置。
     * 换代那一刻旧滚动状态也会 dispose 一次、产一个「旧代次离场读数」——[record] 按本表拒收它，因此刚丢掉的
     * 那份不会被立刻写回来。
     *
     * 幂等：同一层同一代次重复调用什么都不做（组合期每次重组都会调）。
     */
    fun beginGeneration(key: BrowseScrollRecordKey) {
        val layer = key.layer
        if (currentGenerations[layer] == key.generation) return
        currentGenerations[layer] = key.generation
        val stale = { other: BrowseScrollRecordKey -> other.layer == layer && other.generation != key.generation }
        recorded.keys.removeAll(stale)
        entered.keys.removeAll(stale)
        placed.removeAll(stale)
    }

    /**
     * 离场那一刻记一次（`BrowserScreen` 的 `onDispose`）。
     *
     * 一条例外（三个合取项）：**这一屏自己的读数没动过**（离场读数 = 进屏那一下读到的值）**且本屏没请求过
     * 放回**（[notePlaced]，时点 = 请求那一刻）**且这次读数比记录小** ⇒ 不收。那正是「被系统弄丢 / 夹小之后读到的那一下」：
     * 真机日志里同一次过渡会换一份滚动状态，新那份进屏读到 0（实测形态也会读到夹小的 184），90 ms 后又
     * `leave index=0`——不是用户停留的位置，不能覆盖记录（票面「系统夹索引不写」）。
     *
     * 反过来**不能一律拒小值**：
     * - 恢复链把位置放回之后这一屏的读数就变了（用户此后滚到哪就是哪）⇒「600 → 开书 → 返回 → 上移到 5 →
     *   再开书 → 返回」落到 5；少了这道门时，5 会被记录里的 600 一直挡掉、丢失整屏（评审 r1 P1-1）；
     * - 用户在丢态那一屏里等到**请求放回**发出、又滚回顶部离场 ⇒ 读数与基准同值（0），但本屏已请求过放回 ⇒ 记 0；
     *   少了 [notePlaced] 这道门时，记录会停在旧的大值（评审 r2-b1 P2-1）。
     *
     * **基准只在【收下】这一次读数时才消费**（评审 spec-r3-b3 P2-1）：拒写那一支不消费，因此同一屏的**第二个**写点
     *（`BrowseScrollDiskStore.recordEffectivePosition` 的两个调用点：`onDispose` 与 `ON_STOP`）读到的还是同一份
     * 丢态残留时照旧被拒——无条件消费时，第二次调用没有基准可比、必然放行，被夹小的读数会落进记录与磁盘。
     */
    fun record(key: BrowseScrollRecordKey, indexAtLeave: Int) {
        // 代次已过（该层当下登记的是别的代次）：这是换代那一刻**旧滚动状态**的 dispose 读数，不是用户在这一代次
        // 停留的位置——不写（少了这道判据，换代那一刻的旧读数会把 [beginGeneration] 刚丢掉的记录原地写回，
        // 下一次读到该键就还是旧位置）。
        // 该层还没登记过代次时不受此判据约束（单测直调 store 的路径）。
        val current = currentGenerations[key.layer]
        if (current != null && current != key.generation) return
        val existing = recorded[key] ?: 0
        // 只读不消费：拒写那一支要留着基准给同屏的下一个写点用（见上面「基准只在收下时消费」那段）。
        val fromEntry = entered[key]
        val screenMoved = fromEntry == null || indexAtLeave != fromEntry
        if (!screenMoved && key !in placed && indexAtLeave < existing) return
        // 收下这一次读数 = 这一屏的位置由它自己定了：基准消费掉，同一屏里 effect 再重跑会重立一次（[noteEntered]）。
        entered.remove(key)
        recorded[key] = indexAtLeave
    }

    /**
     * 「启动那次真正落地的是这一层」的登记（`BrowseScrollDiskStore.consumeAtStartupLanding` 收口那一刻）；
     * **只记这一层**（收口只发生一次、只发生在那条记录指向的落地层上）。
     */
    fun noteStartupLanding(connId: Long, containerId: String?) {
        startupLandingLayer = BrowseScrollLayerKey(connId, containerId)
    }

    /**
     * 「刚写过盘的那一层」（`BrowseScrollDiskStore.record` 的三个写点都会走）把启动落地层甩到**上级那一级**了
     * ⇒ 登记一句「下一次进落地层回顶部」（票面 ② 的「离开这一层、再从上一级进来」那一条）。
     *
     * 为什么不在落地层自己的离屏写点判：离屏写点只知道「这一层走了」、不知道走去哪
     *（进阅读器 / 进子目录 / 回上一级在那一刻形态相同）——按「第一个离屏写点」无差别作废会把「从阅读器返回」
     *「进 / 出子目录」一并牺牲（票面现行口径第 1 条）。
     *
     * 两个判据**同时看**（任一成立即登记）：
     * ① [containerId] 这一层是落地层的上级（[isAncestorContainer]，只对路径形态 id 成立）；
     * ② 落地层**已经不在浏览链上**（`BrowseHistory.path()`，本函数现读），这是与 id 形态无关的那一半：
     *    服务端 id 形态的来源（判不出路径前后缀）就是靠它成立的；「进阅读器」时没有任何浏览层写盘、
     *   「进 / 出子目录」时落地层还在链上，两者都不会走到这里。
     *
     * 这条「落地层还在不在链上」**在本函数内部算**，不由调用方算好传回来：它的输入是本 store 的私有状态
     *（[startupLandingLayer]），交给调用方去问再传回就是让调用方向被调对象索取它自己的派生值
     *（评审 standards-r19-b2 P2）。没有落地层时那条路径恒假——但本函数在上面已经早退，走不到这里。
     *
     * 边界（如实登记）：② 只说明「落地层不在链上了」，不区分「退回上一级」与「离开浏览区（如去书柜）」——
     * 后者也会登记（不在票面授权的 ② 之内）。登记是**闩锁**、由下一次进落地层消费一次，因此这条判据**仍依赖
     *「上级层那一层组合并写盘」这一时序前提**（进屏写点在 `BrowserScreen` 的首屏 effect 里跑——那一次
     * `BrowseScrollDiskStore.record`）——少了那一次写盘就不会登记，也就是「从上一级进来照旧保持位置」
     *（与本票改动前的行为一致，不会回顶部）。
     */
    fun noteLayerWritten(connId: Long, containerId: String?) {
        val landing = startupLandingLayer ?: return
        if (landing.connId != connId) return
        // 落地层**自己**写盘（进屏 / 离屏 / 切后台）不算「离开这一层」：少了这一句，落地层自己的离屏写点
        // 在「浏览链里还没有它」时（镜像未同步 / 镜像已换成别的层）会把自己登记成「走到上一级去了」。
        if (landing.containerId == containerId) return
        val landingOnChain = ServiceLocator.browseHistory.path().any {
            it.connId == landing.connId && it.containerId == landing.containerId
        }
        if (isAncestorContainer(containerId, landing.containerId) || !landingOnChain) resetOnReentry.add(landing)
    }

    /**
     * 这一次**进这一层**是不是「从上一级进来」（[noteLayerWritten] 登记过）：是 ⇒ 作废该层本代次的位置记录
     *（界面随后读到 0 ⇒ 回顶部）。**取用一次即消**：之后再导航照旧保持位置。
     */
    fun resetOnReentryFromParent(connId: Long, containerId: String?) {
        val layer = BrowseScrollLayerKey(connId, containerId)
        if (!resetOnReentry.remove(layer)) return
        recorded.keys.removeAll { it.layer == layer }
    }

    /** 本代次要恢复到哪一条：没记过就是 0（首屏在顶部） */
    fun valueFor(key: BrowseScrollRecordKey): Int = recorded[key] ?: 0

    /** 单测用：对象是进程级单例，用例之间要互不串味 */
    internal fun clearForTest() {
        recorded.clear()
        entered.clear()
        placed.clear()
        currentGenerations.clear()
        startupLandingLayer = null
        resetOnReentry.clear()
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
