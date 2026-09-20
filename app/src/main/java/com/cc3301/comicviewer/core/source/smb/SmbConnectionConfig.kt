package com.cc3301.comicviewer.core.source.smb

import com.cc3301.comicviewer.core.source.StoredCredential
import com.cc3301.comicviewer.core.source.connectionDisplayName
import org.json.JSONObject

/**
 * SMB 连接配置（票 11）：连接 CRUD 的持久化载体，存进 Room 的 connections.configJson。
 *
 * 字段与 JSON 形状是存储契约（票 #38 只改表单，不动它）：共享名与共享内起始目录继续分开存，
 * 表单上的「服务器地址 / 路径」在 [parseFormTarget] 与 [formatAddress] / [formatPath] 里与它们互转；
 * 票 #72 只往 JSON 里**加了一个非敏感的 `name` 键**（连接名，见 [name]）：不加列、不加迁移，
 * 也不重算存量行。
 *
 * 密码自票 #27 起经存储层加密（Android Keystore + AES-GCM，[StoredCredential]）后才落 configJson：
 * [toJson] 落密文、[fromJson] 解出明文，表单/展示名/后端拿到的仍是明文，界面不感知加密。
 * 旧库里的明文密码读路径照旧认（存量连接不迁移也能连），v4 → v5 迁移用 [protectSecrets] 改写成密文。
 */
data class SmbConnectionConfig(
    val host: String,
    val share: String,
    val rootPath: String = "",
    val username: String = "",
    val password: String = "",
    val domain: String = "",
    val port: Int = DEFAULT_PORT,
    /**
     * 用户**显式写过**端口（票 #72 r2；configJson 的 `portExplicit` 键，非敏感）：`port` 自身区分不了
     * 「写了 `:445`」与「没写」（两者都落 445），展示名据此决定要不要带出端口。
     * **只影响展示名与表单地址回填**：节点 id 前缀（[SmbPaths.idPrefix]）与进度键不看它（见 [hostWithPort]）。
     */
    val portExplicit: Boolean = false,
    /**
     * 用户配置的连接名（票 #72；configJson 的 `name` 键，**非敏感**）：空 = 用自动拼名。
     * 存量行没有这个键，解出来即空 → 编辑保存时按新口径重算（不批量重算，见 `docs/SPEC.md`）。
     */
    val name: String = "",
    /**
     * 连接行 `displayName` 列的名字（票 #72 r2）：**运行期载体，不进 configJson**——报错文案与列表
     * 因此恒等。存量行在用户重存前，列里还是旧口径的名字，而重算出来的 [displayName] 已是新口径，
     * 只由 [com.cc3301.comicviewer.ui.ServiceLocator] 从连接行带上（列名意外为空时不接管，兜底名规则不会被它带出空白标题）。
     */
    val rowDisplayName: String? = null,
    /**
     * 密码密文解不出来（票 #27：换机 / 密钥失效 / 密文损坏）：密码按空处理，
     * 进连接前提示「重新填写密码」，编辑框里能重填；其余字段照旧可用。
     */
    val credentialsNeedReentry: Boolean = false,
) {
    /** 自动拼名（票 #72）：`主机[:端口]/共享名/子目录`（子目录为空即到共享名）。主机为空（配置损坏）时给空串，让展示名回落到兜底名 */
    private val autoName: String get() =
        if (host.isBlank()) {
            ""
        } else {
            // 显式写过的端口（含 445）照样带出：展示名走 [portExplicit] 那一支，id 前缀不走（见 [hostWithPort]）
            listOf(formatAddress(host, port, writeDefaultPort = portExplicit), formatPath(share, rootPath))
                .filter { it.isNotEmpty() }
                .joinToString("/")
        }

    /**
     * 列表展示名（票 #72）：连接行列名（运行期载体，存量行与列表恒等）优先，否则用户配置的 [name]，
     * 留空回落到 [autoName]（规则见 [connectionDisplayName]）。
     */
    val displayName: String get() =
        rowDisplayName?.takeIf { it.isNotBlank() } ?: connectionDisplayName(name, autoName)

    /** 落库文本：密码经 [StoredCredential.protect] 加密（票 #27），其余字段原样；加密失败抛出，绝不落明文 */
    fun toJson(): String = JSONObject()
        .put(KEY_HOST, host)
        .put(KEY_SHARE, share)
        .put(KEY_ROOT_PATH, rootPath)
        .put(KEY_USERNAME, username)
        .put(KEY_PASSWORD, StoredCredential.protect(password))
        .put(KEY_DOMAIN, domain)
        .put(KEY_PORT, port)
        .put(KEY_PORT_EXPLICIT, portExplicit)
        .put(KEY_NAME, name)
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

        /** 连接名（票 #72）：非敏感，明文落库（与 [StoredCredential] 保护的凭据字段不同） */
        private const val KEY_NAME = "name"

        /** 端口是否由用户显式写过（票 #72 r2）：非敏感，明文落库 */
        private const val KEY_PORT_EXPLICIT = "portExplicit"

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
                // 旧库的明文与票 #27 之后的密文都认；密文解不出来（换机/密钥失效）时为 null
                val password = StoredCredential.reveal(obj.optString(KEY_PASSWORD, ""))
                SmbConnectionConfig(
                    host = host,
                    share = share,
                    rootPath = obj.optString(KEY_ROOT_PATH, ""),
                    username = obj.optString(KEY_USERNAME, ""),
                    password = password.orEmpty(),
                    domain = obj.optString(KEY_DOMAIN, ""),
                    port = obj.optInt(KEY_PORT, DEFAULT_PORT),
                    portExplicit = obj.optBoolean(KEY_PORT_EXPLICIT, false),
                    name = obj.optString(KEY_NAME, ""),
                    credentialsNeedReentry = password == null,
                )
            }
        } catch (t: Throwable) {
            null
        }

        /**
         * 存量迁移（票 #27，v4 → v5 用）：configJson 里的密码改写成密文。
         * 幂等（已是密文/空值不动）；解不出 JSON 或加密不可用时返回 null，由调用方保留原行。
         */
        fun protectSecrets(json: String): String? = StoredCredential.protectSecrets(json, listOf(KEY_PASSWORD))

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
         * 写了端口（哪怕是 445）会置 [SmbFormTarget.portExplicit]（票 #72 r2）：展示名据此带出端口。
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
            var portExplicit = false
            var message: String? = null

            /** 端口文本 → [port]；非数字或越界给中文提示；真写了才置 [portExplicit] */
            fun takePort(written: String) {
                val given = written.toIntOrNull()
                if (given == null || given !in 1..65535) {
                    message = MESSAGE_PORT_RANGE
                } else {
                    port = given
                    portExplicit = true
                }
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
                portExplicit = portExplicit,
                share = segments.firstOrNull().orEmpty(),
                rootPath = segments.drop(1).joinToString(separator = "/"),
                message = message ?: pathMessage,
            )
        }

        /**
         * 主机 + 端口 → `主机[:端口]`。**地址的端口写法只有这一处实现**：表单回填（[formatAddress]）、
         * 节点 id 前缀（[SmbPaths.idPrefix]）与展示名共用，免得展示名与进度键把「非默认端口」各判一遍之后漂移。
         *
         * [writeDefaultPort] = false（默认，**id 前缀与既有地址回填的形态**）：默认端口 445 不写端口；
         * true（票 #72 r2：用户显式写过端口，含 445）：照写。**id 前缀不走 true 这一支**——它的形态是
         * 缓存/进度键，改了会让既有进度键失效（存量连接也没有 [SmbConnectionConfig.portExplicit] 这个键）。
         */
        fun hostWithPort(host: String, port: Int, writeDefaultPort: Boolean = false): String =
            if (port == DEFAULT_PORT && !writeDefaultPort) host else host + ":" + port

        /**
         * 连接字段 → 「服务器地址」/展示名里的地址文本：含冒号的主机（IPv6 字面量）加方括号——不加的话它后面的
         * `:端口` 与地址本身分不开（`fe80::1:1445` 回解析不出来）；非法端口原样带出，便于用户改正。
         * [writeDefaultPort] 见 [hostWithPort]（表单回填与展示名按配置里的显式标志传）。
         */
        fun formatAddress(host: String, port: Int, writeDefaultPort: Boolean = false): String =
            hostWithPort(bracketed(host), port, writeDefaultPort)

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
    /** 用户是否真的在地址里写了端口（票 #72 r2）：写了才在展示名里带出端口（含 445） */
    val portExplicit: Boolean = false,
    val share: String = "",
    val rootPath: String = "",
    val message: String? = null,
)
