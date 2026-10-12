package com.cc3301.comicviewer.ui

import com.cc3301.comicviewer.core.source.Source

/**
 * 一次**下拉更新**要失效什么：只此一处，两件事必须一起发生、且顺序固定。
 *
 * 两件事分别落在两处（[Source.invalidateForRefresh] 与界面自己的重取键），「这两件事必须配对」没有任何一处写着，
 * 漏掉任一件的失败模式还都是**静默**的：
 * - 只推进重取键、不清字节缓存：同一条目的旧字节被当成新一代内容重新解码（白解一遍，像素照旧是旧的）；
 * - 只清缓存、不推进键：解码缓存键没换，屏上仍是旧图（看起来「下拉更新对封面没用」）。
 *
 * 顺序也在这里钉住：**先清旧的、再推进重取键**。反过来的话，新一代的取数会在清理落地之前就命中旧缓存。
 *
 * **失效范围由来源自己定**（权威表述在 [Source.invalidateForRefresh] 的 KDoc，不在这里复写）：
 * 文件源只清封面字节缓存（列表新鲜度由「每次真列一次 + 增量比对」保证，清列表快照反而会毁掉比对基线）；
 * Komga 还要额外清它的会话内列表。
 *
 * 有意**不在**这里的东西：
 * - 走 uri 的本地图片封面不吃重取键（既有口径，见 [CoverRoute.uriKey]）：它由系统解码器直解，
 *   不受来源字节缓存影响；
 * - 滚动位置由界面侧在刷新那一代取数落地后复位到 0，不在本函数里。
 */
internal fun applyPullToRefresh(source: Source?, containerId: String?, advanceRefetchKey: () -> Unit) {
    // ① 清旧的：这一层要失效的会话缓存（文件源 = 封面字节；Komga 再加会话内列表）
    source?.invalidateForRefresh(containerId)
    // ② 再换代：列表重新枚举（每次都真列一次目录），可见行的封面解码键随之换代（[CoverPlan.reloadKey]）
    advanceRefetchKey()
}
