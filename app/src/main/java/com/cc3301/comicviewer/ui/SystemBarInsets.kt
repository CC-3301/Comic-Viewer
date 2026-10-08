package com.cc3301.comicviewer.ui

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.systemBars
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection

/**
 * 列表界面用的**稳定版系统栏 inset**（口径见 [provideStableSystemBarInsets]）。
 *
 * 为什么需要它：栏的收放会改变 `WindowInsets.systemBars`，列表界面按它布局的两处（`Scaffold` 的
 * `contentWindowInsets` 与 `TopAppBar` 自己的 `windowInsets`）会跟着重排。阅读器自己的浮层照旧读实时
 * inset：那是**有意的**两支（见 `ui/ReaderMenu.kt` 的 `readerPanelInsets`）。
 *
 * **提供点只有一处**（`MainActivity` 的 `setContent`）：列表屏在过渡里会被销毁重建，按屏记的话重建那一屏
 * 首帧读到的还是「栏已隐藏」的那一份。列表屏只读 [LocalStableSystemBarInsets]；没提供就读是响的。
 */
internal val LocalStableSystemBarInsets: ProvidableCompositionLocal<WindowInsets> = compositionLocalOf {
    error("没有提供稳定版系统栏 inset（见 ui/SystemBarInsets.kt 的 provideStableSystemBarInsets，提供点在 MainActivity）")
}

/**
 * 顶栏用的稳定版 inset（只有上边与左右，与 `TopAppBarDefaults.windowInsets` 同一取法）。
 *
 * `TopAppBar` **不读** `Scaffold` 的 `contentWindowInsets`：它按自己的 `windowInsets` 给状态栏留位，而那一处
 * 一收栏就变小，顶栏内容与整页偏移跟着往上跳（`Scaffold` 的正文偏移取决于顶栏量出来的高）。列表屏的顶栏
 * 因此也要吃这一份。
 */
@Composable
internal fun stableTopAppBarInsets(): WindowInsets =
    LocalStableSystemBarInsets.current.only(WindowInsetsSides.Horizontal + WindowInsetsSides.Top)

/**
 * 提供一份稳定值给下面所有屏幕（调用点：`MainActivity` 的 `setContent`）。
 *
 * **每条边只增不减**：比已记那份大就跟实时值，小就保持。栏隐藏时这份 inset 变小（顶边不归 0），
 * 跟了就让列表整块重排。Activity 重建会重建组合、重新记。
 */
@Composable
internal fun provideStableSystemBarInsets(content: @Composable () -> Unit) {
    val livePx = WindowInsets.systemBars.toInsetsPx()
    var seen by remember { mutableStateOf(livePx) }
    val merged = seen.maxPerEdge(livePx)
    SideEffect { seen = merged }
    CompositionLocalProvider(
        LocalStableSystemBarInsets provides merged.toWindowInsets(),
        content = content,
    )
}

/** 四条边（px）：`WindowInsets` 没有统一的边访问器，比较与重建都先折成这个形状 */
private data class InsetsPx(val left: Int, val top: Int, val right: Int, val bottom: Int) {
    /** 每条边取两份里大的 */
    fun maxPerEdge(other: InsetsPx): InsetsPx = InsetsPx(
        left = maxOf(left, other.left),
        top = maxOf(top, other.top),
        right = maxOf(right, other.right),
        bottom = maxOf(bottom, other.bottom),
    )

    fun toWindowInsets(): WindowInsets =
        WindowInsets(left = left, top = top, right = right, bottom = bottom)
}

/** `WindowInsets` → 四条边 px：读边只走公共的 [asPaddingValues]（各版本里没有统一的边访问器） */
@Composable
private fun WindowInsets.toInsetsPx(): InsetsPx {
    val padding = asPaddingValues()
    val direction = LocalLayoutDirection.current
    return with(LocalDensity.current) {
        InsetsPx(
            left = padding.calculateLeftPadding(direction).roundToPx(),
            top = padding.calculateTopPadding().roundToPx(),
            right = padding.calculateRightPadding(direction).roundToPx(),
            bottom = padding.calculateBottomPadding().roundToPx(),
        )
    }
}
