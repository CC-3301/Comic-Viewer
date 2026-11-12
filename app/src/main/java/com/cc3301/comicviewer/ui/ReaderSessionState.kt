package com.cc3301.comicviewer.ui

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import com.cc3301.comicviewer.core.view.ReaderScrollAnchor
import com.cc3301.comicviewer.core.view.readerSavedAspectPages

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
 * 页比例按页各存一份、**不随位图缓存淘汰**：位图被系统回收后，未解码页仍按真实比例摆高
 * （见 `ReaderScreen` 的占位高与位置换算）。只留**锚点页前后各** `READER_SAVED_ASPECT_REACH` 页
 * （够覆盖一屏即可），锚点一动就把范围外的丢掉。
 */
internal class ReaderSessionState(
    private val savedStateHandle: SavedStateHandle,
) : ViewModel() {

    /** 页号 → 该页真实显示比例（宽 / 高）：真的画出图时记一次，范围见 [readerSavedAspectPages] */
    private val pageAspects: MutableMap<Int, Float> = savedPageAspects()

    /** 条漫的阅读位置（顶部可见页 + 页内百分比）；null = 本次打开还没有记过 */
    var scrollAnchor: ReaderScrollAnchor?
        get() = savedStateHandle.get<Int>(KEY_ANCHOR_PAGE)?.let { page ->
            ReaderScrollAnchor(page = page, percent = savedStateHandle.get<Float>(KEY_ANCHOR_PERCENT) ?: 0f)
        }
        set(value) {
            if (value == null) {
                savedStateHandle.remove<Int>(KEY_ANCHOR_PAGE)
                savedStateHandle.remove<Float>(KEY_ANCHOR_PERCENT)
                return
            }
            savedStateHandle[KEY_ANCHOR_PAGE] = value.page
            savedStateHandle[KEY_ANCHOR_PERCENT] = value.percent
            retainAround(value.page)
        }

    /** 这一页记住的比例；null = 本次打开里没画出来过（也不在保存态里） */
    fun pageAspect(index: Int): Float? = pageAspects[index]

    fun recordPageAspect(index: Int, aspect: Float) {
        // 还没有位置时窗口未知，先留着；位置一出现就按窗口裁一次
        savedStateHandle.get<Int>(KEY_ANCHOR_PAGE)?.let { anchorPage ->
            if (index !in readerSavedAspectPages(anchorPage)) return
        }
        pageAspects[index] = aspect
        writePageAspects()
    }

    /** 锚点一动就把范围外的比例丢掉：保存态里不留用不到的页 */
    private fun retainAround(anchorPage: Int) {
        val window = readerSavedAspectPages(anchorPage)
        if (pageAspects.keys.all { it in window }) return
        pageAspects.keys.retainAll { it in window }
        writePageAspects()
    }

    private fun writePageAspects() {
        savedStateHandle[KEY_PAGE_ASPECTS] = pageAspects.keys.toIntArray()
        savedStateHandle[KEY_PAGE_ASPECT_VALUES] = pageAspects.values.toFloatArray()
    }

    private fun savedPageAspects(): MutableMap<Int, Float> {
        val pages = savedStateHandle.get<IntArray>(KEY_PAGE_ASPECTS) ?: return mutableMapOf()
        val values = savedStateHandle.get<FloatArray>(KEY_PAGE_ASPECT_VALUES) ?: return mutableMapOf()
        val aspects = mutableMapOf<Int, Float>()
        pages.forEachIndexed { index, page -> values.getOrNull(index)?.let { aspects[page] = it } }
        return aspects
    }

    private companion object {
        const val KEY_ANCHOR_PAGE = "reader.anchorPage"
        const val KEY_ANCHOR_PERCENT = "reader.anchorPercent"
        const val KEY_PAGE_ASPECTS = "reader.pageAspects"
        const val KEY_PAGE_ASPECT_VALUES = "reader.pageAspectValues"
    }
}
