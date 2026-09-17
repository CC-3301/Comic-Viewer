package com.cc3301.comicviewer.ui

import android.content.Context
import android.net.Uri
import com.cc3301.comicviewer.core.data.AppDatabase
import com.cc3301.comicviewer.core.data.ConnectionEntity
import com.cc3301.comicviewer.core.data.RoomProgressStore
import com.cc3301.comicviewer.core.input.WheelHandler
import com.cc3301.comicviewer.core.nav.BrowseHistory
import com.cc3301.comicviewer.core.nav.LastRead
import com.cc3301.comicviewer.core.reader.VolumeAction
import com.cc3301.comicviewer.core.source.DocumentTreeSource
import com.cc3301.comicviewer.core.source.Source
import com.cc3301.comicviewer.core.source.SourceType
import com.cc3301.comicviewer.core.source.fs.SafBackend
import com.cc3301.comicviewer.core.source.komga.ClassifyingKomgaApi
import com.cc3301.comicviewer.core.source.komga.HttpKomgaApi
import com.cc3301.comicviewer.core.source.komga.KomgaConnectionConfig
import com.cc3301.comicviewer.core.source.komga.KomgaSource
import com.cc3301.comicviewer.core.source.opds.ClassifyingOpdsApi
import com.cc3301.comicviewer.core.source.opds.HttpOpdsApi
import com.cc3301.comicviewer.core.source.opds.OpdsCache
import com.cc3301.comicviewer.core.source.opds.OpdsConnectionConfig
import com.cc3301.comicviewer.core.source.opds.OpdsSource
import com.cc3301.comicviewer.core.source.smb.ClassifyingTransport
import com.cc3301.comicviewer.core.source.smb.SmbBackend
import com.cc3301.comicviewer.core.source.smb.SmbConnectionConfig
import com.cc3301.comicviewer.core.source.smb.SmbjTransport
import com.cc3301.comicviewer.core.source.webdav.ClassifyingWebDavTransport
import com.cc3301.comicviewer.core.source.webdav.HttpWebDavTransport
import com.cc3301.comicviewer.core.source.webdav.WebDavBackend
import com.cc3301.comicviewer.core.source.webdav.WebDavConnectionConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/** 极简依赖定位（绿地阶段；后续票按需演进） */
object ServiceLocator {

    @Volatile
    private var appContext: Context? = null

    fun init(context: Context) {
        appContext = context.applicationContext
    }

    internal val context: Context get() = appContext ?: throw IllegalStateException("ServiceLocator 未初始化")

    /** APP 级协程域：退出回调等长于组合生命周期的写入 */
    val appScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    val db: AppDatabase by lazy {
        androidx.room.Room.databaseBuilder(context, AppDatabase::class.java, "comic-viewer.db").build()
    }

    /**
     * 会话当前来源：导航参数只传 id，实例跨屏复用（进程常驻）。
     * 切换来源时异步释放上一个会话资源（票 11：SMB 连接/套接字）。
     */
    @Volatile
    var currentSource: Source? = null
        set(value) {
            val previous = field
            field = value
            if (previous != null && previous !== value) {
                appScope.launch { runCatching { previous.close() } }
                // 条目名缓存随来源失效（票 13）：既防止无上限增长，也避免不同来源同名 id 串名
                entryNames.clear()
            }
        }

    /**
     * OPDS 下载缓存（票 15）：所有 OPDS 连接共用一份，上限来自设置（默认 2GB，0 = 不限制）。
     * lazy：只在真正用到（进入 OPDS 或打开设置页）时创建目录。
     */
    val opdsCache: OpdsCache by lazy {
        OpdsCache(
            dir = java.io.File(context.cacheDir, "opds"),
            limitBytesProvider = { AppSettings.opdsCacheLimitMb.toLong() * 1024L * 1024L },
        )
    }

    /**
     * 会话内的条目名缓存（票 13）：文件源的 id 能反解出文件名，Komga 的 id 只有 UUID/数字，
     * 列表里见过就记下来，供浏览页标题与阅读菜单标题使用（进程内会话级，不进 Room）。
     */
    val entryNames = java.util.concurrent.ConcurrentHashMap<String, String>()

    /** 浏览历史（票 09，spec 故事 37/38）：会话级，跨屏共享；阅读器不进历史 */
    val browseHistory = BrowseHistory()

    /** 当前浏览连接 id（与 [currentSource] 同源；书 id 只在对应连接内有效） */
    @Volatile
    var currentConnId: Long? = null

    /** 上次阅读位置（带来源；抽屉「阅读器」入口续读，票 09） */
    @Volatile
    var lastRead: LastRead? = null

    /** 阅读器音量键处理器（票 20）：阅读页在组合期间注册，返回 true = 已消费 */
    @Volatile
    var volumeKeyHandler: ((VolumeAction) -> Boolean)? = null

    /**
     * 前台界面的滚轮接入（票 17，spec 故事 22/35）：列表页与阅读页在组合期间注册，离开即清空。
     * 由 MainActivity 的分发入口读取，界面自己不需要感知平台事件。
     */
    @Volatile
    var wheelHandler: WheelHandler? = null

    /**
     * 前进侧键处理器（票 17，spec 故事 37）：由 AppNav 注册，与抽屉「前进」共用同一份实现。
     * 返回 false = 无前进历史（与抽屉按钮禁用态一致）。
     */
    @Volatile
    var forwardHistoryHandler: (() -> Boolean)? = null

    /**
     * 鼠标右键处理器（票 17，spec 故事 36）：阅读页组合期间注册，参数为点击横坐标。
     * 右键与左键等价（都是触摸区域行为），因此只有存在触摸区域的阅读页注册。
     */
    @Volatile
    var mouseSecondaryTapHandler: ((Float) -> Unit)? = null

    suspend fun sourceForConnection(conn: ConnectionEntity): Source = when (conn.sourceType) {
        SourceType.LOCAL.name -> DocumentTreeSource(
            backend = SafBackend(context, Uri.parse(conn.configJson)),
            progressStore = RoomProgressStore(db.readingProgressDao()),
            // CBZ 封面解压到应用缓存（票 10）
            coverCacheDir = context.cacheDir,
        )
        // SMB / WebDAV（票 11/12）：配置损坏或非法时直接抛中文提示，由 UI 展示
        SourceType.SMB.name -> {
            val config = configOfSmb(conn)
            DocumentTreeSource(
                backend = SmbBackend(ClassifyingTransport(SmbjTransport(config), config), config),
                progressStore = RoomProgressStore(db.readingProgressDao()),
                coverCacheDir = context.cacheDir,
                sourceType = SourceType.SMB,
            )
        }
        SourceType.WEBDAV.name -> {
            val config = WebDavConnectionConfig.fromJson(conn.configJson)
                ?: throw IllegalArgumentException("WebDAV 连接配置损坏，请重新添加")
            WebDavConnectionConfig.validate(config)?.let { throw IllegalArgumentException(it) }
            DocumentTreeSource(
                backend = WebDavBackend(ClassifyingWebDavTransport(HttpWebDavTransport(config), config), config),
                progressStore = RoomProgressStore(db.readingProgressDao()),
                coverCacheDir = context.cacheDir,
                sourceType = SourceType.WEBDAV,
            )
        }
        SourceType.KOMGA.name -> {
            val config = KomgaConnectionConfig.fromJson(conn.configJson)
                ?: throw IllegalArgumentException("Komga 连接配置损坏，请重新添加")
            KomgaConnectionConfig.validate(config)?.let { throw IllegalArgumentException(it) }
            KomgaSource(
                // 归类装饰器把 HTTP/IO 失败转成带中文提示的 KomgaException（地址不通/认证失败/超时）
                api = ClassifyingKomgaApi(HttpKomgaApi(config), config),
                config = config,
                progressStore = RoomProgressStore(db.readingProgressDao()),
            )
        }
        SourceType.OPDS.name -> {
            val config = OpdsConnectionConfig.fromJson(conn.configJson)
                ?: throw IllegalArgumentException("OPDS 连接配置损坏，请重新添加")
            OpdsConnectionConfig.validate(config)?.let { throw IllegalArgumentException(it) }
            OpdsSource(
                api = ClassifyingOpdsApi(HttpOpdsApi(config), config),
                config = config,
                progressStore = RoomProgressStore(db.readingProgressDao()),
                cache = opdsCache,
            )
        }
        else -> throw IllegalArgumentException("来源未实现：${conn.sourceType}")
    }

    /** SMB 连接配置解析（损坏/非法时抛中文提示，由 UI 展示） */
    private fun configOfSmb(conn: ConnectionEntity): SmbConnectionConfig {
        val config = SmbConnectionConfig.fromJson(conn.configJson)
            ?: throw IllegalArgumentException("SMB 连接配置损坏，请重新添加")
        SmbConnectionConfig.validate(config)?.let { throw IllegalArgumentException(it) }
        return config
    }
}
