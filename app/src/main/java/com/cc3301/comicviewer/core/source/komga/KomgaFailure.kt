package com.cc3301.comicviewer.core.source.komga

import com.cc3301.comicviewer.core.source.remote.RemoteException
import com.cc3301.comicviewer.core.source.remote.RemoteFailureKind
import com.cc3301.comicviewer.core.source.remote.classifyRemoteFailure
import com.cc3301.comicviewer.core.source.remote.httpFailureKind
import com.cc3301.comicviewer.core.source.remote.httpFailureMessage

/** Komga 失败原因：枚举与各网络来源共用（地址不通/认证失败/超时/路径不存在/证书问题） */
typealias KomgaFailureKind = RemoteFailureKind

/** 归类后的 Komga 失败（message 即面向用户的提示） */
class KomgaException(
    kind: KomgaFailureKind,
    message: String,
    cause: Throwable? = null,
) : RemoteException(kind, message, cause)

/** 把任意异常转成带中文提示的 [KomgaException]（已是本类则原样返回） */
internal fun asKomgaException(t: Throwable, target: String): KomgaException {
    (t as? KomgaException)?.let { return it }
    val kind = classifyRemoteFailure(t)
    return KomgaException(kind, failureMessage(kind, target), t)
}

/** HTTP 状态码 → 带 Komga 上下文的中文提示 */
internal fun komgaHttpException(status: Int, target: String): KomgaException {
    val kind = httpFailureKind(status)
    return KomgaException(kind, httpFailureMessage(kind, status, target, "Komga"), null)
}

/** 通用文案（IO/TLS 类失败） */
private fun failureMessage(kind: KomgaFailureKind, target: String): String = when (kind) {
    KomgaFailureKind.AUTH -> "认证失败或访问被拒绝 " + target + "（检查 API Key 或邮箱/密码）"
    KomgaFailureKind.NOT_FOUND -> "Komga 上没有这个资源：" + target
    KomgaFailureKind.OTHER -> "Komga 错误（" + target + "）"
    else -> com.cc3301.comicviewer.core.source.remote.remoteFailureMessage(kind, target, "Komga")
}
