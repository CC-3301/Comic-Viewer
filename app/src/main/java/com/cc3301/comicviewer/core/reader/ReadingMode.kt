package com.cc3301.comicviewer.core.reader

import com.cc3301.comicviewer.core.input.WheelSurface

/**
 * 阅读模式（spec 故事 23–25）：条漫（垂直连续滚动）/ 单页（横向翻页）。
 * 只允许在设置里切换，阅读菜单内不放入口。
 */
enum class ReadingMode(val key: String) {
    WEBTOON("webtoon"),
    PAGED("paged"),
    ;

    companion object {
        /** 持久化键解析；未知/缺失回退条漫（默认模式） */
        fun fromKey(key: String?): ReadingMode = entries.firstOrNull { it.key == key } ?: WEBTOON
    }
}

/** 阅读模式 → 滚轮界面类型（票 17，spec 故事 35）：条漫是连续滚动容器，单页是分页容器 */
val ReadingMode.wheelSurface: WheelSurface
    get() = when (this) {
        ReadingMode.WEBTOON -> WheelSurface.WEBTOON
        ReadingMode.PAGED -> WheelSurface.PAGED
    }

/**
 * 单页模式横向方向（spec 故事 24）：左→右（LTR）/ 右→左（RTL，日漫）。
 * 只影响横向排列与滑动方向；点击区语义恒定（左区恒为上一页，见 [com.cc3301.comicviewer.core.touch.tapIntentAt]）。
 */
enum class PageDirection(val key: String) {
    LTR("ltr"),
    RTL("rtl"),
    ;

    /** 右→左需要反转横向布局方向 */
    val reverseLayout: Boolean get() = this == RTL

    companion object {
        /** 持久化键解析；未知/缺失回退左→右 */
        fun fromKey(key: String?): PageDirection = entries.firstOrNull { it.key == key } ?: LTR
    }
}
