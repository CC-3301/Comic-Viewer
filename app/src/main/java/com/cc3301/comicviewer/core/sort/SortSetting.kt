package com.cc3301.comicviewer.core.sort

import com.cc3301.comicviewer.core.source.SortMode

/**
 * 排序方向（spec「排序设置」）：正向 / 反向。
 *
 * 正向 = 三个比较器术语各自描述的规则本身（名称 A→Z、修改时间与发布时间 新→旧），反向把它整份倒过来。
 * 方向是**展示层**概念（票 #29 裁决 7）：来源接口只收 [SortMode]，方向由界面统一施加，四来源行为因此一致。
 */
enum class SortDirection {
    FORWARD,
    REVERSE,
    ;

    companion object {
        /** 落盘键解析；未知/缺失回退正向（三个类别默认都是正向 = 现状，票 #29 裁决 2） */
        fun fromKey(key: String?): SortDirection = entries.firstOrNull { it.name == key } ?: FORWARD
    }
}

/**
 * 全局一份的排序设置（票 #29，spec 故事 10-14）：排序方式 + 三个类别**各自**的方向。
 *
 * 默认方向 = 现状：名称 A→Z（正向）、修改时间 / 发布时间 新→旧（正向）；只有用户手动反向才变（裁决 2）。
 * 浏览列表与书柜柜内读同一份，跨目录层级、跨连接、跨重启保持。
 */
data class SortSetting(
    val mode: SortMode = SortMode.NAME,
    /** 名称类方向：默认 A→Z */
    val nameDirection: SortDirection = SortDirection.FORWARD,
    /** 修改时间类方向：默认 新→旧 */
    val modifiedDirection: SortDirection = SortDirection.FORWARD,
    /** 发布时间类方向：默认 新→旧 */
    val releaseDirection: SortDirection = SortDirection.FORWARD,
) {

    /** 类别当前方向（默认取当前 [mode] 那一类） */
    fun directionOf(mode: SortMode = this.mode): SortDirection = when (mode) {
        SortMode.NAME -> nameDirection
        SortMode.MODIFIED_TIME -> modifiedDirection
        SortMode.RELEASE_TIME -> releaseDirection
    }

    /**
     * 点了一个排序类别（票 #29 裁决 3/4）：点当前类别 = 方向翻转；点别的类别 = 切过去，
     * 并用该类自己记住的方向——方向按类别各记一份，不是全局一个方向轴。
     */
    fun select(mode: SortMode): SortSetting = if (mode == this.mode) {
        withDirection(mode, directionOf(mode).flipped())
    } else {
        copy(mode = mode)
    }

    private fun withDirection(mode: SortMode, direction: SortDirection): SortSetting = when (mode) {
        SortMode.NAME -> copy(nameDirection = direction)
        SortMode.MODIFIED_TIME -> copy(modifiedDirection = direction)
        SortMode.RELEASE_TIME -> copy(releaseDirection = direction)
    }
}

fun SortDirection.flipped(): SortDirection = when (this) {
    SortDirection.FORWARD -> SortDirection.REVERSE
    SortDirection.REVERSE -> SortDirection.FORWARD
}

/**
 * 展示层施加方向（票 #29 裁决 7）：来源只按排序方式返回正向序，界面拿到后整份翻转。
 * 方向不进 `Source.listEntries` 契约，因此四个来源都只有一套排序语义。
 */
fun <T> List<T>.applySortDirection(direction: SortDirection): List<T> = when (direction) {
    SortDirection.FORWARD -> this
    SortDirection.REVERSE -> reversed()
}
