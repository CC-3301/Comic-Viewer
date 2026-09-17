package com.cc3301.comicviewer.core.source.smb

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.ConnectException
import java.net.SocketException
import java.net.SocketTimeoutException

/**
 * 连接级失败的重连策略（票 11，issue #12 AC4）。
 *
 * 真实 `SmbjTransport` 的重连依赖 Android 网络栈与 SMB 服务器，无法单测；
 * 因此把「何时重连、重连几次」抽成纯函数 [retryOnce] 与 [isRecoverableSmbFailure] 在此覆盖，
 * SmbjTransport 只负责提供 reconnect（关旧会话）与真实 IO。
 */
class SmbRetryTest {

    @Test
    fun `可恢复失败重连一次后成功`() {
        var reconnects = 0
        var attempts = 0
        val result = retryOnce(
            isRecoverable = { true },
            reconnect = { reconnects++ },
            block = {
                attempts++
                if (attempts == 1) throw SocketTimeoutException("timed out")
                "page"
            },
        )
        assertEquals("page", result)
        assertEquals("只重连一次", 1, reconnects)
        assertEquals(2, attempts)
    }

    @Test
    fun `认证类失败不重连 原样上抛`() {
        var reconnects = 0
        val thrown = assertThrows(SmbException::class.java) {
            retryOnce(
                isRecoverable = ::isRecoverableSmbFailure,
                reconnect = { reconnects++ },
                block = { throw SmbException(SmbFailureKind.AUTH, "认证失败") },
            )
        }
        assertEquals(SmbFailureKind.AUTH, thrown.kind)
        assertEquals("不该做无意义重连", 0, reconnects)
    }

    @Test
    fun `重连后仍失败只重试一次 异常冒泡`() {
        var reconnects = 0
        var attempts = 0
        assertThrows(SocketTimeoutException::class.java) {
            retryOnce(
                isRecoverable = ::isRecoverableSmbFailure,
                reconnect = { reconnects++ },
                block = {
                    attempts++
                    throw SocketTimeoutException("timed out")
                },
            )
        }
        assertEquals(1, reconnects)
        assertEquals("总尝试次数 = 首次 + 一次重试", 2, attempts)
    }

    @Test
    fun `归类联动 超时与不通可恢复 认证与路径不存在不可恢复`() {
        assertTrue(isRecoverableSmbFailure(SocketTimeoutException("timed out")))
        assertTrue(isRecoverableSmbFailure(ConnectException("Connection refused")))
        // 断链常见形态：包装异常里带 SocketException
        assertTrue(isRecoverableSmbFailure(RuntimeException("read failed", SocketException("Connection reset"))))
        assertFalse(isRecoverableSmbFailure(SmbException(SmbFailureKind.AUTH, "认证失败")))
        assertFalse(isRecoverableSmbFailure(SmbException(SmbFailureKind.NOT_FOUND, "路径不存在")))
    }
}
