package com.cc3301.comicviewer.ui

import com.cc3301.comicviewer.core.sort.SortDirection
import com.cc3301.comicviewer.core.sort.SortSetting
import com.cc3301.comicviewer.core.source.SortMode

/**
 * 浏览页两档滚动状态的复位键（票 #58，spec 故事 14）。
 *
 * 现象：停在顶部把名称 A→Z 点成 Z→A，视口跳到列表另一端。原因不在排序本身，而在条目列表以 id 为 key
 * （`items(list, key = { it.id })`）——Compose 会把「原先可见的那一项」按 key 锚定回视口，顺序翻转后
 * 那一项已经跑到另一端，视口就跟着过去了。修法是顺序变化时交出**一份全新的滚动状态**（索引 0、
 * 没有可锚定的 key），而不是先按新顺序布局再从另一端滑回来。
 *
 * 键由两半组成：
 * - [mode] + [direction]：排序设置里决定条目顺序的那两个值。设置一变就换键——点完排序那一刻就回到顶部。
 * - [displayedIds]：当前展示的条目顺序。换排序类别会**重新枚举**（异步），旧顺序的条目会先在屏上停一帧；
 *   只看设置那半的话，新顺序落地时滚动状态会按「原先可见的那一项」重新锚定、视口又被带走。同一顺序
 *   再次枚举（下拉更新重列同一目录）id 序列不变、键不变，因此不会被额外复位；条目增删导致顺序真的变了
 *   才换键（下拉更新只在停在顶部时发起，那时本来就在顶部）。
 */
internal data class BrowseScrollResetKey(
    /** 排序类别（名称 / 修改时间 / 发布时间） */
    val mode: SortMode,
    /** 当前类别的方向（方向按类别各记一份，但改变展示顺序的只有当前那类） */
    val direction: SortDirection,
    /** 当前展示的条目 id 序列（方向已由展示层施加）；顺序未定时为 null */
    val displayedIds: List<String>?,
)

/**
 * 由排序设置与当前展示顺序算出复位键（纯函数，有单测）。
 * 键相等 = 条目顺序没变 = 不复位；键不等 = 顺序变了 = 两档滚动状态换成全新的（回到顶部）。
 */
internal fun browseScrollResetKey(
    setting: SortSetting,
    displayedIds: List<String>?,
): BrowseScrollResetKey = BrowseScrollResetKey(
    mode = setting.mode,
    direction = setting.directionOf(),
    displayedIds = displayedIds,
)
