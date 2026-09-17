package com.cc3301.comicviewer.core.source.smb

import org.json.JSONObject

/**
 * SMB 连接配置（票 11）：连接 CRUD 的持久化载体，存进 Room 的 connections.configJson。
 *
 * 字段与 JSON 形状是存储契约（票 #38 只改表单，不动它）：共享名与共享内起始目录继续分开存，
 * 表单上的「服务器地址 / 路径」在 [parseFormTarget] 与 [formatAddress] / [formatPath] 里与它们互转。
 *
 * 已知偏差（审查 P2 记录）：密码以明文存在 configJson 里（Room 未加密），
 * 编辑表单回显明文。与 SAF 一样属于本地单用户场景下的可接受取舍，
 * 但 WebDAV/Komga 票会复用同一列，若后续要上系统密钥库（Keystore/EncryptedSharedPreferences），
 * 应在此处一次性改掉（只影响 toJson/fromJson 与 UI 表单）。
 */
data class SmbConnectionConfig(
    val host: String,
    val share: String,
    val rootPath: String = "",
    val username: String = "",
    val password: String = "",
    val domain: String = "",
    val port: Int = DEFAULT_PORT,
) {
    /** 列表展示名：共享名 @ 服务器地址；非默认端口带 `:端口`（同主机同共享的两个端口否则分不清） */
    val displayName: String get() = share + " @ " + formatAddress(host, port)

    fun toJson(): String = JSONObject()
        .put(KEY_HOST, host)
        .put(KEY_SHARE, share)
        .put(KEY_ROOT_PATH, rootPath)
        .put(KEY_USERNAME, username)
        .put(KEY_PASSWORD, password)
        .put(KEY_DOMAIN, domain)
        .put(KEY_PORT, port)
        .toString()

    companion object {
        const val DEFAULT_PORT = 445

        private const val SMB_PREFIX = "smb://"

        private const val KEY_HOST = "host"
        private const val KEY_SHARE = "share"
        private const val KEY_ROOT_PATH = "rootPath"
        private const val KEY_USERNAME = "username"
        private const val KEY_PASSWORD = "password"
        private const val KEY_DOMAIN = "domain"
        private const val KEY_PORT = "port"

        // 校验文案跟表单字段走（票 #38）：地址含端口、共享名与起始目录合成一条「路径」
        private const val MESSAGE_ADDRESS_BLANK = "请填写服务器地址"
        private const val MESSAGE_PATH_BLANK = "请填写路径（格式：共享名/子目录）"
        private const val MESSAGE_PORT_RANGE = "端口必须在 1–65535 之间"
        private const val MESSAGE_PATH_UP = "路径不能包含 .."

        /** 解析失败或必填字段缺失返回 null（配置损坏时由 UI 提示，不崩溃） */
        fun fromJson(json: String): SmbConnectionConfig? = try {
            val obj = JSONObject(json)
            val host = obj.optString(KEY_HOST, "")
            val share = obj.optString(KEY_SHARE, "")
            if (host.isEmpty() || share.isEmpty()) {
                null
            } else {
                SmbConnectionConfig(
                    host = host,
                    share = share,
                    rootPath = obj.optString(KEY_ROOT_PATH, ""),
                    username = obj.optString(KEY_USERNAME, ""),
                    password = obj.optString(KEY_PASSWORD, ""),
                    domain = obj.optString(KEY_DOMAIN, ""),
                    port = obj.optInt(KEY_PORT, DEFAULT_PORT),
                )
            }
        } catch (t: Throwable) {
            null
        }

        /** 校验：合法返回 null，否则返回中文错误提示（兜底路径：进连接前查已存的 configJson） */
        fun validate(config: SmbConnectionConfig): String? = when {
            config.host.isBlank() -> MESSAGE_ADDRESS_BLANK
            config.share.isBlank() -> MESSAGE_PATH_BLANK
            config.port !in 1..65535 -> MESSAGE_PORT_RANGE
            else -> null
        }

        /**
         * 「服务器地址」（可含端口）+「路径」（共享名/子目录）→ 连接字段（票 #38）。
         *
         * 地址：前后空白（含 `smb://` 前缀之后的空白）、`smb://` 前缀（不分大小写）与首尾斜杠/反斜杠都容忍；
         * 不写端口即 [DEFAULT_PORT]。IPv6 字面量按标准写成 `[fe80::1]` 或 `[fe80::1]:1445`（方括号内即主机，
         * 其后只允许 `:端口`），存量里带或不带方括号两种写法都能解析；只有一个冒号或没有冒号的串按
         * `主机:端口` 拆，含多个冒号的裸串（如 `fe80::1`、`fe80::1:1445`）整串当主机——裸串最后一个冒号
         * 后面的数字与地址本身分不开，故非默认端口的 IPv6 必须带方括号（[formatAddress] 就是这么写的）。
         *
         * 路径：前后空白、`/` 与 `\` 混用、前导/尾随/重复斜杠与 `.` 段都容忍；规范化后第一段即共享名，
         * 其余段拼成共享内起始目录（不带前导斜杠）；含 `..` 或一段都没有则给中文提示。
         *
         * [SmbFormTarget.message] 非 null 时字段只是能解出的部分，界面不落库（表单先跑 validate）。
         */
        fun parseFormTarget(address: String, path: String): SmbFormTarget {
            val text = address.trim()
                .let { if (it.startsWith(SMB_PREFIX, ignoreCase = true)) it.substring(SMB_PREFIX.length) else it }
                // smb:// 之后可能还带空白（"smb:// nas.local"）：漏掉会静默存成带空格的主机名
                .trim()
                .trim('/', '\\')

            var host = text
            var port = DEFAULT_PORT
            var message: String? = null

            /** 端口文本 → [port]；非数字或越界给中文提示 */
            fun takePort(written: String) {
                val given = written.toIntOrNull()
                if (given == null || given !in 1..65535) message = MESSAGE_PORT_RANGE else port = given
            }

            val bracketEnd = if (text.startsWith("[")) text.indexOf(']') else -1
            if (text.isEmpty()) {
                message = MESSAGE_ADDRESS_BLANK
            } else if (bracketEnd > 0) {
                // [IPv6] / [IPv6]:端口：方括号内即主机（formatAddress 写出的形式，存量里带方括号的值也走这里）
                host = text.substring(1, bracketEnd)
                val written = text.substring(bracketEnd + 1)
                when {
                    host.isBlank() -> message = MESSAGE_ADDRESS_BLANK
                    written.isEmpty() -> Unit
                    // 方括号之后只允许 ":端口"
                    written.startsWith(":") -> takePort(written.substring(1))
                    else -> message = MESSAGE_PORT_RANGE
                }
            } else if (text.count { it == ':' } == 1) {
                host = text.substringBefore(':')
                if (host.isBlank()) message = MESSAGE_ADDRESS_BLANK else takePort(text.substringAfter(':'))
            }

            val segments = mutableListOf<String>()
            var pathMessage: String? = null
            for (segment in path.trim().replace('\\', '/').split('/')) {
                when (segment) {
                    "", "." -> Unit
                    ".." -> pathMessage = MESSAGE_PATH_UP
                    else -> if (pathMessage == null) segments.add(segment)
                }
            }
            if (pathMessage == null && segments.isEmpty()) pathMessage = MESSAGE_PATH_BLANK

            return SmbFormTarget(
                host = host,
                port = port,
                share = segments.firstOrNull().orEmpty(),
                rootPath = segments.drop(1).joinToString(separator = "/"),
                message = message ?: pathMessage,
            )
        }

        /**
         * 主机 + 端口 → `主机[:端口]`（默认端口不写端口）。**地址的端口写法只有这一处实现**：
         * 表单回填（[formatAddress]）与节点 id 前缀（[SmbPaths.idPrefix]）共用，
         * 免得展示名与进度键把「非默认端口」各判一遍之后漂移。
         */
        fun hostWithPort(host: String, port: Int): String =
            if (port == DEFAULT_PORT) host else host + ":" + port

        /**
         * 连接字段 → 「服务器地址」回填文本：含冒号的主机（IPv6 字面量）加方括号——不加的话它后面的
         * `:端口` 与地址本身分不开（`fe80::1:1445` 回解析不出来）；非法端口原样带出，便于用户改正。
         */
        fun formatAddress(host: String, port: Int): String = hostWithPort(bracketed(host), port)

        /** 含冒号的主机包进方括号；存量里已带方括号的值不重复包 */
        private fun bracketed(host: String): String =
            if (host.contains(':') && !(host.startsWith("[") && host.endsWith("]"))) "[" + host + "]" else host

        /** 连接字段 → 「路径」回填文本：`共享名/子目录`（起始目录为空即只有共享名） */
        fun formatPath(share: String, rootPath: String): String {
            val sub = runCatching { SmbPaths.normalize(rootPath) }.getOrNull()
            return when {
                // 起始目录本身非法（含 ..）：原样带出，让用户在表单里看到问题所在
                sub == null -> share + "/" + rootPath
                sub == SmbPaths.ROOT -> share
                else -> share + sub
            }
        }
    }
}

/**
 * SMB 表单两字段的解析结果（票 #38）：[host] / [port] / [share] / [rootPath] 即连接的四个逻辑量。
 *
 * [message] 非 null 表示输入非法（中文提示，表单就地显示），此时各字段只是能解出的部分，界面不落库。
 */
data class SmbFormTarget(
    val host: String = "",
    val port: Int = SmbConnectionConfig.DEFAULT_PORT,
    val share: String = "",
    val rootPath: String = "",
    val message: String? = null,
)
