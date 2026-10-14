package com.cc3301.comicviewer.ui

import com.cc3301.comicviewer.core.nav.BrowseLocation
import com.cc3301.comicviewer.core.source.SortMode
import com.cc3301.comicviewer.core.source.Source
import kotlinx.coroutines.withTimeoutOrNull

/**
 * 交接槽的有效窗口：装槽到目标层首帧认领之间只隔一次导航（一帧量级）。
 * 过了窗口的那份不再认——留着它会让「过一会儿再进同一层」吃到一份没重列的列表（进目录恒真列一次）。
 */
internal const val BROWSE_HANDOFF_WINDOW_MS: Long = 1_000

/** 返回上级时等交接的上限：超了就直接弹（父层按现状自己加载），返回键不许被慢来源卡住。 */
internal const val BROWSE_BACK_PREPARE_TIMEOUT_MS: Long = 300

/**
 * 「先取数、后弹栈」要用的取数上下文：**来源 + 本层连接 + 排序 + 方向 + 条目名缓存 + 交接槽**。
 * 打包成一个值：不必在几处各摊一遍同一串实参（那串里有四个是同生共死的）。
 */
internal class LayerListing(
    /** 本层的来源（目标层与它同连接，因此同一实例） */
    val source: Source,
    /** 本层的连接 id（交接槽按它认领，见 [LayerHandoff.take]） */
    val connId: Long,
    val sort: SortMode,
    val reverse: Boolean,
    val entryNames: MutableMap<String, String>,
    val handoff: LayerHandoff,
)

/**
 * 「先取数、后弹栈」的一次性交接槽：**返回上级**把父层已取好的首屏放在这里，
 * 父层新组合起来的第一帧直接用它——屏幕上因此不会先出现空帧（返回上级的白闪），也不会先出现该层的旧列表。
 *
 * 认领判据是四个键（连接 id / 容器 / 排序 / 方向）：排到别的层或别的排序上不认，宁可让它自己多列一次目录。
 * **命中即取走**（一次性）；不命中、过了 [BROWSE_HANDOFF_WINDOW_MS] 也一律清槽。
 *
 * 交回的是**目标层自己那一台取数器**（不是一份列表）：分页、截断提示与「落过帧没有」都在它上面，
 * 目标层因此照常续页，不需要第二套「整份列表」口径。
 */
internal class LayerHandoff(private val now: () -> Long = System::nanoTime) {

    private class Armed(
        val connId: Long,
        val containerId: String?,
        val sort: SortMode,
        val reverse: Boolean,
        val armedAtNanos: Long,
        val pager: BrowsePageLoader,
    )

    private var armed: Armed? = null

    /** 装槽：目标层的首屏已经取好（[prepareLayerHandoff] 取完才调）。 */
    fun arm(connId: Long, containerId: String?, sort: SortMode, reverse: Boolean, pager: BrowsePageLoader) {
        armed = Armed(connId, containerId, sort, reverse, now(), pager)
    }

    /** 目标层首帧认领：四个键全对且没过窗口才交回那台取数器；其余情况一律清槽、交回 null。 */
    fun take(connId: Long, containerId: String?, sort: SortMode, reverse: Boolean): BrowsePageLoader? {
        val current = armed ?: return null
        armed = null
        val withinWindow = now() - current.armedAtNanos <= BROWSE_HANDOFF_WINDOW_MS * 1_000_000
        val sameLayer = current.connId == connId && current.containerId == containerId
        val sameListing = current.sort == sort && current.reverse == reverse
        return current.pager.takeIf { withinWindow && sameLayer && sameListing }
    }

    /** 放弃这一次交接（取数失败 / 被取消）。 */
    fun clear() {
        armed = null
    }
}

/**
 * 「先取数、后弹栈」里**取数**那一步：把父层的首屏取到手再装槽。
 * 取数抛错（含取消）时槽里什么都不留——调用方一律照旧弹栈。
 */
internal suspend fun prepareLayerHandoff(layer: LayerListing, target: BrowseLocation) {
    val pager = BrowsePageLoader(layer.source, target.containerId, layer.sort, entryNames = layer.entryNames)
    pager.loadFirstScreenFor(layer.source, target.containerId, layer.sort, layer.reverse)
    layer.handoff.arm(target.connId, target.containerId, layer.sort, layer.reverse, pager)
}

/**
 * 返回上级（返回键 / 系统返回手势）：**先取数、后弹栈**。
 *
 * [parent] = 回退栈里当前层下面那一条浏览层。它与当前层**不同连接**时不取数——本层来源不是它那一份，
 * 直接弹（父层自己加载）。取数失败、或超过 [timeoutMillis] 没回来，也照样弹：返回键不许被慢来源卡住，
 * 那两种情况父层按现状自己加载（先空一帧，与改前一致）。
 */
internal suspend fun popToParentLayer(
    layer: LayerListing,
    parent: BrowseLocation,
    timeoutMillis: Long = BROWSE_BACK_PREPARE_TIMEOUT_MS,
    pop: () -> Unit,
) {
    if (parent.connId == layer.connId) {
        withTimeoutOrNull(timeoutMillis) {
            // 失败不是返回失败：只记下「这一份没取到」，槽里不留半成品；被取消则整条路都不弹
            //（这一层已经离开了，再弹一次就是多弹）。
            catchingNonCancellation { prepareLayerHandoff(layer, parent) }
        }
    }
    pop()
}
