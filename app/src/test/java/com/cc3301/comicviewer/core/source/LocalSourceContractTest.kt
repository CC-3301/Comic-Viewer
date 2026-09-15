package com.cc3301.comicviewer.core.source

import java.io.File

/** 契约测试的本地实现绑定 */
class LocalSourceContractTest : SourceBehaviorContract() {
    override fun createSource(root: File, progressStore: ProgressStore): Source =
        LocalSource(root, progressStore)
}
