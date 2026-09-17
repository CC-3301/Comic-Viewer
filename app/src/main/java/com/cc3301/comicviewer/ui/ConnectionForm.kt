package com.cc3301.comicviewer.ui

import com.cc3301.comicviewer.core.source.SourceType
import com.cc3301.comicviewer.core.source.komga.KomgaConnectionConfig
import com.cc3301.comicviewer.core.source.opds.OpdsConnectionConfig
import com.cc3301.comicviewer.core.source.smb.SmbConnectionConfig
import com.cc3301.comicviewer.core.source.webdav.WebDavConnectionConfig

/** 表单字段（票 11/12）：连接 CRUD 界面各来源只有字段与编解码不同 */
data class ConnectionField(
    val key: String,
    val label: String,
    val numeric: Boolean = false,
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

    /** 列表展示名 */
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
    SourceType.OPDS -> OpdsFormSpec
    else -> throw IllegalArgumentException("该来源没有连接表单：" + type)
}

/** SMB 连接表单（票 11） */
object SmbFormSpec : ConnectionFormSpec {
    override val title: String = "SMB"
    override val sourceType: SourceType = SourceType.SMB
    override val fields: List<ConnectionField> = listOf(
        ConnectionField("host", "服务器地址"),
        ConnectionField("port", "端口", numeric = true),
        ConnectionField("share", "共享名"),
        ConnectionField("rootPath", "起始目录（可空）"),
        ConnectionField("username", "用户名（可空）"),
        ConnectionField("password", "密码（可空）", secret = true),
        ConnectionField("domain", "域（可空）"),
    )

    private fun toConfig(values: Map<String, String>) = SmbConnectionConfig(
        host = values["host"].orEmpty().trim(),
        share = values["share"].orEmpty().trim(),
        rootPath = values["rootPath"].orEmpty().trim(),
        username = values["username"].orEmpty(),
        password = values["password"].orEmpty(),
        domain = values["domain"].orEmpty(),
        port = values["port"].orEmpty().trim().toIntOrNull() ?: 0,
    )

    override fun displayName(values: Map<String, String>): String = toConfig(values).displayName

    override fun encode(values: Map<String, String>): String = toConfig(values).toJson()

    override fun decode(configJson: String): Map<String, String> {
        val config = SmbConnectionConfig.fromJson(configJson) ?: return emptyMap()
        return mapOf(
            "host" to config.host,
            "port" to config.port.toString(),
            "share" to config.share,
            "rootPath" to config.rootPath,
            "username" to config.username,
            "password" to config.password,
            "domain" to config.domain,
        )
    }

    override fun validate(values: Map<String, String>): String? = SmbConnectionConfig.validate(toConfig(values))
}

/** WebDAV 连接表单（票 12） */
object WebDavFormSpec : ConnectionFormSpec {
    override val title: String = "WebDAV"
    override val sourceType: SourceType = SourceType.WEBDAV
    override val fields: List<ConnectionField> = listOf(
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
    )

    override fun displayName(values: Map<String, String>): String = toConfig(values).displayName

    override fun encode(values: Map<String, String>): String = toConfig(values).toJson()

    override fun decode(configJson: String): Map<String, String> {
        val config = WebDavConnectionConfig.fromJson(configJson) ?: return emptyMap()
        return mapOf(
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
    )

    override fun displayName(values: Map<String, String>): String = toConfig(values).displayName

    override fun encode(values: Map<String, String>): String = toConfig(values).toJson()

    override fun decode(configJson: String): Map<String, String> {
        val config = KomgaConnectionConfig.fromJson(configJson) ?: return emptyMap()
        return mapOf(
            "baseUrl" to config.baseUrl,
            "apiKey" to config.apiKey,
            "username" to config.username,
            "password" to config.password,
        )
    }

    override fun validate(values: Map<String, String>): String? = KomgaConnectionConfig.validate(toConfig(values))
}

/** OPDS 连接表单（票 15）：feed 地址 + 可选凭据；缓存上限在设置页（全局，见 AppSettings.opdsCacheLimitMb） */
object OpdsFormSpec : ConnectionFormSpec {
    override val title: String = "OPDS"
    override val sourceType: SourceType = SourceType.OPDS
    override val fields: List<ConnectionField> = listOf(
        ConnectionField("feedUrl", "feed 地址（http(s)://主机:端口/opds）"),
        ConnectionField("username", "用户名（可空）"),
        ConnectionField("password", "密码（可空）", secret = true),
    )

    private fun toConfig(values: Map<String, String>) = OpdsConnectionConfig(
        feedUrl = values["feedUrl"].orEmpty().trim(),
        username = values["username"].orEmpty(),
        password = values["password"].orEmpty(),
    )

    override fun displayName(values: Map<String, String>): String = toConfig(values).displayName

    override fun encode(values: Map<String, String>): String = toConfig(values).toJson()

    override fun decode(configJson: String): Map<String, String> {
        val config = OpdsConnectionConfig.fromJson(configJson) ?: return emptyMap()
        return mapOf(
            "feedUrl" to config.feedUrl,
            "username" to config.username,
            "password" to config.password,
        )
    }

    override fun validate(values: Map<String, String>): String? = OpdsConnectionConfig.validate(toConfig(values))
}
