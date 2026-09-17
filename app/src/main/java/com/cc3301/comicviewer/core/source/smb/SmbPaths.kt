package com.cc3301.comicviewer.core.source.smb

/**
 * SMB 路径规范（票 11）：共享内绝对路径统一为以 "/" 开头的形式，根 = "/"。
 * 纯字符串处理，与后端无关，便于 JVM 单测；".." 直接拒绝以防越出共享根。
 */
object SmbPaths {

    const val ROOT = "/"

    /** 规范化：补前导 /、折叠重复 /、去末尾 /（根除外）、丢弃 "."；含 ".." 抛异常 */
    fun normalize(path: String): String {
        val segments = path.replace('\\', '/').split('/')
        val kept = mutableListOf<String>()
        for (segment in segments) {
            when (segment) {
                "", "." -> Unit
                ".." -> throw IllegalArgumentException("SMB 路径不允许包含 ..：" + path)
                else -> kept.add(segment)
            }
        }
        return if (kept.isEmpty()) ROOT else kept.joinToString(separator = "/", prefix = "/")
    }

    /** 子路径（结果已规范化） */
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

    /** → Windows 风格（smbj 用反斜杠）；根 → 空串 */
    fun toWindows(path: String): String {
        val norm = normalize(path)
        return if (norm == ROOT) "" else norm.removePrefix("/").replace('/', '\\')
    }

    /** Windows 风格 → 规范路径（空串 → 根） */
    fun fromWindows(path: String): String = normalize(path)

    /** 目录列举里的伪条目（“.” 与 “..”）：smbj 会带出来，必须过滤 */
    fun isPseudoEntry(name: String): Boolean = name == "." || name == ".."

    /**
     * 节点 id / 进度键的标识前缀：默认端口省略，非默认端口带 ":端口"，
     * 否则同主机同共享的不同端口（例如 445 与 1445 映射两个服务）会共用同一批 id。
     */
    fun idPrefix(host: String, share: String, port: Int): String =
        if (port == SmbConnectionConfig.DEFAULT_PORT) {
            "smb://" + host + "/" + share
        } else {
            "smb://" + host + ":" + port + "/" + share
        }
}
