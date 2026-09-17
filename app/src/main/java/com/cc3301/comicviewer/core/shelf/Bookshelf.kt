package com.cc3301.comicviewer.core.shelf

import com.cc3301.comicviewer.core.source.BrowseEntry

/** 分柜依据：仍存在的连接（显示名即柜名） */
data class CabinetRef(val connectionId: Long, val displayName: String)

/** 柜：书柜里的一条连接与它的根条目（spec 故事 43/44） */
data class BookshelfCabinet(
    val connectionId: Long,
    val displayName: String,
    /** 该连接根容器的全部条目（`Source.listEntries(null, sort)`）；未取到时为空 */
    val entries: List<BrowseEntry>,
)

/**
 * 分柜（spec 故事 44 + Out of Scope「书柜跨来源混排视图」）：一条连接一个柜，柜内只装该连接的根条目
 * （[rootEntries] 以 connectionId 索引），多连接永不混排；柜序与 [connections] 一致。
 *
 * 离线、加载失败、空库的连接同样成柜——柜名取自连接配置，不需要会话（票 31 决策 7）；
 * 柜列表那一层不枚举来源（离线连接也要照常列柜），因此调用时传空表，条目留给进柜时取（票 31 决策 1）。
 */
fun groupIntoCabinets(
    connections: List<CabinetRef>,
    rootEntries: Map<Long, List<BrowseEntry>>,
): List<BookshelfCabinet> = connections.map { ref ->
    BookshelfCabinet(ref.connectionId, ref.displayName, rootEntries[ref.connectionId].orEmpty())
}
