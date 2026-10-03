package com.cc3301.comicviewer.core.source.smb

import com.cc3301.comicviewer.core.source.remote.RemoteException
import com.cc3301.comicviewer.core.source.remote.RemoteFailureKind
import com.cc3301.comicviewer.core.source.remote.classifyRemoteFailure
import com.cc3301.comicviewer.core.source.remote.isRecoverableRemoteFailure
import com.cc3301.comicviewer.core.source.remote.remoteFailureMessage

/** SMB 失败原因（错误提示要区分 地址不通 / 认证失败 / 超时）；枚举与各网络来源共用 */
typealias SmbFailureKind = RemoteFailureKind

/**
 * 归类后的 SMB 失败（message 即面向用户的提示）。
 * `open`：退避拒绝（[SmbRebuildBackedOffException]）是它的子类——
 * 打点要分得出「App 拒绝重建」与真读失败，而上层的归类路径必须照旧原样透传（`asSmbException`）。
 */
open class SmbException(
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

/**
 * `smbReadFail op=` 的取值：与 [SmbTransport] 的四个入口一一对应。
 * `stat` 这条同时覆盖心跳的共享根探活（探针走的就是 `stat(ROOT)`）。
 */
internal enum class SmbReadOp(val token: String) {
    LIST("list"),
    STAT("stat"),
    BYTES("bytes"),
    RANDOM_ACCESS("ra"),
}

/**
 * `smbReadFail kind=` 的取值：失败**那一刻**属于哪一类。
 *
 * 四类就是口径（对端断开 / 读超时 / App 主动关 / 其它），另加 [BACKOFF]：
 * 退避期内被就地拒掉的读必须与真读失败分开——判读「退避生效了没有」靠的就是它
 * （特征：`ms≈0` 且紧跟在一条 `smbRebuild failed=` 之后）。
 */
internal enum class SmbReadFailKind(val token: String) {
    /** 连接层故障：传输异常 / socket 断开 / 半包 / 地址不通（判定复用 [isRecoverableRemoteFailure]） */
    PEER_CLOSED("peerClose"),

    /** 读超时（含建连超时）：库把状态码写进消息，判定复用 [classifySmbFailure] */
    READ_TIMEOUT("readTimeout"),

    /** App 主动关：传输已释放（来源实例被换掉 / 退出），此刻任何失败都不该记到对端头上 */
    APP_CLOSE("appClose"),

    /** 退避期内被就地拒掉（[SmbRebuildBackedOffException]）：不排队、不再建，见 [SmbSessionLifecycle] */
    BACKOFF("backoff"),

    /** 其余（认证失败 / 路径不存在 / 共享名拼错 / 未知） */
    OTHER("other"),
}

/**
 * 失败那一刻的归类。
 *
 * **App 主动关优先**：传输已经释放时，一条在飞的读撞上的失败可能长成超时或 socket 断开的样子，
 * 但那是 App 自己关会话造成的，记到「对端断开」上会把判读带偏。
 * 「对端断开」直接复用 [isRecoverableRemoteFailure]：那是仓库里「连接层故障」的既有判定，
 * 不另写一份标记表（两份口径就是会过期的那种）。
 */
internal fun classifySmbReadFail(t: Throwable, appReleased: Boolean): SmbReadFailKind = when {
    appReleased -> SmbReadFailKind.APP_CLOSE
    t is SmbRebuildBackedOffException -> SmbReadFailKind.BACKOFF
    classifySmbFailure(t) == SmbFailureKind.TIMEOUT -> SmbReadFailKind.READ_TIMEOUT
    isRecoverableRemoteFailure(t) -> SmbReadFailKind.PEER_CLOSED
    else -> SmbReadFailKind.OTHER
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
