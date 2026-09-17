package com.cc3301.comicviewer.core.source.opds

import com.cc3301.comicviewer.core.source.remote.endpointParts
import java.util.Base64

/**
 * OPDS 节点 id 规则（票 15）：id 同时是书 id、进度键与导航参数。
 *
 * OPDS 的条目 id 是服务器给的 URN/URL，且同一本书的「获取链接」才是我们真正需要的地址，
 * 因此把 URL 本身（feed 地址 / 获取链接地址）用 URL-safe Base64 编进 id：
 * - feed：`opds-scheme://主机[:端口]/路径/feed/<base64(feedUrl)>`
 * - 书　：`.../book/<base64(acquisitionUrl)>`
 *
 * 好处：无状态（进程重启后仍能打开读过的书）、不碰撞（URL 唯一）、且能反解出下载地址；
 * 书名不放进 id（会变长且需转义），标题由界面层用列表里见过的名字显示。
 */
object OpdsIds {

    fun prefix(baseUrl: String): String = endpointParts(baseUrl).idPrefix("opds")

    fun feedId(prefix: String, feedUrl: String): String = prefix + "/feed/" + encode(feedUrl)

    fun bookId(prefix: String, acquisitionUrl: String): String = prefix + "/book/" + encode(acquisitionUrl)

    /** 反解 feed 地址；格式不对返回 null */
    fun feedUrl(prefix: String, id: String): String? = decodePart(prefix, id, "feed")

    /** 反解获取链接地址；格式不对返回 null */
    fun acquisitionUrl(prefix: String, id: String): String? = decodePart(prefix, id, "book")

    private fun decodePart(prefix: String, id: String, kind: String): String? {
        val marker = prefix + "/" + kind + "/"
        if (!id.startsWith(marker)) return null
        return runCatching {
            String(Base64.getUrlDecoder().decode(id.removePrefix(marker)), Charsets.UTF_8)
        }.getOrNull()
            ?.takeIf { it.isNotEmpty() }
    }

    private fun encode(url: String): String =
        Base64.getUrlEncoder().withoutPadding().encodeToString(url.toByteArray(Charsets.UTF_8))
}
