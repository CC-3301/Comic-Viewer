package com.cc3301.comicviewer.ui

import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.cc3301.comicviewer.core.sort.SortDirection
import com.cc3301.comicviewer.core.sort.SortSetting
import com.cc3301.comicviewer.core.source.SortMode

/**
 * 排序入口（spec 故事 14）：浏览列表与书柜柜内共用同一个控件（票 31 决策 2），
 * 两处的条目与切换语义因此永远一致；柜列表那一层（条目是连接，没有时间/发布日期的意义）不放入口。
 *
 * 菜单保持三个类别（票 #29 裁决 3）：点别的类别 = 切过去，点当前类别 = 方向翻转；按钮文字带方向，
 * 一眼看得出当前是正向还是反向（方向按类别各记一份，见 [SortSetting]）。
 */
@Composable
fun SortMenuButton(setting: SortSetting, onSelect: (SortMode) -> Unit) {
    var open by remember { mutableStateOf(false) }
    TextButton(onClick = { open = true }) { Text(sortLabel(setting)) }
    DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
        SortMode.entries.forEach { mode ->
            DropdownMenuItem(
                text = { Text(sortModeLabel(mode)) },
                onClick = {
                    onSelect(mode)
                    open = false
                },
            )
        }
    }
}

/** 按钮文字（票 #29 裁决 3）：类别 + 当前方向 */
private fun sortLabel(setting: SortSetting): String =
    sortModeLabel(setting.mode) + " " + sortDirectionLabel(setting.mode, setting.directionOf())

/** 排序方式中文标签（spec 故事 14：全部来源支持名称/修改时间/发布时间） */
private fun sortModeLabel(mode: SortMode): String = when (mode) {
    SortMode.NAME -> "名称"
    SortMode.MODIFIED_TIME -> "修改时间"
    SortMode.RELEASE_TIME -> "发布时间"
}

/** 方向文案按类别自己的口径（正向 = 该比较器术语的规则本身）：名称论 A→Z，时间类论新→旧 */
private fun sortDirectionLabel(mode: SortMode, direction: SortDirection): String = when (mode) {
    SortMode.NAME -> if (direction == SortDirection.FORWARD) "A→Z" else "Z→A"
    SortMode.MODIFIED_TIME, SortMode.RELEASE_TIME ->
        if (direction == SortDirection.FORWARD) "新→旧" else "旧→新"
}
