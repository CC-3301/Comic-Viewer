package com.cc3301.comicviewer.ui

import kotlinx.coroutines.CancellationException

/**
 * `runCatching` 的取消安全版（票 25 第 1 项，纯函数，由 [CancellationTest] 锁定）：
 * 只吞非取消异常，`CancellationException` 原样抛出。
 *
 * `runCatching { withContext(...) { … } }` 会把 `withContext` 进出时抛出的 `CancellationException`
 * 一起吞掉，于是「组合已销毁/配置变更」被当成「会话建不起来」处理，调用方继续往下走导航与写库
 * （票 #26 登记项）。启动路径的五处 `runCatching` 都换成它，「取消照常传播」才是结构性的。
 */
internal inline fun <T> catchingNonCancellation(block: () -> T): Result<T> =
    runCatching(block).onFailure { if (it is CancellationException) throw it }
