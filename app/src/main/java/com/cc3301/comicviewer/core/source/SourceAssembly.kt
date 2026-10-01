package com.cc3301.comicviewer.core.source

import com.cc3301.comicviewer.core.data.ConnectionEntity
import com.cc3301.comicviewer.core.source.fs.FsBackend
import com.cc3301.comicviewer.core.source.komga.ClassifyingKomgaApi
import com.cc3301.comicviewer.core.source.komga.HttpKomgaApi
import com.cc3301.comicviewer.core.source.komga.KomgaConnectionConfig
import com.cc3301.comicviewer.core.source.komga.KomgaSource
import com.cc3301.comicviewer.core.source.smb.ClassifyingTransport
import com.cc3301.comicviewer.core.source.smb.SmbBackend
import com.cc3301.comicviewer.core.source.smb.SmbConnectionConfig
import com.cc3301.comicviewer.core.source.smb.SmbjTransport
import com.cc3301.comicviewer.core.source.webdav.ClassifyingWebDavTransport
import com.cc3301.comicviewer.core.source.webdav.HttpWebDavTransport
import com.cc3301.comicviewer.core.source.webdav.WebDavBackend
import com.cc3301.comicviewer.core.source.webdav.WebDavConnectionConfig
import java.io.File

/**
 * 一条连接的装配失败（票 #136）：原先三种出路（配置损坏 / 凭据重入 / 校验失败）都只是
 * [IllegalArgumentException] + 一句中文，四个互不相识的 catch 点（浏览页、柜页、连接列表、启动导航）
 * 靠「message 里有中文」默许它。三种出路各有类型后，调用点按类型分支也不会再漏掉某一支；
 * 提示文本仍是用户能照做的那句，界面侧照旧只读 `message`（表现零变化）。
 *
 * 继承 [IllegalArgumentException] 是既有契约：这些失败一直是参数类错误（连接记录不可用），
 * 界面统一 `runCatching` 后展示 message。
 */
internal sealed class SourceAssemblyFailure(message: String) : IllegalArgumentException(message) {
    /** 配置损坏：JSON 解不出来、必填字段缺失、来源类型未知——用户只能重新添加该连接 */
    class ConfigCorrupt(message: String) : SourceAssemblyFailure(message)

    /** 凭据重入：密文解不出来（换机 / 密钥失效 / 密文损坏）——重填凭据即可修好，不是配置损坏 */
    class CredentialReentry(message: String) : SourceAssemblyFailure(message)

    /** 校验失败：字段能解出来但不合法（端口越界、地址缺协议头、缺凭据）——用户改一处即可 */
    class InvalidConfig(message: String) : SourceAssemblyFailure(message)
}

/**
 * 装配一条连接所需的外部依赖（票 #136）：装配模块在 `core/source`，不认 `Context` / Room / 缓存目录，
 * 由 App 侧（`ui.ServiceLocator`）把这几样交过来。
 *
 * 四样都是**取值闭包而不是值**：装配路径上只用到其中一两样（Komga 不用封面目录与落盘快照），
 * 取巧地在这里先求值会让「未知来源类型」这条出路也去碰 Context（原先不碰）。
 */
internal class SourceDeps(
    /** 阅读进度读写（四个来源都交同一实现，App 侧是 Room） */
    val progressStore: () -> ProgressStore,
    /** 封面落盘缓存目录；null = 不落盘、每次按需解出 */
    val coverCacheDir: () -> File?,
    /** 某连接名下的落盘列表快照表（票 #74）：键 = 连接 id + 容器 id；null = 不落盘 */
    val listingSnapshots: (Long) -> ListingSnapshotStore?,
    /** SAF 后端工厂（本地来源）：生产里建它要做 provider IPC，因此只在这里被调用一次 */
    val safBackend: (String) -> FsBackend,
)

/** 装配一条连接（票 #136）：接口非泛型，装配表才能把四个来源装进同一个 Map / when */
internal interface ConnectionAssembly {
    fun build(conn: ConnectionEntity, deps: SourceDeps): Source
}

/**
 * 一个来源的**装配说明**（票 #136）：「JSON → 配置 → 可用来源」的每一步各是它的一份数据。
 *
 * 新增一个带凭据的来源，**装配路径上**只要交一份 [ConnectionSpec]：新的 config 类自己当然还得写，
 * 收掉的是原先围着它写的四处**接线**——指向它的那几行（`parseConfig` / `needsReentry` / `validate` /
 * `carryRowName`）、`sourceForConnection` 的分支、`xxxConfigOf`、它那条凭据重入提示常量——现在这四处
 * 都由这一份说明派生（解析 / 凭据重入 / 校验三步原先在 SMB、WebDAV、Komga 三处逐字同形，提示文案是
 * 同一句话换来源名，如今只有这一处的模板）。
 *
 * （两个容易误读的点：`xxxConfigOf` 那三个转发行已在修复轮 b1/2 删除，生产入口只剩 `sourceForConnection`；
 * `protectStoredCredentials` 的 when **不在**这四处里，它不属于装配路径，见下段。）
 *
 * **仍要摸的几处**（不在装配路径上，本票不动，别以为交一份说明就完事）：
 * `ui/ConnectionForm.kt` 的 `connectionFormSpec`（表单定义；未支持的来源直接抛）、
 * `core/source/StoredCredential.kt` 的 `protectStoredCredentials`（v4→v5 迁移认哪些字段是凭据）、
 * `ui/AppNav.kt` 首页的来源列表（四个来源各一行入口）。
 *
 * [buildSource] 拿到的是**已解析、已校验、已带连接行名字**的配置：装配说明不重复这三步。
 */
internal class ConnectionSpec<C : Any>(
    /** 提示文案里的来源名（「SMB」/「WebDAV」/「Komga」） */
    private val title: String,
    /** 提示文案里的凭据词：SMB/WebDAV 是「密码」，Komga 有 API Key，说「凭据」 */
    private val credentialWord: String,
    private val parseConfig: (String) -> C?,
    private val needsReentry: (C) -> Boolean,
    private val validate: (C) -> String?,
    /** 带上连接行 `displayName` 列的名字（运行期载体，不进 configJson）：报错文案与列表里的名字因此恒等 */
    private val carryRowName: (C, String) -> C,
    private val buildSource: (C, Long, SourceDeps) -> Source,
) : ConnectionAssembly {

    /** 配置的三条出路之一：解析成功返回配置，三条失败各抛出对应的类型化失败 */
    fun resolve(conn: ConnectionEntity): C {
        val config = parseConfig(conn.configJson)
            ?: throw SourceAssemblyFailure.ConfigCorrupt("$title 连接配置损坏，请重新添加")
        if (needsReentry(config)) throw SourceAssemblyFailure.CredentialReentry(reentryHint)
        validate(config)?.let { throw SourceAssemblyFailure.InvalidConfig(it) }
        return carryRowName(config, conn.displayName)
    }

    override fun build(conn: ConnectionEntity, deps: SourceDeps): Source =
        buildSource(resolve(conn), conn.id, deps)

    /**
     * 凭据解不出来的提示（票 #27 口径不变）：不崩、不静默连不上，而是告诉用户可以自己修。
     * 原先三个来源各一条常量、同一句话换来源名，收在这里；**提示里不含任何凭据内容**。
     */
    private val reentryHint: String
        get() = "${title} 连接的${credentialWord}已无法解密（密钥失效或换了设备），" +
            "请在首页点「${title}」进入连接列表，编辑该连接后重新填写${credentialWord}"
}

/**
 * 装配表（票 #136）：四个来源各一份 [ConnectionSpec]，装配路径的**唯一入口**。
 *
 * 与 `browsingSourceFor` 的分工：这里只回答「一行连接记录 → 一个可用的来源实例」，
 * 实例复用、会话槽位、释放守卫都在 `ui.ServiceLocator`（那是会话状态，不是装配）。
 */
internal object SourceAssembly {

    /** 本地来源：configJson 就是 SAF 授权 uri 原样（没有配置类，也没有凭据） */
    val local: ConnectionSpec<String> = ConnectionSpec(
        title = "本地",
        credentialWord = "凭据",
        parseConfig = { it },
        needsReentry = { false },
        // 空的授权 uri 不在这一层拦：以前就是交给 SafBackend 抛「无效的授权目录树」（表现零变化）
        validate = { null },
        carryRowName = { config, _ -> config },
        buildSource = { uri, connId, deps ->
            documentTree(
                backend = deps.safBackend(uri),
                connId = connId,
                deps = deps,
                sourceType = SourceType.LOCAL,
            )
        },
    )

    /** SMB（票 11）：配置损坏或非法时按类型化失败抛出，由 UI 展示 */
    val smb: ConnectionSpec<SmbConnectionConfig> = ConnectionSpec(
        title = "SMB",
        credentialWord = "密码",
        parseConfig = SmbConnectionConfig::fromJson,
        needsReentry = { it.credentialsNeedReentry },
        validate = SmbConnectionConfig::validate,
        carryRowName = { config, rowName -> config.copy(rowDisplayName = rowName) },
        buildSource = { config, connId, deps ->
            documentTree(
                backend = SmbBackend(ClassifyingTransport(SmbjTransport(config), config), config),
                connId = connId,
                deps = deps,
                sourceType = SourceType.SMB,
            )
        },
    )

    /** WebDAV（票 12）：同 [smb] */
    val webDav: ConnectionSpec<WebDavConnectionConfig> = ConnectionSpec(
        title = "WebDAV",
        credentialWord = "密码",
        parseConfig = WebDavConnectionConfig::fromJson,
        needsReentry = { it.credentialsNeedReentry },
        validate = WebDavConnectionConfig::validate,
        carryRowName = { config, rowName -> config.copy(rowDisplayName = rowName) },
        buildSource = { config, connId, deps ->
            documentTree(
                backend = WebDavBackend(ClassifyingWebDavTransport(HttpWebDavTransport(config), config), config),
                connId = connId,
                deps = deps,
                sourceType = SourceType.WEBDAV,
            )
        },
    )

    /** Komga（票 12）：不用封面落盘缓存与落盘列表快照（会话内列表一份，见 `Source.cachedEntries`） */
    val komga: ConnectionSpec<KomgaConnectionConfig> = ConnectionSpec(
        title = "Komga",
        credentialWord = "凭据",
        parseConfig = KomgaConnectionConfig::fromJson,
        needsReentry = { it.credentialsNeedReentry },
        validate = KomgaConnectionConfig::validate,
        carryRowName = { config, rowName -> config.copy(rowDisplayName = rowName) },
        buildSource = { config, _, deps ->
            KomgaSource(
                // 归类装饰器把 HTTP/IO 失败转成带中文提示的 KomgaException（地址不通/认证失败/超时）
                api = ClassifyingKomgaApi(HttpKomgaApi(config), config),
                config = config,
                progressStore = deps.progressStore(),
            )
        },
    )

    /**
     * 文件源（本地 / SMB / WebDAV 共用 [DocumentTreeSource]）的装配尾巴：三份 spec 的差异只有 [backend]
     * 与 [sourceType] 两个值，其余四行装配逐字同形。
     *
     * 这是工单问题陈述点名的那三行（`progressStore` / `coverCacheDir` / `listingSnapshots`）的**唯一**出处：
     * 收口前它们在 ServiceLocator 的三个分支里各写一遍，收口后若在这里再各写一遍就只是搬了家。
     *
     * [sourceType] **没有默认值**，三个调用点各显式传（本地也得写 `SourceType.LOCAL`）：
     * 默认值会与 [DocumentTreeSource] 自己的默认值**镜像**，而两份默认值之间没有任何编译期约束钉住相等
     *（不等时本地来源的 `Source.type` 会静默变），宁可在调用点多写一个词。
     */
    private fun documentTree(
        backend: FsBackend,
        connId: Long,
        deps: SourceDeps,
        sourceType: SourceType,
    ): Source = DocumentTreeSource(
        backend = backend,
        progressStore = deps.progressStore(),
        // 封面落盘缓存（票 10「封面生成后缓存」；票 #30 只在按需取封面时才写，枚举期不再写）
        coverCacheDir = deps.coverCacheDir(),
        sourceType = sourceType,
        // 列表快照落盘（票 #74）：键 = 连接 id + 容器 id
        listingSnapshots = deps.listingSnapshots(connId),
    )

    /** 已知来源的装配说明；未知来源类型返回 null（旧库残留 / 手工改库 / 降级安装） */
    private fun specFor(sourceType: String): ConnectionAssembly? = when (sourceType) {
        SourceType.LOCAL.name -> local
        SourceType.SMB.name -> smb
        SourceType.WEBDAV.name -> webDav
        SourceType.KOMGA.name -> komga
        else -> null
    }

    /**
     * 一行连接记录 → 一个可用的来源实例（[Source]）：装配路径的唯一入口。
     *
     * 未知来源按既有约定抛带中文提示的失败（「配置损坏」那一类），由 UI 统一 `runCatching` 展示
     * （浏览页/柜页/连接列表），不崩溃也不静默——提示文本与票 #136 之前逐字一致。
     */
    fun build(conn: ConnectionEntity, deps: SourceDeps): Source =
        specFor(conn.sourceType)?.build(conn, deps)
            ?: throw SourceAssemblyFailure.ConfigCorrupt(
                "来源类型未知（连接配置损坏），请重新添加该连接：" + conn.sourceType,
            )
}
