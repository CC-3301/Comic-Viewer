package com.cc3301.comicviewer.ui

import androidx.compose.runtime.Composable
import com.cc3301.comicviewer.core.view.ViewMode

/**
 * 「视图」入口（票 #53）：顶栏 actions 区原先的刷新按钮由它取代（手动刷新改成下拉更新）。
 *
 * 四项 = 列表 / 网格 2 列 / 网格 3 列 / 网格 4 列，当前档位打勾；按钮文字直接写当前档位
 * （与排序按钮「名称 降序」同一种读法：一眼看得出当前处在哪一档）。
 *
 * 档位是**全 app 一份**的设置（[ViewModeStore]），因此这里只接一个写回调，不持有本地状态。
 * 菜单骨架（按钮 + 下拉 + 打勾）走 [TopBarMenuButton]，与「排序」菜单共用一份。
 */
@Composable
fun ViewMenuButton(setting: ViewMode, onSelect: (ViewMode) -> Unit) {
    TopBarMenuButton(
        buttonLabel = viewModeLabel(setting),
        items = ViewMode.entries,
        labelOf = ::viewModeLabel,
        isCurrent = { mode -> mode == setting },
        checkDescription = "当前档位",
        onSelect = onSelect,
    )
}

/** 视图档位中文标签（票 #53：四项菜单的文案） */
internal fun viewModeLabel(mode: ViewMode): String = when (mode) {
    ViewMode.LIST -> "列表"
    ViewMode.GRID_2 -> "网格 2 列"
    ViewMode.GRID_3 -> "网格 3 列"
    ViewMode.GRID_4 -> "网格 4 列"
}
