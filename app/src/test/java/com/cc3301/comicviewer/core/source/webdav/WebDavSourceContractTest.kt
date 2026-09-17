package com.cc3301.comicviewer.core.source.webdav

import com.cc3301.comicviewer.core.source.DocumentTreeSource
import com.cc3301.comicviewer.core.source.ProgressStore
import com.cc3301.comicviewer.core.source.Source
import com.cc3301.comicviewer.core.source.SourceBehaviorContract
import com.cc3301.comicviewer.core.source.SourceType
import java.io.File
import java.nio.file.Files

/**
 * WebDAV 后端跑与本地同一套 Source 行为契约（票 12 AC3：浏览/排序/取页/进度/相邻书/压缩包）。
 * 传输层用文件系统伪装（FakeWebDavTransport）+ PROPFIND 解析单测（PropfindParserTest）；
 * 真实 DAV 服务器链路由真机验收清单覆盖（本机无 Docker）。
 */
class WebDavSourceContractTest : SourceBehaviorContract() {

    override fun createSource(root: File, progressStore: ProgressStore): Source {
        val config = WebDavConnectionConfig(baseUrl = "http://nas:5006/dav")
        return DocumentTreeSource(
            backend = WebDavBackend(ClassifyingWebDavTransport(FakeWebDavTransport(root), config), config),
            progressStore = progressStore,
            // 封面缓存不能放 fixture 内，否则会多出目录条目影响根列表断言
            coverCacheDir = Files.createTempDirectory("webdav-covers").toFile(),
            sourceType = SourceType.WEBDAV,
        )
    }
}
