package com.cc3301.comicviewer.core.source.remote

/**
 * HTTP 状态码 → 失败原因（票 12/13：WebDAV 与 Komga 共用）。
 * 2xx 之外由各传输层调用，让「认证失败 / 路径不存在 / 超时」在 HTTP 来源里语义一致。
 */
fun httpFailureKind(status: Int): RemoteFailureKind = when {
    status == 401 || status == 403 -> RemoteFailureKind.AUTH
    status == 404 || status == 410 -> RemoteFailureKind.NOT_FOUND
    status == 408 || status == 504 -> RemoteFailureKind.TIMEOUT
    else -> RemoteFailureKind.OTHER
}

/**
 * HTTP 失败提示（带来源名，如 label = "WebDAV" / "Komga"）；
 * 405/501 单独提示「方法不被支持」，多半是把普通 HTTP 服务当成了 DAV/Komga。
 */
fun httpFailureMessage(kind: RemoteFailureKind, status: Int, target: String, label: String): String {
    if (status == 405 || status == 501) {
        return "服务器不支持该请求方法（地址可能不是 " + label + " 服务）：" + target + "（HTTP " + status + "）"
    }
    if (status == 401 || status == 403) {
        return "认证失败或访问被拒绝 " + target + "（检查用户名/密码，或 API Key）" + "（HTTP " + status + "）"
    }
    return remoteFailureMessage(kind, target, label) + "（HTTP " + status + "）"
}
