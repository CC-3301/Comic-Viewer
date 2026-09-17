package com.cc3301.comicviewer.core.source.remote

import com.cc3301.comicviewer.core.source.TransportFailure
import java.io.FileNotFoundException
import java.io.InterruptedIOException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.PortUnreachableException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

/**
 * 网络来源的失败原因（票 11/12）：SMB、WebDAV、Komga 共用同一套归类，
 * 让「地址不通 / 认证失败 / 超时 / 路径不存在 / 证书问题」在各网络来源里语义一致、文案风格一致。
 */
enum class RemoteFailureKind { UNREACHABLE, AUTH, TIMEOUT, NOT_FOUND, TLS, OTHER }

/** 归类后的网络来源失败（message 即面向用户的提示）；实现 [TransportFailure] 以便元数据层不被当成文件问题吞掉 */
open class RemoteException(
    val kind: RemoteFailureKind,
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause), TransportFailure

/**
 * 底层异常 → 失败原因：沿 cause 链最多 10 层判定（防自环）。
 *
 * 先按异常类型判（可靠），再把「类名 + 消息」交给 [extraMarkers] 按各协议的文本标记判
 * （SMB 状态码、HTTP 文案等），这样各来源只需给出自己的标记表，归类顺序与深度保护共享。
 */
fun classifyRemoteFailure(
    t: Throwable,
    extraMarkers: (String) -> RemoteFailureKind? = { null },
): RemoteFailureKind {
    var current: Throwable? = t
    var depth = 0
    while (current != null && depth < MAX_CAUSE_DEPTH) {
        classifyByType(current)?.let { return it }
        extraMarkers(textOf(current))?.let { return it }
        current = current.cause
        depth++
    }
    return RemoteFailureKind.OTHER
}

/** 通用提示文案；各来源可用更具体的文案覆盖（如 SMB 的共享权限提示） */
fun remoteFailureMessage(
    kind: RemoteFailureKind,
    target: String,
    label: String = "网络",
): String = when (kind) {
    RemoteFailureKind.UNREACHABLE -> "无法连接 " + target + "（地址不通或服务未开放）"
    RemoteFailureKind.AUTH -> "认证失败或访问被拒绝 " + target + "（检查用户名/密码与权限）"
    RemoteFailureKind.TIMEOUT -> "连接 " + target + " 超时（检查网络或服务器状态）"
    RemoteFailureKind.NOT_FOUND -> "路径不存在：" + target
    // 自签证书是 NAS 的常态：给出可执行的原因，而不是笼统的网络错误
    RemoteFailureKind.TLS -> "证书不受信任 " + target + "（自签证书需先在系统里信任，或改用 http）"
    RemoteFailureKind.OTHER -> label + "错误（" + target + "）"
}

/** 消息文本（类名 + message，统一小写）供标记匹配 */
fun textOf(t: Throwable): String = (t::class.java.name + " " + (t.message ?: "")).lowercase()

private fun classifyByType(t: Throwable): RemoteFailureKind? = when {
    // SSLException 要在 IOException/FileNotFoundException 之前判（握手失败会被包在 IO 里）
    t is javax.net.ssl.SSLException -> RemoteFailureKind.TLS
    t is SocketTimeoutException || t is InterruptedIOException -> RemoteFailureKind.TIMEOUT
    t is UnknownHostException || t is ConnectException ||
        t is NoRouteToHostException || t is PortUnreachableException -> RemoteFailureKind.UNREACHABLE
    t is FileNotFoundException -> RemoteFailureKind.NOT_FOUND
    else -> null
}

private const val MAX_CAUSE_DEPTH = 10
