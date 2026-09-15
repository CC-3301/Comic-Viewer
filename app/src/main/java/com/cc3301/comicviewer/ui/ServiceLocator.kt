package com.cc3301.comicviewer.ui

import android.content.Context
import android.net.Uri
import com.cc3301.comicviewer.core.data.AppDatabase
import com.cc3301.comicviewer.core.data.ConnectionEntity
import com.cc3301.comicviewer.core.data.RoomProgressStore
import com.cc3301.comicviewer.core.source.DocumentTreeSource
import com.cc3301.comicviewer.core.source.Source
import com.cc3301.comicviewer.core.source.fs.SafBackend

/** 极简依赖定位（绿地阶段；后续票按需演进） */
object ServiceLocator {

    @Volatile
    private var appContext: Context? = null

    fun init(context: Context) {
        appContext = context.applicationContext
    }

    private val context: Context get() = appContext ?: throw IllegalStateException("ServiceLocator 未初始化")

    val db: AppDatabase by lazy {
        androidx.room.Room.databaseBuilder(context, AppDatabase::class.java, "comic-viewer.db").build()
    }

    /** 会话当前来源：导航参数只传 id，实例跨屏复用（进程常驻） */
    @Volatile
    var currentSource: Source? = null

    suspend fun sourceForConnection(conn: ConnectionEntity): Source = when (conn.sourceType) {
        "LOCAL" -> DocumentTreeSource(
            backend = SafBackend(context, Uri.parse(conn.configJson)),
            progressStore = RoomProgressStore(db.readingProgressDao()),
        )
        else -> throw IllegalArgumentException("来源未实现：${conn.sourceType}")
    }
}
