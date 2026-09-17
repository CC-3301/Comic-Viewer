package com.cc3301.comicviewer.core.source.opds

import com.cc3301.comicviewer.core.source.remote.RemoteException
import com.cc3301.comicviewer.core.source.remote.RemoteFailureKind
import com.cc3301.comicviewer.core.source.remote.classifyRemoteFailure
import com.cc3301.comicviewer.core.source.remote.httpFailureKind
import com.cc3301.comicviewer.core.source.remote.httpFailureMessage
import com.cc3301.comicviewer.core.source.remote.remoteFailureMessage
import java.io.File

/** OPDS 失败原因：枚举与各网络来源共用（地址不通/认证失败/超时/路径不存在/证书问题） */
typealias OpdsFailureKind = RemoteFailureKind

/** 归类后的 OPDS 失败（message 即面向用户的提示） */
class OpdsException(
    kind: OpdsFailureKind,
    message: String,
    cause: Throwable? = null,
) : RemoteException(kind, message, cause)

/** 下载进度回调（票 15）：已下载/总量（总量未知为 null） */
typealias DownloadListener = (downloaded: Long, total: Long?) -> Unit

/**
 * OPDS 访问窄接口（票 15）：取 feed XML 与下载获取链接。
 * 实现：`HttpOpdsApi`（OkHttp）；测试：`FakeOpdsApi`（内存 feed + 内存下载）。
 */
interface OpdsApi : AutoCloseable {
    /** 取 feed XML；失败必须抛（不返回空串） */
    fun fetchFeed(url: String): String

    /**
     * 下载到 [target]（先写临时文件再原子改名）；[onProgress] 用于界面进度反馈。
     * [isActive] 返回 false 时立即中止并删掉半成品（退出阅读页/切换来源后不该继续占着连接与 IO 线程）。
     */
    fun download(
        url: String,
        target: File,
        isActive: () -> Boolean = { true },
        onProgress: DownloadListener,
    )
}

/**
 * 失败归类装饰器（票 15）：把底层 HTTP/IO 异常统一转成 [OpdsException]，
 * 使 UI 能区分 地址不通 / 认证失败 / 超时 / 证书问题 / 路径不存在。
 */
class ClassifyingOpdsApi(
    private val delegate: OpdsApi,
    private val config: OpdsConnectionConfig,
) : OpdsApi {

    override fun fetchFeed(url: String): String = classify("feed " + url) { delegate.fetchFeed(url) }

    override fun download(
        url: String,
        target: File,
        isActive: () -> Boolean,
        onProgress: DownloadListener,
    ): Unit = classify("下载 " + url) { delegate.download(url, target, isActive, onProgress) }

    override fun close() {
        runCatching { delegate.close() }
    }

    private fun <T> classify(what: String, block: () -> T): T = try {
        block()
    } catch (t: Throwable) {
        throw asOpdsException(t, config.displayName + " 的 " + what)
    }
}

/** 把任意异常转成带中文提示的 [OpdsException]（已是本类则原样返回） */
internal fun asOpdsException(t: Throwable, target: String): OpdsException {
    // 协程取消不是网络失败：原样上抛，否则用户退出阅读页会看到一条假的错误提示
    if (t is kotlinx.coroutines.CancellationException) throw t
    (t as? OpdsException)?.let { return it }
    val kind = classifyRemoteFailure(t)
    return OpdsException(kind, opdsFailureMessage(kind, target), t)
}

/** HTTP 状态码 → 带 OPDS 上下文的中文提示 */
internal fun opdsHttpException(status: Int, target: String): OpdsException {
    val kind = httpFailureKind(status)
    return OpdsException(kind, httpFailureMessage(kind, status, target, "OPDS"), null)
}

/** 通用文案（IO/TLS 类失败） */
private fun opdsFailureMessage(kind: OpdsFailureKind, target: String): String = when (kind) {
    OpdsFailureKind.AUTH -> "认证失败或访问被拒绝 " + target + "（检查用户名/密码）"
    OpdsFailureKind.NOT_FOUND -> "OPDS 上没有这个地址：" + target
    OpdsFailureKind.OTHER -> "OPDS 错误（" + target + "）"
    else -> remoteFailureMessage(kind, target, "OPDS")
}
