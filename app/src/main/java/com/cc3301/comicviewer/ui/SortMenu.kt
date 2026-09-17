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
import com.cc3301.comicviewer.core.source.SortMode

/**
 * 排序入口（spec 故事 14）：浏览列表与书柜柜内共用同一个控件（票 31 决策 2），
 * 两处的条目与切换语义因此永远一致；柜列表那一层（条目是连接，没有时间/发布日期的意义）不放入口。
 */
@Composable
fun SortMenuButton(sort: SortMode, onSelect: (SortMode) -> Unit) {
    var open by remember { mutableStateOf(false) }
    TextButton(onClick = { open = true }) { Text(sortLabel(sort)) }
    DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
        SortMode.entries.forEach { mode ->
            DropdownMenuItem(
                text = { Text(sortLabel(mode)) },
                onClick = {
                    onSelect(mode)
                    open = false
                },
            )
        }
    }
}

/** 排序方式中文标签（spec 故事 14：全部来源支持名称/修改时间/发布时间） */
private fun sortLabel(mode: SortMode): String = when (mode) {
    SortMode.NAME -> "名称"
    SortMode.MODIFIED_TIME -> "修改时间"
    SortMode.RELEASE_TIME -> "发布时间"
}
