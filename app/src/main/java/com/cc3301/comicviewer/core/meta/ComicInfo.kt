package com.cc3301.comicviewer.core.meta

import java.io.ByteArrayInputStream
import javax.xml.parsers.DocumentBuilderFactory

/**
 * 发布时间（spec 故事 12/13）：来自 CBZ 内 ComicInfo.xml 的 Year/Month/Day。
 * 无元数据时由调用方回退为修改时间（图片文件夹等）。
 */
data class ReleaseDate(val year: Int, val month: Int, val day: Int) : Comparable<ReleaseDate> {

    /** 排序键 yyyyMMdd（月/日缺失时按 1 处理） */
    val sortKey: Long get() = year * 10000L + month * 100L + day

    override fun compareTo(other: ReleaseDate): Int = sortKey.compareTo(other.sortKey)
}

/**
 * 发布日期 → 毫秒时间戳（本地时区当日 00:00）。
 * 发布时间排序与 mtime 回退值必须同量纲，否则有元数据的书会被无元数据的书按毫秒量级压到后面。
 */
fun ReleaseDate.toEpochMillis(zone: java.time.ZoneId = java.time.ZoneId.systemDefault()): Long =
    java.time.LocalDate.of(year, month, day).atStartOfDay(zone).toInstant().toEpochMilli()

/**
 * 解析 ComicInfo.xml 中的发布时间。
 * 缺 `Year`（或非数字）返回 null → 调用方回退修改时间；`Month`/`Day` 缺失按 1 处理。
 * 解析失败（非 XML/损坏）同样返回 null（排序要始终可用，不能因元数据崩掉）。
 */
fun parseReleaseDate(xml: ByteArray): ReleaseDate? = runCatching {
    val factory = DocumentBuilderFactory.newInstance().apply {
        // 元数据来自本地文件，仍禁用 DOCTYPE 与外部实体（XXE 防御）
        runCatching { setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) }
        isExpandEntityReferences = false
        isNamespaceAware = false
    }
    val doc = factory.newDocumentBuilder().parse(ByteArrayInputStream(xml))
    val root = doc.documentElement ?: return null
    val year = root.childText("Year")?.trim()?.toIntOrNull() ?: return null
    if (year <= 0) return null
    val month = root.childText("Month")?.trim()?.toIntOrNull()?.coerceIn(1, 12) ?: 1
    val day = root.childText("Day")?.trim()?.toIntOrNull()?.coerceIn(1, 31) ?: 1
    ReleaseDate(year, month, day)
}.getOrNull()

private fun org.w3c.dom.Element.childText(tag: String): String? {
    val nodes = getElementsByTagName(tag)
    return if (nodes.length > 0) nodes.item(0).textContent else null
}
