package com.cc3301.comicviewer.ui

import androidx.lifecycle.ViewModel
import com.cc3301.comicviewer.core.view.ReaderScrollAnchor

/**
 * 本次打开的阅读位置与页比例（spec §重建后的位置恢复）。
 *
 * 挂在**阅读页导航条目**上（与 [ReaderMenuState] 同一层，取法见那里）：
 *
 * - 旋转、深浅色切换这类 Activity 重建不重建 ViewModel ⇒ 位置与页比例跟着回来，
 *   重建后首帧就能按记住的页比例摆好页高，再按百分比恢复位置；
 * - 换书（换 entry）与退出阅读器（entry 被弹掉）都换/清掉这份状态 ⇒ 从「没有位置」开始；
 * - 进程结束不恢复：不做保存态，也不落盘。
 *
 * 页比例按页各存一份，且**不随位图缓存淘汰**：位图被系统回收后，未解码页仍按真实比例摆高
 * （见 `ReaderScreen` 的占位高与位置换算）。
 */
internal class ReaderSessionState : ViewModel() {

    /** 条漫的阅读位置（顶部可见页 + 页内百分比）；null = 本次打开还没有记过 */
    var scrollAnchor: ReaderScrollAnchor? = null

    /** 页号 → 该页真实显示比例（宽 / 高）：真的画出图时记一次 */
    private val pageAspects = mutableMapOf<Int, Float>()

    /** 这一页记住的比例；null = 本次打开里还没画出来过 */
    fun pageAspect(index: Int): Float? = pageAspects[index]

    fun recordPageAspect(index: Int, aspect: Float) {
        pageAspects[index] = aspect
    }
}
