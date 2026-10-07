package com.cc3301.comicviewer.ui

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.systemBars
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection

/**
 * 列表界面用的**稳定版系统栏 inset**：栏可见时跟着实时值走，栏被隐藏（阅读器沉浸）时保持不可见前那一份。
 *
 * 为什么需要它：栏的收放会改变 `WindowInsets.systemBars`，而 `Scaffold` 默认按它布局。列表界面按实时 inset
 * 布局时，那份变化落在过渡中间——静止当背景的那一屏往上跳、被露出的那一屏往下跳并整块重排（出档因此掉帧）。
 * 阅读器自己的浮层照旧读实时 inset：那是**有意的**两支（见 `ui/ReaderMenu.kt` 的 `readerPanelInsets`）。
 *
 * **提供点只有一处**（`MainActivity` 的 `setContent`：`provideStableSystemBarInsets`）：列表屏在过渡里会被销毁重建，
 * 按屏记的话重建那一屏首帧读到的还是「栏已隐藏」的 0，出档照旧跳一下。列表屏只读 [LocalStableSystemBarInsets]。
 */
internal val LocalStableSystemBarInsets = compositionLocalOf { WindowInsets(0, 0, 0, 0) }

/**
 * 提供一份稳定值给下面所有屏幕（调用点：`MainActivity` 的 `setContent`）。只记**更宽**的那一份（栏可见时一定非零），
 * 之后一直用它；Activity 重建会重建组合、重新记。
 */
@Composable
internal fun provideStableSystemBarInsets(content: @Composable () -> Unit) {
    val livePx = WindowInsets.systemBars.toInsetsPx()
    val seen = remember { mutableStateOf(livePx) }
    val wider = livePx.isWiderThan(seen.value)
    SideEffect { if (wider) seen.value = livePx }
    val stable = if (wider) livePx else seen.value
    CompositionLocalProvider(
        LocalStableSystemBarInsets provides WindowInsets(
            left = stable.left,
            top = stable.top,
            right = stable.right,
            bottom = stable.bottom,
        ),
        content = content,
    )
}

/** 四条边（px）。比较按边值走：`WindowInsets` 每次组合都是新实例，直接比实例会写出重组循环 */
private data class InsetsPx(val left: Int, val top: Int, val right: Int, val bottom: Int) {
    fun isWiderThan(other: InsetsPx): Boolean =
        left > other.left || top > other.top || right > other.right || bottom > other.bottom
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
