package com.cc3301.comicviewer.core.view

import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ensureActive

/**
 * 同一 id 的封面**字节请求在飞合并**（票 #108 r6，纯内存状态，由 [CoverByteRequestsTest] 锁定）。
 *
 * 为什么需要它（真机现象：点进子文件夹，封面要等一小会才出来）：浏览页的**预取**与**可见行**会同时要同一张
 * 封面的字节——预取按可见区 ±1 屏（含可见区）在进入目录那一帧就发，可见行的 `CoverThumb` 也在同一帧发；
 * 来源侧的 `coverBytes` 只有**结果**缓存、没有在飞去重，于是同一张封面在 SMB/WebDAV 上被取**两遍**（每遍都是
 * 「resolve + 读字节」的往返）。慢来源上这两遍互相挤占带宽，首屏反而更慢。
 *
 * 语义（与 `Source.coverBytes` 的契约一致）：
 * - 同一 id 的并发调用**只执行一次** [load]，其余调用者拿到同一份结果（含 null = 本次取不到）；
 * - **不缓存结果**：调用结束即从在飞表里移除（结果缓存是来源自己的 `CoverByteCache`，本类不重复一份，
 *   否则又会与它的淘汰口径漂移——票 #108 r4 的教训）；
 * - 主人的失败/取消**不当成等待方的结果**：等待方在自己的协程仍存活时自己再取一遍；
 * - 等待方自己被取消时照常传播 `CancellationException`（`ui/Cancellation.kt` 票 #26 口径）。
 */
internal class CoverByteRequests {

    private val inFlight = ConcurrentHashMap<String, CompletableDeferred<ByteArray?>>()

    /**
     * 取 [entryId] 的封面字节：在飞时并入同一次请求，否则由本次调用执行 [load]。
     * [load] 的返回值（含 null）就是本次的结果，原样返回给所有等待方。
     */
    suspend fun load(entryId: String, load: suspend () -> ByteArray?): ByteArray? {
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
            val mine = CompletableDeferred<ByteArray?>()
            if (inFlight.putIfAbsent(entryId, mine) != null) continue
            try {
                val bytes = load()
                mine.complete(bytes)
                return bytes
            } catch (t: Throwable) {
                mine.completeExceptionally(t)
                throw t
            } finally {
                inFlight.remove(entryId, mine)
            }
        }
    }
}
