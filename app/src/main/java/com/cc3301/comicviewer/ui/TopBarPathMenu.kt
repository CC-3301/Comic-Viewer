package com.cc3301.comicviewer.ui

import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.cc3301.comicviewer.ui.nav.BrowseJumpTarget

/**
 * 顶栏标题 + 路径菜单（spec 故事 55）：标题整块可点，弹出菜单做浏览层跳转。
 *
 * 标题仍是 [TopBarTitle]（恒单行 + 末尾省略的口径不在这里另写一份），因此接上菜单不改顶栏的排版口径；
 * [targets] 为空时**不挂点击**——停在起点层时菜单没有可跳的目标，标题点了也不该有反应。
 *
 * 与 [TopBarMenuButton] 的区别：那个骨架是「按钮文字 = 当前值、当前项打勾」，按钮本体是 `TextButton`
 * （不带上限与省略，不能当标题用）；这里点的是标题本体、菜单项是一组动作、不涉及「当前项」。
 * 两者共用的只是 `DropdownMenu` 的用法。
 *
 * 本小件不取数、不导航（spec 的「形态」）：文案与目标层都由调用方算好（`browseJumpTargets`），
 * 点选只把选中的那一项交回调用方。
 *
 * 菜单窗口高度卡在 [PATH_MENU_MAX_ROWS] 行，超出的在菜单内滚：菜单本体不会随目录数长成整屏。
 */

/** 菜单窗口最多显示几行（超出在菜单内滚动，不截断条目） */
private const val PATH_MENU_MAX_ROWS = 10

/** 一行菜单项的高度（Material3 `DropdownMenuItem` 的最小高度），行数上限按它折算 */
private val PATH_MENU_ROW_HEIGHT = 48.dp

@Composable
internal fun TopBarPathMenu(
    title: String,
    targets: List<BrowseJumpTarget>,
    onSelect: (BrowseJumpTarget) -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    val openMenu: (() -> Unit)? = if (targets.isEmpty()) null else ({ open = true })
    TopBarTitle(text = title, onClick = openMenu)
    DropdownMenu(
        expanded = open,
        onDismissRequest = { open = false },
        modifier = Modifier.heightIn(max = PATH_MENU_ROW_HEIGHT * PATH_MENU_MAX_ROWS),
    ) {
        targets.forEach { target ->
            DropdownMenuItem(
                text = { Text(target.label) },
                onClick = {
                    onSelect(target)
                    open = false
                },
            )
        }
    }
}
