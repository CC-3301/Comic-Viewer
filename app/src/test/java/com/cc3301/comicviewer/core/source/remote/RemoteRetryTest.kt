package com.cc3301.comicviewer.core.source.remote

import com.cc3301.comicviewer.core.source.smb.SmbException
import com.cc3301.comicviewer.core.source.smb.SmbFailureKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.ConnectException
import java.net.SocketException
import java.net.SocketTimeoutException

/**
 * 网络来源共用的重连策略与失败归类（票 11/12）。
 *
 * 真实 SMB/WebDAV 传输的重连依赖网络与服务器，无法单测；
 * 因此把「何时重连、重连几次」与「怎么归类失败」抽成纯函数在此覆盖。
 */
class RemoteRetryTest {

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
                isRecoverable = ::isRecoverableRemoteFailure,
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
                isRecoverable = ::isRecoverableRemoteFailure,
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
        assertTrue(isRecoverableRemoteFailure(SocketTimeoutException("timed out")))
        assertTrue(isRecoverableRemoteFailure(ConnectException("Connection refused")))
        // 断链常见形态：包装异常里带 SocketException
        assertTrue(isRecoverableRemoteFailure(RuntimeException("read failed", SocketException("Connection reset"))))
        assertFalse(isRecoverableRemoteFailure(SmbException(SmbFailureKind.AUTH, "认证失败")))
        assertFalse(isRecoverableRemoteFailure(SmbException(SmbFailureKind.NOT_FOUND, "路径不存在")))
    }

    @Test
    fun `归类入口可带各协议自己的文本标记`() {
        // 默认标记表：只按异常类型判
        assertEquals(RemoteFailureKind.UNREACHABLE, classifyRemoteFailure(ConnectException("refused")))
        assertEquals(RemoteFailureKind.OTHER, classifyRemoteFailure(RuntimeException("HTTP 500")))
        // 各来源传入自己的标记表（WebDAV 走 HTTP 状态码）
        val httpMarkers: (String) -> RemoteFailureKind? = { text ->
            when {
                text.contains("401") || text.contains("403") -> RemoteFailureKind.AUTH
                text.contains("404") -> RemoteFailureKind.NOT_FOUND
                else -> null
            }
        }
        assertEquals(RemoteFailureKind.AUTH, classifyRemoteFailure(RuntimeException("HTTP 401 Unauthorized"), httpMarkers))
        assertEquals(RemoteFailureKind.NOT_FOUND, classifyRemoteFailure(RuntimeException("HTTP 404"), httpMarkers))
    }
}
