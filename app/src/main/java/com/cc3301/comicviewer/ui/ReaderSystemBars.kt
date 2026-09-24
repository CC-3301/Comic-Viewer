package com.cc3301.comicviewer.ui

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat

/**
 * 沉浸态系统栏的驱动（票 #111 r9 ③）：离开阅读器时**不在 pop 那一帧就恢复系统栏**。
 *
 * 真机现象（返回时闪一下）：pop 那一帧 `currentRoute` 已是浏览页，而黑底阅读页还在往右滑出去，
 * 系统栏此刻 `show()` 就是「黑底还没走、栏先冒出来」。因此路由离开阅读器后再把沉浸态**多留一个过渡窗口**
 *（[windowMillis] = 页面过渡时长），窗口走完才恢复。
 *
 * **时间必须由调用方给「当下时刻」**（票 #111 r10 修复，评审 r9 P1-1）：窗口是从**离开那一下**起算的，
 * 喂上一次路由变化时刻的话，停在阅读器里多久窗口就早到期多久（停在阅读器期间没有写点）——`show()`
 * 仍在 pop 后约 1 帧，本类等于没生效。接线点因此按「路由变了 / 窗口到期了」两个键**重算一次**判定，
 * 并在那一刻现读 `SystemClock.uptimeMillis()`（`AppNav` 的 `remember(currentRoute, immersiveBarsRecheck)`）；
 * 判定仍是纯函数（同 `RootBackExitState` 在事件回调里传时钟的手法），能用用例钉住。
 */
internal class ReaderImmersiveBarsState(private val windowMillis: Int = NavTransitions.DURATION_MILLIS) {
    private var lastRoute: String? = null

    /** 离开阅读器后沉浸态还要留到哪个时刻（毫秒，[lastRoute] 一变就重算） */
    private var keepImmersiveUntilMillis: Long = 0

    /**
     * 此刻系统栏该不该隐藏：[route] 是阅读器就隐藏；刚从阅读器离开则**过渡窗口内仍隐藏**，窗口过完才恢复。
     *
     * 只认「上一帧真的是阅读器」这一条边：别处的路由切换（首页↔书柜）不藏系统栏。
     */
    fun immersiveFor(route: String?, nowMillis: Long): Boolean {
        if (route != lastRoute) {
            val previous = lastRoute
            lastRoute = route
            keepImmersiveUntilMillis =
                if (previous == Routes.READER) nowMillis + windowMillis else 0L
        }
        return route == Routes.READER || nowMillis < keepImmersiveUntilMillis
    }
}

/**
 * 阅读器的沉浸式系统栏（票 #61）：[immersive] 为真时隐藏状态栏与导航栏，为假时恢复可见。
 *
 * 调用点只有一处——`AppNav` 组合期按**路由模板**算（阅读器 = 沉浸）并多留一个过渡窗口（判定在
 * [ReaderImmersiveBarsState]，`currentRoute == Routes.READER` 已不在任何调用点）。为什么
 * 用路由而不是「阅读页组合是否存活」：换书（菜单上/下一本、跨书确认条）会 pop 掉旧阅读页 entry 再压入新的
 * （[newReaderNavOptions]），旧 entry 的组合销毁可能落在新 entry 组合之后的一帧；按组合存活期开关的话，
 * 旧 entry 的「恢复」会盖掉新 entry 的「隐藏」，阅读页里就会突然冒出系统栏。按路由驱动则换书前后路由串不变
 * （`reader/{bookId}` 是同一个 destination），效果根本不会重跑，进/出阅读器的过渡也影响不到它。
 *
 * 两条边都做对称设置，避免状态残留（AC：「反复进出阅读器 ≥5 次、旋转、深浅主题切换后无残留」）：
 * - 隐藏：行为取 [WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE]——边缘滑动仍能**临时**
 *   唤出系统栏（AC「手势导航仍可用」），短暂显示后自动收起，不留半透明残影；
 * - 恢复：栏重新可见，行为回到 `BEHAVIOR_DEFAULT`，不把沉浸态的行为旗标留给列表界面。
 *
 * 与既有接线共存：窗口的 edge-to-edge 与挖孔模式在 `MainActivity.onCreate` 设置（票 #44）、系统栏图标明暗由
 * `MainActivity` 的 `LaunchedEffect(dark)` 设置，本函数只动**可见性**，不碰外观（浅色主题下白底白图标的问题不因此回归）。
 *
 * 测试面：Robolectric 的 `rootWindowInsets` 恒为全 0、探不到系统栏可见性，仓库也没有 Compose UI 测试基建，
 * 因此这里只做接线，真正落地由真机验收把守（AC5：手机手势导航 + 平板各一次、附前后对比截图）；
 * 沉浸态下贴底浮层的 inset 兜底口径另有纯函数单测（[com.cc3301.comicviewer.core.view.ReaderOverlayLayout]）。
 */
@Composable
internal fun ReaderImmersiveSystemBars(immersive: Boolean) {
    val view = LocalView.current
    LaunchedEffect(view, immersive) {
        val controller = view.context.findActivity()?.window
            ?.let { WindowCompat.getInsetsController(it, view) }
            ?: return@LaunchedEffect
        if (immersive) {
            controller.systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            controller.hide(WindowInsetsCompat.Type.systemBars())
        } else {
            controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_DEFAULT
            controller.show(WindowInsetsCompat.Type.systemBars())
        }
    }
}

/**
 * 视图上下文 → 承载它的 Activity（[WindowCompat] 与帧量测需要窗口，而 Compose 侧只给得到 view 的 context）；
 * 调用点是本文件的系统栏接线与 [BrowseScrollFrameMetrics]（票 #109）。
 */
internal tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
