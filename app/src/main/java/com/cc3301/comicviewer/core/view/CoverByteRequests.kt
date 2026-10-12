package com.cc3301.comicviewer.core.view

import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ensureActive

/**
 * 同一 id 的封面**字节请求在飞合并**（纯内存状态，由 [CoverByteRequestsTest] 锁定）。
 *
 * 为什么需要它（设备现象：点进子文件夹，封面要等一小会才出来）：浏览页的**预取**与**可见行**会同时要同一张
 * 封面的字节——预取按可见区 ±1 屏（含可见区）在进入目录那一帧就发，可见行的 `CoverThumb` 也在同一帧发；
 * 来源侧的 `coverBytes` 只有**结果**缓存、没有在飞去重，于是同一张封面在 SMB/WebDAV 上被取**两遍**（每遍都是
 * 「resolve + 读字节」的往返）。慢来源上这两遍互相挤占带宽，首屏反而更慢。
 *
 * 并发闸：[load] 的**取字节那一段**过 [gate]（同时最多 [CoverByteGate.MAX_CONCURRENT_BYTE_LOADS] 张在飞，
 * 可见格优先取牌、预取排队让位）。为什么闸放这里：本入口已经是「界面要这一条封面字节」的唯一通道
 * （可见行与预取共同经过），在飞合并也在它手里——同一张封面被两方同时要时只会算一次取字节，
 * 闸因此数的是**真的发往来源的请求条数**，不是调用次数。**解码不进这道闸**（调用方在 [load] 返回后才解）。
 *
 * 语义（与 `Source.coverBytes` 的契约一致）：
 * - 同一 id 的并发调用**只执行一次** [load]，其余调用者拿到同一份结果（含 null = 本次取不到）；
 * - **不缓存结果**：调用结束即从在飞表里移除（结果缓存是来源自己的 `CoverByteCache`，本类不重复一份，
 *   否则又会与它的淘汰口径漂移）；
 * - 主人的失败/取消**不当成等待方的结果**：等待方在自己的协程仍存活时自己再取一遍；主人失败时**先摘牌再唤醒**
 *   （否则等待方会对同一张已完成失败的 deferred 反复 `await()` 而短时自旋）；
 * - 等待方自己被取消时照常传播 `CancellationException`（`ui/Cancellation.kt` 口径）。
 *
 * 同一套形状也用在**页字节**取数上（`ui/PageDecoder` 的 `pageRequests`，闸位 `PAGE_MAX_CONCURRENT_LOADS`）：
 * 一页被阅读页与阅读菜单预览格同时需要时只向来源取一次。结果因此带型参 [T]（页侧要连量测一起交给两方，
 * 见 `ui/PageDecoder.PageBytesFetched`），封面那侧填 `ByteArray?`。
 */
internal class CoverByteRequests<T>(
    /** 取字节的并发闸：与在飞合并同一作用域——界面每进一层容器 new 一个入口实例，闸跟着它 */
    private val gate: CoverByteGate = CoverByteGate(),
) {

    private val inFlight = ConcurrentHashMap<String, CompletableDeferred<T>>()

    /**
     * 取 [entryId] 的封面字节：在飞时并入同一次请求，否则由本次调用执行 [load]。
     * [load] 的返回值（含 null）就是本次的结果，原样返回给所有等待方。
     *
     * [priority] 只决定**排队序**：可见格优先取牌、预取让位；已经在飞的那次不被抢占。
     */
    suspend fun load(entryId: String, priority: CoverBytePriority, load: suspend () -> T): T {
        while (true) {
            val shared = inFlight[entryId]
            if (shared != null) {
                try {
                    return shared.await()
                } catch (t: Throwable) {
                    // 共享的那一次失败/被取消：自己没被取消就再取一遍（不把别人的取消当自己的结果）
                    coroutineContext.ensureActive()
                }
            }
            val mine = CompletableDeferred<T>()
            if (inFlight.putIfAbsent(entryId, mine) != null) continue
            try {
                val ctx = coroutineContext
                val bytes = gate.withPermit(priority) {
                    // 取牌与取消可能撞在一起（闸上已把牌交给本次调用，而调用方已不需要它了）：
                    // 先把牌还回去再走，别把一次已没人要的下行真发出去（该分支的牌子交接在 `CoverByteGate.acquire`）。
                    ctx.ensureActive()
                    load()
                }
                mine.complete(bytes)
                return bytes
            } catch (t: Throwable) {
                // **先摘牌再唤醒**：否则等待方会读到同一张已完成失败的 deferred、
                // 反复 `await()`（不再挂起、立即抛）而短时自旋；摘牌在前则等待方只会看到「表里没有」⇒ 自己成为主人。
                inFlight.remove(entryId, mine)
                mine.completeExceptionally(t)
                throw t
            } finally {
                inFlight.remove(entryId, mine) // 成功路径的摘牌（失败路径已在上面摘过，这里是幂等的）
            }
        }
    }
}
