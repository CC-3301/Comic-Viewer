package com.cc3301.comicviewer.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.key
import androidx.compose.runtime.saveable.SaveableStateHolder
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalSavedStateRegistryOwner
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavHostController
import com.cc3301.comicviewer.ui.nav.browseLocationOf
import com.cc3301.comicviewer.ui.nav.inBrowseRegion

/**
 * 常驻浏览层：浏览页**住在 `NavHost` 之外**，进阅读器时不被销毁、退出时已经就位。
 *
 * 为什么得在 `NavHost` 之外：`navigation-compose` 只组合**当前目的地**（过渡期同时组合两屏，过渡一结束
 * 旧屏就出组合、它的 `onDispose` 随即跑）。留在 `NavHost` 里的话，从阅读器退出时浏览页必须从零重建——
 * 取数、整层重列、重建网格、放回位置、重画可见封面全部落在滑出窗口里。要保住它，只能让这一屏活在
 * `NavHost` 之外（规格见 `docs/spec/shell.md`「导航过渡」）。
 *
 * 挂哪一条 entry 由 [hostedBrowseEntry] 定；进 / 出阅读器的形态不变：过渡仍由 `NavHost` 给
 * （阅读页滑入 / 滑出、浏览层在下面静止当背景），`NavHost` 里那条浏览路由落成空占位——位移仍只由
 * 阅读屏那一支给，过渡文件一行不动。
 */

/**
 * 常驻层该渲染哪一条浏览 entry：
 *
 * - 栈顶是浏览层 ⇒ 渲染它；
 * - 栈顶是阅读器 ⇒ 跳过**连续的**阅读器层（换书也是阅读器层），往下第一条非阅读器层**必须是浏览层**才挂
 *   —— 那一条正是退出时落回的那一屏，换书前后因此是同一个实例（不闪断、不重建）；
 *   它若是别的层（如退出落点是设置页），被盖住的这一屏退出时根本不会回到，白建白画一层；
 * - 其余栈顶（首页 / 书柜 / 设置 / 来源列表 / 启动中转页）不挂——那一屏不在组合树里，与改前一致。
 */
internal fun hostedBrowseEntry(route: String?, stack: List<NavBackStackEntry>): NavBackStackEntry? {
    if (!inBrowseRegion(route)) return null
    // 栈顶起第一条非阅读器层（栈顶不是阅读器时就是栈顶本身）
    val firstBelowReaders = stack.lastOrNull { it.destination.route != Routes.READER } ?: return null
    return firstBelowReaders.takeIf { browseLocationOf(it) != null }
}

/**
 * 浏览层是否正被阅读页整屏盖住。提供点在 [BrowseLayerHost]。
 *
 * 消费点只有一处：浏览页的平台事件槽（滚轮）。被盖住时它让出槽位——阅读页自己那一份才该收滚轮；
 * 槽位是单值的，而退出阅读器时浏览层**不重建**、不会重挂自己那一份，不让出就会让列表滚轮永久失效
 * （见 `HandlerSlot`）。
 */
internal val LocalBrowseLayerCovered = compositionLocalOf { false }

/**
 * 常驻浏览层的宿主。
 *
 * 组合本地提供者与保存态桶都与 `NavHost` 对一条目的地做的事**逐项对齐**
 * （`NavBackStackEntryProviderKt` 的 `LocalOwnersProvider`）：`LocalViewModelStoreOwner` /
 * `LocalLifecycleOwner` / `LocalSavedStateRegistryOwner` 都指向 [entry]，`rememberSaveable` 走按
 * [entry] 的 id 分键的 `SaveableStateHolder`。少任何一项，浏览页就挂到 Activity 级的宿主上——
 * 逐层下钻时多层共用一份宿主态。
 *
 * `key(entry.id)`：换一层浏览层（进子目录 / 返回上级，都是硬切）必须换一份全新组合，层与层之间不串
 * `remember` 状态；`rememberSaveable` 那一份仍按 entry id 存在保存态桶里（旋屏、进程重建都找得回来）。
 * 桶由调用方持有（见 `AppNav`）：常驻层卸载（栈顶换成首页 / 设置等）时它里面的保存态要留着。
 */
@Composable
internal fun BrowseLayerHost(
    nav: NavHostController,
    entry: NavBackStackEntry,
    saveableStateHolder: SaveableStateHolder,
    /** 被阅读页整屏盖住：不接收指针事件，并让出平台事件槽 */
    covered: Boolean,
    onOpenDrawer: () -> Unit,
) {
    val location = browseLocationOf(entry) ?: return
    CompositionLocalProvider(
        LocalViewModelStoreOwner provides entry,
        LocalLifecycleOwner provides entry,
        LocalSavedStateRegistryOwner provides entry,
        LocalBrowseLayerCovered provides covered,
    ) {
        saveableStateHolder.SaveableStateProvider(entry.id) {
            Box(Modifier.fillMaxSize()) {
                key(entry.id) {
                    BrowserScreen(
                        nav = nav,
                        connId = location.connId,
                        containerId = location.containerId,
                        containerName = location.containerName,
                        onOpenDrawer = onOpenDrawer,
                    )
                }
                if (covered) {
                    // 吃掉本层范围内的一切指针事件。Compose 不做遮挡剔除，而阅读页在书还没打开 / 打开失败时
                    // 整屏只有黑底、连一个指针输入节点都没有——不挡的话点击与滚动会落到被盖住的浏览层上。
                    Box(Modifier.fillMaxSize().consumePointerEvents())
                }
            }
        }
    }
}

/**
 * 吃掉范围内的一切指针事件（点击 / 拖动 / 滚轮）。
 *
 * 指针事件按 z 序找第一个命中的输入节点，本修饰符铺满全屏即恒命中；它挂在浏览内容**之上**、
 * 又在 `NavHost` 之下，所以阅读页自己接到的那一下不会被它抢走，阅读页没接住的那一下也不会漏到浏览层。
 */
private fun Modifier.consumePointerEvents(): Modifier = pointerInput(Unit) {
    awaitPointerEventScope {
        while (true) {
            awaitPointerEvent().changes.forEach { it.consume() }
        }
    }
}
