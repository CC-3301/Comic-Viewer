package com.cc3301.comicviewer.ui

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import com.cc3301.comicviewer.core.view.ReaderScrollAnchor
import com.cc3301.comicviewer.core.view.readerSavedAspectPages
import java.io.Serializable

/**
 * 本次打开的阅读位置与页比例（spec §重建后的位置恢复）。
 *
 * 挂在**阅读页导航条目**上（与 [ReaderMenuState] 同一层，取法见那里），内容写进该条目的
 * [SavedStateHandle]：
 *
 * - 旋转、深浅色切换这类 Activity 重建：ViewModel 不重建，位置与页比例原样还在；
 * - 进程被系统杀掉：条目的保存态随 Activity 的实例状态落盘、重建时还原 ⇒ 位置与页比例跟着回来，
 *   重建后首帧就按记住的比例摆页高、按记住的位置定位；
 * - 换书（换 entry）与退出阅读器（entry 被弹掉）都换/清掉这份保存态 ⇒ 从「没有位置」开始。
 *
 * 两个页号不是同一个口径：
 * - **位置锚点** [ReaderScrollAnchor.page] 是**顶部可见页**：恢复时它对应列表的首个可见项；
 * - **比例窗口**以**当前页**（视口中线所在的页，与菜单同一口径）为锚取前后各若干页。
 *
 * 位置与页比例是**一份保存值**（[SavedReaderSession]）：要么整份没有（本次打开还没记过位置），
 * 要么一次写全——页号与页内百分比成对，比例表与它自己的页号成对，读的一侧因此不会读到半份。
 *
 * 页比例按页各存一份、**不随位图缓存淘汰**：位图被系统回收后，未解码页仍按真实比例摆高
 * （见 `ReaderScreen` 的占位高与位置换算）。只留**当前页前后各** `READER_SAVED_ASPECT_REACH` 页
 * （够覆盖一屏即可），当前页一动就把范围外的丢掉。
 */
internal class ReaderSessionState(
    private val savedStateHandle: SavedStateHandle,
) : ViewModel() {

    /** 保存值的唯一副本：与 [SavedStateHandle] 同步，写只走 [save] */
    private var session: SavedReaderSession? = savedStateHandle.get(KEY_SESSION)

    /** 条漫的阅读位置（顶部可见页 + 页内百分比）；null = 本次打开还没有记过位置 */
    val scrollAnchor: ReaderScrollAnchor?
        get() = session?.let { ReaderScrollAnchor(page = it.page, percent = it.percent) }

    /** 这一页记住的比例；null = 本次打开里没画出来过（也不在保存态里） */
    fun pageAspect(index: Int): Float? = session?.pageAspects?.get(index)

    /**
     * 记位置（顶部可见页 + 页内百分比），并按**当前页**（视口中线所在的页）裁剪比例窗口。
     */
    fun recordScrollAnchor(anchor: ReaderScrollAnchor, currentPage: Int) {
        val window = readerSavedAspectPages(currentPage)
        val aspects = session?.pageAspects ?: hashMapOf()
        aspects.keys.retainAll { it in window }
        save(SavedReaderSession(anchor.page, anchor.percent, currentPage, aspects))
    }

    /**
     * 记这一页真实画出来的比例；窗口以保存值里的当前页为准。
     *
     * 还没有位置时不记：位置与比例是同一份保存值，位置还没有就没有可恢复的东西，比例也无处用。
     */
    fun recordPageAspect(index: Int, aspect: Float) {
        val current = session ?: return
        if (index !in readerSavedAspectPages(current.currentPage)) return
        current.pageAspects[index] = aspect
        save(current)
    }

    /** 保存值的唯一写入点：一份写全，页号 / 百分比 / 当前页 / 比例表不分开成键 */
    private fun save(value: SavedReaderSession) {
        session = value
        savedStateHandle[KEY_SESSION] = value
    }

    private companion object {
        const val KEY_SESSION = "reader.session"
    }
}

/**
 * 一次打开的完整保存值：位置（顶部可见页 + 页内百分比）、比例窗口的锚（当前页）与页号 → 页比例。
 *
 * 四项放在同一个对象里落保存态，不存在「读到页号却读不到百分比」「读到比例却没有页号」的中间态。
 */
private data class SavedReaderSession(
    val page: Int,
    val percent: Float,
    val currentPage: Int,
    val pageAspects: HashMap<Int, Float>,
) : Serializable
