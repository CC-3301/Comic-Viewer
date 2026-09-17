package com.cc3301.comicviewer.core.source.webdav

import org.w3c.dom.Element
import org.w3c.dom.Node
import java.io.ByteArrayInputStream
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import javax.xml.parsers.DocumentBuilderFactory

/**
 * PROPFIND 响应解析（票 12）：把 Depth:1 的 XML 变成目录项列表。
 *
 * 纯函数（输入 XML 字符串），因此能用固定响应样本做 JVM 单测——网络层只负责发请求与取 body。
 * 解析按「元素本地名」匹配，兼容服务器使用任意命名空间前缀（D:、d:、lp1: …）。
 */
fun parsePropfind(xml: String, baseUrl: String, selfPath: String): List<WebDavEntry> {
    val doc = DocumentBuilderFactory.newInstance()
        .apply {
            isNamespaceAware = true
            // 关掉外部实体：DAV 响应来自网络，不能让它拉本地文件
            runCatching { setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) }
            runCatching { setFeature("http://xml.org/sax/features/external-general-entities", false) }
            runCatching { setFeature("http://xml.org/sax/features/external-parameter-entities", false) }
            isExpandEntityReferences = false
        }
        .newDocumentBuilder()
        .parse(ByteArrayInputStream(xml.toByteArray(Charsets.UTF_8)))

    val out = mutableListOf<WebDavEntry>()
    for (response in localNameElements(doc.documentElement, "response")) {
        val href = localNameElements(response, "href").firstOrNull()?.textContent?.trim() ?: continue
        val path = WebDavPaths.fromHref(href, baseUrl) ?: continue
        val normalized = WebDavPaths.normalize(path)
        // Depth:1 会把请求目录自己也列出来；目录列表要排除自己
        if (normalized == WebDavPaths.normalize(selfPath)) continue
        // 只取状态为 2xx 的 propstat：RFC 4918 允许「属性不存在」的 propstat 排在前面，
        // 取错会让目录丢掉 collection 标记（整批目录被当文件）
        val props = localNameElements(response, "propstat")
            .firstOrNull { propstat ->
                localNameElements(propstat, "status").firstOrNull()
                    ?.textContent?.contains(" 2") == true
            }
            ?.let { localNameElements(it, "prop").firstOrNull() }
            ?: localNameElements(response, "prop").firstOrNull()
            ?: response
        val isDirectory = localNameElements(props, "collection").isNotEmpty() || href.endsWith("/")
        val size = localNameElements(props, "getcontentlength")
            .firstOrNull()?.textContent?.trim()?.toLongOrNull() ?: 0L
        val modified = localNameElements(props, "getlastmodified")
            .firstOrNull()?.textContent?.trim()?.let(::parseHttpDate)
        out += WebDavEntry(
            path = normalized,
            name = normalized.substringAfterLast('/'),
            isDirectory = isDirectory,
            lastModifiedMs = modified,
            size = if (isDirectory) 0L else size,
        )
    }
    return out
}

/** HTTP 日期（RFC 1123，如 `Wed, 21 Oct 2015 07:28:00 GMT`）→ epoch 毫秒；解析失败返回 null */
fun parseHttpDate(value: String): Long? = runCatching {
    ZonedDateTime.parse(value, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toEpochMilli()
}.getOrNull()

/** 取子元素中本地名等于 [name] 的所有元素（忽略命名空间前缀） */
private fun localNameElements(root: Node, name: String): List<Element> {
    val out = mutableListOf<Element>()
    fun walk(node: Node) {
        val children = node.childNodes
        for (i in 0 until children.length) {
            val child = children.item(i)
            if (child is Element) {
                if (child.localName == name || child.nodeName == name) out += child
                walk(child)
            }
        }
    }
    walk(root)
    return out
}
