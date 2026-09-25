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
 * 阅读器的沉浸式系统栏（票 #61）：[immersive] 为真时隐藏状态栏与导航栏，为假时恢复可见。
 *
 * 调用点只有一处：`AppNav` 组合期按**当前路由**算（阅读器 = 沉浸）——`currentRoute == Routes.READER`。
 * 为什么用路由而不是「阅读页组合是否存活」：换书（菜单上/下一本、跨书确认条）会 pop 掉旧阅读页 entry 再压入新的
 * （[newReaderNavOptions]），旧 entry 的组合销毁可能落在新 entry 组合之后的一帧；按组合存活期开关的话，
 * 旧 entry 的「恢复」会盖掉新 entry 的「隐藏」，阅读页里就会突然冒出系统栏。按路由驱动则换书前后路由串不变
 * （`reader/{bookId}` 是同一个 destination），效果根本不会重跑，进/出阅读器的过渡也影响不到它。
 *
 * **r11 §3：取消「多留一个过渡窗口」**。r9 曾按「离开阅读器后再多留一个过渡窗口才恢复」来避开「黑底阅读页
 * 还在往外滑、系统栏先冒出来」，真机验收把它否了：从阅读器返回时顶部系统 UI 会一直藏着、约 0.5s 后才**突然蹦
 * 出来**（比早出来更刺眼）。现在回到按路由直接判：**返回动作一开始就恢复系统栏**。
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
 * r11 之前那个「过渡窗口」判定类（`ReaderImmersiveBarsState`）与它的用例已按 r11 §3 一并删除。
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
