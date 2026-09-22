package com.cc3301.comicviewer.ui

import androidx.navigation.NavController
import com.cc3301.comicviewer.core.nav.LastRead
import com.cc3301.comicviewer.core.source.BookHandle
import com.cc3301.comicviewer.core.source.BookOpening
import com.cc3301.comicviewer.core.source.Source
import com.cc3301.comicviewer.core.source.commitOpeningProgress
import com.cc3301.comicviewer.core.source.openBookAtLanding
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicLong

/**
 * 打开书的前置槽（票 #108 E1-A；票 #122 起导航先发生）：点击时预打开的结果，交给阅读页取走
 * （组合期那份已到货就同步取走，否则在阅读页侧有界等它到货）。
 *
 * 维护者现象（#108 当时的口径）：点开一本书先看到黑底「准备打开」整页，然后才出现图片。**票 #122 起改序**：
 * 点击那一帧就切页（滑入立刻开始），「打开书 + 首批解码」在会话级作用域里继续跑，阅读页在那边有界等它；
 * 等页期间阅读页是主题背景色纯色、不显示加载指示（#111 的呈现侧），黑底「准备打开」不再出现。
 * 因此「已打开」这件事仍要在两层之间传一次：写入口在发起那一屏的点击路径，读出口在 `ReaderScreen` 的组合期。
 *
 * 键是**连接 id + 书 id**（票 #110）：书 id 只在对应连接内有效（见 `ServiceLocator` 的 currentConnId 契约），
 * 只按书 id 认主的话，先在来源 A 点开编号 X 的书（前置还没取走就被取消/超时），再到来源 B 点开编号也是 X
 * 的书，B 的阅读页会取走 A 的句柄（页数/正文来自另一个库）。带连接 id 后旧连接的前置不被新连接取走。
 *
 * 只认**同一本书**：`take` 拿到别的连接或别的书 id 时返回 null 且不清槽（导航参数与槽位错配时宁可走一次
 * 正常打开，也不能把 A 的句柄交给 B 的阅读页——句柄带页数，错交会直接读错书）。
 *
 * 单槽即可：同一时刻只有一个阅读页在等前置，第二次点击（同一本或另一本）只会顶替第一次。
 *
 * **票 #122 起的形状**：导航已在点击那一帧发生，因此前置往往**还在飞**就被阅读页取用——阅读页用
 * [await] 有界等它到货（≤1.5s）；没有在飞的前置就不等，直接自己开书（进程被杀后重建这类没有点击前置的
 * 入口因此不会被白等 1.5s）。
 *
 * **票 #122 r2：前置按「哪一次打开」认主（世代号）**。[begin] 每次发一个递增世代号，只有**最新那次**请求的
 * 前置才入槽、才可被兑换（`take`/`await`）；同键的旧世代条目在 [begin] 与 [take] 两处都被作废。
 * 没有它时，慢来源上会出现这样一条链：点书 A → 阅读页 1.5s 兜底自己开书、用户读到第 105 页 → 前置姗姗入槽
 * （它的落点是**上一次**点击时刻算的）→ 再点开 A → 取到旧条目 → 回到旧落点，随后 savePage 把旧页写回进度。
 * 跳书（键不匹配）与同键两种过期都由它盖住。
 */
internal class ReaderPrelude {

    /** 槽位键（票 #110）：连接 id + 书 id；具名键比嵌套 Pair 可读（`slot.first.connId`） */
    private data class PreludeKey(val connId: Long, val bookId: String)

    /** 一次打开请求（票 #122 r2）：键 + 世代号；世代号表达「这是第几次打开」 */
    private data class Request(val key: PreludeKey, val generation: Long)

    /**
     * [pending] / [latest] / [inFlight] / [arrival] 共用的锁（票 #122 r2 评审 F1）。
     *
     * 为什么必须有它：[await] 跑在阅读页组合的 Main 上，[put] 跑在会话级作用域的 IO 上，两段可指令级交错。
     * 「取槽 / 判在飞 / 记下要等的信号」若不是同一把锁里的一步，就会把等待挂在**没人会完成**的信号实例上
     * （等满 1.5s 返回 null，而槽里其实已有前置 ⇒ 重复开书 + 最长 1.5s 空屏）。
     */
    private val lock = Any()

    /** 世代号发号器（单调递增；单槽、单阅读页，一个 Long 就够） */
    private var issued = 0L

    /** 最近一次 [begin] 的那次请求（= 「这次打开」），也是唯一的有效世代 */
    private var latest: Request? = null

    /** 仍在飞的那次请求（[begin] 起、[end]/[put] 止）：阅读页据此决定「要不要等」 */
    private var inFlight: Request? = null

    private var pending: Pair<Request, ReaderPreludeEntry>? = null

    /**
     * 「状态变了」的信号（票 #122）：[await] 靠它醒来，因此每次 [signalArrival] 都**换一个新实例**
     * （完成过的 Deferred 再 await 会立刻返回，不换就会变成忙等）。轮换与写入同在 [lock] 里。
     */
    private var arrival = CompletableDeferred<Unit>()

    /**
     * 登记一次前置请求（票 #122）：发起侧在开跑前置**之前**调，拿到这次请求的世代号
     * （[put]/[end] 用它认「是不是这次」）；阅读页据此决定要不要等（[await] 内部问同一个状态）。
     *
     * 同时**作废旧条目**（票 #122 r2 P1）：槽里那份若属于上一次打开，它的落点是上一次点击时刻算的，
     * 不能被这一次取用。
     */
    fun begin(connId: Long, bookId: String): Long = synchronized(lock) {
        val request = Request(PreludeKey(connId, bookId), ++issued)
        latest = request
        inFlight = request
        pending = null
        signalArrival()
        request.generation
    }

    /** 结束一次前置请求（票 #122）：前置失败/超时/被取消时由工作侧调（成功那份由 [put] 结束） */
    fun end(connId: Long, bookId: String, generation: Long) = synchronized(lock) {
        val request = Request(PreludeKey(connId, bookId), generation)
        if (inFlight == request) inFlight = null
        signalArrival()
    }

    /**
     * 这本书那次打开的前置还在飞吗（票 #122）：false ⇒ 阅读页不等，直接自己开书。
     * 判据是「最近一次请求就是这本书、且它还在飞」——被后一次打开顶替后旧的等待不再算数。
     */
    fun isInFlight(connId: Long, bookId: String): Boolean = synchronized(lock) {
        val request = latest ?: return@synchronized false
        request.key == PreludeKey(connId, bookId) && inFlight == request
    }

    /**
     * 记下一次预打开的结果（发起那一屏侧；票 #122 起工作在会话级作用域里跑，那一屏可能已被导航销毁）：
     * 键 = 连接 id + 书 id（票 #110），世代 = [begin] 发给这次请求的那个。
     *
     * **只有最新那次请求的前置才入槽**（票 #122 r2 P1）：被后一次打开顶替（同键或别的键）的那份过期前置一律丢掉，
     * 否则它会以旧落点覆盖下一次打开的落地。
     */
    fun put(connId: Long, bookId: String, generation: Long, entry: ReaderPreludeEntry) = synchronized(lock) {
        val request = Request(PreludeKey(connId, bookId), generation)
        if (latest == request) pending = request to entry
        if (inFlight == request) inFlight = null
        signalArrival()
    }

    /**
     * 取走某连接下某本书的预打开结果：取到即清槽（同一本书只兑现一次）。
     *
     * - 连接或书 id 不匹配 → 返回 null 且**保留**槽位（#110：宁可走一次正常打开，也不能把 A 的句柄交给 B）；
     * - 同键但**不是最新那次请求**（过期世代，票 #122 r2 P1）→ 丢弃该条目并返回 null。
     */
    fun take(connId: Long, bookId: String): ReaderPreludeEntry? = synchronized(lock) {
        val slot = pending ?: return@synchronized null
        if (slot.first.key != PreludeKey(connId, bookId)) return@synchronized null
        pending = null
        if (slot.first != latest) return@synchronized null
        slot.second
    }

    /**
     * 阅读页侧的「等前置到货」（票 #122）：导航已经发生，前置工作还在飞时最多等 [timeoutMillis]。
     *
     * - 到货且属于**这次**请求 → 交出并清槽（与 [take] 同一个「只兑现一次」口径）；
     * - **没有在飞的前置**（启动还原/进程重建、已 [retire] 或被后一次打开顶替）→ 立即返回 null，不白等；
     * - 到点 / 到货的是过期世代 → 返回 null，由阅读页走 [openAndLandReaderEntry] 的兜底分支（#110 的「否则自己开书」）；
     *   阅读页决定自己开书时调 [retire] 退掉这次请求（票 #122 r3），迟到的条目因此既不入槽、也不会留给组合重建。
     *
     * 上限与 #108 的闸门同一个 1.5s 口径（[PRELUDE_TIMEOUT_MILLIS]）：等待被截断，**工作不取消**——
     * 它继续把字节/位图填进缓存（发起侧给的是会话级作用域）。取消照常传播（阅读页离开/换书即停）。
     */
    suspend fun await(connId: Long, bookId: String, timeoutMillis: Long): ReaderPreludeEntry? {
        // 快速路径也在**一次持锁**里问完（评审 #126，本票根因）：`take` 与 `isInFlight` 若各自持锁，
        // 工作侧能落在两条语句之间——`put` 在**同一把锁**里「入槽 + 清 `inFlight`」，于是 `take` 已错过、
        // `isInFlight` 读到 false，`await` 立刻返回 null 而槽里其实已有前置；调用方 `takeReaderPreludeForOpen`
        // 随即 `retire` 把它丢掉，阅读页走兜底自己重开书（正是 F1 那把锁要消灭的「重复开书/空屏」）。
        // 窗口只有两条加锁语句之间，因此只在机器负载高时被抢占撞上（#125 全量跑红、单跑绿）。
        // 口径与下面等待循环里那三步一致：取槽 / 判在飞是**同一个状态快照**。
        var taken: ReaderPreludeEntry? = null
        var waitable = false
        synchronized(lock) {
            taken = take(connId, bookId)
            if (taken == null) waitable = isInFlight(connId, bookId)
        }
        taken?.let { return it }
        if (!waitable) return null
        return withTimeoutOrNull(timeoutMillis) {
            var waiting = true
            var entry: ReaderPreludeEntry? = null
            while (waiting && entry == null) {
                var signal: CompletableDeferred<Unit>? = null
                // 「取槽 + 判在飞 + 记下要等的信号」三步同在 [lock] 里（评审 F1）：出锁之后 put/end 只会完成**这个**
                // 实例，「判完之后才 put」因此会把我们唤醒而不是让我们睡在不装人的信号上（take/isInFlight 可重入）。
                synchronized(lock) {
                    entry = take(connId, bookId)
                    if (entry == null) {
                        if (isInFlight(connId, bookId)) signal = arrival else waiting = false
                    }
                }
                if (entry == null && signal != null) signal.await()
            }
            entry
        }
    }

    /**
     * 退役这本书**当前那次**打开（票 #122 r3，评审 spec r2 P1）：阅读页决定自己开书时调——
     * 它不再需要这次请求的前置，因此迟到的条目不得再入槽（[put] 的 `latest` 比对落空）、也不得留给
     * **界面重建**后的 `take`（旋转屏幕 / 打开失败重试都会重新组合，`ReaderScreen` 的组合期会再取一次）。
     *
     * 没有它时的可达链（慢来源，正是本票的动因）：点书 A（前置 > 1.5s）→ 阅读页兜底开书并落地 →
     * 用户继续读到后面页 → 前置姗姗入槽（**没有新的 `begin`**，因此仍是 `latest`）→ 旋转屏幕 →
     * 新组合的 `take` 命中它 → 跳回点击时刻那一页，退出时把低页写回进度。
     *
     * 只退**这本书**这次打开：`latest` 属于别的书（用户又点了别的书）时不动它，那是别人的在飞请求。
     * 幂等：没有在飞请求（或已退役过）时什么都不做。
     */
    fun retire(connId: Long, bookId: String) = synchronized(lock) {
        val request = latest ?: return@synchronized
        if (request.key != PreludeKey(connId, bookId)) return@synchronized
        latest = null
        inFlight = null
        pending = null
        signalArrival()
    }

    /** 轮换并完成信号（调用方必须已持 [lock]）：见 [arrival] 的注释 */
    private fun signalArrival() {
        val signal = arrival
        arrival = CompletableDeferred()
        signal.complete(Unit)
    }
}

/**
 * 一份**待落地**的预打开结果（票 #110）：句柄 + 落点（[BookOpening]），加上**点击时刻**读到的
 * 「始终从第一页打开」判据。
 *
 * 判据随句柄一起带到落地那一刻（r3）：落地时重读设置会让「判据」与「落点/写入值」不同源——
 * 落点按点击时刻算、写不写按落地时刻算，两次读值不同就会留下「停在第 1 页但进度没被覆盖」的半状态。
 */
internal data class ReaderPreludeEntry(val opening: BookOpening, val alwaysFirstPage: Boolean)

/**
 * 阅读页落地的**票号**（票 #110 r3）：每次「切进这本书」领一个递增票号，落地写「上次阅读位置」时只有
 * **最新那一个**票号能写。
 *
 * 为什么需要：落地写在 `NonCancellable` 块里（不随组合取消而丢，SPEC 故事 40），而块内的进度写可能是
 * 慢来源的网络往返（Komga 的 PATCH 可达秒级）。用户此时读内换书会新建一条 reader entry，旧 entry 那半截
 * 仍会写完——于是**后到的旧写**可能把「上次阅读位置」压回旧书。票号把这件事变成「谁是最新的那次落地」，
 * 不依赖完成顺序。
 */
internal object ReaderEntryTickets {

    private val issued = AtomicLong()

    /** 领一个票号（阅读页每次落地前领，越晚领越大） */
    fun issue(): Long = issued.incrementAndGet()

    /** 这个票号还是最新的吗？（写记录之前问一次） */
    fun isLatest(ticket: Long): Boolean = issued.get() == ticket
}

/**
 * 阅读页组合期的「打开 + 落地」（票 #110）：**前置在手（票 #108）就用它，否则自己开书**，
 * 两条分支都在 [landReaderEntry] 里一次落地（进度覆盖 + 上次阅读位置）。
 *
 * **票号在两条分支之前就领**（票 #112 第 8 条）：兜底分支的开书是阻塞的来源调用，
 * 「先开书、后领号」会让后完成的那次落地拿到更大的号，后到的旧写于是能盖掉新记录。
 * 票号因此按**发起顺序**发（= 用户切书的顺序），不按开书完成顺序。
 *
 * 抽成非 Composable 的挂起函数：阅读页那条 `LaunchedEffect` 只剩「调它 + 处理打开失败」，于是
 * 「落地这一步有没有被调」有自动化守护（`ReaderEntryLandingTest`）——落在 Composable 里就守不住
 * （本仓无 Compose UI 测试基建）。
 *
 * 打开失败**照旧冒出去**（阅读页有自己的失败提示与重试）；落地写失败在 [landReaderEntry] 里被吞掉。
 */
internal suspend fun openAndLandReaderEntry(
    source: Source,
    connId: Long?,
    bookId: String,
    /** 前置槽里那份（票 #108）；null = 兜底分支（前置超时/失败或其它入口），由本函数自己开书 */
    prelude: ReaderPreludeEntry?,
): BookOpening {
    // 票号在**开书之前**领（票 #112 第 8 条）：票号表达的是「这是第几次切进阅读页」，必须按**发起顺序**
    // 而不按**完成顺序**。旧写法先开书、后领号（兜底分支的开书是可能阻塞的来源调用，慢来源上可达秒级）——
    // 切进 A 后马上（或稍后）切进 B 时，A 的开书若比 B 晚结束就会领到**更大**的号，
    // 「后到的旧写不得覆盖更新的记录」于是失效（#110 影响面复验报的 P2）。领号提前后，
    // 落后的旧 entry 拿到的是更小的号，它的落地被 [applyReaderEntry] 丢掉。
    // 落地那句仍用同一个号：领号与落地之间不再有别的领号点插进来（本函数是唯一生产领号点）。
    val ticket = ReaderEntryTickets.issue()
    val entry = prelude ?: fallbackPreludeEntry(source, bookId)
    landReaderEntry(source, connId, bookId, entry, ticket)
    return entry.opening
}

/**
 * 兜底分支的「开书 + 判据」：**只开书、不落地**（与前置体同一个口径：开书与落地分开），
 * 判据与落点同一次读——于是写入值（落点）与判据同源（`Source.kt` 的「判据与写入值同一份」两条分支都成立）。
 *
 * 整段跑在 `Dispatchers.IO` 上（票 #110 r4）：`openBook` / `readProgress` 在 Komga 来源里是**阻塞**的
 * OkHttp `execute()`（`callTimeout` 90s），而阅读页的调用点是组合期 `LaunchedEffect`（主线程）——
 * 不在这一处切 IO 就会把主线程卡在网络上（ANR 风险）。切在**阻塞调用自己这一层**而不是调用方，
 * 任何入口调 [openAndLandReaderEntry] 都受保护（落地那半截的 IO + NonCancellable 见 [landReaderEntry]）。
 */
private suspend fun fallbackPreludeEntry(source: Source, bookId: String): ReaderPreludeEntry =
    withContext(Dispatchers.IO) {
        val alwaysFirstPage = AppSettings.alwaysOpenFirstPage
        ReaderPreludeEntry(openBookAtLanding(source, bookId, alwaysFirstPage), alwaysFirstPage)
    }

/**
 * 切进阅读页这一刻的落地（票 #110）：进度覆盖 + 上次阅读位置，**一次调用、一个保护块**。
 *
 * - `NonCancellable + Dispatchers.IO`：不随组合取消而丢（SPEC 故事 40：进入马上退出也只算读了 1 页），
 *   且两笔写共用同一种线程/取消语义——不会出现「进度写在了 IO、记录写在 Main」的半状态；
 * - `runCatching`：落地写失败按 `ReaderScreen.savePage` 的既有口径吞掉（不冒出组合协程）；
 * - [ticket]：本次落地的票号（[ReaderEntryTickets]）——**后到的旧写不得覆盖更新的记录**。
 */
internal suspend fun landReaderEntry(
    source: Source,
    connId: Long?,
    bookId: String,
    entry: ReaderPreludeEntry,
    ticket: Long,
) {
    withContext(NonCancellable + Dispatchers.IO) {
        runCatching {
            commitOpeningProgress(source, bookId, entry.alwaysFirstPage, entry.opening)
            applyReaderEntry(connId, bookId, ticket)
        }
    }
}

/**
 * 「上次阅读位置」的落地（票 #110）：写在与阅读进度**同一时点**——阅读页真正切进这本书的那一刻。
 *
 * **全仓唯一的新记录写入点**（票 #110 维护者指示）：点击路径（`BrowserScreen` 打开书）与读内换书
 * （`AppNav` 的 `onOpenBook`）都不再写，于是「点击后取消 / 被后一次点击顶替」（没进阅读页）都不改
 * 「上次阅读位置」（启动还原与抽屉「阅读器」入口读的就是这一条）。
 * `AppNav` 启动还原那一处只是把**刚读出的落盘值**回填会话态，写入值恒等于已落盘值，不产生新记录。
 *
 * [ticket] 不是最新票号时丢掉这次写：后到的旧写不得把记录压回旧书（见 [ReaderEntryTickets]）。
 */
internal fun applyReaderEntry(connId: Long?, bookId: String, ticket: Long) {
    if (!ReaderEntryTickets.isLatest(ticket)) return
    connId?.let { ServiceLocator.lastRead = LastRead(it, bookId) }
}

/**
 * 点击一本书时的打开前置（票 #108 E1-A，由 [ReaderPreludeTest] 锁定）：**先开书、再按落点解「首批」若干页**。
 *
 * 返回值与阅读页自己打开时同一个类型 [BookOpening]（落点由 [openBookAtLanding] 算），因此阅读页拿到它
 * 就能直接开画，不必再跑一遍打开（省掉的就是原来那段黑底「准备打开」）。
 *
 * **不落地进度**（票 #110）：前置跑的这段时间里用户随时可能改点另一本 / 返回 / 切走（= 取消，书没被打开），
 * 打开瞬间就写会把这本书记成读了第 1 页。那一步改由阅读页取走前置时调 `landReaderEntry` 落地
 * （与「上次阅读位置」一次写齐）。
 *
 * 解的页不只落点那一页：维护者原话是「等打开、**并且附近几页加载完成**后再切过去」，而条漫首屏通常不止
 * 一页——只解一页的话切过去后仍会接着解码并出现占位（“一波波补齐”）。张数取 [PRELOAD_PAGE_COUNT]，
 * 从落点起连续取、夹到末页（[preloadPageIndices]）。
 *
 * 首帧解码走注入的 [decodePage]（生产 = `PageDecoder.decodePage`，按阅读页同一个目标宽度解码、**进同一个
 * 解码缓存**；书柜页不自己画这些图）。**一页失败就停**：同一本书后续页多半同样失败，继续只会把切页时间拉长；
 * 打开成功但首帧算不出来时，正确结果是「照常进阅读页」（那里有失败提示与重试），而不是把人留在书柜页。
 *
 * 0 页的书不解码（`ReaderScreen` 对它显示空态而非页面，解码只会白跑）。
 */
internal suspend fun preloadReaderOpening(
    source: Source,
    bookId: String,
    alwaysFirstPage: Boolean,
    targetWidthPx: Int,
    decodePage: suspend (BookHandle, Int, Int) -> Unit,
): BookOpening {
    val opening = openBookAtLanding(source, bookId, alwaysFirstPage)
    for (index in preloadPageIndices(opening.startIndex, opening.handle.pageCount)) {
        val decoded = catchingNonCancellation { decodePage(opening.handle, index, targetWidthPx) }
        if (decoded.isFailure) break
    }
    return opening
}

/** 打开前置的等待上限（毫秒，票 #108 r5；票 #122 起是**阅读页侧**等待的上限）：见 `ReaderPrelude.await`。 */
internal const val PRELUDE_TIMEOUT_MILLIS: Long = 1_500

/**
 * 「不在浏览页点书」入口的**一次请求**（票 #111 r2 修复 P1/P2，r3 换成栈项身份；由 `ReaderEntryRequestTest` 锁定）。
 *
 * 为什么要有这个判定：抽屉「阅读器」入口的等待跑在 `AppNav` 的组合作用域上（只有整个 AppNav 离开组合才
 * 取消），因此「用户已经走开」不会被取消观察到——≤1.5s 的等待里按返回、或再开抽屉点书柜/设置之后，
 * 阅读器仍会被压到**已经变了**的回退栈上。对照浏览页点击那条（票 #108）：它用 `openRequestAlive`
 * 记住「这次点击还算不算数」（`BrowserScreen`；票 #122 起前置工作归会话级作用域，不再随那一屏销毁）。
 * 这里把那条守卫抽成**可断言的一处**：
 * **没被后一次点击顶替（单调 token）+ 用户仍停在发起时那一项**。
 *
 * 「那一项」必须是 [EntryKey]（路由 pattern + back stack entry 的 id），不能只比路由字符串（r2 的写法）：
 * 浏览层级（子文件夹 ↔ 父目录）是**同一个 destination、同一个 pattern、不同参数**，只比 pattern 时
 * 「等待窗口里按返回回到父目录」会被判成「没离开」⇒ 用户刚按了返回，阅读器仍被压进栈（r3 收口的正是这条分支）。
 * 取法只有一处：[keyOf]。
 *
 * 为什么是单调 token 而不是值相等（读内换书曾用值相等）：值相等会撞 ABA——A→B→A 三连点后**旧** A 请求
 * 被重新判为「当前」，与新 A 请求各导航一次（同一本书被切两次，第二次取不到已被取走的前置槽，重现一帧
 * 「准备打开」）。
 *
 * 与 #108 的「取消不导航」同口径：不算数就不导航（票 #122 起导航在点击那一帧发生，因此守卫判定的是
 * **那一次点击**当时的状态）。入槽与「导不导航」是两件事：票 #122 起交付（`onReady`）在**工作协程**里跑、
 * 可能晚于 `navigate`，一份前置能否被兑现由 `ReaderPrelude` 的世代号与退役判据决定（见那里的注释）；
 * `ReaderPreludeTest` 的「守卫为假时不导航」仍断言句柄照旧交出来，本票不改。
 *
 * **本类只覆盖三条 AppNav 入口**（启动还原 / 抽屉「阅读器」/ 读内换书）。**浏览页点击那条不用本类**：
 * 它的守卫是自己的（组合存活标志 `openRequestAlive` + 「当前要开的那一本」`pendingOpenBookId`，见 `BrowserScreen`）。
 * 四条开书入口共用的是**前置槽**（`ReaderPrelude`）与 `enterReaderThenPreload`，**守卫各入口各一条**
 * ——「这次点击算不算数」在不同入口的判据本来就不同（那一条在页面里，这三条在回退栈项上）。启动还原那条的
 * 等待挂在 `LaunchedEffect` 上，因此它另外还要求 AppNav 组合仍存活（`startupEffectAlive`）。
 */
internal class ReaderEntryRequest {

    /**
     * 栈顶那一项的**具体身份**（票 #111 r3）：[route] 是该 destination 的 pattern（同一 destination 的
     * 不同参数下**完全相同**），[entryId] 是 back stack entry 的 id——两者一起才说得上「仍是那一项」。
     */
    data class EntryKey(val route: String?, val entryId: String?)

    /** 一次请求：单调 [token] + 发起时栈顶那一项 [origin]（`null` = 栈顶尚未定，按原样比较） */
    data class Request(val token: Int, val origin: EntryKey?)

    private var issued = 0

    /** 发起一次请求（每次点击领一个**单调递增**的 token，不复用） */
    fun begin(origin: EntryKey?): Request = Request(++issued, origin)

    /** 这次请求还算数吗（[current] = 判定这一刻栈顶那一项）：没被顶替，且用户仍停在发起时那一项 */
    fun isCurrent(request: Request, current: EntryKey?): Boolean =
        request.token == issued && request.origin == current

    companion object {
        /**
         * 读「当前栈顶那一项」（生产唯一取法）：三条入口都走这里，避免各自去读 pattern 或 id。
         * `currentBackStackEntry` 是栈顶那一项，任何导航（含同 pattern 不同参数的浏览层级）都会换一个。
         */
        fun keyOf(nav: NavController): EntryKey? =
            nav.currentBackStackEntry?.let { EntryKey(it.destination.route, it.id) }
    }
}

/**
 * 四条开书入口（浏览页点击 / 启动还原 / 抽屉「阅读器」/ 读内换书）共用的**切页 + 前置**（票 #111，票 #122 改序）。
 *
 * **票 #122 的口径是「点了立刻滑」**：本函数**先导航**（[enterReader]，滑入动画在点击那一帧启动），
 * 前置工作（开书 + 首批解码）随后在 [workScope] 里跑完并写入 [prelude] 槽，由阅读页取用：
 * 阅读页用 `ReaderPrelude.await` **有界等它**（≤[timeoutMillis]）——阅读页等页期间是主题背景色纯色、
 * 不显示加载指示（#111 的呈现侧不变），到点就自己开书（`openAndLandReaderEntry` 的兜底分支，票 #110）。
 *
 * [workScope] 因此必须**长于发起那一屏**（导航会立刻销毁它）：生产传会话级作用域。
 * 前置工作不随导航取消——它是阅读页首帧的来源，也是 #108 r6 已接受的取舍（工作跑完只是往缓存里填字节）。
 *
 * 连接 id 为空时**不做前置工作**：前置槽按「连接 id + 书 id」认主（票 #110），键都没有就无处可交。
 */
internal suspend fun enterReaderThenPreload(
    /** 前置工作的作用域（生产 = 会话级：导航会立刻销毁发起那一屏） */
    workScope: CoroutineScope,
    /** 前置槽（生产 = `ServiceLocator.readerPrelude`；注入是为了让用例核对「导航与入槽的先后」） */
    prelude: ReaderPrelude,
    source: Source?,
    connId: Long?,
    bookId: String,
    targetWidthPx: () -> Int,
    alwaysFirstPage: Boolean,
    isRequestCurrent: () -> Boolean,
    enterReader: () -> Unit,
    timeoutMillis: Long = PRELUDE_TIMEOUT_MILLIS,
    /** 前置工作切到的调度器（生产 = [Dispatchers.IO]：开书/取页是可能阻塞的来源 I/O）；
     * 用例注入虚拟调度器，才能在虚拟时间里断言「先导航、后入槽」与上限 */
    dispatcher: CoroutineContext = Dispatchers.IO,
    /** 首批解码（默认 = 生产那条：与阅读页同一个解码路径与缓存，见 [decodePageForPrelude]） */
    decodePage: suspend (BookHandle, Int, Int) -> Unit = ::decodePageForPrelude,
) {
    val src = if (connId == null) null else source
    // 登记「这本书的前置还在飞」并拿到这次请求的世代号（票 #122 r2）：阅读页据此决定要不要等，
    // 而交付/结束都带世代号——只有最新那次请求的前置才会入槽（过期的不覆盖后一次打开的落地）。
    val generation = if (src != null && connId != null) prelude.begin(connId, bookId) else null
    awaitReaderPrelude(
        workScope = workScope,
        timeoutMillis = timeoutMillis,
        preload = {
            if (src == null || connId == null || generation == null) {
                null
            } else {
                // 宽度在开跑这一刻读（见上：调用点可能在首帧布局之前）
                val widthPx = targetWidthPx()
                try {
                    withContext(dispatcher) { preloadReaderOpening(src, bookId, alwaysFirstPage, widthPx, decodePage) }
                } catch (c: CancellationException) {
                    prelude.end(connId, bookId, generation) // 被取消：别再让阅读页等一份不会到的前置
                    throw c
                } catch (t: Throwable) {
                    prelude.end(connId, bookId, generation) // 失败也不交半份：阅读页改走自己的那一次打开
                    null
                }
            }
        },
        onReady = { opening ->
            if (connId != null && generation != null) {
                prelude.put(connId, bookId, generation, ReaderPreludeEntry(opening, alwaysFirstPage))
            }
        },
        isRequestCurrent = isRequestCurrent,
        navigate = enterReader,
    )
}

/**
 * 前置首批的**生产解码实现**（票 #111）：与阅读页 `PageImage`、浏览页点击前置走同一条路
 * （`PageDecoder.decodePage` + 页字节读取函数），因此进的是同一份解码缓存。四条入口的前置都走本函数
 * （[enterReaderThenPreload] 的默认值：浏览页点击与三条 AppNav 入口因此是同一条解码路径与缓存）。
 */
internal suspend fun decodePageForPrelude(handle: BookHandle, index: Int, targetWidthPx: Int) {
    PageDecoder.decodePage(handle, index, targetWidthPx) { PageDecoder.loadPageBytes(handle, index) }
}

/**
 * 阅读页组合期的「取本次打开的前置」（票 #122 r3，由 [ReaderPreludeTest] 锁定）：
 * 到货就取走，**到点/拿不到就退役这次打开**——阅读页接下来会自己开书（[openAndLandReaderEntry] 的兜底分支），
 * 迟到的条目因此不得再入槽、也不得留给界面重建（旋转 / 重试）后的取用。
 *
 * 为什么必须退役（评审 spec r2 P1）：不退役时，慢来源上「兜底已经落地、用户读到后面页」之后姗姗到货的那份
 * 仍是 `latest`，而阅读页的组合期 `take` 在**每次重建**时重跑（旋转屏幕、打开失败重试）——那一取会拿点击时刻的
 * 落点覆盖当前落地，退出时再把低页写回进度。
 */
internal suspend fun takeReaderPreludeForOpen(
    prelude: ReaderPrelude,
    connId: Long?,
    bookId: String,
    timeoutMillis: Long = PRELUDE_TIMEOUT_MILLIS,
): ReaderPreludeEntry? {
    if (connId == null) return null
    val entry = prelude.await(connId, bookId, timeoutMillis)
    if (entry == null) prelude.retire(connId, bookId)
    return entry
}

/**
 * 前置的**有界等待 + 放行**（票 #108 r5，r6 拆开等待与工作；票 #122 改序；由 [ReaderPreludeTest] 锁定）：
 * 把 [preload] 跑在 [workScope] 里、**先放行再等**，等待上限是 [timeoutMillis]。
 *
 * 票 #122 的改序：**导航（[navigate]）不再等前置**——它在点击那一帧就发生（滑入立刻开始），
 * 前置的等待改由阅读页侧的有界等待（`ReaderPrelude.await`）承担。
 *
 * 为什么必须把等待与工作拆开（评审 r5 P1-1）：`withTimeoutOrNull` 只靠协程**取消**生效，而取消只在**挂起点**被观察。
 * 把上限包在「工作」身上时，工作体一旦是**阻塞**调用（Komga 的 OkHttp `execute()` 期间协程在运行、不在挂起），
 * 取消要等到阻塞调用自己返回才生效—— OkHttp 的 `callTimeout` 是 90s。
 * 因此工作在 [workScope] 里跑（票 #122：生产传会话级作用域——发起那一屏被导航立刻销毁，它的组合作用域带不走前置工作），
 * 本函数只包住 `deferred.await()` 这个**可取消挂起点**：任何来源的阻塞体都拦不住上限；到点后后台工作**不取消**，
 * 继续把字节/位图填进缓存（不浪费）。
 *
 * 交付（[onReady]）在**工作里**完成，不由调用方在等待之后做（票 #122）：调用方会随导航销毁，
 * 由它交付就等于「导航一走，前置永远不入槽」——而阅读页正在等这一份。
 *
 * 放行规则：
 * - **先导航**（[isRequestCurrent] 为假时不导航：仍是「这次点击算不算数」的守卫）；
 * - 前置抛错 / 超时 / 拿不到（返回 null） → 不入槽、不报错：阅读页到点自己开书（那里有自己的加载态、
 *   失败提示与重试）；
 * - **被取消 → 不再有后续动作**（与 `ui/Cancellation.kt` 票 #26 与 `docs/SPEC.md` 的仓库口径一致：取消照常传播）。
 *   票 #122 起取消已经拦不住那次导航（它发生在点击那一刻）；取消在新形状下的对应是**落地侧**：
 *   阅读页自己的组合消失就不会落地（见 `ReaderPrelude.await` 与 `ReaderScreen`）。
 */
internal suspend fun awaitReaderPrelude(
    /** 前置工作的作用域（票 #122：生产 = 会话级，长于发起那一屏） */
    workScope: CoroutineScope,
    timeoutMillis: Long,
    preload: suspend () -> BookOpening?,
    onReady: (BookOpening) -> Unit,
    isRequestCurrent: () -> Boolean,
    navigate: () -> Unit,
) {
    val work = workScope.async {
        // 前置失败**不**让 deferred 失败：否则结构化并发会把它抛给 [workScope]（生产 = 会话级），
        // 一次 SMB 断链会把会话级作用域整个取消（r6 用例「前置抛错也放行」实测到的真问题）
        val opening = try {
            preload()
        } catch (t: CancellationException) {
            throw t
        } catch (t: Throwable) {
            null // 前置失败不是错误：照常放行，阅读页有自己的失败提示与重试
        }
        // 交付在**工作里**（票 #122）：调用方随导航销毁后这次交付仍要发生
        if (opening != null) onReady(opening)
        opening
    }
    // 先导航（票 #122）：滑入在点击那一帧启动
    if (isRequestCurrent()) navigate()
    // 等待侧是**可取消挂起点**：上限对任何来源都成立（前置体是不是阻塞都不影响），到点后工作不被取消
    withTimeoutOrNull(timeoutMillis) { work.await() }
}

/**
 * 打开前置解的页张数（票 #108 E1-A 的「首批」口径）：落点那一页 + 其后 [PRELOAD_PAGE_COUNT] − 1 页。
 *
 * 3 页是「条漫首屏不止一页」与「别把切页时间拉长」之间的取舍：单页抓不住条漫首屏，而再多几页会让
 * 慢来源（SMB/网盘）上的切页等待成倍变长。
 */
internal const val PRELOAD_PAGE_COUNT: Int = 3

/**
 * 前置要解的页序（纯函数，由 [ReaderPreludeTest] 锁定）：从落点起连续 [PRELOAD_PAGE_COUNT] 页，夹到末页。
 * - 落点越界（负数/超出）一律夹回去（落点本身由 [openBookAtLanding] 保证在界内，此处是防御）；
 * - 末页附近自然只剩剩下的那几页；
 * - 页数 ≤ 0 返回空（空书不解码）。
 */
internal fun preloadPageIndices(startIndex: Int, pageCount: Int): List<Int> {
    if (pageCount <= 0) return emptyList()
    val start = startIndex.coerceIn(0, pageCount - 1)
    return (start until minOf(start + PRELOAD_PAGE_COUNT, pageCount)).toList()
}
