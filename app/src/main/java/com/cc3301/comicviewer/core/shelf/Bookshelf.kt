package com.cc3301.comicviewer.core.shelf

/** 分柜依据：仍存在的连接（显示名即柜名） */
data class CabinetRef(val connectionId: Long, val displayName: String)

/**
 * 柜：书柜里的一条连接（spec 故事 43/44）。
 *
 * 不携带柜内条目（票 41）：柜列表只按连接立柜，柜内条目由单柜页自己按 [connectionId] 取来源枚举，
 * 这一层拼出来的那一份在生产路径上恒空——留着只会把一条生产不存在的组装路径锁进测试。
 */
data class BookshelfCabinet(
    val connectionId: Long,
    val displayName: String,
)

/**
 * 分柜（spec 故事 44 + Out of Scope「书柜跨来源混排视图」）：一条连接一个柜，[connections] 的顺序即柜序，
 * 多连接永不混排——每个柜只带自己的 connectionId，柜内条目由单柜页按它取来源（见 [BookshelfCabinet]）。
 *
 * 离线、加载失败、空库的连接同样成柜——柜名取自连接配置，不需要会话（票 31 决策 7）；
 * 连接被删除后就不在 [connections] 里了，它的柜位随之消失。
 */
fun groupIntoCabinets(connections: List<CabinetRef>): List<BookshelfCabinet> = connections.map { ref ->
    BookshelfCabinet(ref.connectionId, ref.displayName)
}
