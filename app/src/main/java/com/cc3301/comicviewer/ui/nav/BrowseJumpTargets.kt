package com.cc3301.comicviewer.ui.nav

import com.cc3301.comicviewer.core.nav.BrowseLocation
import com.cc3301.comicviewer.ui.browserTitle

/** 连接起点层在路径菜单里的文案 */
private const val ROOT_LABEL = "/"

/** 路径菜单的一项：显示的文案 + 点它要落到的那一层 */
internal data class BrowseJumpTarget(val label: String, val location: BrowseLocation)

/**
 * 浏览层路径菜单的判定（spec 故事 55：点顶栏标题就地跳到连接起点或本路径的一级目录）：**纯函数**。
 *
 * [chain] = 浏览链（回退栈里实际的浏览层，栈底 → 当前层）。菜单**两项封顶**：连接起点层（文案 `/`）
 * 与本路径的**一级目录**（链第 2 项）。四个边界：
 *
 * - 空链，或链首的容器不是连接起点层（`containerId != null`）⇒ **空**：链不可信时宁可不给入口，
 *   也不跳到一层不是起点层的地方
 * - 链长 1（停在起点层）⇒ **空**：那时菜单只剩 `/` 一项、点了也不动
 * - 链长 2（停在一级目录）⇒ 只有 `/`
 * - 链长 ≥3（停在更深层）⇒ `/` + 一级目录
 *
 * 一级目录的文案走与顶栏标题**同一条**名字兜底链（[browserTitle] 的子层那一支）：
 * 路由带回来的名字 → [cachedName] → id 末段。
 *
 * 输入全是现成的当前位置数据：不读来源、不列目录，因此任意深度即时可用、零网络。
 */
internal fun browseJumpTargets(
    chain: List<BrowseLocation>,
    cachedName: (String) -> String?,
): List<BrowseJumpTarget> {
    val root = chain.firstOrNull() ?: return emptyList()
    if (root.containerId != null) return emptyList()
    if (chain.size < 2) return emptyList()
    val targets = mutableListOf(BrowseJumpTarget(ROOT_LABEL, BrowseLocation(root.connId, null)))
    if (chain.size >= 3) {
        val firstLevel = chain[1]
        targets += BrowseJumpTarget(
            label = browserTitle(
                containerId = firstLevel.containerId,
                routeName = firstLevel.containerName,
                cachedName = firstLevel.containerId?.let(cachedName),
                connectionName = null,
            ),
            location = firstLevel,
        )
    }
    return targets
}
