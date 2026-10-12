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
 * 失效范围（**由来源自己定**，见 [Source.invalidateForRefresh] 的实现契约）：
 * - 文件源：只清整个来源的封面字节缓存。列表的新鲜度不由刷新提供——每次进目录都真列 1 次目录 +
 *   按子项修改时间增量比对，所以清掉列表快照只会把下次进这一层变成整层全探；
 * - Komga：除封面外还要清它的会话内列表（它的列表没有 mtime，没有比对基线可用）。
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
