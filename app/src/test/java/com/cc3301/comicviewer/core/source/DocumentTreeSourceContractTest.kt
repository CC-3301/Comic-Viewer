package com.cc3301.comicviewer.core.source

import com.cc3301.comicviewer.core.source.fs.FileBackend
import java.io.File

/** 契约测试的本地 File 后端绑定（SAF 后端行为等价，真机手动验收） */
class DocumentTreeSourceContractTest : SourceBehaviorContract() {
    override fun createSource(root: File, progressStore: ProgressStore): Source =
        DocumentTreeSource(FileBackend(root), progressStore)
}
