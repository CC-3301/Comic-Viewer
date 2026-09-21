package com.cc3301.comicviewer.ui

import com.cc3301.comicviewer.core.source.BookHandle
import com.cc3301.comicviewer.core.source.BookOpening
import com.cc3301.comicviewer.core.source.Source
import com.cc3301.comicviewer.core.source.openForReading

/**
 * 打开书的前置槽（票 #108 E1-A）：书柜页点击时预打开的结果，交给阅读页**同步**取走。
 *
 * 维护者现象：点开一本书先看到黑底「准备打开」整页，然后才出现图片。口径是**留在书柜页等**——
 * 点击后不切页，先在书柜页把书打开、把首帧解码完，就绪后一次性切进阅读页（等待期间不做任何提示条/toast/遮罩）。
 * 因此「已打开」这件事必须在两层之间传一次：写入口在书柜页的点击路径，读出口在 `ReaderScreen` 的组合期。
 *
 * 只认**同一本书**：`take` 拿到别的书 id 时返回 null 且不清槽（导航参数与槽位错配时宁可走一次正常打开，
 * 也不能把 A 的句柄交给 B 的阅读页——句柄带页数，错交会直接读错书）。
 *
 * 单槽即可：切页前用户还在书柜页，第二次点击只会覆盖第一次（连点同一本不重启，见 `BrowserScreen` 的点击闸）。
 */
internal class ReaderPrelude {

    private var pending: Pair<String, BookOpening>? = null

    /** 记下一次预打开的结果（书柜页侧） */
    fun put(bookId: String, opening: BookOpening) {
        pending = bookId to opening
    }

    /** 取走某本书的预打开结果：取到即清槽（同一本书只兑现一次），不是这本书返回 null 且保留槽位 */
    fun take(bookId: String): BookOpening? {
        val slot = pending ?: return null
        if (slot.first != bookId) return null
        pending = null
        return slot.second
    }
}

/**
 * 点击一本书时的打开前置（票 #108 E1-A，由 [ReaderPreludeTest] 锁定）：**先开书、再按落点解码首帧**。
 *
 * 返回值与阅读页自己打开时同一个类型 [BookOpening]（落点与「开启即覆盖进度」的写都由 [openForReading] 算），
 * 因此阅读页拿到它就能直接开画，不必再跑一遍打开（省掉的就是原来那段黑底「准备打开」）。
 *
 * 首帧解码走注入的 [decodePage]（生产 = `PageDecoder.decodePage`，按阅读页同一个目标宽度解码、**进同一个
 * 解码缓存**；书柜页不自己画这张图）。解码失败只吞掉并照常返回前置——打开成功但首帧算不出来时，
 * 正确结果是「照常进阅读页」，而不是把人留在书柜页（那里没有任何提示位）。
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
    val opening = openForReading(source, bookId, alwaysFirstPage)
    if (opening.handle.pageCount > 0) {
        catchingNonCancellation { decodePage(opening.handle, opening.startIndex, targetWidthPx) }
    }
    return opening
}
