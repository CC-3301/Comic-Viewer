package com.cc3301.comicviewer.ui.nav

import com.cc3301.comicviewer.core.nav.BrowseLocation
import com.cc3301.comicviewer.core.source.TopLevelEntry
import com.cc3301.comicviewer.core.source.TopLevelListing

/** 连接起点层在路径菜单里的文案 */
private const val ROOT_LABEL = "/"

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
 * [chain] = 浏览链（回退栈里实际的浏览层，栈底 → 当前层），[listing] = 来源的顶层及其地址
 *（`Source.topLevelEntries`：Komga 恒为四入口），[connectionName] = 连接显示名
 *（四入口那一屏用另一个地址时，它的标题与根层同口径取连接名）。
 * 菜单 = `/`（顶层那一屏）＋该层里的**目录**（`isBook = false`）**全部**列出
 *（顺序由调用方保证：菜单固定用名称升序那一档；菜单窗口的行数上限在呈现侧，不在这里截断）。
 *
 * 两种情形**不弹菜单**（返回空）：
 * - 链首的容器不是连接起点层（`containerId != null`，链不可信）：宁可不给入口，
 *   也不跳到一层不是起点层的地方
 * - 链长 < 2（停在起点层）：那时菜单里列的都是身后那张列表已经列着的一级目录
 *
 * 每个目录项的 [BrowseJumpTarget.anchor] 都是链首：目标若不在当前链上（兄弟目录、Komga 的四入口），
 * 导航据此把链钉成「起点层 → 目标」，返回因此落起点层而不是跳走前那一层。
 * 标了 [TopLevelEntry.isStartLayer] 的那一项（起始路径正好落在这一类）**目标就是起点层**：
 * 两者同一屏内容，指回起点层才不会压出内容相同的一层。
 * 目标**就是当前层**时标 [BrowseJumpTarget.isCurrentLayer]：界面据此只刷新这一层而不导航。
 */
internal fun browseJumpTargets(
    chain: List<BrowseLocation>,
    listing: TopLevelListing,
    connectionName: String?,
): List<BrowseJumpTarget> {
    val root = chain.firstOrNull() ?: return emptyList()
    if (root.containerId != null) return emptyList()
    if (chain.size < 2) return emptyList()
    val rootLocation = BrowseLocation(root.connId, null)
    // 当前层 = 链尾（本屏只在它是栈顶时组合）
    val current = chain.last()
    // 「/」= 顶层那一屏：默认就是起点层（containerId = null，已在链底 ⇒ 截断即可，不必钉链底）；
    // Komga 起始路径不默认时根容器成了那一处的列表，顶层另有地址（`.../cat`），链底因此钉在起点层。
    // 名字只在另有地址时随路由带走（根层的标题本就不看它，带了反而把连接名写进根层参数）。
    val topLocation = BrowseLocation(
        connId = root.connId,
        containerId = listing.containerId,
        containerName = if (listing.containerId == null) null else connectionName,
    )
    val targets = mutableListOf(
        BrowseJumpTarget(
            label = ROOT_LABEL,
            location = topLocation,
            anchor = if (topLocation == rootLocation) null else rootLocation,
            isCurrentLayer = topLocation == current,
        ),
    )
    listing.entries.asSequence()
        .filter { !it.entry.isBook }
        .forEach { top ->
            val location = if (top.isStartLayer) {
                rootLocation
            } else {
                BrowseLocation(root.connId, top.entry.id, top.entry.name)
            }
            targets += BrowseJumpTarget(
                label = top.entry.name,
                location = location,
                anchor = rootLocation,
                // 目标就是现在这一层（在 A 层又选 A）：跳过去只会压出内容相同的一层，界面据此改成刷新
                isCurrentLayer = location == current,
            )
        }
    return targets
}
