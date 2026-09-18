package com.cc3301.comicviewer.ui

import android.net.Uri
import android.provider.DocumentsContract

/**
 * 浏览页标题（票 #49，纯函数，由 [BrowseEntryPointTest] 锁定）：
 *
 * - **根层**（[containerId] 为 null，即该连接的浏览根）：显示**连接显示名**。根层没有条目名可回填
 *   （条目名缓存记的是条目 id → 名称，根层不在其中），因此只能取连接名——首页与书柜两条入口
 *   落到的都是这一条规则，标题因此必然一致。
 * - **子层**：优先会话内回填的条目名（Komga 这类 id 只有 UUID 的来源只能靠列表见过一次），
 *   缺失时退回 id 末段（[displayNameOf]）。
 *
 * 两处都取不到才用「浏览」兜底（连接还在查询中/已删除时的过渡帧）。
 */
internal fun browserTitle(containerId: String?, containerName: String?, connectionName: String?): String =
    if (containerId == null) {
        connectionName?.takeIf { it.isNotBlank() } ?: "浏览"
    } else {
        containerName?.takeIf { it.isNotBlank() } ?: displayNameOf(containerId) ?: "浏览"
    }

/**
 * 来源内 id → 显示名（书名/目录名）。
 * SAF content uri 取 documentId 末段；其它 scheme 退化取路径末段。
 */
fun displayNameOf(id: String?): String? {
    if (id == null) return null
    val fromDocumentId = runCatching {
        DocumentsContract.getDocumentId(Uri.parse(id))?.substringAfterLast('/')
    }.getOrNull()
    return fromDocumentId
        ?: Uri.parse(id).lastPathSegment?.substringAfterLast('/')
        ?: id
}
