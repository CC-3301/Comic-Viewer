package com.cc3301.comicviewer.core.source.webdav

import com.cc3301.comicviewer.core.source.remote.RemoteException
import com.cc3301.comicviewer.core.source.remote.RemoteFailureKind
import com.cc3301.comicviewer.core.source.remote.classifyRemoteFailure
import com.cc3301.comicviewer.core.source.remote.remoteFailureMessage

/** WebDAV 失败原因：枚举与各网络来源共用（地址不通/认证失败/超时/路径不存在） */
typealias WebDavFailureKind = RemoteFailureKind

/** 归类后的 WebDAV 失败（message 即面向用户的提示） */
class WebDavException(
    kind: WebDavFailureKind,
    message: String,
    cause: Throwable? = null,
) : RemoteException(kind, message, cause)

/** HTTP 状态与常见文案标记（OkHttp 的异常消息里会带状态码或原因短语） */
private val AUTH_MARKERS = listOf("401", "403", "unauthorized", "forbidden")
private val NOT_FOUND_MARKERS = listOf("404", "not found")
private val TIMEOUT_MARKERS = listOf("timeout", "timed out")
private val TLS_MARKERS = listOf("ssl", "certificate", "trust anchor", "certpath")

/**
 * 底层异常 → 失败原因：共用的类型判定（超时/不通/不存在）+ HTTP 状态码文本标记。
 * 传输层遇到非 2xx 响应时会直接用 [httpFailureKind] 归类并抛出带文案的 [WebDavException]。
 */
fun classifyWebDavFailure(t: Throwable): WebDavFailureKind = classifyRemoteFailure(t, ::classifyWebDavText)

private fun classifyWebDavText(text: String): WebDavFailureKind? = when {
    TIMEOUT_MARKERS.any { text.contains(it) } -> WebDavFailureKind.TIMEOUT
    AUTH_MARKERS.any { text.contains(it) } -> WebDavFailureKind.AUTH
    NOT_FOUND_MARKERS.any { text.contains(it) } -> WebDavFailureKind.NOT_FOUND
    TLS_MARKERS.any { text.contains(it) } -> WebDavFailureKind.TLS
    else -> null
}

/** HTTP 状态码 → 失败原因（2xx 之外由传输层调用） */
fun httpFailureKind(status: Int): WebDavFailureKind = when {
    status == 401 || status == 403 -> WebDavFailureKind.AUTH
    status == 404 || status == 410 -> WebDavFailureKind.NOT_FOUND
    status == 408 || status == 504 -> WebDavFailureKind.TIMEOUT
    else -> WebDavFailureKind.OTHER
}

/** 面向用户的中文提示（区分原因，含 URL/路径上下文） */
fun webDavFailureMessage(kind: WebDavFailureKind, target: String): String = when (kind) {
    WebDavFailureKind.AUTH -> "认证失败或访问被拒绝 " + target + "（检查用户名/密码与权限）"
    WebDavFailureKind.NOT_FOUND -> "路径不存在：" + target
    WebDavFailureKind.OTHER -> "WebDAV 错误（" + target + "）"
    else -> remoteFailureMessage(kind, target, "WebDAV")
}

/** 把任意异常转成带中文提示的 [WebDavException]（已是本类则原样返回） */
internal fun asWebDavException(t: Throwable, target: String): WebDavException {
    (t as? WebDavException)?.let { return it }
    val kind = classifyWebDavFailure(t)
    return WebDavException(kind, webDavFailureMessage(kind, target), t)
}
