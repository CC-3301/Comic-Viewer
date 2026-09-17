package com.cc3301.comicviewer.core.source.webdav

import java.net.URI

/**
 * WebDAV 路径规范（票 12）：内部一律用「已解码、以 / 开头」的规范路径（根 = "/"），
 * 只在拼 URL 与解析 href 时做百分号编解码。
 *
 * 与 SmbPaths 同构（纯字符串处理，便于 JVM 单测），".." 直接拒绝以防越出 DAV 根。
 */
object WebDavPaths {

    const val ROOT = "/"

    /** 规范化：补前导 /、折叠重复 /、去末尾 /（根除外）、丢弃 "."；含 ".." 抛异常 */
    fun normalize(path: String): String {
        val kept = mutableListOf<String>()
        for (segment in path.replace('\\', '/').split('/')) {
            when (segment) {
                "", "." -> Unit
                ".." -> throw IllegalArgumentException("WebDAV 路径不允许包含 ..：" + path)
                else -> kept.add(segment)
            }
        }
        return if (kept.isEmpty()) ROOT else kept.joinToString(separator = "/", prefix = "/")
    }

    fun child(parent: String, name: String): String = normalize(normalize(parent) + "/" + name)

    /** 父路径；根返回 null */
    fun parent(path: String): String? {
        val norm = normalize(path)
        if (norm == ROOT) return null
        val idx = norm.lastIndexOf('/')
        return if (idx <= 0) ROOT else norm.substring(0, idx)
    }

    /** path 是否等于 root 或位于其下（按段比较，"/a" 不匹配 "/ab"） */
    fun isWithin(root: String, path: String): Boolean {
        val r = normalize(root)
        if (r == ROOT) return true
        val p = normalize(path)
        return p == r || p.startsWith(r + "/")
    }

    /** 规范路径 → URL 片段：按段百分号编码（中文、空格、# 等），保留分隔符 "/"。
     * 不用 java.net.URI 的多参构造：它对非 ASCII 字符不编码（只处理空格等）。
     */
    fun encodePath(path: String): String {
        val norm = normalize(path)
        if (norm == ROOT) return ROOT
        // 注意：split 会对前导 "/" 产生空段，必须丢掉，否则会拼出 "//..."（被 URI 当作 authority）
        return norm.split('/')
            .filter { it.isNotEmpty() }
            .joinToString(separator = "/", prefix = "/") { encodeSegment(it) }
    }

    private fun encodeSegment(segment: String): String =
        runCatching { java.net.URLEncoder.encode(segment, "UTF-8") }
            .getOrDefault(segment)
            // 路径里的空格必须是 %20（URLEncoder 按表单规则输出 +）
            .replace("+", "%20")

    /**
     * DAV 集合 URL 规范形式：一律以 "/" 结尾。
     * RFC 4918：集合 URL 以 / 结尾，且只有带尾斜杠时相对 href 才能相对本集合解析。
     */
    fun collectionUrl(baseUrl: String): String = baseUrl.trim() + if (baseUrl.trim().endsWith("/")) "" else "/"

    /** 拼接完整 URL：集合 URL（带尾斜杠）+ 编码后的路径 */
    fun join(baseUrl: String, path: String): String =
        collectionUrl(baseUrl) + encodePath(path).removePrefix("/")

    /**
     * PROPFIND 的 href → 规范路径；不在 [baseUrl] 之下返回 null。
     * href 可能是绝对 URL、绝对路径或相对路径（RFC 4918 允许服务器任选其一）。
     */
    fun fromHref(href: String, baseUrl: String): String? {
        val base = runCatching { URI(collectionUrl(baseUrl)) }.getOrNull() ?: return null
        val target = runCatching { base.resolve(href.trim()) }.getOrNull() ?: return null
        val basePath = (base.path ?: "").trimEnd('/')
        val raw = target.path ?: return null
        // URI.path 已解码；相对 href 会被 resolve 到 base 之下
        if (basePath.isNotEmpty() && raw != basePath && !raw.startsWith(basePath + "/")) return null
        val rest = if (basePath.isEmpty()) raw else raw.removePrefix(basePath)
        return normalize(rest)
    }
}
