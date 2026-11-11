package com.cc3301.comicviewer.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel

/**
 * 阅读菜单的显隐（spec §阅读菜单的显隐状态）。
 *
 * 挂在**阅读页导航条目**上（`NavHost` 给每条 entry 提供自己的
 * [androidx.lifecycle.ViewModelStoreOwner]，组合里用 `viewModel()` 取本对象）：
 *
 * - 旋转、深浅色切换这类 Activity 重建不重建 ViewModel ⇒ 菜单保持离开时的开/关；
 * - 换书（`newReaderNavOptions()` 换新 entry）与退出阅读器（entry 被弹掉）都换/清掉这份状态 ⇒ 重置为关闭；
 * - 进程结束不恢复：不做保存态，也不落盘。
 *
 * 显隐的写入只有三处（点屏幕中区、点空白处、系统返回键），见 `ReaderScreen` 的调用点。
 */
internal class ReaderMenuState : ViewModel() {

    /** 菜单是否显示 */
    var menuVisible by mutableStateOf(false)
}
