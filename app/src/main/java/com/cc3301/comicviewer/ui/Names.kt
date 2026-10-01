package com.cc3301.comicviewer.ui

import android.net.Uri
import android.provider.DocumentsContract

/**
 * 浏览页标题（纯函数，由 [BrowseEntryPointTest] 锁定；含路由名字这一来源）：
 *
 * - **根层**（[containerId] 为 null，即该连接的浏览根）：显示**连接显示名**。根层没有条目名可回填
 *   （条目名缓存记的是条目 id → 名称，根层不在其中），因此只能取连接名——首页与书柜两条入口
 *   落到的都是这一条规则，标题因此必然一致。
 * - **子层**：名字兜底链**三档**，取到第一个非空者——
 *   ① [routeName]：进目录时随路由带回来的条目名；
 *   ② [cachedName]：会话内回填的条目名（Komga 这类 id 只有 UUID 的来源靠列表见过一次）；
 *   ③ id 末段（[displayNameOf]）。
 *
 * 为什么①排在②前面：进程重建（退出 APP 再回来）后缓存是空的，而这次恢复不经过父层枚举
 * （列表只在枚举某一层时回填名字）——只有随路由带回来的名字才在这条路上拿得到。反过来不排第一也
 * 会出错：路由参数是**进入这一层那一刻**的真实条目名，与缓存里的同源同值，快照与缓存不一致只可能发生在
 * 「名字是缓存学的、路由没见过」的旧数据上，那时②自然接住。
 *
 * 两处都取不到才用「浏览」兜底（连接还在查询中/已删除时的过渡帧）；[routeName] 为空串与「没带名字」同义。
 */
internal fun browserTitle(
    containerId: String?,
    routeName: String?,
    cachedName: String?,
    connectionName: String?,
): String =
    if (containerId == null) {
        connectionName?.takeIf { it.isNotBlank() } ?: "浏览"
    } else {
        routeName?.takeIf { it.isNotBlank() }
            ?: cachedName?.takeIf { it.isNotBlank() }
            ?: displayNameOf(containerId)
            ?: "浏览"
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
