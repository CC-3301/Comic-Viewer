package com.cc3301.comicviewer.ui

import android.content.Context
import android.net.Uri
import com.cc3301.comicviewer.core.data.AppDatabase
import com.cc3301.comicviewer.core.data.ConnectionEntity
import com.cc3301.comicviewer.core.data.RoomProgressStore
import com.cc3301.comicviewer.core.nav.BrowseHistory
import com.cc3301.comicviewer.core.nav.LastRead
import com.cc3301.comicviewer.core.reader.VolumeAction
import com.cc3301.comicviewer.core.source.DocumentTreeSource
import com.cc3301.comicviewer.core.source.Source
import com.cc3301.comicviewer.core.source.SourceType
import com.cc3301.comicviewer.core.source.fs.SafBackend
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

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

    /** 会话当前来源：导航参数只传 id，实例跨屏复用（进程常驻） */
    @Volatile
    var currentSource: Source? = null

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

    suspend fun sourceForConnection(conn: ConnectionEntity): Source = when (conn.sourceType) {
        SourceType.LOCAL.name -> DocumentTreeSource(
            backend = SafBackend(context, Uri.parse(conn.configJson)),
            progressStore = RoomProgressStore(db.readingProgressDao()),
            // CBZ 封面解压到应用缓存（票 10）
            coverCacheDir = context.cacheDir,
        )
        else -> throw IllegalArgumentException("来源未实现：${conn.sourceType}")
    }
}
