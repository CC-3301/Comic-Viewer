package com.cc3301.comicviewer.ui

import com.cc3301.comicviewer.core.source.Source

/**
 * 一次**下拉更新**要失效什么（票 #136 步骤②）：只此一处，两件事必须一起发生、且顺序固定。
 *
 * 改前是两条路互不知情：清列表快照与封面字节缓存写在**两个来源实现各自的**
 * [Source.invalidateListCache] 里（`DocumentTreeSource` / `KomgaSource` 各一份），而封面重取键是界面自己
 * `++`（`BrowserScreen.reloadTick`，经 `CoverPlan.reloadKey` 进解码键）——「这两件事必须配对」没有任何一处写着，
 * 漏掉任一件的失败模式还都是**静默**的：
 * - 只推进重取键、不清字节缓存：同一条目的旧字节被当成新一代内容重新解码（白解一遍，像素照旧是旧的）；
 * - 只清缓存、不推进键：解码缓存键没换，屏上仍是旧图（看起来「下拉更新对封面没用」）。
 *
 * 顺序也在这里钉住：**先清旧的、再推进重取键**。反过来的话，新一代的取数会在清理落地之前就命中旧快照。
 *
 * 失效范围（与改前逐字一致，**没有**扩大）：
 * - 清：`containerId` 这一层的列表快照（内存 + 落盘，票 #74 起落盘也在内）与**整个来源**的封面字节缓存
 *   ——后者是 [Source.invalidateListCache] 的既有语义（票 #51），本票**不改**它的签名与语义；
 * - 不清：别的容器/别的连接的快照——它们没被刷新，下次进那一层照旧命中；
 * - 推进：[advanceRefetchKey] 恰好一次，且来源为 null（页面还没解析出来）时**也要**推进——
 *   下拉更新同时是一次「重新解析来源」的请求，那一步由重取键换代驱动。
 *
 * 有意**不在**这里的东西（别顺手加）：
 * - 走 uri 的本地图片封面不吃重取键（票 #53 的口径，见 [CoverRoute.uriKey]）：它由系统解码器直解，
 *   不受来源字节缓存影响；
 * - 容器 mtime 的失效决策树（`snapshotOf`）不归刷新管：文件改动本来就由它自动失效（票面边界）。
 */
internal fun applyPullToRefresh(source: Source?, containerId: String?, advanceRefetchKey: () -> Unit) {
    // ① 清旧的：这一层的列表快照（内存 + 落盘）与整个来源的封面字节缓存
    source?.invalidateListCache(containerId)
    // ② 再换代：列表重新枚举，可见行的封面解码键随之换代（[CoverPlan.reloadKey]）
    advanceRefetchKey()
}
