package com.cc3301.comicviewer.core.source.opds

import org.w3c.dom.Element
import org.w3c.dom.Node
import java.io.ByteArrayInputStream
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import javax.xml.parsers.DocumentBuilderFactory

/**
 * feed 链接（票 15）：rel + type + href（href 已相对 feed URL 解析成绝对地址）。
 * OPDS-PSE 分页流不在首版范围（SPEC：首版一律下载后阅读），因此不解析 pse:count/template。
 */
data class OpdsLink(
    val rel: String,
    val type: String,
    val href: String,
)

/** feed 条目（票 15）：导航条目（subsection）与书条目（acquisition）共用同一结构 */
data class OpdsEntry(
    /** feed 内唯一 id（缺 id 时用 href/标题兜底） */
    val id: String,
    val title: String,
    val links: List<OpdsLink>,
    /** Atom updated/published（毫秒）；用于修改时间/发布时间排序 */
    val updatedMs: Long?,
    val publishedMs: Long?,
    /** 条目摘要（部分服务器给系列名/作者），仅用于诊断与将来展示 */
    val summary: String?,
) {
    /** 导航链接：进入下一级 feed */
    val navigationHref: String? get() = links.firstOrNull { it.rel == REL_SUBSECTION }?.href

    /** 获取链接：可下载的书（压缩包优先，其次单图） */
    val acquisition: OpdsLink?
        get() = links.filter { it.rel in ACQUISITION_RELS }
            .sortedByDescending { linkScore(it) }
            .firstOrNull()

    val thumbnailHref: String?
        get() = links.firstOrNull { it.rel == REL_THUMBNAIL }?.href
            ?: links.firstOrNull { it.rel == REL_IMAGE }?.href

    val isNavigation: Boolean get() = navigationHref != null && acquisition == null

    companion object {
        const val REL_SUBSECTION = "subsection"
        const val REL_THUMBNAIL = "http://opds-spec.org/image/thumbnail"
        const val REL_IMAGE = "http://opds-spec.org/image"
        val ACQUISITION_RELS = setOf(
            "http://opds-spec.org/acquisition",
            "http://opds-spec.org/acquisition/open-access",
            "http://opds-spec.org/acquisition/buy",
            "http://opds-spec.org/acquisition/borrow",
            "http://opds-spec.org/acquisition/sample",
        )

        /**
         * 压缩包 > 单图 > 其它：漫画阅读需要整本，优先能当压缩包读的链接。
         * EPUB 明确排除（不是漫画包）；type 缺失/octet-stream 的链接保留（服务器常写错 type，
         * 下载后按魔数判断才是可靠的）。
         */
        private fun linkScore(link: OpdsLink): Int = when {
            link.type.contains("epub") -> 0
            link.type.contains("comicbook") || link.type.contains("zip") || link.type.contains("cbz") -> 3
            link.type.startsWith("image/") -> 2
            else -> 1
        }

        /** 是否可作为一本书打开（压缩包/图片/类型未知；OPDS-PSE 分页流不在首版范围） */
        fun isReadable(link: OpdsLink): Boolean = linkScore(link) > 0
    }
}

/**
 * 解析结果（票 15）：条目 + feed 标题 + 分页链接。
 *
 * OPDS 1.x 用 feed 级的 `rel="next"`/`rel="previous"` 分页：不支持它的话，
 * 真实服务器的大目录会被静默截断成第一页。
 */
data class OpdsFeed(
    val title: String,
    val entries: List<OpdsEntry>,
    val nextPageUrl: String? = null,
    val previousPageUrl: String? = null,
)

/**
 * OPDS 1.x Atom feed 解析（票 15）：纯函数（输入 XML + feed URL），可用固定样本做 JVM 单测。
 *
 * 支持的要点：`feed/entry`、`title`、`id`、`updated`/`published`、`link`（rel/type/href，含相对地址）、
 * feed 级 `rel="next"`/`rel="previous"` 分页；命名空间前缀无关（按本地名匹配）。
 * OPDS-PSE 流式阅读与 OPDS 2.x（JSON）不在首版范围。
 */
fun parseOpdsFeed(xml: String, feedUrl: String): OpdsFeed {
    val doc = DocumentBuilderFactory.newInstance()
        .apply {
            isNamespaceAware = true
            // 关掉外部实体：feed 来自网络，不允许它拉本地文件
            runCatching { setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) }
            runCatching { setFeature("http://xml.org/sax/features/external-general-entities", false) }
            runCatching { setFeature("http://xml.org/sax/features/external-parameter-entities", false) }
            isExpandEntityReferences = false
        }
        .newDocumentBuilder()
        .parse(ByteArrayInputStream(xml.toByteArray(Charsets.UTF_8)))

    val root = doc.documentElement
    // feed 标题只取 feed 的直接子元素：避免把第一个条目的标题当成 feed 标题
    val feedTitle = directChild(root, "title")?.textContent?.trim().orEmpty()
    val entries = localNameElements(root, "entry").mapNotNull { element ->
        parseEntry(element, feedUrl)
    }
    // feed 级链接：分页（next/previous）单独取出，不能和条目链接混在一起
    // 只认 feed 的直接子元素（条目内部的 link 由 parseEntry 处理）
    val feedLinks = localNameElements(root, "link").filter { it.parentNode === root }
    fun pageLink(rel: String): String? = feedLinks.firstOrNull { link ->
        link.attributeOrNull("rel")?.trim() == rel
    }?.let { link -> link.attributeOrNull("href")?.let { resolveUrl(feedUrl, it) } }
    return OpdsFeed(
        title = feedTitle,
        entries = entries,
        nextPageUrl = pageLink(REL_NEXT),
        previousPageUrl = pageLink(REL_PREVIOUS),
    )
}

private const val REL_NEXT = "next"
private const val REL_PREVIOUS = "previous"

private fun parseEntry(element: Element, feedUrl: String): OpdsEntry? {
    val links = localNameElements(element, "link").mapNotNull { link ->
        val href = link.attributeOrNull("href") ?: return@mapNotNull null
        val rel = link.attributeOrNull("rel")?.trim().orEmpty()
        OpdsLink(
            rel = rel,
            type = link.attributeOrNull("type")?.trim().orEmpty(),
            href = resolveUrl(feedUrl, href),
        )
    }
    val title = localNameElements(element, "title").firstOrNull()?.textContent?.trim().orEmpty()
    if (title.isEmpty() && links.isEmpty()) return null
    val id = localNameElements(element, "id").firstOrNull()?.textContent?.trim()
    val updated = localNameElements(element, "updated").firstOrNull()?.textContent?.trim()?.let(::parseAtomDate)
    val published = localNameElements(element, "published").firstOrNull()?.textContent?.trim()?.let(::parseAtomDate)
    val summary = localNameElements(element, "summary").firstOrNull()?.textContent?.trim()
        ?: localNameElements(element, "content").firstOrNull()?.textContent?.trim()
    return OpdsEntry(
        id = id?.takeIf { it.isNotEmpty() }
            ?: links.firstOrNull()?.href
            ?: title,
        title = title.ifEmpty { links.firstOrNull()?.href.orEmpty() },
        links = links,
        updatedMs = updated,
        publishedMs = published,
        summary = summary,
    )
}

/**
 * href 解析成绝对 URL：OPDS 允许相对地址（相对 feed 所在目录）。
 * 解析失败时原样返回，交给传输层报错（不在这里吞掉）。
 */
internal fun resolveUrl(baseUrl: String, href: String): String {
    val trimmed = href.trim()
    if (trimmed.startsWith("http://") || trimmed.startsWith("https://")) return trimmed
    return runCatching { java.net.URI(baseUrl.trim()).resolve(trimmed).toString() }.getOrDefault(trimmed)
}

/** Atom 日期（RFC 3339，如 2015-10-21T07:28:00Z）→ epoch 毫秒；解析失败返回 null */
fun parseAtomDate(value: String): Long? = runCatching {
    ZonedDateTime.parse(value, DateTimeFormatter.ISO_OFFSET_DATE_TIME).toInstant().toEpochMilli()
}.getOrNull()

private fun Element.attributeOrNull(name: String): String? {
    val value = getAttribute(name)
    if (value.isNotEmpty()) return value
    // 带命名空间前缀的属性（如 pse:count）在 namespaceAware 解析下要用 getAttributeNS
    val attrs = attributes
    for (index in 0 until attrs.length) {
        val attr = attrs.item(index)
        if (attr.localName == name || attr.nodeName == name) return attr.nodeValue
    }
    return null
}

/** feed 的直接子元素里本地名等于 [name] 的第一个（不递归：避免命中条目内部的同名元素） */
private fun directChild(root: Node, name: String): Element? {
    val children = root.childNodes
    for (i in 0 until children.length) {
        val child = children.item(i)
        if (child is Element && (child.localName == name || child.nodeName == name)) return child
    }
    return null
}

/** 取后代元素中本地名等于 [name] 的所有元素（忽略命名空间前缀） */
private fun localNameElements(root: Node, name: String): List<Element> {
    val out = mutableListOf<Element>()
    fun walk(node: Node) {
        val children = node.childNodes
        for (i in 0 until children.length) {
            val child = children.item(i)
            if (child is Element) {
                if (child.localName == name || child.nodeName == name) out += child
                // 不递归进 entry 内部：避免嵌套 feed 时把子条目的 link 算进父条目
                if (name != "entry") walk(child)
            }
        }
    }
    walk(root)
    return out
}
