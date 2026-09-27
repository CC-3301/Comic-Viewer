package com.cc3301.comicviewer.ui

import androidx.compose.runtime.compositionLocalOf

/**
 * 抽屉此刻是否**关着**（票 #144）：由 [AppDrawer] 从 `drawerState.isClosed` 提供，内容层各屏据此让位。
 *
 * 为什么要有这个 Local：`drawerState` 原本只有**单向**通道（`AppNav` → 各屏的 `onOpenDrawer`），
 * 内容层拿不到「抽屉开着吗」；返回回调按「后注册先派发」派发，内容层各屏的 `BackHandler` 注册得比
 * 抽屉自己的返回接管**更晚**，于是抽屉开着时它们先拿到返回（真机现象：按返回走的是浏览历史后退）。
 *
 * 默认 `true`（没有抽屉的宿主按「没有抽屉可让」处理）：单测/preview 里单独组合某一屏时行为与加它之前一致。
 */
internal val LocalDrawerIsClosed = compositionLocalOf { true }

/**
 * 内容层某个返回处理器此刻是否接管返回（票 #144，纯判据，由 [DrawerBackGateTest] 钉住取值）：
 * **自己那条判据成立，且抽屉关着**。
 *
 * 抽屉开着时一律让位：那次返回只关抽屉（抽屉内建的返回接管），内容层的「返回上一级 / 先关菜单」都不接管。
 */
internal fun contentBackEnabled(ownEnabled: Boolean, drawerIsClosed: Boolean): Boolean = ownEnabled && drawerIsClosed
