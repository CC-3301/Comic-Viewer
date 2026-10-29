package com.cc3301.comicviewer.core.view

/**
 * 「本次打开里第一次真的画出图」的页（纯内存状态，由 [ReaderPageFirstAppearanceTest] 锁定）。
 *
 * 页图淡入只给首次画出图的页：回看 / 重进（条目被回收后重新组合）直接上屏。
 * 领取时机是**位图到手那一刻**（不是组合期）：被预组合、或组合过却没上屏的页不算。
 * 记录活在阅读器这一次打开里（按书分槽）：换书 = 新的一次打开，上一本的记录不带过来。
 */
internal class ReaderPageFirstAppearance {

    private val seen = mutableSetOf<Int>()

    /** 这一页是本次打开里第一次真的画出图吗（第一次返回 true，之后恒 false） */
    fun claim(index: Int): Boolean = seen.add(index)
}
