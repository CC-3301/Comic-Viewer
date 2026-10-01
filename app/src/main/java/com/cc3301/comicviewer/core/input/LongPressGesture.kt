package com.cc3301.comicviewer.core.input

/**
 * 长按判定（长按顶栏「排序」按钮 = 浏览列表回顶部，点按仍是开菜单；纯逻辑，由 [LongPressGestureTest] 锁定）。
 *
 * **它只判一件事：这串按下按够久了没。** 时间从事件自带的 `uptimeMillis` 来（[down] / [advance]），
 * 而「按住不动、指针不再送事件」那一小段由调用方的超时补（`withTimeoutOrNull(waitMillis) { awaitPointerEvent() }`
 * 超时 ⇒ [onTimeout]）——两路通向同一个判定，因此按住不动与一边按一边挪都能成立。
 *
 * **点按与长按的分界就是本类的状态**：[longPressed] 为 false 时调用方**一个事件都不消费** ⇒ 点按照旧落到
 * 内层按钮的 `onClick` 上（开菜单）；一旦成立，调用方消费掉这一串按下的剩余部分（含最后那一下抬起）
 * ⇒ 内层按钮的点按作废（长按不弹菜单）。分界值由调用方给（平台量 `ViewConfiguration.longPressTimeoutMillis`），
 * 本类不写死任何一个时间常量。
 */
internal class LongPressGesture(private val timeoutMillis: Long) {

    private var downAt: Long? = null
    private var started = false

    /** 调用方 `withTimeoutOrNull` 该等多久：判定与等待取同一个值，只有这一处出处 */
    val waitMillis: Long get() = timeoutMillis

    /**
     * 长按是否已成立。成立后**一直为 true 直到下一次 [down]**：最后那一下抬起发生在 [release] 之前，
     * 调用方要靠它决定「这一下抬起要不要消费」——消费掉，内层按钮才不会把它当成点按。
     */
    val longPressed: Boolean get() = started

    /** 指针按下：开一串新的判定（上一串的结论作废） */
    fun down(uptimeMillis: Long) {
        downAt = uptimeMillis
        started = false
    }

    /** 按下期间的一次指针事件：到达分界即成立；返回「这一刻刚成立」（调用方据此跳顶一次） */
    fun advance(uptimeMillis: Long): Boolean =
        startIfReached(downAt?.let { uptimeMillis - it >= timeoutMillis } == true)

    /** 按住不动时的到点（调用方的超时）：仍按着且尚未成立即成立 */
    fun onTimeout(): Boolean = startIfReached(downAt != null)

    /** 抬起 / 手势取消：这一串按下结束（[longPressed] 留到下一次 [down] 才复位，理由见它） */
    fun release() {
        downAt = null
    }

    private fun startIfReached(reached: Boolean): Boolean {
        if (!reached || started) return false
        started = true
        return true
    }
}
