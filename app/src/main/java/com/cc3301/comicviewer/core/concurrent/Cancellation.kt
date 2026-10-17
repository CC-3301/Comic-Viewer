package com.cc3301.comicviewer.core.concurrent

import kotlinx.coroutines.CancellationException

/**
 * `runCatching` 的取消安全版（纯函数，由 `CancellationTest` 锁定）：
 * 只吞非取消异常，`CancellationException` 原样抛出。
 *
 * `runCatching { withContext(...) { … } }` 会把 `withContext` 进出时抛出的 `CancellationException`
 * 一起吞掉，于是「组合已销毁/配置变更」被当成「会话建不起来」处理，调用方继续往下走导航与写库。
 * 「取消照常传播」的入口只有这一处：启动路径、取数路径与各来源适配器都改用它。
 */
internal inline fun <T> catchingNonCancellation(block: () -> T): Result<T> =
    runCatching(block).onFailure { if (it is CancellationException) throw it }

/**
 * 取消不是来源故障（异常归类器共用这一条判据）：取消原样上抛，其余什么都不做。
 * 各来源的 `asXException` 都从这里取判据，而不是各写一遍 `if (t is CancellationException) throw t`。
 *
 * 「取消不当失败」在本仓库有三种写法，按用途选：
 * - [catchingNonCancellation]：整段包住，取消原样上抛、其余收进 [Result]；
 * - [throwIfCancellation]：异常归类器里的一句判据；
 * - 裸 `catch (c: CancellationException) { …; throw c }`：**只**在取消与失败要落不同打点时才用
 *   （取消不是失败，不能并进同一条失败分支）；两者处理相同时不写它。
 */
internal fun Throwable.throwIfCancellation() {
    if (this is CancellationException) throw this
}
