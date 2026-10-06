package com.cc3301.comicviewer.ui.nav

import com.cc3301.comicviewer.core.nav.BrowseLocation
import com.cc3301.comicviewer.core.source.BrowseEntry

/** 连接起点层在路径菜单里的文案 */
private const val ROOT_LABEL = "/"

/** 菜单里一级目录的条数上限：超出部分不列（名单按调用方给的顺序取前 N，菜单固定用名称升序那一档） */
private const val MAX_DIRS = 10

/**
 * 路径菜单的一项：[label] 显示的文案、[location] 点它要落到的那一层、
 * [anchor] 目标不在当前浏览链上时要钉在链底的层（起点层）；`null` = 目标自己就是链底，
 * [isCurrentLayer] 目标就是**当前层**（点它 = 刷新这一层，不导航）。
 */
internal data class BrowseJumpTarget(
    val label: String,
    val location: BrowseLocation,
    val anchor: BrowseLocation?,
    val isCurrentLayer: Boolean,
)

/**
 * 浏览层路径菜单的判定（spec 故事 55）：**纯函数**——输入就是浏览链与顶层条目，
 * 不读全局状态、不取数、不碰导航。
 *
 * [chain] = 浏览链（回退栈里实际的浏览层，栈底 → 当前层），[topEntries] = 来源的目录树顶层条目
 *（`Source.topLevelEntries`，Komga 恒为四入口）。菜单 = `/` ＋该层里的**目录**（`isBook = false`）
 * 取前 [MAX_DIRS] 个：**所有一级目录都列**（含当前所在的那一层），不只看当前路径上那一条。
 *
 * 两种情形**不弹菜单**（返回空）：
 * - 链首的容器不是连接起点层（`containerId != null`，链不可信）：宁可不给入口，
 *   也不跳到一层不是起点层的地方
 * - 链长 < 2（停在起点层）：那时菜单里列的都是身后那张列表已经列着的一级目录
 *
 * 每个目录项的 [BrowseJumpTarget.anchor] 都是链首：目标若不在当前链上（兄弟目录、Komga 的四入口），
 * 导航据此把链钉成「起点层 → 目标」，返回因此落起点层而不是跳走前那一层。
 * 目标**就是当前层**时标 [BrowseJumpTarget.isCurrentLayer]：界面据此只刷新这一层而不导航
 * （跳过去只会压出内容相同的一层，返回看起来没反应）。
 */
internal fun browseJumpTargets(
    chain: List<BrowseLocation>,
    topEntries: List<BrowseEntry>,
): List<BrowseJumpTarget> {
    val root = chain.firstOrNull() ?: return emptyList()
    if (root.containerId != null) return emptyList()
    if (chain.size < 2) return emptyList()
    val rootLocation = BrowseLocation(root.connId, null)
    // 当前层 = 链尾（本屏只在它是栈顶时组合）
    val current = chain.last()
    val targets = mutableListOf(BrowseJumpTarget(ROOT_LABEL, rootLocation, null, isCurrentLayer = false))
    topEntries.asSequence()
        .filter { !it.isBook }
        .take(MAX_DIRS)
        .forEach { entry ->
            val location = BrowseLocation(root.connId, entry.id, entry.name)
            targets += BrowseJumpTarget(
                label = entry.name,
                location = location,
                anchor = rootLocation,
                // 目标就是现在这一层（在 A 层又选 A）：跳过去只会压出内容相同的一层，界面据此改成刷新
                isCurrentLayer = location == current,
            )
        }
    return targets
}
