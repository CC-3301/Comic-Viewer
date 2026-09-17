package com.cc3301.comicviewer.core.shelf

import com.cc3301.comicviewer.core.data.BookshelfEntryEntity
import com.cc3301.comicviewer.core.source.SourceType

/** 分柜依据：仍存在的连接（显示名即柜名） */
data class CabinetRef(val connectionId: Long, val displayName: String)

/** 柜：书柜里某一个连接的全部条目（spec 故事 44） */
data class BookshelfCabinet(
    val connectionId: Long,
    val displayName: String,
    val entries: List<BookshelfEntryEntity>,
)

/**
 * 分柜（spec 故事 44 + Out of Scope「书柜跨来源混排视图」）：条目按连接归组，永不跨连接混排。
 * 连接已不存在的条目视为孤儿并跳过，空柜不显示；柜序与 [connections] 一致，柜内条目序沿用入参顺序。
 */
fun groupIntoCabinets(
    connections: List<CabinetRef>,
    entries: List<BookshelfEntryEntity>,
): List<BookshelfCabinet> {
    val byConnection = entries.groupBy { it.connectionId }
    return connections.mapNotNull { ref ->
        byConnection[ref.connectionId]
            ?.takeIf { it.isNotEmpty() }
            ?.let { BookshelfCabinet(ref.connectionId, ref.displayName, it) }
    }
}

/**
 * 书柜入口覆盖的来源：四种来源全部暴露「加入书柜」动作（spec 故事 43）。
 * 本地/SMB 由工单 17 接入、Komga 由工单 19、WebDAV 由工单 24（与本地/SMB 共用
 * DocumentTreeSource，只是后端换 HTTP）。分支保持穷举：将来新增来源时必须先定夺它是否入柜。
 */
fun SourceType.supportsBookshelf(): Boolean = when (this) {
    SourceType.LOCAL, SourceType.SMB, SourceType.WEBDAV, SourceType.KOMGA -> true
}
