package com.cc3301.comicviewer.core.view

import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ensureActive

/**
 * **同键在飞登记表**（纯内存状态）：同一键在飞时只登记一次。
 *
 * 它只管「谁在飞」，不合并结果——用在**发起方自己就是唯一取数方**的地方（封面预取的派发登记：
 * 同一批 id 在窗口变化时不该被重复发给来源）。要合并结果用 [InFlightRequests]。
 */
internal class InFlight<K : Any> {

    private val keys = ConcurrentHashMap.newKeySet<K>()

    /** 键不在飞则登记并返回 true；已在飞返回 false（调用方据此跳过） */
    fun claim(key: K): Boolean = keys.add(key)

    /** 摘牌（成功、失败、取消各路径都要摘，否则这一键本会话再不会被派发） */
    fun release(key: K) {
        keys.remove(key)
    }
}

/**
 * 同一键的**在飞合并**（纯内存状态，由 `InFlightRequestsTest` 锁定）。
 *
 * 为什么需要它（设备现象：点进子文件夹，封面要等一小会才出来）：浏览页的**预取**与**可见行**会同时要同一张
 * 封面的字节——预取按可见区 ±1 屏（含可见区）在进入目录那一帧就发，可见行的 `CoverThumb` 也在同一帧发；
 * 来源侧的 `coverBytes` 只有**结果**缓存、没有在飞去重，于是同一张封面在 SMB/WebDAV 上被取**两遍**（每遍都是
 * 「resolve + 读字节」的往返）。慢来源上这两遍互相挤占带宽，首屏反而更慢。
 *
 * 并发闸：[load] 的**取数那一段**过 [gate]（同时最多 [CoverByteGate.MAX_CONCURRENT_BYTE_LOADS] 条在飞，
 * 可见格优先取牌、预取排队让位）。为什么闸放这里：本入口已经是「界面要这一条数据」的唯一通道
 * （可见行与预取共同经过），在飞合并也在它手里——同一键被两方同时要时只会算一次取数，
 * 闸因此数的是**真的发往来源的请求条数**，不是调用次数。**解码不进这道闸**（调用方在 [load] 返回后才解）。
 *
 * 语义（与调用方的取数契约一致）：
 * - 同一键的并发调用**只执行一次** [load]，其余调用者拿到同一份结果（含 null = 本次取不到）；
 * - **不缓存结果**：调用结束即从在飞表里移除（结果缓存是来源自己的事，本类不重复一份，
 *   否则又会与它的淘汰口径漂移）；
 * - 主人的失败/取消**不当成等待方的结果**：等待方在自己的协程仍存活时自己再取一遍；主人失败时**先摘牌再唤醒**
 *   （否则等待方会对同一张已完成失败的 deferred 反复 `await()` 而短时自旋）；
 * - 等待方自己被取消时照常传播 `CancellationException`（`core/concurrent/Cancellation.kt` 口径）。
 *
 * 键与结果都带型参：封面侧是 `InFlightRequests<String, ByteArray?>`（可见行与预取共用一次来源往返），
 * 页侧是 `InFlightRequests<String, PageBytesFetched>`（一页被阅读页与阅读菜单预览格同时需要时只向来源取一次，
 * 见 `ui/PageBytes` 的 `requests`）。
 */
internal class InFlightRequests<K : Any, T>(
    /** 取数的并发闸：与在飞合并同一作用域——界面每进一层容器 new 一个入口实例，闸跟着它 */
    private val gate: CoverByteGate = CoverByteGate(),
) {

    private val inFlight = ConcurrentHashMap<K, CompletableDeferred<T>>()

    /**
     * 取 [key] 的数据：在飞时并入同一次请求，否则由本次调用执行 [load]。
     * [load] 的返回值（含 null）就是本次的结果，原样返回给所有等待方。
     *
     * [priority] 只决定**排队序**：可见格优先取牌、预取让位；已经在飞的那次不被抢占。
     */
    suspend fun load(key: K, priority: CoverBytePriority, load: suspend () -> T): T {
        while (true) {
            val shared = inFlight[key]
            if (shared != null) {
                try {
                    return shared.await()
                } catch (t: Throwable) {
                    // 共享的那一次失败/被取消：自己没被取消就再取一遍（不把别人的取消当自己的结果）
                    coroutineContext.ensureActive()
                }
            }
            val mine = CompletableDeferred<T>()
            if (inFlight.putIfAbsent(key, mine) != null) continue
            try {
                val ctx = coroutineContext
                val value = gate.withPermit(priority) {
                    // 取牌与取消可能撞在一起（闸上已把牌交给本次调用，而调用方已不需要它了）：
                    // 先把牌还回去再走，别把一次已没人要的下行真发出去（该分支的牌子交接在 `CoverByteGate.acquire`）。
                    ctx.ensureActive()
                    load()
                }
                mine.complete(value)
                return value
            } catch (t: Throwable) {
                // **先摘牌再唤醒**：否则等待方会读到同一张已完成失败的 deferred、
                // 反复 `await()`（不再挂起、立即抛）而短时自旋；摘牌在前则等待方只会看到「表里没有」⇒ 自己成为主人。
                inFlight.remove(key, mine)
                mine.completeExceptionally(t)
                throw t
            } finally {
                inFlight.remove(key, mine) // 成功路径的摘牌（失败路径已在上面摘过，这里是幂等的）
            }
        }
    }
}
