package com.cc3301.comicviewer.core.view

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * 预览格要的那张图：[index] 是页位，[heightPx] 是**解码高度**（按预览条高分桶，见
 * `ReaderMenuLayout.previewDecodeHeightPx`）——同一页在不同预览条高下是不同的那张，
 * 因此它是键的一部分（换尺寸就是另一张，不会把旧高度的图当命中）。
 */
internal data class PreviewThumbKey(val index: Int, val heightPx: Int)

/**
 * 阅读菜单预览格缩略图的**取解调度**（纯策略，由 `PreviewThumbLoadsTest` 锁定）：
 * 谁去取、最多几个在飞、谁该被丢下。图本身不在这里存——解好的那张在 `PageDecoder` 的内存缓存里，
 * 格子按解码高度同步查得到。
 *
 * 为什么需要它：格子自己发起取解时，格子一滚走协程就被取消——取消得早图还没解出来，什么都没留下，
 * 于是**滚回来还是空的**（设备反馈的「拖滑条跳页时上/下一页闪一下」）。发起权因此从格子挪到条子上：
 *
 * - **已发出请求的跑完**：请求挂在 [scope]（条子的作用域，菜单关闭才停），格子被回收不影响它；
 *   解出来的那张进内存缓存 ⇒ 滚回来第一帧就有图；
 * - **在飞最多 [maxInFlight] 个**：预览的自限在阅读页之下（闸位里预览本来就让位给可见页，
 *   见 `docs/spec/reader.md` 的「页取数」）；**多出来的排队**，不在飞的格子滚走就直接出队
 *   （还没开工，同一次都没白花）；
 * - **视口外排队中最多 [maxOrphans] 个**：快速拖过几十格时，已经看不见的排队请求多半没人要，
 *   只留最近 [maxOrphans] 个；**已经开始的不取消**（跑完存下来）。请求由格子被组合触发
 *   （列表会预取视口边上一格），因此这一层上限就是给它们兜底的。
 * - **只有还在视口里的格子值得重试**：退避重试（最多 3 次、最长 4s）只为屏上那一格服务；
 *   滚走的那张跑完当前这次就停，免得长期占着额度。
 * - **去重按「页位 + 解码高度」这个键**（不是只按页位）：预览条高度变一次（标题行数回填）就是
 *   每格一次新的要图，旧高度那张换成新高度重取。
 *
 * 额度**不使用信号量**：进队 / 出队都在这里同步算（`pump`），因此"谁在飞、谁在排队"当场就定下来，
 * 不必等协程被调度——策略本身也就可单测。
 *
 * 内存命中（[cached]）直接不取：同一个键问几次也只有一次请求。
 */
internal class PreviewThumbLoads(
    /** 取解挂靠的作用域：条子自己的（菜单关闭即停），因此**不随格子回收取消** */
    private val scope: CoroutineScope,
    /** 内存里已经有这张：有就不重复取 */
    private val cached: (PreviewThumbKey) -> Boolean,
    /** 真取一次（含重试）；[stillVisible] 是重试判据 */
    private val load: suspend (key: PreviewThumbKey, stillVisible: () -> Boolean) -> Unit,
    private val maxInFlight: Int = MAX_IN_FLIGHT,
    private val maxOrphans: Int = MAX_ORPHANS,
) {
    /** 请求顺序（`LinkedHashMap` 的迭代序 = 插入序）：视口外超额时先出队**最早**那个 */
    private val entries = LinkedHashMap<PreviewThumbKey, Entry>()
    private var inFlight = 0
    private var visible: Set<Int> = emptySet()

    private class Entry {
        /** 已经占到额度、真在取：**已经开始的跑完，不取消** */
        var running = false

        /** 在飞的那次取解（**换高度时**要把它撤掉：旧高度那张进不了新键，留着只是白占额度） */
        var job: Job? = null
    }

    /** 页位 [index] 现在在屏上吗（重试判据也用它） */
    fun isVisible(index: Int): Boolean = index in visible

    /** 可见页位变了：视口外**排队中**的请求最多留 [maxOrphans] 个，更早的出队 */
    fun onVisible(visibleIndices: Set<Int>) {
        visible = visibleIndices
        val orphans = entries.filterValues { !it.running }.keys.filterNot { it.index in visibleIndices }
        val excess = orphans.size - maxOrphans
        if (excess > 0) orphans.take(excess).forEach { entries.remove(it) }
        pump()
    }

    /**
     * 这一格要图（格子被组合时问一次；名单外的预取格由 [onVisible] 的出队上限兜住）：
     * **同一个键**已经解好 / 已经在飞或排队就不重复取。
     *
     * 去重与键的关系：**同一页换了解码高度是新的一次要图**，不是重复问。预览条高度会变一次
     * （首帧按 1 行标题算面板上限，标题实测行数回填后重算 ⇒ 格高变），每格的键随之变一次；
     * 按页位去重会让新键永远没有图。
     * 换高度时把旧那边撤掉（已经开工也撤：解出来也进不了新键，留着只占额度）。
     */
    fun request(key: PreviewThumbKey) {
        if (cached(key)) return
        entries.keys.firstOrNull { it.index == key.index }?.let { stale ->
            if (stale == key) return
            entries.remove(stale)?.job?.cancel()
        }
        entries[key] = Entry()
        pump()
    }

    /** 按额度把排队的放出去（进队 / 一个跑完 / 清掉排队时都走这里） */
    private fun pump() {
        while (inFlight < maxInFlight) {
            val (key, entry) = entries.entries.firstOrNull { !it.value.running } ?: return
            entry.running = true
            inFlight++
            val job = scope.launch { load(key) { isVisible(key.index) } }
            entry.job = job
            // 交还额度挂在**完成回调**上，不写在协程体里：撤销的那条若在被调度开工之前就被取消，
            // 体根本不执行，体里的 `finally` 也就不会跑——额度会永久少一个
            job.invokeOnCompletion {
                inFlight--
                // 只删「还是这一条」：同键在取消之后被重新插回时不误删新那条
                entries.remove(key, entry)
                pump()
            }
        }
    }

    companion object {
        /** 同时在飞的预览取解：留出闸位给阅读页（见 `docs/spec/reader.md` 的「页取数」） */
        const val MAX_IN_FLIGHT: Int = 2

        /** 视口外还允许排队的请求数 */
        const val MAX_ORPHANS: Int = 2
    }
}
