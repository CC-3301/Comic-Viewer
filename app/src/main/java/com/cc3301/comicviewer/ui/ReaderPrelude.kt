package com.cc3301.comicviewer.ui

import com.cc3301.comicviewer.core.source.BookHandle
import com.cc3301.comicviewer.core.source.BookOpening
import com.cc3301.comicviewer.core.source.Source
import com.cc3301.comicviewer.core.source.openBookAtLanding
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.withTimeoutOrNull

/**
 * 打开书的前置槽（票 #108 E1-A）：书柜页点击时预打开的结果，交给阅读页**同步**取走。
 *
 * 维护者现象：点开一本书先看到黑底「准备打开」整页，然后才出现图片。口径是**留在书柜页等**——
 * 点击后不切页，先在书柜页把书打开、把首帧解码完，就绪后一次性切进阅读页（等待期间不做任何提示条/toast/遮罩）。
 * 因此「已打开」这件事必须在两层之间传一次：写入口在书柜页的点击路径，读出口在 `ReaderScreen` 的组合期。
 *
 * 键是**连接 id + 书 id**（票 #110）：书 id 只在对应连接内有效（见 `ServiceLocator` 的 currentConnId 契约），
 * 只按书 id 认主的话，先在来源 A 点开编号 X 的书（前置还没取走就被取消/超时），再到来源 B 点开编号也是 X
 * 的书，B 的阅读页会取走 A 的句柄（页数/正文来自另一个库）。带连接 id 后旧连接的前置不被新连接取走。
 *
 * 只认**同一本书**：`take` 拿到别的连接或别的书 id 时返回 null 且不清槽（导航参数与槽位错配时宁可走一次
 * 正常打开，也不能把 A 的句柄交给 B 的阅读页——句柄带页数，错交会直接读错书）。
 * *
 * 单槽即可：切页前用户还在书柜页，第二次点击只会覆盖第一次（连点同一本不重启，见 `BrowserScreen` 的点击闸）。
 */
internal class ReaderPrelude {

    private var pending: Pair<Pair<Long, String>, BookOpening>? = null

    /** 记下一次预打开的结果（书柜页侧）：键 = 连接 id + 书 id（票 #110） */
    fun put(connId: Long, bookId: String, opening: BookOpening) {
        pending = (connId to bookId) to opening
    }

    /**
     * 取走某连接下某本书的预打开结果：取到即清槽（同一本书只兑现一次），连接或书 id 任一不匹配时
     * 返回 null 且**保留**槽位。
     */
    fun take(connId: Long, bookId: String): BookOpening? {
        val slot = pending ?: return null
        if (slot.first.first != connId || slot.first.second != bookId) return null
        pending = null
        return slot.second
    }
}

/**
 * 点击一本书时的打开前置（票 #108 E1-A，由 [ReaderPreludeTest] 锁定）：**先开书、再按落点解「首批」若干页**。
 *
 * 返回值与阅读页自己打开时同一个类型 [BookOpening]（落点由 [openBookAtLanding] 算），因此阅读页拿到它
 * 就能直接开画，不必再跑一遍打开（省掉的就是原来那段黑底「准备打开」）。
 *
 * **不落地进度**（票 #110）：前置跑的这段时间里用户随时可能改点另一本 / 返回 / 切走（= 取消，书没被打开），
 * 打开瞬间就写会把这本书记成读了第 1 页。那一步改由阅读页取走前置时调 `commitOpeningProgress` 落地。
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

/** 打开前置的等待上限（毫秒，票 #108 r5）：见 [awaitReaderPrelude]。 */
internal const val PRELUDE_TIMEOUT_MILLIS: Long = 1_500

/**
 * 前置的**有界等待 + 放行**（票 #108 r5，r6 拆开等待与工作；由 [ReaderPreludeTest] 锁定）：把 [preload] 跑在
 * [timeoutMillis] 之内。**等待**与**工作**分开：
 *
 * 为什么必须把两者拆开（评审 r5 P1-1）：`withTimeoutOrNull` 只靠协程**取消**生效，而取消只在**挂起点**被观察。
 * 把上限包在「工作」身上时，工作体一旦是**阻塞**调用（Komga 的 OkHttp `execute()` 期间协程在运行、不在挂起），
 * 取消要等到阻塞调用自己返回才生效—— OkHttp 的 `callTimeout` 是 90s，用户依旧「点了没反应」。
 * 因此工作在 [workScope] 里跑，本函数只包住 `deferred.await()` 这个**可取消挂起点**：
 * 任何来源的阻塞体都拦不住 1.5s 放行；到点后后台工作**不取消**，继续把字节/位图填进缓存（不浪费）。
 *
 * 放行规则（真机现象：点开一本书有几率**卡在书柜不动**，多发生在 SMB/WebDAV）：
 * - 就绪 → 先交句柄（[onReady]）再导航；
 * - 前置抛错 / 超时 / 拿不到（返回 null） → 照常导航：前置只是「让人在书柜页多等一会首帧」的优化，
 *   进阅读页后那里有自己的加载态、失败提示与重试；
 * - **被取消 → 不导航**（与 `ui/Cancellation.kt` 票 #26 与 `docs/SPEC.md:208` 的仓库口径一致：取消照常传播，
 *   不在取消后做任何导航）。本函数的取消只可能来自两处：① 被后一次点击顶替（新请求自己会导航）；
 *   ② 已离开组合（切 tab / 返回上一页 / 配置变更）——两处都不该把人拉进阅读页。
 *
 * [isRequestCurrent] 是第二道守卫（组合仍存活 + 仍是当前那次点击）：取消已经挡住了上面两处，
 * 这一道防的是「取消还没送达、导航已经执行」的窄窗口（`DisposableEffect` 的存活标志比 effect 取消更早可见）。
 * 调用方在本函数返回后**不得再挂起**（组合可能已销毁）——放行动作只做非挂起的事（导航、置状态）。
 */
internal suspend fun awaitReaderPrelude(
    /** 前置工作的作用域（调用方给组合作用域：随页面销毁取消，工作因此不会泄漏） */
    workScope: CoroutineScope,
    timeoutMillis: Long,
    preload: suspend () -> BookOpening?,
    onReady: (BookOpening) -> Unit,
    isRequestCurrent: () -> Boolean,
    navigate: () -> Unit,
) {
    val work = workScope.async {
        // 前置失败**不**让 deferred 失败：否则结构化并发会把它抛给 [workScope]（生产 = 组合作用域），
        // 一次 SMB 断链会把浏览页的整个作用域连坐取消（r6 用例「前置抛错也放行」实测到的真问题）
        try {
            preload()
        } catch (t: CancellationException) {
            throw t
        } catch (t: Throwable) {
            null // 前置失败不是错误：照常放行，阅读页有自己的失败提示与重试
        }
    }
    // 等待侧是**可取消挂起点**：上限对任何来源都成立（前置体是不是阻塞都不影响），到点后工作不被取消
    val opening = withTimeoutOrNull(timeoutMillis) { work.await() }
    if (opening != null) onReady(opening)
    if (isRequestCurrent()) navigate()
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
