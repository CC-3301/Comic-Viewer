package com.cc3301.comicviewer.ui

import android.net.Uri
import android.provider.DocumentsContract

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
