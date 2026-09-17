package com.cc3301.comicviewer.core.source.remote

import java.io.InterruptedIOException
import java.net.SocketException

/**
 * 连接级失败的重试策略（票 11/12：issue #12 AC4「连接中断有恢复机制」）。
 *
 * 抽成纯函数是为了让「重连一次」这条策略能被 JVM 单测覆盖：
 * 真实的 SMB/WebDAV 传输依赖网络与服务器，无法在单测里跑；
 * 策略（何时重连、重连几次）与传输实现（怎么重连）分开后，
 * 前者有测试，后者只需保证把 reconnect 传对。
 */
internal fun <T> retryOnce(
    isRecoverable: (Throwable) -> Boolean,
    reconnect: () -> Unit,
    block: () -> T,
): T = try {
    block()
} catch (t: Throwable) {
    if (!isRecoverable(t)) throw t
    reconnect()
    block()
}

/**
 * 是否值得重连：超时、地址不通，或原因链上有传输中断（TransportException / SocketException）
 * 才重连；认证失败、路径不存在重连也不会成功，直接上抛让用户看到原因。
 *
 * 按类名而不是 import 具体库类型判定：既保持本文件纯 JVM 可测，也避免策略与库绑死
 * （与 [classifyRemoteFailure] 的判定方式一致）。
 */
internal fun isRecoverableRemoteFailure(t: Throwable): Boolean = when (classifyRemoteFailure(t)) {
    RemoteFailureKind.TIMEOUT, RemoteFailureKind.UNREACHABLE -> true
    else -> generateSequence(t) { it.cause }.take(CAUSE_DEPTH).any { cause ->
        val name = cause::class.java.name
        // smbj 与 OkHttp 的断链形态：传输异常、socket 被重置、半个响应体（HTTP/1 EOF、HTTP/2 stream reset）
        name.contains("TransportException") ||
            name.contains("StreamResetException") ||
            name.contains("ConnectionShutdownException") ||
            cause is SocketException ||
            cause is java.io.EOFException ||
            cause is InterruptedIOException
    }
}

private const val CAUSE_DEPTH = 6
