package com.cc3301.comicviewer.ui

import com.cc3301.comicviewer.core.source.SourceType
import com.cc3301.comicviewer.core.source.komga.ClassifyingKomgaApi
import com.cc3301.comicviewer.core.source.komga.HttpKomgaApi
import com.cc3301.comicviewer.core.source.komga.KomgaBrowsePaths
import com.cc3301.comicviewer.core.source.komga.KomgaConnectionConfig
import com.cc3301.comicviewer.core.source.sanitizeConnectionName
import com.cc3301.comicviewer.core.source.smb.SmbConnectionConfig
import com.cc3301.comicviewer.core.source.webdav.WebDavConnectionConfig

/** 「名称（可空）」字段的键（票 #72）：三个网络来源表单共用，也是 configJson 里存连接名的键 */
const val CONNECTION_NAME_FIELD: String = "name"

/**
 * 表单字段（票 11/12）：连接 CRUD 界面各来源只有字段与编解码不同。
 *
 * [hint]（票 #76）是输入框**下方**的说明文字——地址格式与「不写端口时的默认端口」放这里，
 * 标签因此只写字段名（带括号的长标签在真机上换行且被输入框边框缺口截掉，理由同票 #52）。
 *
 * [readOnly] / [pickerDescription]（票 #78）描述「只能点选、不能键盘输入」的字段（Komga 的「路径」）：
 * 输入框只读、右侧画一个**文件夹图标按钮**打开选择器（票 #78 修复轮：按参考图用图标而非文字按钮），
 * 候选与写回都由 [ConnectionFormSpec.pathPicker] 提供。
 */
data class ConnectionField(
    val key: String,
    val label: String,
    val secret: Boolean = false,
    val hint: String = "",
    /** 只读字段（票 #78）：键盘输入无效，只能由右侧按钮打开的选择器改值 */
    val readOnly: Boolean = false,
    /** 右侧图标按钮的无障碍描述（票 #78）；为空则不画按钮 */
    val pickerDescription: String = "",
    /** 新增连接时的初始值（票 #78）：表单里显示它，并随保存一起落库（空 = 与既有字段一致） */
    val defaultValue: String = "",
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

    /**
     * 只读字段（票 #78，Komga 的「路径」）的选择器数据来源：[fields] 里标了
     * [ConnectionField.readOnly] 的字段靠它取候选。默认 null（没有只读字段的来源不需要），
     * 调用点只在按钮被点击时调它（[PathPicker] 会建 HTTP 会话）。
     */
    fun pathPicker(values: Map<String, String>): PathPicker? = null
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
        // 标签只写字段名（票 #76，同 #52）：格式说明与默认端口改由输入框下方的提示承载
        ConnectionField(
            "baseUrl",
            "服务器地址",
            hint = "格式：http(s)://主机:端口/路径；不写端口时 http 按 80、https 按 443 连接",
        ),
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

/**
 * Komga 连接表单（票 13 / #76）：**只提供邮箱 + 密码认证**（Basic）——API Key 字段已删除，
 * 表单值、校验、编辑回填里都不再出现它；存量 API Key 连接仍走 [KomgaConnectionConfig.apiKey]
 * 的读取路径连接与浏览，编辑保存时改为要求填邮箱与密码。
 */
object KomgaFormSpec : ConnectionFormSpec {
    override val title: String = "Komga"
    override val sourceType: SourceType = SourceType.KOMGA
    override val fields: List<ConnectionField> = listOf(
        ConnectionField(CONNECTION_NAME_FIELD, "名称（可空）"),
        // 标签只写字段名（票 #76，同 #52）：格式说明与默认端口改由输入框下方的提示承载
        ConnectionField(
            "baseUrl",
            "服务器地址",
            hint = "格式：http(s)://主机:端口；不写端口时 http 按 80、https 按 443 连接",
        ),
        // 「路径」（票 #78）：默认 `/`、键盘输入无效（只读）、右侧文件夹图标按钮打开选择器；
        // 决定进连接后从哪一层开始（`/` = 四个入口）
        ConnectionField(
            // 字段键与 configJson 键同名（browsePath，票 #78 修复轮）：与 baseUrl 里的 URL 路径区分开
            "browsePath",
            "路径",
            hint = "决定进连接后从哪一层开始：/ 是四个入口",
            readOnly = true,
            pickerDescription = "选择路径",
            defaultValue = KomgaBrowsePaths.ROOT,
        ),
        ConnectionField("username", "邮箱"),
        ConnectionField("password", "密码", secret = true),
    )

    private fun toConfig(values: Map<String, String>) = KomgaConnectionConfig(
        baseUrl = values["baseUrl"].orEmpty().trim(),
        username = values["username"].orEmpty().trim(),
        password = values["password"].orEmpty(),
        name = sanitizeConnectionName(values[CONNECTION_NAME_FIELD].orEmpty()),
        // 选择器只产出规范形态（稳定 token 段名）；空值/非法值回落 `/`（票 #78）
        browsePath = KomgaBrowsePaths.normalize(values["browsePath"].orEmpty()),
    )

    override fun displayName(values: Map<String, String>): String = toConfig(values).displayName

    override fun encode(values: Map<String, String>): String = toConfig(values).toJson()

    override fun decode(configJson: String): Map<String, String> {
        val config = KomgaConnectionConfig.fromJson(configJson) ?: return emptyMap()
        // 不回填 apiKey（票 #76）：它不是表单值了；存量 API Key 连接编辑时邮箱/密码为空，
        // 保存会被 [validate] 拦下并要求填写两项
        return mapOf(
            CONNECTION_NAME_FIELD to config.name,
            "baseUrl" to config.baseUrl,
            "browsePath" to config.browsePath,
            "username" to config.username,
            "password" to config.password,
        )
    }

    override fun validate(values: Map<String, String>): String? = KomgaConnectionConfig.validate(toConfig(values))

    /**
     * 路径选择器（票 #78）：用**表单当前值**建一个只读会话（地址/凭据可能还没保存），
     * 选择器只做「类别 → 收藏/系列」的读取；调用点负责在弹窗关闭时 [PathPicker.close] 释放它。
     */
    override fun pathPicker(values: Map<String, String>): PathPicker {
        val config = toConfig(values)
        return KomgaPathPicker(ClassifyingKomgaApi(HttpKomgaApi(config), config))
    }
}
