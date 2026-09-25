package com.cc3301.comicviewer.ui

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue

/**
 * 顶栏下拉菜单的骨架（票 #80 收口）：按钮文字 = 当前值，菜单项逐项渲染、当前项打勾、点选后关闭菜单。
 *
 * 「视图」档位（[ViewMenuButton]）与「排序」（[SortMenuButton]）原先各写一遍同样的十来行骨架，
 * 现在只留这一份；两者的差别都用参数表达。
 *
 * @param buttonLabel 按钮上显示的当前值文案（与 [labelOf] 同一份来源时两边恒等）
 * @param items 菜单项，顺序即渲染顺序
 * @param labelOf 菜单项 → 文案
 * @param isCurrent 菜单项是否当前生效（打勾判据）
 * @param checkDescription 打勾图标的无障碍描述
 * @param onSelect 点选回调（骨架负责随后关闭菜单）
 */
@Composable
internal fun <T> TopBarMenuButton(
    buttonLabel: String,
    items: List<T>,
    labelOf: (T) -> String,
    isCurrent: (T) -> Boolean,
    checkDescription: String,
    onSelect: (T) -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    TextButton(onClick = { open = true }) { Text(buttonLabel) }
    DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
        items.forEach { item ->
            DropdownMenuItem(
                text = { Text(labelOf(item)) },
                trailingIcon = {
                    if (isCurrent(item)) Icon(Icons.Filled.Check, contentDescription = checkDescription)
                },
                onClick = {
                    onSelect(item)
                    open = false
                },
            )
        }
    }
}
