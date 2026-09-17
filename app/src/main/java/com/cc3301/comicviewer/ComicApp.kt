package com.cc3301.comicviewer

import android.app.Application
import com.cc3301.comicviewer.ui.PageDecoder
import com.cc3301.comicviewer.ui.ServiceLocator
import com.cc3301.comicviewer.ui.purgeLegacyOpdsData
import kotlinx.coroutines.launch

class ComicApp : Application() {
    override fun onCreate() {
        super.onCreate()
        ServiceLocator.init(this)
        PageDecoder.init(this)
        // OPDS 下线后的存量清理（票 33）：放 APP 级 IO 协程，冷启动不被它阻塞；
        // 清理本身吞掉一切失败，界面无需等待或感知结果
        ServiceLocator.appScope.launch { purgeLegacyOpdsData(this@ComicApp) }
    }
}
