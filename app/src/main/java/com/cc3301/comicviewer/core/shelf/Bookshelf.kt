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
 * 本票书柜入口覆盖的来源（工单 17「文件源：本地 + SMB」）：只有它们暴露「加入书柜」动作。
 * WebDAV 与服务器源（Komga/OPDS）留给工单 19，避免吃掉其范围。
 */
fun SourceType.supportsBookshelf(): Boolean =
    this == SourceType.LOCAL || this == SourceType.SMB
