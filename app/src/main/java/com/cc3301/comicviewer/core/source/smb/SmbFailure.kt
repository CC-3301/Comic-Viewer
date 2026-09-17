package com.cc3301.comicviewer.core.source.smb

import com.cc3301.comicviewer.core.source.remote.RemoteException
import com.cc3301.comicviewer.core.source.remote.RemoteFailureKind
import com.cc3301.comicviewer.core.source.remote.classifyRemoteFailure
import com.cc3301.comicviewer.core.source.remote.remoteFailureMessage

/** SMB 失败原因（issue #12 AC：错误提示要区分 地址不通 / 认证失败 / 超时）；枚举与各网络来源共用 */
typealias SmbFailureKind = RemoteFailureKind

/** 归类后的 SMB 失败（message 即面向用户的提示） */
class SmbException(
    kind: SmbFailureKind,
    message: String,
    cause: Throwable? = null,
) : RemoteException(kind, message, cause)

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
 * 底层异常 → 失败原因：类型判定 + 库状态码文本标记，沿 cause 链（共用 [classifyRemoteFailure]）。
 * 只按异常类型与消息文本判定，不引用 smbj 类型，保持纯 JVM 可测。
 */
fun classifySmbFailure(t: Throwable): SmbFailureKind = classifyRemoteFailure(t, ::classifySmbText)

private fun classifySmbText(text: String): SmbFailureKind? = when {
    TIMEOUT_MARKERS.any { text.contains(it) } -> SmbFailureKind.TIMEOUT
    AUTH_MARKERS.any { text.contains(it) } -> SmbFailureKind.AUTH
    NOT_FOUND_MARKERS.any { text.contains(it) } -> SmbFailureKind.NOT_FOUND
    else -> null
}

/** 面向用户的中文提示（区分原因，含主机/共享/路径上下文） */
fun smbFailureMessage(kind: SmbFailureKind, host: String, share: String = ""): String = when (kind) {
    // access_denied 既可能是认证失败，也可能是共享/文件级拒绝：文案两者都覆盖，避免误导
    SmbFailureKind.AUTH -> "认证失败或访问被拒绝 " + host + "（检查用户名/密码/域与共享权限）"
    SmbFailureKind.NOT_FOUND -> "路径不存在：" + host + "/" + share
    SmbFailureKind.OTHER -> "SMB 错误（" + host + "）"
    else -> remoteFailureMessage(kind, host, "SMB")
}

/** 把任意异常转成带中文提示的 [SmbException]（已是本类则原样返回；[share] 可带共享内路径） */
internal fun asSmbException(t: Throwable, host: String, share: String = ""): SmbException {
    // 协程取消不是网络失败：原样上抛
    if (t is kotlinx.coroutines.CancellationException) throw t
    (t as? SmbException)?.let { return it }
    val kind = classifySmbFailure(t)
    return SmbException(kind, smbFailureMessage(kind, host, share), t)
}
