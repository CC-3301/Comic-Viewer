package com.cc3301.comicviewer.ui

import androidx.compose.runtime.Composable
import com.cc3301.comicviewer.core.sort.SortDirection
import com.cc3301.comicviewer.core.sort.SortSetting
import com.cc3301.comicviewer.core.source.SortMode

/**
 * 排序入口（spec 故事 14）：浏览列表与书柜柜内共用同一个控件（票 31 决策 2），
 * 两处的条目与切换语义因此永远一致；柜列表那一层（条目是连接，没有时间/发布日期的意义）不放入口。
 *
 * 菜单给出**恰好 6 项**（票 #80）= 类别 × 方向（见 [sortMenuOptions]），点一项即类别与方向同时生效、
 * 一步到位，当前生效那一项打勾（与 [ViewMenuButton] 同款读法）；按钮文字与菜单项共用 [sortLabel]，
 * 因此「名称 降序」这种当前状态在按钮上直接可读。方向按类别各记一份，见 [SortSetting]。
 * 菜单骨架（按钮 + 下拉 + 打勾）走 [TopBarMenuButton]，与「视图」菜单共用一份。
 */
@Composable
fun SortMenuButton(setting: SortSetting, onSelect: (SortMode, SortDirection) -> Unit) {
    TopBarMenuButton(
        buttonLabel = sortLabel(setting.mode, setting.directionOf()),
        items = sortMenuOptions,
        labelOf = { option -> option.label },
        isCurrent = { option -> option.isCurrent(setting) },
        checkDescription = "当前排序方式",
        onSelect = { option -> onSelect(option.mode, option.direction) },
    )
}

/**
 * 菜单的六个选项（票 #80）= 类别 × 方向：类别按 [SortMode.entries] 排、每类内正向在前，
 * 即 名称 升序 / 名称 降序 / 修改时间 新→旧 / 修改时间 旧→新 / 发布时间 新→旧 / 发布时间 旧→新。
 */
internal val sortMenuOptions: List<SortMenuOption> = SortMode.entries.flatMap { mode ->
    SortDirection.entries.map { direction -> SortMenuOption(mode, direction) }
}

/**
 * 一个「类别 + 方向」的菜单项：[label] 与按钮文字是同一份文案（[sortLabel]），
 * [isCurrent] 即打勾判据（只看当前 [SortSetting.mode] 那一格的方向）。
 */
internal data class SortMenuOption(val mode: SortMode, val direction: SortDirection) {

    val label: String = sortLabel(mode, direction)

    fun isCurrent(setting: SortSetting): Boolean =
        mode == setting.mode && direction == setting.directionOf(mode)
}

/** 按钮与菜单项共用的文案（票 #80）：类别 + 方向 */
internal fun sortLabel(mode: SortMode, direction: SortDirection): String =
    sortModeLabel(mode) + " " + sortDirectionLabel(mode, direction)

/** 排序方式中文标签（spec 故事 14：全部来源支持名称/修改时间/发布时间） */
internal fun sortModeLabel(mode: SortMode): String = when (mode) {
    SortMode.NAME -> "名称"
    SortMode.MODIFIED_TIME -> "修改时间"
    SortMode.RELEASE_TIME -> "发布时间"
}

/** 方向文案按类别自己的口径（正向 = 该比较器术语的规则本身）：名称论升序/降序，时间类论新→旧 */
internal fun sortDirectionLabel(mode: SortMode, direction: SortDirection): String = when (mode) {
    SortMode.NAME -> if (direction == SortDirection.FORWARD) "升序" else "降序"
    SortMode.MODIFIED_TIME, SortMode.RELEASE_TIME ->
        if (direction == SortDirection.FORWARD) "新→旧" else "旧→新"
}
