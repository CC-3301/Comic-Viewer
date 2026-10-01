package com.cc3301.comicviewer.core.source.smb

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.SupervisorJob
import java.util.concurrent.atomic.AtomicBoolean

/**
 * SMB 会话探活心跳（修法第 3 条；纯调度逻辑，由 [SmbSessionHeartbeatTest] 用虚拟时间锁定）。
 *
 * 为什么需要它：设备日志里会话是被**服务端**在空闲后作废的（重建间隔1~6 分钟，多数紧跟在
 * 「一段时间没取字节」之后的第一次取数）。App 侧对「会话已死」无感（smbj 的 `Share.isConnected()`
 * 只是本地标志），于是死会话要等**用户的下一次读**才暴露——那一次读付的就是停摆那几秒。
 * 心跳的作用是**让 App 在用户之前撞上死会话**：空闲时主动读一次共享根，触发现成的重建链
 * （`invalidate` → 重建 → `settled` 分批放行），用户再翻页时用到的已经是一条活会话。
 *
 * 三条承重语义：
 * 1. **空闲才探**（[noteActivity]）：间隔内有真实读 ⇒ 会话刚被用过 ⇒ 跳过这次探活，不给会话平白加一条读；
 * 2. **失败退避** 30 → 60 → 120 → 240 → 封顶 300 秒，一次成功即回到 30 秒：服务器不可达时
 *    不该变成每 30 秒一次的失败重连；
 * 3. **探针自己的活动不算用户活动**：探针走的是同一套 `withSession` 链，链路上也会 [noteActivity]——
 *    不排除掉的话心跳会退化成 60 秒一次（用例 `探针自己走的那次读不算用户活动` 钉的就是这条）。
 *
 * 另外每一拍都经 [report] 报一次（打点 3）：真探 / 跳过 / 结果 / 耗时。
 * 为什么必须有它：设备日志里 18:57:43 → 18:58:33 有 50 秒空闲、本该有 1~2 次探活，
 * 而当时**探针成功不产行** ⇒ 无法判断心跳到底跑没跑、有没有探到死会话，那三条结论只能是推测。
 *
 * 生命周期：[start] 由传输层在**会话建立成功之后**喊（幂等，重连成功也喊），[stop] 在传输被释放时喊；
 * [stop] 是**单向**的——释放之后再喊 [start] 为空操作（见 [start]）。
 * 没 start 过就一条都不探——**没被用过的来源不该被心跳拉起来联网**。
 *
 * 探针的失败在这里被吞掉（`runCatching`）：探活是后台保活，失败只该影响退避，不该冒到 UI。
 */
internal class SmbSessionHeartbeat(
    /**
     * 探一次活：读一次共享根（走现成的 `stat` 与同一条 `withSession` 链）；失败抛异常。
     *
     * 返回 `false` = **这一拍什么都没探**（还没有会话 / 在退避窗口里 / 已经释放在拆），不是失败：探针的职责是保活，
     * 不是把一个没被用过的来源拉起来联网（见 `SmbjTransport.probeShareRoot`）。
     */
    private val probe: () -> Boolean,
    /** 探针跑在哪个调度器上（生产 = [Dispatchers.IO]：探针是可能阻塞的来源 I/O） */
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val intervalMs: Long = PROBE_INTERVAL_MS,
    private val maxIntervalMs: Long = MAX_INTERVAL_MS,
    /**
     * 每一拍的上报（打点 3）：`(probed, ok, ms)`——何时真探了、探的结果、花了多久。
     * `probed=false` 时另两个字段无意义（行里也不写）。默认空实现：只关心调度节奏的用例用得上。
     */
    private val report: (probed: Boolean, ok: Boolean, ms: Long) -> Unit = { _, _, _ -> },
    /** 量探针耗时的时间源（可注入：`runTest` 的虚拟时间不驱 [`System.nanoTime`]） */
    private val nanoTime: () -> Long = System::nanoTime,
) {

    /** 上一次 tick 之后有没有真实读动过会话（探针自己的读会被清掉） */
    private val sessionUsed = AtomicBoolean(false)

    /** 循环的宿主；没 [start] 过就是 null（不预先建作用域、不起线程） */
    private var scope: CoroutineScope? = null

    /** 终态：传输被释放（[stop]）之后置位，此后 [start] 不再建循环 */
    private var stopped = false

    /**
     * 记一次「会话被真实读用过」：由传输层在每次进入 `withSession` 时喊。
     * 语义是「这次读一开始会话就（被认为）是活的」，因此下一个 tick 不必再探。
     */
    fun noteActivity() {
        sessionUsed.set(true)
    }

    /**
     * 会话建立成功后启动（重复调用是空操作：一次会话可能建成功后又被重建，那条路径也会喊）。
     *
     * **[stop] 之后是空操作**（终态单向）：`SmbjTransport.close()` 先置 `released` 再 [stop]，而建会话是**慢 I/O**，
     * 一个在置位前就进了 `withSession` 的调用可以在 [stop] 之后才走到这里——那时 `scope` 已被置空，
     * 只判 `scope != null` 会新建作用域把循环复活，而之后没人再喊 [stop]：探针撞上 `released` 会直接返回
     * （`SmbjTransport.probeShareRoot`）⇒ 判成功 ⇒ **每 30 秒空转一次、永不退避**，循环永不结束。
     * 这与 `docs/spec/sources.md` 的「来源实例释放后心跳一并停掉」相抵。
     */
    @Synchronized
    fun start() {
        if (stopped) return
        if (scope != null) return
        val newScope = CoroutineScope(SupervisorJob() + dispatcher)
        scope = newScope
        newScope.launch {
            // wait 是「下一次探活的间隔」：失败翻倍到封顶，成功回到基础间隔
            var wait = intervalMs
            while (isActive) {
                delay(wait)
                if (sessionUsed.getAndSet(false)) {
                    report(false, true, 0L) // 被真实读顶掉：这一拍没探
                    continue // 上一条真实读已经证明会话活着
                }
                val startedNanos = nanoTime()
                val outcome = runCatching { probe() }
                val ms = (nanoTime() - startedNanos) / NANOS_PER_MS
                sessionUsed.set(false) // 探针刚走的 withSession 记的活动是它自己的，不算用户活动
                // 探针抛异常时 `getOrNull()` 是 null：那一次是**真探了**（只是失败了）
                report(outcome.getOrNull() ?: true, outcome.isSuccess, ms)
                wait = if (outcome.isSuccess) intervalMs else minOf(wait * 2, maxIntervalMs)
            }
        }
    }

    /**
     * 传输被释放：停掉循环（重复调用是空操作），并置终态——此后 [start] 不再复活循环。
     * 已经在飞的那次探针不打断，读超时后自然结束。
     */
    @Synchronized
    fun stop() {
        stopped = true
        scope?.cancel()
        scope = null
    }

    companion object {
        /** 探活间隔（口径定的 30 秒：设备日志里最短重建间隔约 1 分钟，30s 在其之下） */
        const val PROBE_INTERVAL_MS: Long = 30_000L

        /** 失败退避的上限（服务器不可达时最多每 5 分钟试一次） */
        const val MAX_INTERVAL_MS: Long = 300_000L

        private const val NANOS_PER_MS: Long = 1_000_000L
    }
}
