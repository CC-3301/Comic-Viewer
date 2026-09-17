package com.cc3301.comicviewer.core.source.smb

/**
 * 连接级失败的重试策略（票 11，issue #12 AC4「连接中断有恢复机制」）。
 *
 * 抽成纯函数是为了让「重连一次」这条策略能被 JVM 单测覆盖：
 * 真实的 `SmbjTransport` 依赖 Android 网络栈与 SMB 服务器，无法在单测里跑；
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
 * 是否值得重连：超时/地址不通，或原因链上有传输中断（TransportException / SocketException）才重连；
 * 认证失败、路径不存在重连也不会成功，直接上抛让用户看到原因。
 *
 * 按类名而不是 import smbj 类型判定：既保持本文件纯 JVM 可测，也避免策略与具体库绑死
 * （与 SmbFailure 的判定方式一致）。
 */
internal fun isRecoverableSmbFailure(t: Throwable): Boolean = when (classifySmbFailure(t)) {
    SmbFailureKind.TIMEOUT, SmbFailureKind.UNREACHABLE -> true
    else -> generateSequence(t) { it.cause }.take(CAUSE_DEPTH).any { cause ->
        cause::class.java.name.contains("TransportException") ||
            cause is java.net.SocketException ||
            cause is java.io.InterruptedIOException
    }
}

private const val CAUSE_DEPTH = 6
