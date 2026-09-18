package com.cc3301.comicviewer.core.view

/**
 * 浏览页视图档位（票 #53 的「视图」菜单 + 票 #45 的条目形态，spec「视图设置」）：
 * 列表 / 网格 2 列 / 网格 3 列 / 网格 4 列四档，**全 app 一份**（跨目录层级、跨连接、跨重启保持）。
 *
 * 默认 [GRID_2]：全新安装没有该设置时是网格 2 列（票 #53 AC）；非法或缺失的落盘值同样回落 [GRID_2]。
 * 档位集合与「几列」是同一件事（不是一个布局开关 + 一个列数开关），因此界面只需一个入口、一份状态。
 */
enum class ViewMode(
    /** 网格档的固定列数；列表档为 null（没有列数概念） */
    val columns: Int?,
) {
    LIST(columns = null),
    GRID_2(columns = 2),
    GRID_3(columns = 3),
    GRID_4(columns = 4),
    ;

    val isGrid: Boolean get() = columns != null

    companion object {
        /** 落盘键解析：未知/缺失/非法一律回落 [GRID_2]（票 #53 AC：「设置值非法/缺失时回落网格 2 列」） */
        fun fromKey(key: String?): ViewMode = entries.firstOrNull { it.name == key } ?: GRID_2

        /** 网格档位由列数给出（列数非法时回落 [GRID_2]） */
        fun ofColumns(columns: Int?): ViewMode =
            entries.firstOrNull { it.columns == columns && it.isGrid } ?: GRID_2
    }
}
