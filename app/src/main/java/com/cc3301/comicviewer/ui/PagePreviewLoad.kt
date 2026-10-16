package com.cc3301.comicviewer.ui

import androidx.compose.ui.graphics.ImageBitmap
import com.cc3301.comicviewer.core.concurrent.RetryOutcome
import com.cc3301.comicviewer.core.concurrent.catchingNonCancellation
import com.cc3301.comicviewer.core.concurrent.driveRetries
import com.cc3301.comicviewer.core.source.BookHandle
import com.cc3301.comicviewer.core.view.coverRetryPolicy
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * 阅读菜单预览格的取解（有界重试驱动，由 `PagePreviewLoadTest` 锁定）。
 *
 * 为什么要重试：来源对「真的没有这一页」与「这一次取不到」给的是同一个结果，不重试的话一次失败就是
 * **一格永远空着**——格子还在屏上、键也没变，就再没有第二次。口径与封面**同一套**
 * （[coverRetryPolicy]：首发 1 次 + 重试 3 次，退避 500ms → 1.5s → 4s，各 ±25% 抖动），
 * 策略只在那一处声明；「字节到手但解不出来」与封面一样算确定性失败（[RetryOutcome.Fatal]），不重试。
 *
 * 停止条件：取解与 [await] 抛出的取消异常**原样上抛**——换书、离开阅读器（条子作用域整体取消）走这条路；
 * 格子滚走**不再**取消取解（见 `PreviewThumbLoads`：让它跑完存下来），只由注入的 [await] 变成「不再退避重试」；
 * 每次重试前先查内存里有没有已经解好的那张（[PageDecoder.decodePageByHeight] 开头就查，
 * 因此别人解好的那张不会白重试）；4 次用完仍取不到就返回 [RetryOutcome.Retryable]，
 * 格子保持空白（底透明，没图就是一格空的，不再是一次定生死）。内存命中不产 `pageAttempt` 行
 * （一行 = 一次真取数，判读口径见 `PageDecoder.loadPageBytes`）。
 *
 * 反复尝试直到拿到图 / 次数用完；[await] 是两次之间的等待，用例注入空实现即可在真实时间上瞬跑。
 */
internal suspend fun loadPreviewPageWithRetry(
    handle: BookHandle,
    index: Int,
    decodeHeightPx: Int,
    await: suspend (Long) -> Unit = { delay(it) },
): RetryOutcome<ImageBitmap> = driveRetries(
    policy = coverRetryPolicy,
    attempt = { attemptNumber -> loadPreviewPageOnce(handle, index, decodeHeightPx, attemptNumber) },
    await = await,
)

/** 第 [attempt] 次尝试：按高度取解（内存命中直接返回），并按「字节有没有到手」给出三态结论 */
private suspend fun loadPreviewPageOnce(
    handle: BookHandle,
    index: Int,
    decodeHeightPx: Int,
    attempt: Int,
): RetryOutcome<ImageBitmap> {
    // 「这一格需要图」那一刻在主线程侧取：`waitMs` 因此含协程派发与等闸（口径见 `PageDecoder.loadPageBytes`）
    val askedNanos = System.nanoTime()
    // 字节有没有到手是重试的判据（同 `RetryOutcome`）：没有就值得重试，到手而解不出来是确定性的
    var bytesArrived = false
    val bitmap = withContext(Dispatchers.IO) {
        // 取消安全：取消不是「这一页没有图」——裸 `runCatching` 会把它吞成 null，于是格子永远空着
        catchingNonCancellation {
            PageDecoder.decodePageByHeight(handle, index, decodeHeightPx) {
                val bytes = PageDecoder.loadPageBytes(
                    handle,
                    index,
                    PageLoadTrace(PageLoadSource.Preview, attempt, askedNanos),
                )
                bytesArrived = true
                bytes
            }
        }.getOrNull()
    }
    return when {
        bitmap != null -> RetryOutcome.Loaded(bitmap)
        // 字节没到手（来源抛了异常 / 这次取不到）：值得重试，次数由 [coverRetryPolicy] 兜
        !bytesArrived -> RetryOutcome.Retryable
        // 字节到手但解不出来：确定性的，重试只是白花三次请求
        else -> RetryOutcome.Fatal
    }
}
