package com.cc3301.comicviewer.ui

import com.cc3301.comicviewer.core.source.SourceType
import com.cc3301.comicviewer.core.source.sanitizeConnectionName
import com.cc3301.comicviewer.core.source.komga.KomgaConnectionConfig
import com.cc3301.comicviewer.core.source.smb.SmbConnectionConfig
import com.cc3301.comicviewer.core.source.webdav.WebDavConnectionConfig

/** 「名称（可空）」字段的键（票 #72）：三个网络来源表单共用，也是 configJson 里存连接名的键 */
const val CONNECTION_NAME_FIELD: String = "name"

/** 表单字段（票 11/12）：连接 CRUD 界面各来源只有字段与编解码不同 */
data class ConnectionField(
    val key: String,
    val label: String,
    val secret: Boolean = false,
)

/**
 * 连接配置编解码（票 11/12）：把表单键值 ↔ configJson 的差异收在一处，
 * 界面（SourceConnectionsScreen）因此对 SMB / WebDAV / 后续来源完全复用。
 */
interface ConnectionFormSpec {
    /** 顶栏标题（如 "SMB"） */
    val title: String

    val sourceType: SourceType

    val fields: List<ConnectionField>

    /**
     * 列表展示名（票 #72）：用户填的「名称」优先，留空回落到各来源自动拼出的名字（`主机[:端口]/路径`）——
     * 形态规则只有 [com.cc3301.comicviewer.core.source.connectionDisplayName] 一处实现，
     * 表单 / 书柜柜名 / 顶栏标题 / 连接列表 / 错误提示都消费它的结果（落 `connections.displayName` 列）。
     */
    fun displayName(values: Map<String, String>): String

    /** 表单值 → configJson（调用前必须已通过 [validate]） */
    fun encode(values: Map<String, String>): String

    /** configJson → 表单值（用于编辑回填；解不出来返回空表） */
    fun decode(configJson: String): Map<String, String>

    /** 校验：合法返回 null，否则返回中文错误提示 */
    fun validate(values: Map<String, String>): String?
}

/** 按来源类型取连接表单定义；未支持的来源抛异常（路由只在已支持来源上出现） */
fun connectionFormSpec(type: SourceType): ConnectionFormSpec = when (type) {
    SourceType.SMB -> SmbFormSpec
    SourceType.WEBDAV -> WebDavFormSpec
    SourceType.KOMGA -> KomgaFormSpec
    else -> throw IllegalArgumentException("该来源没有连接表单：" + type)
}

/**
 * SMB 连接表单（票 11）：票 #38 起字段收成「服务器地址（可含端口）+ 路径（共享名/子目录）」，
 * 端口与共享名不再是独立字段；解析口径见 [SmbConnectionConfig.parseFormTarget]。
 */
object SmbFormSpec : ConnectionFormSpec {
    override val title: String = "SMB"
    override val sourceType: SourceType = SourceType.SMB
    override val fields: List<ConnectionField> = listOf(
        // 标签只写字段名（票 #52）：带括号的长标签在真机上换行且被输入框边框缺口截掉第一行，
        // 「可含端口」与「共享名/子目录」的格式说明改由报错文案承载（见 SmbConnectionConfig 的校验常量）
        ConnectionField(CONNECTION_NAME_FIELD, "名称（可空）"),
        ConnectionField("address", "服务器地址"),
        ConnectionField("path", "路径"),
        ConnectionField("username", "用户名（可空）"),
        ConnectionField("password", "密码（可空）", secret = true),
        ConnectionField("domain", "域（可空）"),
    )

    private fun toConfig(values: Map<String, String>): SmbConnectionConfig {
        val target = SmbConnectionConfig.parseFormTarget(
            address = values["address"].orEmpty(),
            path = values["path"].orEmpty(),
        )
        return SmbConnectionConfig(
            host = target.host,
            share = target.share,
            rootPath = target.rootPath,
            username = values["username"].orEmpty(),
            password = values["password"].orEmpty(),
            domain = values["domain"].orEmpty(),
            port = target.port,
            portExplicit = target.portExplicit,
            name = sanitizeConnectionName(values[CONNECTION_NAME_FIELD].orEmpty()),
        )
    }

    override fun displayName(values: Map<String, String>): String = toConfig(values).displayName

    override fun encode(values: Map<String, String>): String = toConfig(values).toJson()

    /** 编辑回填（票 #38）：老配置的 share + rootPath 合成一条「路径」，非默认端口折进「服务器地址」 */
    override fun decode(configJson: String): Map<String, String> {
        val config = SmbConnectionConfig.fromJson(configJson) ?: return emptyMap()
        return mapOf(
            CONNECTION_NAME_FIELD to config.name,
            // 写过的端口（含 445）回填出来（票 #72 r2）：否则编辑一次保存就把 portExplicit 抹掉，展示名不再带端口
            "address" to SmbConnectionConfig.formatAddress(config.host, config.port, writeDefaultPort = config.portExplicit),
            "path" to SmbConnectionConfig.formatPath(config.share, config.rootPath),
            "username" to config.username,
            "password" to config.password,
            "domain" to config.domain,
        )
    }

    /** 解析成功即已保证主机/共享非空且端口在 1–65535，故不必再走 [SmbConnectionConfig.validate] */
    override fun validate(values: Map<String, String>): String? = SmbConnectionConfig.parseFormTarget(
        address = values["address"].orEmpty(),
        path = values["path"].orEmpty(),
    ).message
}

/** WebDAV 连接表单（票 12） */
object WebDavFormSpec : ConnectionFormSpec {
    override val title: String = "WebDAV"
    override val sourceType: SourceType = SourceType.WEBDAV
    override val fields: List<ConnectionField> = listOf(
        ConnectionField(CONNECTION_NAME_FIELD, "名称（可空）"),
        ConnectionField("baseUrl", "服务器地址（http(s)://主机:端口/路径）"),
        ConnectionField("rootPath", "起始目录（可空）"),
        ConnectionField("username", "用户名（可空）"),
        ConnectionField("password", "密码（可空）", secret = true),
    )

    private fun toConfig(values: Map<String, String>) = WebDavConnectionConfig(
        baseUrl = values["baseUrl"].orEmpty().trim(),
        rootPath = values["rootPath"].orEmpty().trim(),
        username = values["username"].orEmpty(),
        password = values["password"].orEmpty(),
        name = sanitizeConnectionName(values[CONNECTION_NAME_FIELD].orEmpty()),
    )

    override fun displayName(values: Map<String, String>): String = toConfig(values).displayName

    override fun encode(values: Map<String, String>): String = toConfig(values).toJson()

    override fun decode(configJson: String): Map<String, String> {
        val config = WebDavConnectionConfig.fromJson(configJson) ?: return emptyMap()
        return mapOf(
            CONNECTION_NAME_FIELD to config.name,
            "baseUrl" to config.baseUrl,
            "rootPath" to config.rootPath,
            "username" to config.username,
            "password" to config.password,
        )
    }

    override fun validate(values: Map<String, String>): String? = WebDavConnectionConfig.validate(toConfig(values))
}

/** Komga 连接表单（票 13）：API Key 与 邮箱+密码 二选一 */
object KomgaFormSpec : ConnectionFormSpec {
    override val title: String = "Komga"
    override val sourceType: SourceType = SourceType.KOMGA
    override val fields: List<ConnectionField> = listOf(
        ConnectionField(CONNECTION_NAME_FIELD, "名称（可空）"),
        ConnectionField("baseUrl", "服务器地址（http(s)://主机:端口）"),
        ConnectionField("apiKey", "API Key（推荐，与下两项二选一）", secret = true),
        ConnectionField("username", "邮箱（可空）"),
        ConnectionField("password", "密码（可空）", secret = true),
    )

    private fun toConfig(values: Map<String, String>) = KomgaConnectionConfig(
        baseUrl = values["baseUrl"].orEmpty().trim(),
        username = values["username"].orEmpty().trim(),
        password = values["password"].orEmpty(),
        apiKey = values["apiKey"].orEmpty().trim(),
        name = sanitizeConnectionName(values[CONNECTION_NAME_FIELD].orEmpty()),
    )

    override fun displayName(values: Map<String, String>): String = toConfig(values).displayName

    override fun encode(values: Map<String, String>): String = toConfig(values).toJson()

    override fun decode(configJson: String): Map<String, String> {
        val config = KomgaConnectionConfig.fromJson(configJson) ?: return emptyMap()
        return mapOf(
            CONNECTION_NAME_FIELD to config.name,
            "baseUrl" to config.baseUrl,
            "apiKey" to config.apiKey,
            "username" to config.username,
            "password" to config.password,
        )
    }

    override fun validate(values: Map<String, String>): String? = KomgaConnectionConfig.validate(toConfig(values))
}
