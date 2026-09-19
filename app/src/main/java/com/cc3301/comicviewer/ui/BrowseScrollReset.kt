package com.cc3301.comicviewer.ui

import com.cc3301.comicviewer.core.sort.SortDirection
import com.cc3301.comicviewer.core.sort.SortSetting
import com.cc3301.comicviewer.core.source.SortMode

/**
 * 浏览页两档滚动状态的复位键（票 #58；行为已登记在 SPEC「排序在展示层翻转（票 #29）」那一条）。
 *
 * 现象：停在顶部把名称 A→Z 点成 Z→A，视口跳到列表另一端。原因不在排序本身，而在条目列表以 id 为 key
 * （`items(list, key = { it.id })`）——Compose 会把「原先可见的那一项」按 key 锚定回视口，顺序翻转后
 * 那一项已经跑到另一端，视口就跟着过去了。修法是排序变化时交出**一份全新的滚动状态**（索引 0、
 * 没有可锚定的 key），而不是先按新顺序布局再从另一端滑回来。
 *
 * 键**只**取排序设置里决定条目顺序的那两个值（类别 + 该类方向）。排序设置变化是唯一触发源
 * （spec 故事 10-14「排序设置」）：换类别、同类反向都换键，其余一律不换。
 *
 * 为什么键里不放「当前展示的条目顺序」：键变即 `rememberSaveable` 换新状态、把它的位置恢复一并冲掉。
 * 而条目顺序会在**非排序变化**时跳变——重新进屏（从阅读器返回、子目录返回上级）时枚举从 null 落地、
 * 下拉更新重列一次，键都会跟着跳，视口就被顶回顶部，与改动前（`rememberLazyListState` 的 saveable
 * 恢复）不一致。因此触发面收在此处，宁可少复位、不可多复位。
 */
internal data class BrowseScrollResetKey(
    /** 排序类别（名称 / 修改时间 / 发布时间） */
    val mode: SortMode,
    /** 当前类别的方向（方向按类别各记一份，但改变展示顺序的只有当前那类） */
    val direction: SortDirection,
)

/**
 * 由排序设置算出复位键（纯函数，有单测）。键相等 = 排序设置没变 = 不复位；键不等 = 排序设置变了 =
 * 两档滚动状态换成全新的（回到顶部）。
 */
internal fun browseScrollResetKey(setting: SortSetting): BrowseScrollResetKey = BrowseScrollResetKey(
    mode = setting.mode,
    direction = setting.directionOf(),
)
