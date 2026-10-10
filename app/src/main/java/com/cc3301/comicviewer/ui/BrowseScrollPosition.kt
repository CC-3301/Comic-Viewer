package com.cc3301.comicviewer.ui

import android.content.Context
import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.cc3301.comicviewer.core.source.PerfTiming
import com.cc3301.comicviewer.ui.session.SessionState
import kotlin.math.roundToInt

/**
 * 浏览页滚动位置（Browse Scroll Position）的**唯一模块**：把「记住滚到哪」这件事收在一处。
 *
 * 为什么要有它（机制，`BrowseScrollRestoreTest` 用 Robolectric 钉住）：直取档的会话内列表只含第 0 页，
 * 返回时首帧那份**短列表**先上屏，`Lazy` 列表按它测量一次，恢复的滚动索引那时就被夹到已加载末尾
 * （恢复到 600、首帧 200 条 ⇒ 索引落到 184），此后列表涨长**不会**自己回到原索引。因此
 * 「首屏取够多少条」与「取够后把位置放回去」是同一件事的两半：前者保证有内容可落，后者保证真的落回去。
 *
 * 模块收进来的五处记忆（此前散在三个文件里）：
 * 1. **进屏要恢复到第几条**（会话内，[recorded]）；
 * 2. **这次离场读数算不算数**（[entered] + [placed]，见 [record]）；
 * 3. **取数落地后要不要把位置放回去**（[scrollRestoreTarget]，经 [BrowseScrollLanding.placementTarget] 问）；
 * 4. **重启落在哪一层**（盘上那一条 + [startupLanding] 的落地层判定）；
 * 5. **落位要吃掉多少顶部内容留白**（[browseTopContentPadding]：容器与落位共用这一处口径）。
 *
 * 一并收进来的还有「什么情况下跳回顶部」那套判断（复位键与代次：[beginGeneration] /
 * [resetOnReentryFromParent]）与两个**由调用方传入**的外部依赖（构造参数）：
 * 「位置存在哪」（[BrowseScrollStorage]）·「现在在哪一层」（[browseChain]）。
 *
 * 界面上只做这几件事（六个入口，`BrowserScreen` / `AppNav` 都只走这六个）：
 * ① [enter] 进屏（组合期）：问「上次停在第几条 / 落位带多少偏移」；
 * ①′ [enterFirstScreen] 进屏（首屏 effect）：交回进屏那一刻的当下索引，换本次取数下限；
 * ② [leave] 离开：记一次（含「现在在哪一层」；真正离屏时后面再跟一步 [endEntrySession]）；
 * ③ [startupLanding] 开机：交回「这次落在哪一层」；
 * ④ [placed] 位置已放回。
 *
 * 其余方法都是**模块内部步骤**（[beginGeneration] / [noteEntered] / [record] …）：一律 `private`
 * ——生产只有那六个入口，单测也走同一批入口（步骤层不设 seam，见 `docs/SPEC.md` Testing Decisions）。
 *
 * 本文件里的函数与持有者都不碰 Compose 状态，取值/接线留在 `BrowserScreen`。
 */

/** 一层：连接 + 容器（容器 id 为 null = 根层）。模块内外**唯一一处**层键，落盘与浏览链都用它 */
internal data class BrowseScrollLayer(val connId: Long, val containerId: String?)

/** 盘上那一条记录：层 + 该层的项索引（不带代次——它只喂启动那一代，见 [BrowseScrollPosition.enter]） */
internal data class BrowseScrollPositionRecord(val layer: BrowseScrollLayer, val index: Int)

/**
 * 「位置存在哪」：跨重启那一份**单条**记录（层 + 项索引），**用掉即清**。
 *
 * 由调用方传入 [BrowseScrollPosition]（模块不自己找存储）：生产接的是 SharedPreferences
 * （[SharedPrefsBrowseScrollStorage]），单测可以换内存实现。
 */
internal interface BrowseScrollStorage {
    fun read(): BrowseScrollPositionRecord?
    fun write(layer: BrowseScrollLayer, index: Int)
    fun clear()
}

/**
 * 生产那份存储：落 SharedPreferences、每次现读不缓存——重启的读要同步完成。
 *
 * 硬约束：这里**不记目录层级**（层级仍由 `StartupStore` 的两份记录负责）、**不进** `BrowseLocation`
 * （它保持只记目录层级）、排序也不进（全局一份在 `SortSettingStore`）。
 */
internal class SharedPrefsBrowseScrollStorage(
    private val context: () -> Context,
) : BrowseScrollStorage {
    private val prefs: android.content.SharedPreferences
        get() = context().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    override fun read(): BrowseScrollPositionRecord? {
        val p = prefs
        if (!p.contains(KEY_CONN)) return null
        val container = p.getString(KEY_CONTAINER, null) ?: return null
        return BrowseScrollPositionRecord(
            layer = BrowseScrollLayer(p.getLong(KEY_CONN, 0L), container.ifEmpty { null }),
            index = p.getInt(KEY_INDEX, 0),
        )
    }

    override fun write(layer: BrowseScrollLayer, index: Int) {
        prefs.edit()
            .putLong(KEY_CONN, layer.connId)
            .putString(KEY_CONTAINER, layer.containerId ?: ROOT_CONTAINER)
            .putInt(KEY_INDEX, index)
            .apply()
    }

    override fun clear() {
        prefs.edit().remove(KEY_CONN).remove(KEY_CONTAINER).remove(KEY_INDEX).apply()
    }

    private companion object {
        /** 根层（容器 id 为 null）的落盘写法：SharedPreferences 存不了 null，空串即根层 */
        const val ROOT_CONTAINER = ""
        const val PREFS_NAME = "browse_scroll"
        const val KEY_CONN = "scroll_conn"
        const val KEY_CONTAINER = "scroll_container"
        const val KEY_INDEX = "scroll_index"
    }
}

/**
 * 「界面这次要恢复到哪一条」的持有者：首屏 effect 每次运行都来读它。
 *
 * 两条规则：
 * - **同一次枚举里只读一次**：`source` 异步解析会让首屏 effect 重跑，第二次读到的索引已被首帧那份短列表
 *   夹过（600 → 184），不能覆盖第一次的值；
 * - **每次重新枚举换一代（[valueFor] 的 `generation`），换代就重读当下索引**：下拉更新（与重试）会换 pager，
 *   若沿用旧索引，在顶部刷新会被拽回上次恢复的位置，且直取档首屏取数从 1 页变成 ⌈旧索引 / 每页⌉ 页
 *   （沿用旧索引就等于推翻「下拉更新仍照旧恢复 / 保持原位」）。
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
 * 界面这次要恢复到的那一条的**项索引**（`Lazy` 项坐标，与 [scrollRestoreTarget] 的 [loadedItems]
 * 同一套——条目 + 截断提示行 + 尾部触发件行）：两档的 `firstVisibleItemIndex` 都是项索引
 * （`LazyGridState` 给的是首个可见**行的首个格子**，行号 = 项索引 ÷ 列数，见 `core/view/QuickScrollBarGeometry.kt`
 * 的行号推导与 `QuickScrollBarTest` 的口径），因此这里**不做换算**——列数不参与。
 *
 * @param listIndex 列表档 `LazyListState.firstVisibleItemIndex`
 * @param gridIndex 网格档 `LazyGridState.firstVisibleItemIndex`
 * @param columns 当前视图档位的列数；null = 列表档（[com.cc3301.comicviewer.core.view.ViewMode.columns]）
 */
internal fun restoredScrollItemIndex(listIndex: Int, gridIndex: Int, columns: Int?): Int =
    if (columns == null) listIndex else gridIndex

/**
 * 两档滚动状态的**初值**（方案见 2026-09-28 评论）：`min(本代次的位置记录, 首帧项数 - 1)`，下界 0。
 *
 * 为什么放在组合期（而不是沿用「先组在 0、取够页后再 `requestScrollToItem` 跳过去」）：设备读数里
 * `browseRestore phase=read` 那一刻 `now=0` —— 列表**已经组在顶部**，记录里的位置要等 `phase=apply`
 * 才跳过去（两条相差 3–4 帧），那一下「先到顶部再跳」就是观察到的闪。给了初值，首帧本来就落在原位。
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
 * @param recordedIndex **本代次**的位置记录：**启动那一代 = 盘上那条一次性记录**（[BrowseScrollPosition]，由
 * [restoredIndexOnLeaveFor] 在「当前复位键 = 启动那一代」时吃到，盘上没有时退回内存记录）；其余代次 = 内存记录
 *（[BrowseScrollPosition.valueFor]；没记过 = 0）
 * @param firstFrameItemCount 首帧那份列表的长度（界面传 `pager.entries.size`，`Lazy` 项坐标）。它是**上界**
 * 而不是精确项数：附加行（截断提示 / 尾部触发件）不参与——初值只需落在首帧范围内，附加行只会把范围放大
 * @return 不小于 0 的项索引：首帧还没有列表（冷启动没落过帧）时是 0
 */
internal fun initialScrollItemIndex(recordedIndex: Int, firstFrameItemCount: Int): Int =
    minOf(recordedIndex, firstFrameItemCount - 1).coerceAtLeast(0)

/**
 * 恢复到某一项时滚动状态该带的**纵向偏移**：把列表**顶部那段内容留白**吃掉，被恢复那一项的
 * **封面顶边**因此贴视口上沿，上方不露出上一行（名字 / 封面）的任何像素。
 *
 * 为什么落位要自己吃掉它（Robolectric 真尺寸，`BrowseScrollRestoreTest` 钉住）：`Lazy` 把内容留白算在
 * **视口之外**——顶部留白 12dp 时 `viewportStartOffset = -12dp`，而按「项索引 + 偏移 0」落地的那一项上沿
 * 正好落在**留白之下**（`firstVisibleItemIndex=26`、偏移 0 ⇒ 该项顶边在视口上沿之下 12dp，
 * 上一行还在可见区里）。设备量到的 12.3dp 偏移就是它（`docs/spec/browsing.md`「重启恢复位置」）。
 * **留白本身不改**（12dp 是排版口径，格子槽高与格内几何都由它定）——改的只是**落位**。
 *
 * 两条边界：
 * - **列表档没有顶部内容留白**（`LazyColumn` 不设 `contentPadding`）⇒ 该档传 [topContentPaddingPx] = 0，
 *   偏移恒为 0（列表档本来就落在偏移 0，本函数对它是个恒等）；
 * - **落在第 0 项**（[initialScrollItemIndex] 给 0 的三条路：记录本来就是 0、首帧项数 ≤ 1（只含一个子目录那样
 *   的短帧，记录 26 也会被夹成 0）、首帧还没有列表）不吃留白：
 *   那 12dp 正是「停在顶部」的排版边距，吃掉它等于把整屏内容上移 12dp。
 *
 * 记录仍是**项索引**（不引入像素级偏移记录）：项内的滚动量照旧丢弃，这里补的只是那一段**内容留白**。
 *
 * @param restoreIndex 这次要落到的那一项（[initialScrollItemIndex] 的初值，或 [scrollRestoreTarget] 给的目标）
 * @param topContentPaddingPx 该档列表顶部的**内容留白**（网格档 = `GRID_CONTENT_PADDING_VERTICAL` 的真 px 值）
 * @return 不小于 0 的纵向偏移 px
 */
internal fun restoredLandingOffsetPx(restoreIndex: Int, topContentPaddingPx: Int): Int =
    if (restoreIndex >= 1) topContentPaddingPx else 0

/**
 * 「离开这一屏那一刻」这次要用的位置记录：盘上那条启动恢复值**只属于启动那一代**。
 *
 * 为什么必须这么判：盘上那份一次性记录（[BrowseScrollPosition]）**不带代次**，而模块把它记住整个屏期
 * （[EntrySession] 的 `startupIndex`）⇒ 换排序换代次、两档滚动状态
 * 按新键重建时，初值与首屏链的取数下限照旧取它，**压过「本代次内存记录 = 0」**：在启动恢复命中的那一层上
 * 换排序（含重选当前排序）不回顶部，违反 `docs/spec/browsing.md`「排序在展示层翻转 / 滚动复位」。
 * 判据因此是「当前复位键是不是启动那一代」：是 ⇒ 吃盘上那条（盘上没有就退回本代次内存记录）；否 ⇒ 只吃
 * 本代次内存记录。「启动那一代」由调用方在**本屏第一次组合**时记下（[EntrySession] 的 `startupGeneration`）。
 *
 * 换键之后还会不会再回到启动那一代：不会。`BrowseScrollResetKey` 含 [SortSettingStore.revision]，
 * 每次排序写入 +1 ⇒ A→B→A 也是新键，盘上那条不会在之后再被吃到。
 *
 * @param diskAtStartup 启动落地已定时收下的那条一次性落盘记录（不是落地层时为 null）
 * @param startupGeneration 本屏第一次组合时看到的复位键 = 「启动那一代」
 * @param currentGeneration 这次组合的复位键
 * @param inMemoryIndex 本代次的内存记录（[BrowseScrollPosition.valueFor]，没记过 = 0）
 */
internal fun restoredIndexOnLeaveFor(
    diskAtStartup: Int?,
    startupGeneration: BrowseScrollResetKey,
    currentGeneration: BrowseScrollResetKey,
    inMemoryIndex: Int,
): Int =
    if (currentGeneration == startupGeneration) diskAtStartup ?: inMemoryIndex else inMemoryIndex

/**
 * 取够页之后要不要把滚动位置放回去：要放回时返回目标索引，不动时返回 null。
 *
 * 只在「确实有要恢复的位置（[restoredIndex] ≥ 1）」「这一层有这么多项（[restoredIndex] < [loadedItems]）」
 * 「当前位置确实退到了它前面（[currentIndex] < [restoredIndex]，即被短帧夹过）」三条同时成立时才放回——
 * 位置还在（含用户自己滚到恢复索引之后的场景）或这一层没那么长时都不动用户的位置。
 *
 * 三个索引都在**同一套坐标**里：`Lazy` 列表的项坐标（条目 + 截断提示行 + 尾部触发件行），
 * 与 `firstVisibleItemIndex` 同源——[loadedItems] 因此是项数，不是条目数。
 *
 * 代价：取数那一小段里用户自己往回滚时也会被放回（这一屏刚重建，位置恢复优先于这一次滚动）。
 */
internal fun scrollRestoreTarget(restoredIndex: Int, currentIndex: Int, loadedItems: Int): Int? =
    if (restoredIndex >= 1 && restoredIndex < loadedItems && currentIndex < restoredIndex) restoredIndex else null

/**
 * 滚动恢复打点行的前缀：`adb logcat -s ComicViewerPerf | grep browseRestore`，
 * 或设置页「诊断日志」开着时直接导出 .txt。
 *
 * **默认关**：三行都由调用点写在 `PerfTiming.log { ... }` 的 lambda 里（开关关着零开销、不拼字符串）。
 * 行格式的唯一出处是本文件的这三个拼行函数（与 `listEntries` / `coverBytes` 同一套 `key=value` 写法）。
 */
internal const val BROWSE_RESTORE_PREFIX: String = "browseRestore"

/**
 * 记录键：**一层**（连接 + 容器）在**一个复位代次**里的一条位置记录。
 *
 * 三个字段都参与相等性：
 * - [connId]：根层（[containerId] 为 null）在不同连接上同名，不带它就互相串位；
 * - [containerId]：同一连接下的不同层各记各的；
 * - [generation]：与两档滚动状态的复位键同源（换排序就换代次）⇒ 换排序后进屏读不到旧代次的记录，
 *   回到顶部（「下拉更新仍照旧恢复 / 保持原位」的承诺不破）。复位键里已有 [SortSettingStore.revision]（每次排序写入 +1）
 *   ⇒ 排序 A→B→A **不再**回到同一个键；换代时 [BrowseScrollPosition.beginGeneration] 仍丢掉该层
 *   **其他代次**的旧记录（那些键不会再被读，留着只是堆内存记录）。
 */
internal data class BrowseScrollRecordKey(
    val connId: Long,
    val containerId: String?,
    val generation: BrowseScrollResetKey,
)

/** [BrowseScrollRecordKey] 的层部分（去掉代次）：换代清理、代次判据、落地层判定都按它分组 */
internal val BrowseScrollRecordKey.layer: BrowseScrollLayer
    get() = BrowseScrollLayer(connId, containerId)

/**
 * 「离开这一屏那一刻记下的位置」的持有者：**活在界面之外**，不押 `rememberSaveable` 的交回。
 *
 * 为什么不能只押 saved state：诊断日志（`references/` 里那份导出）里，离场那一刻确实记下了
 * `leave index=18`，而返回后读到的是 `saved=0 now=0`——**整屏 saved state 都是 0**（两档滚动状态的
 * `Saver` 与那个 `rememberSaveable` 一起丢）。逐条枚举「用户主动定位」的输入路径
 * （触摸拖动 / 滑条 / 带内滚轮 / 鼠标拖动带惯性 / 列表本体滚轮）的修法没有收敛（2026-09-27 决定换机制），
 * 因此改成：**只在离场那一刻记一次**（不枚举输入路径），记进这个界面之外的记录里。
 *
 * 三条口径：
 * - **按（层，复位代次）记**（见 [BrowseScrollRecordKey]）：不同层、不同代次互不干扰；
 * - **换代丢掉该层其他代次的记录**（[beginGeneration]）：复位键里含
 *   [SortSettingStore.revision]（每次排序写入 +1）⇒ A→B→A 不再回到同一个键；换代丢掉的是该层用不上的
 *   旧代次记录（`docs/spec/browsing.md`「排序在展示层翻转 / 滚动复位」）；
 * - **这一屏读数没动过、也没放过回的那一次离场，且它比记录小** ⇒ 不算数（[record] 的判据，三个合取项）：
 *   日志里同一次过渡里会换一份滚动状态，新那份进屏读到 0（也会读到被短帧夹小的 184），
 *   90 ms 后又 `leave index=0`；那个值不是用户停留的位置，不能覆盖记录。位置**请求放回那一刻**
 *   （[notePlaced]）起窗口关闭——放回之后用户滚到哪就是哪（含再滚回 0 离场）。
 *
 * 本 store 是**进程内**记录（进程重启即空）；跨重启那一份在 [BrowseScrollPosition]——「上次停留那一层 + 位置」
 * 单条落盘、**用掉即清**。那份记录用掉后只有「离开落地层、再从上一级进来」
 * 那一种去向才作废该层的位置记录（[resetOnReentryFromParent]）——「用掉即清」因此延伸到那一种离场之后；
 *「进 / 出子目录」照旧保持原位。
 * @param store 「位置存在哪」：跨重启那一份单条记录由调用方传入（生产 = [SharedPrefsBrowseScrollStorage]）
 * @param browseChain 「现在在哪一层」：浏览链（回退栈里浏览层的镜像），最后一项 = 链顶，由调用方传入
 */
internal class BrowseScrollPosition(
    private val store: BrowseScrollStorage,
    private val browseChain: () -> List<BrowseScrollLayer>,
) {
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
     * **「拒写就不消费基准」这条收口的边界**（已登记，不修）：拒写那一支不
     * `entered.remove` ⇒ 基准停在**进屏那一下的旧值**上 ⇒ 同键的**兄弟组合**（过渡里出现过两份组合，
     * 见 [notePlaced]）下一次 [noteEntered] 的 `putIfAbsent` 返回非 null ⇒ **既不重立基准、也不清 [placed]**。
     * 于是那份组合只要进屏读到的是**与旧基准不同的值**（同一份残留被夹到别的数、或状态这次真的交回来了），
     * 它离场时就会被判成「这一屏读数动过」而写进记录——一次丢态残留因此可以覆盖记录。触发条件是
     * 「本屏被拒写过 + 同键兄弟组合进屏读出另一个值」，窗口只有一帧宽；它的反面（无条件消费基准）正是
     * 之前修掉的那个窗口（同屏第二个写点失去基准、必然放行）。两个窗口互斥，关一个就开另一个，
     * 只能由设备时序定（与 [notePlaced] KDoc 里那对边界同一性质）。
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
    private var startupLandingLayer: BrowseScrollLayer? = null

    /**
     * 层 → 「用户已经走到这一层的**上级**去了（那一层的写盘以**链顶**身份落下），下一次从上一级进这一层
     * 要回顶部」（[noteLayerWritten] 写；[resetOnReentryFromParent] 取用一次即消）。
     */
    private val resetOnReentry = mutableSetOf<BrowseScrollLayer>()

    /**
     * 每层**当下**登记的复位代次（[beginGeneration] 写）：换代时据此丢掉该层其他代次的记录，[record] 也据此
     * 拒收「代次已过」的离场读数。按「层」而不是「代次」分组——同一层同时只会有一个当下代次。
     */
    private val currentGenerations = mutableMapOf<BrowseScrollLayer, BrowseScrollResetKey>()

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
     * 它照样不是用户的位置。
     */
    private fun noteEntered(key: BrowseScrollRecordKey, readNow: Int) {
        // 基准缺失时重建它、并清 [placed]（拒写窗口重新打开）。**基准缺失 ≠ 这是新的一屏**：
        // [notePlaced] 会一并消费基准，因此同一屏里「请求放回」之后 effect 再重跑，也会走到这里重立基准、
        // 清掉标记 ⇒ 拒写窗口重新打开（早一帧的窗口，见 [notePlaced] 的边界口径）。
        // 不这么做时，同键的兄弟组合会继承上一份组合「已请求放回」的标记。
        if (entered.putIfAbsent(key, readNow) == null) placed.remove(key)
    }

    /**
     * 恢复链把位置**请求**放回那一刻（`BrowseFirstScreenChainPorts.requestScrollTo` 以 `target != null`
     * 请求的那一处）。本屏从此不再拒写：请求之后这一屏的读数就是用户的。
     *
     * **同时把这一屏的基准一并消费掉**（与 [record] 一样 `entered.remove`）：**标记之后才进屏**的同键兄弟组合
     * （过渡里出现过两份组合）下一次 [noteEntered] 的 `putIfAbsent` 因此返回 null ⇒ 它会重立基准、清 `placed`、
     * 重新处于「丢态」口径；不消费基准时，那个标记会被兄弟组合继承。
     *
     * **这道收口是有边界的**（两条都已登记、不修）：① **标记之前**就已经进屏的同键
     * 兄弟组合不在收口范围内——它的基准还在，会继续把丢态残留写进记录；② 标记之后的同键重进屏会按住
     * 合法的「回到顶部离场」（这一屏不再重建基准时）。两条触发窗口都只有一帧、且互相打架（关 ① 就开 ②），
     * 只能由设备时序定。
     *
     * 时点是**请求**而不是「真落到屏上」：`requestScrollToItem` 非挂起，真正落地在下一帧测量时，
     * 因此本标记比实际落地早一帧（口径如实写在这里，不硬做落地观测）。
     */
    private fun notePlaced(key: BrowseScrollRecordKey) {
        placed.add(key)
        entered.remove(key)
    }

    /**
     * 登记「这一层此刻的复位代次」：**换代时丢掉该层其他代次的记录**。
     *
     * 为什么必须丢：`BrowseScrollResetKey` 现含（类别, 方向, 旧序残留, [SortSettingStore.revision]）——
     * revision 每次排序写入 +1 ⇒ A→B→A **不再**回到同一个键；换代仍要丢掉该层**其他代次**的旧记录：
     * 那些键不会再被读，留着只会堆内存记录（`docs/spec/browsing.md`「排序在展示层翻转 / 滚动复位」
     * 要求排序设置变化即**回顶部**）。调用点见 `BrowserScreen`（组合期同步登记）。
     *
     * **只丢其他代次，不清本代次**：同一代次里离开 / 返回（进出子目录、返回上级）照旧恢复位置。
     * 换代那一刻旧滚动状态也会 dispose 一次、产一个「旧代次离场读数」——[record] 按本表拒收它，因此刚丢掉的
     * 那份不会被立刻写回来。
     *
     * 幂等：同一层同一代次重复调用什么都不做（组合期每次重组都会调）。
     */
    private fun beginGeneration(key: BrowseScrollRecordKey) {
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
     * 日志里同一次过渡会换一份滚动状态，新那份进屏读到 0（也会读到夹小的 184），90 ms 后又
     * `leave index=0`——不是用户停留的位置，不能覆盖记录。
     *
     * 反过来**不能一律拒小值**：
     * - 恢复链把位置放回之后这一屏的读数就变了（用户此后滚到哪就是哪）⇒「600 → 开书 → 返回 → 上移到 5 →
     *   再开书 → 返回」落到 5；少了这道门时，5 会被记录里的 600 一直挡掉、丢失整屏；
     * - 用户在丢态那一屏里等到**请求放回**发出、又滚回顶部离场 ⇒ 读数与基准同值（0），但本屏已请求过放回 ⇒ 记 0；
     *   少了 [notePlaced] 这道门时，记录会停在旧的大值。
     *
     * **基准只在【收下】这一次读数时才消费**：拒写那一支不消费，因此同一屏的**第二个**写点
     *（`BrowseScrollPosition.recordEffectivePosition` 的两个调用点：`onDispose` 与 `ON_STOP`）读到的还是同一份
     * 丢态残留时照旧被拒——无条件消费时，第二次调用没有基准可比、必然放行，被夹小的读数会落进记录与磁盘。
     */
    private fun record(key: BrowseScrollRecordKey, indexAtLeave: Int) {
        // 代次已过（该层当下登记的是别的代次）：这是换代那一刻**旧滚动状态**的 dispose 读数，不是用户在这一代次
        // 停留的位置——不写（少了这道判据，换代那一刻的旧读数会把 [beginGeneration] 刚丢掉的记录原地写回，
        // 下一次读到该键就还是旧位置）。
        // 该层还没登记过代次时不受此判据约束（该层还没走过进屏那一问的路径）。
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
     * 「启动那次真正落地的是这一层」的登记（`BrowseScrollPosition.consumeAtStartupLanding` 收口那一刻）；
     * **只记这一层**（收口只发生一次、只发生在那条记录指向的落地层上）。
     */
    private fun noteStartupLanding(connId: Long, containerId: String?) {
        startupLandingLayer = BrowseScrollLayer(connId, containerId)
    }

    /**
     * 「刚写过盘的那一层」（`BrowseScrollPosition.record` 的三个写点都会走）把启动落地层甩到**上级那一级**了
     * ⇒ 登记一句「下一次进落地层回顶部」（「离开这一层、再从上一级进来」那一条）。
     *
     * 为什么不在落地层自己的离屏写点判：离屏写点只知道「这一层走了」、不知道走去哪
     *（进阅读器 / 进子目录 / 回上一级在那一刻形态相同）——按「第一个离屏写点」无差别作废会把「从阅读器返回」
     *「进 / 出子目录」一并牺牲。
     *
     * 两个判据**同时成立才登记**（任一单独都不成立），且两个都只看「连接 + 容器」、与容器 id 形态无关
     *（文档树来源的路径形态 id 与服务端 id 形态在这里一视同仁）：
     * ① **写盘的那一层就是此刻的浏览链顶**（`BrowseHistory.path()` 的最后一层 = 用户此刻所处的那一层）；
     * ② 落地层**已经不在浏览链上**。
     *
     * 为什么必须有 ①（它之前那版判据是「写盘的那一层是落地层的**上级**」，靠路径前缀，只对文档树来源成立）：
     * 设备上过渡期的写点是**乱序**的——用户从上一级 P **进入**落地层 A 时，P 的离屏写点在 A 已经上屏之后才落。
     * 只看「P 是 A 的上级」会把这一步读成「用户从 A 退回 P」⇒ 闩锁被错误置上 ⇒ 下一次「出子目录回本层」
     * 被作废、回顶部（违反 `docs/spec/browsing.md`「滚动复位」）。
     * 补上「写盘的那一层必须是链顶」就挡住了它：那一刻链顶是 A、写盘的是 P。真正的「退回上一级」时，
     * 上一级那一层的写点是在**它自己成为链顶之后**才落的（链上那条已经把落地层弹掉）⇒ ① 成立、② 也成立。
     *
     * 这两条输入**都在本函数内部算**，不由调用方算好传回来：它们的输入是本 store 的私有状态
     * （[startupLandingLayer]）与浏览链，交给调用方去问再传回就是让调用方向被调对象索取它自己的派生值。
     * 没有落地层时恒不登记——本函数在上面已经早退，走不到这里。
     *
     * 边界（如实登记）：两条判据都**认不出去向**，只认「链顶那一层写过盘」——因此登记**仍依赖
     * 「上一级那一层组合并写盘」这一时序前提**（进屏写点在 `BrowserScreen` 的首屏 effect 里跑——
     * 那一次 `BrowseScrollPosition.record`）：用户离开落地层后没在上一级停住、直接去了别处（如书柜）时，
     * 没有哪一层以链顶身份写盘 ⇒ 不登记，照旧「保持原位置」。两个覆盖不到的情形：落地层是**根层**
     * （`containerId == null`）时这一条永不触发（根层之上那一级不是浏览层——书柜 / 设置等入口，没有浏览层写盘；根层落地是受支持的输入，见
     * `BrowseScrollPositionTest` 的「落盘记录根层也能往返」用例）；以及上面那一条时序前提本身缺失时。
     */
    private fun noteLayerWritten(connId: Long, containerId: String?) {
        val landing = startupLandingLayer ?: return
        if (landing.connId != connId) return
        // 落地层**自己**写盘（进屏 / 离屏 / 切后台）不算「离开这一层」：少了这一句，落地层自己的离屏写点
        // 在「浏览链里还没有它」时（镜像未同步 / 镜像已换成别的层）会把自己登记成「走到上一级去了」。
        if (landing.containerId == containerId) return
        val path = browseChain()
        // 判据 ①：写盘的这一层就是此刻的浏览链顶（乱序写点里只有「用户已经站在这一层上」的那一次算数）
        val top = path.lastOrNull()
        if (top == null || top.connId != connId || top.containerId != containerId) return
        // 判据 ②：落地层已经不在浏览链上（进阅读器时写盘的是落地层自己，已被上面那句早退挡住；
        // 进子目录时 ① 成立、而落地层仍在链上 ⇒ 由这一条拦住 ⇒ 不登记）
        if (path.any { it.connId == landing.connId && it.containerId == landing.containerId }) return
        resetOnReentry.add(landing)
    }

    /**
     * 这一次**进这一层**是不是「从上一级进来」（[noteLayerWritten] 登记过）：是 ⇒ 作废该层本代次的位置记录
     *（界面随后读到 0 ⇒ 回顶部）。**取用一次即消**：之后再导航照旧保持位置。
     */
    private fun resetOnReentryFromParent(connId: Long, containerId: String?) {
        val layer = BrowseScrollLayer(connId, containerId)
        if (!resetOnReentry.remove(layer)) return
        recorded.keys.removeAll { it.layer == layer }
    }

    /** 本代次要恢复到哪一条：没记过就是 0（首屏在顶部） */
    private fun valueFor(key: BrowseScrollRecordKey): Int = recorded[key] ?: 0

    /**
     * 一层**这一屏**的进屏会话（[enter] 第一次问时建立、[endEntrySession] 时作废）：启动那一代 + 它吃到的盘上那条 +
     * 本次取数下限的持有者（同代只读一次）。
     *
     * 它必须与**这一屏**同寿命（界面那边原来是两个 `remember(connId, containerId)`）：离开这一层再进来是
     * **新的一屏**，要重新问一次（「离开落地层、再从上一级进来 ⇒ 回顶部」靠的就是这一下），
     * 而取数下限的持有者也不能跨屏复用（跨屏复用会让新一代的第一读返回上一屏记住的值）。
     * 因此**切后台不结束它**（那一屏没有销毁，回前台照旧是同一屏），见 [endEntrySession]。
     */
    private class EntrySession(
        val startupGeneration: BrowseScrollResetKey,
        /** 启动落地已定时收下的那条一次性落盘记录（不是落地层时为 null，见 [consumeAtStartupLanding]） */
        val startupIndex: Int?,
    ) {
        val holders = mutableMapOf<BrowseScrollResetKey, RestoredScrollIndex>()
    }

    /** 层 → 该层**当前那一屏**的进屏会话 */
    private val sessions = mutableMapOf<BrowseScrollLayer, EntrySession>()

    // ---------------- 对外入口（生产只走这六个；① 进屏分两次问，② 离开分两步走） ----------------

    /**
     * 入口 ① · **进屏（组合期）**：只要初值——两档滚动状态按 `landing.index` + `landing.offsetPx(档)` 构造，
     * 首帧即在原位。
     *
     * @param firstFrameItemCount 首帧那份列表的长度（`Lazy` 项坐标的上界，见 [initialScrollItemIndex]）
     */
    fun enter(
        layer: BrowseScrollLayer,
        resetKey: BrowseScrollResetKey,
        firstFrameItemCount: Int,
    ): BrowseScrollLanding {
        val prepared = prepareEntry(layer, resetKey, firstFrameItemCount)
        // 组合期那次只给初值：`restoredIndexNow` 为 0，本次取数下限由入口 ①′ 给出
        return BrowseScrollLanding(
            index = prepared.index,
            restoredIndexHolder = prepared.holder,
            restoredIndexNow = 0,
        )
    }

    /**
     * 入口 ①′ · **进屏（首屏 effect）**：交回进屏这一刻两档读到的当下项索引，换**本次取数下限**。
     *
     * 三件事：登记进屏基准（[noteEntered]）、算出本次取数要恢复到第几条
     *（[BrowseScrollLanding.restoredIndexHolder] / `restoredIndexNow`）、并把「此刻所处的那一层 + 位置」
     * 写进那份一次性落盘记录——任务被划掉 / 进程被杀这类**没有离场回调**的退出也要能恢复
     *（这一次写盘同时是「哪一层刚写过盘」的登记口，见 [noteLayerWritten]）。
     *
     * @param firstFrameItemCount 首帧那份列表的长度（`Lazy` 项坐标的上界，见 [initialScrollItemIndex]）
     * @param readNow 进屏这一刻两档滚动状态读到的**当下**项索引（[restoredScrollItemIndex] 给的）
     * @param reloadTick 本次取数的代次（下拉更新 / 重试）：非 0 时不吃「离开时那个值」
     */
    fun enterFirstScreen(
        layer: BrowseScrollLayer,
        resetKey: BrowseScrollResetKey,
        firstFrameItemCount: Int,
        readNow: Int,
        reloadTick: Int,
    ): BrowseScrollLanding {
        val prepared = prepareEntry(layer, resetKey, firstFrameItemCount)
        // 进屏这一读交给记录当**进屏基准**（判据见 [noteEntered]）
        noteEntered(prepared.key, readNow)
        val restoredIndexNow = if (reloadTick == 0) {
            unclippedRestoredScrollIndex(prepared.restoredOnLeave, readNow)
        } else {
            readNow
        }
        record(layer.connId, layer.containerId, prepared.restoredOnLeave)
        PerfTiming.log {
            browseRestoreReadLine(layer.containerId, prepared.restoredOnLeave, readNow, restoredIndexNow, reloadTick)
        }
        return BrowseScrollLanding(
            index = prepared.index,
            restoredIndexHolder = prepared.holder,
            restoredIndexNow = prepared.holder.valueFor(reloadTick, restoredIndexNow),
        )
    }

    /**
     * 两个进屏入口的**共用前半段**：换代登记（[beginGeneration]）→ 取本屏的进屏会话 → 算「离开那一刻」的记录
     * 与本次初值。两处走同一段，组合期那一问与首屏 effect 那一问看到的才是同一份答案。
     *
     * 盘上那条启动恢复值**只属于启动那一代**（本屏第一次看到的复位键）：复位键换代（换排序，含重选当前排序）
     * 之后不得再吃它，否则按新键重建的滚动状态会落回盘上那个位置（换排序不回顶部）——判据在
     * [restoredIndexOnLeaveFor]（纯函数，有单测），`diskAtStartup` 那一项就是进屏会话收下的启动答案。
     */
    private fun prepareEntry(
        layer: BrowseScrollLayer,
        resetKey: BrowseScrollResetKey,
        firstFrameItemCount: Int,
    ): EntryPreparation {
        val key = BrowseScrollRecordKey(layer.connId, layer.containerId, resetKey)
        // 换代登记（幂等）：丢掉该层其他代次的记录——组合期每次重组、首屏 effect 每次重跑都会走到这里
        beginGeneration(key)
        val session = sessions.getOrPut(layer) {
            EntrySession(
                startupGeneration = resetKey,
                startupIndex = consumeAtStartupLanding(layer.connId, layer.containerId),
            )
        }
        val restoredOnLeave = restoredIndexOnLeaveFor(
            diskAtStartup = session.startupIndex,
            startupGeneration = session.startupGeneration,
            currentGeneration = resetKey,
            inMemoryIndex = valueFor(key),
        )
        return EntryPreparation(
            key = key,
            restoredOnLeave = restoredOnLeave,
            index = initialScrollItemIndex(restoredOnLeave, firstFrameItemCount),
            holder = session.holders.getOrPut(resetKey) { RestoredScrollIndex() },
        )
    }

    /** [prepareEntry] 交回的那几样：组合期那一问只用 [index]，首屏 effect 那一问其余都要 */
    private class EntryPreparation(
        val key: BrowseScrollRecordKey,
        val restoredOnLeave: Int,
        val index: Int,
        val holder: RestoredScrollIndex,
    )

    /**
     * 入口 ② · **离开**：记一次（含「现在在哪一层」）。
     *
     * 两个写点共用它：`onDispose`（离屏）与生命周期 `ON_STOP`（切后台）。两条路必须同源
     *（先过 [record] 的丢态判据、再落它过滤后的**生效值**），否则「系统夹索引不写」那条承诺被整条绕开。
     *
     * 「现在在哪一层」由盘侧写点自己问：写盘的那一层是不是把**启动落地层**甩到上级那一级了
     *（[noteLayerWritten]）——两个判据都在模块内部算，调用方不替它算。
     *
     * 本入口**只记一次**，不动这一屏的进屏会话：切后台那一屏没有销毁，回前台照旧是同一屏，
     * 会话（启动那一代 + 它吃到的盘上那条）因此要活到真正离屏（[endEntrySession]）——
     * 少了这一步，切后台后再组合就会重新问一次启动落地、提前丢掉「启动那条」的持有者。
     */
    fun leave(layer: BrowseScrollLayer, resetKey: BrowseScrollResetKey, indexAtLeave: Int) {
        val key = BrowseScrollRecordKey(layer.connId, layer.containerId, resetKey)
        recordEffectivePosition(key, indexAtLeave, layer.connId, layer.containerId)
    }

    /**
     * 入口 ② 的**后半步**：真正离屏（`onDispose`）时结束这一层的**进屏会话**（见 [EntrySession] 那张表）——
     * 再进来是新的一屏，要重新问一次启动落地。
     *
     * 只有真离屏那一路走它：切后台（生命周期 `ON_STOP`）只走 [leave]，那一屏还在（见 [leave]）。
     * 生产那一路收在 `BrowserScreen.kt` 的 `BrowseScrollLeaveEffect`（离屏写点唯一接缝）：它依次调
     * [leave] 与本方法，不再由界面那边的 lambda 体自己拼——那儿是测试看不见的地方。
     */
    fun endEntrySession(layer: BrowseScrollLayer) {
        sessions.remove(layer)
    }

    /**
     * 入口 ③ · **开机**：交回「这次落在哪一层」（启动链在导航前调）。
     *
     * [layer] 为 null = **已定的**非浏览层（顶层路由 首页 / 书柜 / 设置、阅读器、启动落地的兜底支）：
     * 收口时按「非落地层」当场丢弃那条记录（「重启后只有落地那一层有记录」）。
     *
     * 非以「调用过本方法」断言「本次落地不是浏览层」：启动链在块首先交一个默认值（那时还不知道本次会不会落到
     * 浏览层），落到浏览层的那一支随后用真的层覆盖它——交回的是「**已定**」这一位。
     */
    fun startupLanding(layer: BrowseScrollLayer?) {
        landingLayer = layer
        landingDecided = true
    }

    /**
     * 入口 ④ · **位置已放回**（首屏链以 `target != null` 请求放回那一刻）。
     *
     * 本屏从此不再拒写：放回之后这一屏的读数就是用户的（含再滚回顶部离场记 0）。
     * 时点是**请求**而不是「真落到屏上」：`requestScrollToItem` 非挂起，落地在下一帧测量时，
     * 因此本标记比实际落地早一帧（口径与判据见 [notePlaced]）。
     */
    fun placed(layer: BrowseScrollLayer, resetKey: BrowseScrollResetKey) {
        notePlaced(BrowseScrollRecordKey(layer.connId, layer.containerId, resetKey))
    }

    // ---------------- 模块内部步骤（生产与单测都只走上面那六个入口；这些一律 private） ----------------

    /**
     * 启动链交回的**已定落地层**：null + [landingDecided] 真 = 「已定的非浏览层」；
     * 两者合起来是一个**三态**：**还没交回** / **已定：某个浏览层** / **已定：非浏览层**。
     * 少了 [landingDecided] 这一位，「还没交回」就会被读成「**一定不是**」——第一次消费即静默销毁记录。
     */
    private var landingLayer: BrowseScrollLayer? = null

    /** 「落地层**已定**」（[startupLanding] 置真）：把 `landingLayer == null` 收窄成「本次落地不是浏览层」这一种含义 */
    private var landingDecided = false

    /**
     * 「启动落地已经收口过」的标记：收口之后本模块**不再交回任何层**（盘上那份留着给下一次重启）——
     * 盘上那条记录在本次启动里会被反复改写（三个写点），拿它当「启动那条」会再把位置交回本不该交回的层。
     */
    private var landed = false

    /**
     * 盘侧收口：**启动落地已定**那一刻的启动恢复（[prepareEntry] 建进屏会话时问它）。
     *
     * 这一层正是记录指向的那一层（= 本次落地的那一层）时交回上次记下的项索引；否则**当场丢弃**那条记录。
     * 落地层**还没交回**时不适用这两条：判不出「这一层是不是落地层」⇒ **既不消费也不丢弃**，
     * 只有「问的正是记录那一层」时才先给值（系统还原回退栈那条路上，还原出的浏览层当帧就是栈顶，
     * 它的组合早于启动 effect 的交回，而那条路的落地层就是记录那一层）。
     */
    private fun consumeAtStartupLanding(connId: Long, containerId: String?): Int? {
        val layer = BrowseScrollLayer(connId, containerId)
        // 口径 ②「离开这一层、再从上一级进来 ⇒ 回顶部」的判据落在**这一层被重新进入**这一刻：
        // 用户离开后走到了这一层的**上一级**（那一层的写盘以链顶身份在 [noteLayerWritten] 里登记）
        // ⇒ 作废本层的位置记录（下面读到的就是 0 ⇒ 顶部）。只对**启动落地层**成立。
        resetOnReentryFromParent(layer.connId, layer.containerId)
        val stored = store.read() ?: return null
        if (!landed && !landingDecided) return if (stored.layer == layer) stored.index else null
        // 收口之后不再交回（任何层）：盘上那条留着给下一次重启
        if (landed) return null
        landed = true
        if (landingLayer != layer) {
            // 落地层不是这一层（含 landingDecided 真、landingLayer 为 null = 本次落地不是浏览层）⇒ 当场丢弃
            store.clear()
            return null
        }
        if (stored.layer != layer) return null
        // 收口：交出的这一条就是**启动那条**，也是本函数**唯一**会交出的那一条（盘上那份不清）
        noteStartupLanding(layer.connId, layer.containerId)
        return stored.index
    }

    /**
     * 记下「这一层 + 这一刻的项索引」（盘上那份一次性记录）。三个写点：进屏（[enter]）/ 离屏 / 切后台（[leave]）。
     * **只保留最后一条**——本记录问的是「此刻所处的那一层」，后来的写覆盖先前的。
     */
    private fun record(connId: Long, containerId: String?, index: Int) {
        // 哪一层刚写过盘，就在这里登记一句（口径 ② 的「返回上一级」判据）
        noteLayerWritten(connId = connId, containerId = containerId)
        store.write(BrowseScrollLayer(connId, containerId), index)
    }

    /**
     * 「离屏 / 切后台」两个写点共用的落盘（两条路必须同源）：
     * 先经 [record]（`key` 那一支）过**丢态判据**（「系统夹索引不写」），再落它过滤后的**生效值**
     *（[valueFor]）——直接落裸读数会整条绕开那条判据。
     * 判据里那份**进屏基准在拒写时不消费**（见 [record]）：因此同屏的两个写点先后读到同一份丢态残留时
     * **两个都会被拒**——基准被前一次消费掉时，第二次调用没有东西可比、必然把被夹小的读数放行到记录与磁盘。
     */
    private fun recordEffectivePosition(key: BrowseScrollRecordKey, rawIndex: Int, connId: Long, containerId: String?) {
        record(key, rawIndex)
        record(connId, containerId, valueFor(key))
    }
}

/**
 * 进屏那一刻问到的**本次落位方案**（入口 ① 的答案）。
 *
 * 它回答两问：**上次停在第几条**（[index]，两档滚动状态的初值）与**落位带多少偏移**（[offsetPx]）；
 * 另外两问也挂在它身上，因为都是同一份进屏答案的续问：**取数落地后要不要把位置放回去**
 *（[placementTarget]）与**放回时带多少偏移**（[placementOffsetPx]）。
 *
 * @param index 「本次该恢复到哪一条」的项索引（已夹到首帧范围内，见 [initialScrollItemIndex]）
 * @param restoredIndexHolder 本次取数下限的持有者：首屏 effect 重跑时**同代只读一次**（[RestoredScrollIndex]）
 * @param restoredIndexNow 本次取数要恢复到第几条（或「进屏读数」代次下取的当下值）；组合期那次为 0，不得当取数下限用
 */
internal class BrowseScrollLanding internal constructor(
    val index: Int,
    internal val restoredIndexHolder: RestoredScrollIndex,
    val restoredIndexNow: Int,
) {
    /** 初值要带的**纵向偏移** px：把该档列表顶部那段内容留白吃掉（列表档恒 0，见 [browseTopContentPadding]） */
    fun offsetPx(isGrid: Boolean, density: Float): Int =
        restoredLandingOffsetPx(index, browseTopContentPaddingPx(isGrid, density))

    /** 「取数落地后要不要把位置放回去」：目标项索引，null = 不动（判据见 [scrollRestoreTarget]） */
    fun placementTarget(currentIndex: Int, loadedItems: Int): Int? =
        scrollRestoreTarget(restoredIndexNow, currentIndex, loadedItems)

    /** 「放回」请求要带的纵向偏移（与初值同一份口径，含「索引 0 不吃留白」那条边界） */
    fun placementOffsetPx(targetIndex: Int, isGrid: Boolean, density: Float): Int =
        restoredLandingOffsetPx(targetIndex, browseTopContentPaddingPx(isGrid, density))
}

/**
 * 网格档的**纵向**外边距（上下同值）：**保持 12dp 原值**（与格子槽高同源，见
 * `com.cc3301.comicviewer.core.view.gridCellMaxHeight`）。
 *
 * 它同时是**落位口径**里被吃掉的那段顶部内容留白：容器（`BrowserGrid` 的 `contentPadding`）与落位
 *（[browseTopContentPaddingPx]）都从这一处取值，两者因此不可能各说一个数。
 */
internal val GRID_CONTENT_PADDING_VERTICAL = 12.dp

/**
 * 该档列表**顶部的内容留白**：网格档 = [GRID_CONTENT_PADDING_VERTICAL]（排版口径），
 * 列表档**没有**（`LazyColumn` 不设 `contentPadding`）。
 *
 * 留白与落位的关系：`Lazy` 把内容留白算在**视口之外**（顶部留白 12dp 时 `viewportStartOffset = -12dp`），
 * 按「项索引 + 偏移 0」落地的那一项上沿因此落在留白**之下**（设备量到 12.3dp 偏移就是它）。
 * **留白本身不改**（格子槽高与格内几何都由它定）——改的只是**落位**要怎么吃掉它（[restoredLandingOffsetPx]）。
 */
internal fun browseTopContentPadding(isGrid: Boolean): Dp = if (isGrid) GRID_CONTENT_PADDING_VERTICAL else 0.dp

/** 上面那条留白的真 px 值（落位偏移用）：密度由调用方给（界面从 `LocalDensity` 取） */
internal fun browseTopContentPaddingPx(isGrid: Boolean, density: Float): Int =
    (browseTopContentPadding(isGrid).value * density).roundToInt()

/**
 * 位置模块的装配（组合根调）：两个外部依赖在这里注入——
 * 「位置存在哪」= 单条落盘（[SharedPrefsBrowseScrollStorage]）、
 *「现在在哪一层」= 浏览链（会话状态的 [SessionState.browseHistory]，回退栈里浏览层的镜像）。
 *
 * 六个入口都走这个实例（`BrowserScreen` / `AppNav` 只碰它们）。
 */
internal fun newBrowseScrollPosition(session: SessionState): BrowseScrollPosition = BrowseScrollPosition(
    store = SharedPrefsBrowseScrollStorage { ServiceLocator.context },
    browseChain = { session.browseHistory.path().map { BrowseScrollLayer(it.connId, it.containerId) } },
)

/**
 * 位置模块的**组合期提供点**：`MainActivity` 提供（与 `LocalSessionState` 同一处），
 * 界面按 [LocalBrowseScrollPosition] 取实例。没提供就报错——漏接是接线错，不静默降级。
 */
internal val LocalBrowseScrollPosition: ProvidableCompositionLocal<BrowseScrollPosition> = compositionLocalOf {
    error("没有提供位置模块（提供点在 MainActivity，见 ui/BrowseScrollPosition.kt）")
}

/**
 * 「本次该恢复到哪一条」那一行（`phase=read`）。
 *
 * **首屏 effect 每跑一次产一行**（它的键含 `pager`，而来源是异步解析的 ⇒ 同一 `gen` 可能出多行）：
 * 所以它记的是「每次都算了什么」，不是「第一次的决定」。
 *
 * 四个字段（读数口径只写在这里，别处不复写）：
 * - `saved` = **离开这一屏那一刻**记下的项索引（= [BrowseScrollPosition] 里这一层的记录，
 *   不再是 `onDispose` 写的 `rememberSaveable`——那份在日志里返回时读到 0）。
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
 * 「该不该把位置放回去」那一刻的一行（`phase=apply`）。
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
 * 「离开这一屏那一刻读到的候选值」那一行（`phase=leave`）。
 *
 * **它是 `phase=read` 里 `saved` 的候选来源**（`saved` 读自记录，见下行）。这一行打的是
 * **候选值**：[BrowseScrollPosition.record] 收下它，下一次 `read` 的 `saved` 才是这个值；**被拒写时**
 * （判据见 `record`）记录仍是**旧值**，两行这时对不上是正常的。只有它能把下面几件事分开：
 * - `leave` 非 0、而接下来 `phase=read` 的 `saved` 也随之回升 ⇒ 真记进了记录、交得回来（机制在工作）；
 * - `leave` 非 0、而 `saved` 仍是 0 / 旧值 ⇒ **键没命中**（层或代次对不上）或那次离场**被拒写**了；
 * - `leave=0` 而当时列表在中段 ⇒ 记录点本身取错了（`currentScrollItemIndex()` 取的不是可见项）；
 * - **整份日志里一条 `leave` 也没有** ⇒ 离场时 `onDispose` 根本没跑，这个值从没被写下。
 *
 * （「`leave` 非 0 而 `saved=0` ⇒ 丢在**保存 / 交回**这一段」那条判读随换机制作废：`saved` 不再来自
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

/**
 * 切后台写点（生命周期 `ON_STOP`）交给 [BrowseScrollPosition.leave] 的那个值（`phase=stop`）。
 *
 * 离屏写点之外唯一另一个落盘路径：没有这行时，「无离场回调的退出路径的落盘行为」在日志里是盲区——
 * `phase=leave` 只来自 `onDispose`，强杀前有没有一次 ON_STOP 落盘、落的是哪个值，只能靠这行回答。
 * 判读口径与 [browseRestoreLeaveLine] 同一：**打的是交给 leave 的候选值**，被拒写时记录仍是旧值；
 * `leave`（onDispose）与 `stop`（ON_STOP）两行都来自同一个入口，同一段停留两行都在是正常形态
 *（ON_STOP 在前、onDispose 在后），按时间戳配对。
 */
internal fun browseRestoreStopLine(container: String?, index: Int, isGrid: Boolean): String =
    BROWSE_RESTORE_PREFIX + " phase=stop container=" + (container ?: "<root>") +
        " index=" + index + " mode=" + (if (isGrid) "grid" else "list")
