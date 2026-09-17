package com.cc3301.comicviewer.core.source.smb

import com.cc3301.comicviewer.core.source.TransportFailure
import java.io.FileNotFoundException
import java.io.InterruptedIOException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.PortUnreachableException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

/** SMB 失败原因（issue #12 AC：错误提示要区分 地址不通 / 认证失败 / 超时） */
enum class SmbFailureKind { UNREACHABLE, AUTH, TIMEOUT, NOT_FOUND, OTHER }

/** 归类后的 SMB 失败（message 即面向用户的提示）；实现 [TransportFailure] 以便元数据解析层不被当成文件问题吞掉 */
class SmbException(
    val kind: SmbFailureKind,
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause), TransportFailure

/** 超时类文本标记（库把状态码写进消息） */
private val TIMEOUT_MARKERS = listOf("timeout", "timed out")

/** 认证类文本标记（SMB 状态码 / 库消息） */
private val AUTH_MARKERS = listOf(
    "status_logon_failure",
    "status_access_denied",
    "status_account_disabled",
    "status_password_expired",
    "logon_failure",
    "access_denied",
    "authentication",
)

/**
 * 路径不存在类文本标记（含共享名拼错：库报 STATUS_BAD_NETWORK_NAME）
 */
private val NOT_FOUND_MARKERS = listOf(
    "status_object_name_not_found",
    "status_object_path_not_found",
    "status_no_such_file",
    "status_bad_network_name",
    "bad_network_name",
)

/**
 * 底层异常 → 失败原因：沿 cause 链最多 10 层判定（防自环），
 * 只按异常类型与消息文本判定，不引用 smbj 类型，保持纯 JVM 可测。
 */
fun classifySmbFailure(t: Throwable): SmbFailureKind {
    var current: Throwable? = t
    var depth = 0
    while (current != null && depth < MAX_CAUSE_DEPTH) {
        classifyOne(current)?.let { return it }
        current = current.cause
        depth++
    }
    return SmbFailureKind.OTHER
}

/** 单层判定：先看异常类型（可靠），再看消息文本（库把状态码写在消息里） */
private fun classifyOne(t: Throwable): SmbFailureKind? {
    val text = (t::class.java.name + " " + (t.message ?: "")).lowercase()
    return when {
        t is SocketTimeoutException || t is InterruptedIOException -> SmbFailureKind.TIMEOUT
        t is UnknownHostException || t is ConnectException ||
            t is NoRouteToHostException || t is PortUnreachableException -> SmbFailureKind.UNREACHABLE
        t is FileNotFoundException -> SmbFailureKind.NOT_FOUND
        TIMEOUT_MARKERS.any { text.contains(it) } -> SmbFailureKind.TIMEOUT
        AUTH_MARKERS.any { text.contains(it) } -> SmbFailureKind.AUTH
        NOT_FOUND_MARKERS.any { text.contains(it) } -> SmbFailureKind.NOT_FOUND
        else -> null
    }
}

/** 面向用户的中文提示（区分原因，含主机/共享/路径上下文） */
fun smbFailureMessage(kind: SmbFailureKind, host: String, share: String = ""): String = when (kind) {
    SmbFailureKind.UNREACHABLE -> "无法连接 " + host + "（地址不通或服务未开放）"
    // access_denied 既可能是认证失败，也可能是共享/文件级拒绝：文案两者都覆盖，避免误导
    SmbFailureKind.AUTH -> "认证失败或访问被拒绝 " + host + "（检查用户名/密码/域与共享权限）"
    SmbFailureKind.TIMEOUT -> "连接 " + host + " 超时（检查网络或服务器状态）"
    SmbFailureKind.NOT_FOUND -> "路径不存在：" + host + "/" + share
    SmbFailureKind.OTHER -> "SMB 错误（" + host + "）"
}

private const val MAX_CAUSE_DEPTH = 10
