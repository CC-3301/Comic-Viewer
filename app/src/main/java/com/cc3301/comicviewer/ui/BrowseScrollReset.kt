package com.cc3301.comicviewer.ui

import com.cc3301.comicviewer.core.sort.SortDirection
import com.cc3301.comicviewer.core.sort.SortSetting
import com.cc3301.comicviewer.core.source.SortMode

/**
 * 浏览页两档滚动状态的复位键（票 #58；行为已登记在 SPEC「排序在展示层翻转（票 #29）」那一条）。
 *
 * 现象：停在顶部把名称 升序 换成 降序，视口跳到列表另一端。原因不在排序本身，而在条目列表以 id 为 key
 * （`items(list, key = { it.id })`）——Compose 会把「原先可见的那一项」按 key 锚定回视口，顺序翻转后
 * 那一项已经跑到另一端，视口就跟着过去了。修法是在**每一次重排**时交出全新的滚动状态（索引 0、
 * 没有可锚定的 key），而不是先按新顺序布局再从另一端滑回来。
 *
 * 排序设置变化是唯一触发源，但有两次重排要接住：
 * - 点排序那一刻：换类别、同类换方向都立刻换键 → 新状态（回到顶部）。
 * - 换类别后**新顺序异步落地**那一帧：重新枚举（`produceState` 以类别为键重启、保留旧值）之前，
 *   屏上还停着上一档的旧顺序；落地帧再换一次键 → 重排不会带着上一帧记下的锚点把视口带走。
 *   同类换方向是同步重排，本来就落在第一次里。
 *
 * [staleIds] 只在「展示顺序还不是当前设置的产物」时才填（见 [browseScrollResetKey]）：重新进屏
 * （从阅读器返回、子目录返回上级）时枚举从 null 落地、下拉更新重列——这些时刻展示顺序都是当前设置的
 * 产物，键不跳，`rememberSaveable` 的位置恢复因此不会被冲掉。
 */
internal data class BrowseScrollResetKey(
    /** 排序类别（名称 / 修改时间 / 发布时间） */
    val mode: SortMode,
    /** 当前类别的方向（方向按类别各记一份，但改变展示顺序的只有当前那类） */
    val direction: SortDirection,
    /** 展示顺序还不是当前设置的产物时＝它此刻的条目 id 序列，否则 null */
    val staleIds: List<String>?,
)

/**
 * 由排序设置、当前条目的枚举类别与当前展示顺序算出复位键（纯函数，有单测）。键相等 = 不复位。
 *
 * [enumeratedMode] = 当前 `entries` 是按哪个排序类别列出来的；还没列出来时传 null，视同当前设置
 * （「尚未产出」与「已产出且与设置一致」得到同一个键——重新进屏因此不换键）。
 * 它与 [setting] 的类别不一致 = 这一屏还停着上一档的旧顺序，此时把 [displayedIds] 折进键，
 * 好让新顺序落地那一帧再换一次键。类别一致（或尚未产出）时不折：那样「进屏落地」「下拉更新重列」
 * 都不会换键，不会被额外复位。
 *
 * 只认当前类别的方向：`SortSetting.select` 给三个类别各记一份方向，但改变展示顺序的只有当前那类。
 */
internal fun browseScrollResetKey(
    setting: SortSetting,
    enumeratedMode: SortMode?,
    displayedIds: List<String>?,
): BrowseScrollResetKey = BrowseScrollResetKey(
    mode = setting.mode,
    direction = setting.directionOf(),
    staleIds = if (enumeratedMode != null && enumeratedMode != setting.mode) displayedIds else null,
)
