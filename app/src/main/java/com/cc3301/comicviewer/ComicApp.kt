package com.cc3301.comicviewer

import android.app.Application
import com.cc3301.comicviewer.ui.ServiceLocator

class ComicApp : Application() {
    override fun onCreate() {
        super.onCreate()
        ServiceLocator.init(this)
    }
}
