package com.cc3301.comicviewer.ui

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow

/**
 * 各屏 `TopAppBar` title 槽复用的顶栏标题（票 #79，同 [DrawerMenuButton] 一样是「顶栏小件」）：
 * **恒为单行**，放不下时**末尾省略号**——前缀（连接名/主机开头的部分）优先保留。
 *
 * 为什么必须显式给行数上限：`TopAppBar` 是**固定高度的单行槽位**，title 槽里的 `Text` 默认
 * `maxLines = Int.MAX_VALUE`。长连接名（默认名是 `主机[:端口]/路径`，票 #72 起不带 scheme）不会撑高顶栏，而是折成
 * 多行、**溢出画进内容区**（压到封面/条目上）；右侧还有「视图」「排序」两个按钮占宽，折行几乎必然发生。
 *
 * 口径的一处写法：所有顶栏标题都走这里（浏览页根层=连接名、子层=条目名，与固定文案的首页/书柜/本地/
 * 设置/连接列表各屏同款）。想看全名的补救路径是**给连接起短名**（连接名可配置），长标题的展开/tooltip
 * 与自适应缩小字号都不做（改字号的方案被否：同一顶栏在不同层级/连接之间字号会忽大忽小）。
 *
 * 行数上限与省略口径由 [TopBarTitleTest] 用真实组合出来的 `Text` 排版结果锁定。
 */
@Composable
fun TopBarTitle(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = modifier,
    )
}
