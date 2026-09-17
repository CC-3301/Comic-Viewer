package com.cc3301.comicviewer.core.source.smb

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.FileNotFoundException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

/** SMB 失败归类与提示文案（票 11，issue #12 AC：区分地址不通/认证失败/超时） */
class SmbFailureTest {

    @Test
    fun `地址不通归类`() {
        assertEquals(SmbFailureKind.UNREACHABLE, classifySmbFailure(UnknownHostException("nas.local")))
        assertEquals(SmbFailureKind.UNREACHABLE, classifySmbFailure(ConnectException("Connection refused")))
    }

    @Test
    fun `认证失败归类 支持库状态文本`() {
        assertEquals(
            SmbFailureKind.AUTH,
            classifySmbFailure(RuntimeException("STATUS_LOGON_FAILURE (0xc000006d): Authentication failed")),
        )
        assertEquals(SmbFailureKind.AUTH, classifySmbFailure(RuntimeException("STATUS_ACCESS_DENIED")))
    }

    @Test
    fun `超时归类`() {
        assertEquals(SmbFailureKind.TIMEOUT, classifySmbFailure(SocketTimeoutException()))
        assertEquals(SmbFailureKind.TIMEOUT, classifySmbFailure(RuntimeException("connect timed out")))
    }

    @Test
    fun `路径不存在归类`() {
        assertEquals(SmbFailureKind.NOT_FOUND, classifySmbFailure(FileNotFoundException("/x")))
        assertEquals(SmbFailureKind.NOT_FOUND, classifySmbFailure(RuntimeException("STATUS_OBJECT_NAME_NOT_FOUND")))
        // 共享名拼错：库报 STATUS_BAD_NETWORK_NAME，不能归成未知错误
        assertEquals(SmbFailureKind.NOT_FOUND, classifySmbFailure(RuntimeException("STATUS_BAD_NETWORK_NAME (0xc00000cc)")))
    }

    @Test
    fun `沿 cause 链递归且环不死循环`() {
        val wrapper = RuntimeException("包装层")
        wrapper.initCause(ConnectException("Connection refused"))
        assertEquals(SmbFailureKind.UNREACHABLE, classifySmbFailure(wrapper))

        // 两层互指环（initCause 只禁止自指，双节点环合法）
        val a = RuntimeException("a")
        val b = RuntimeException("STATUS_LOGON_FAILURE")
        a.initCause(b)
        b.initCause(a)
        assertEquals(SmbFailureKind.AUTH, classifySmbFailure(a))

        // 自环（getCause 返回自身）必须靠深度上限终止
        assertEquals(SmbFailureKind.OTHER, classifySmbFailure(SelfCausedException()))
    }

    /** 自指 cause：模拟异常链成环（initCause 不允许自指，故重写 cause） */
    private class SelfCausedException : RuntimeException("自环") {
        override val cause: Throwable get() = this
    }

    @Test
    fun `未知失败归 OTHER`() {
        assertEquals(SmbFailureKind.OTHER, classifySmbFailure(RuntimeException("boom")))
    }

    @Test
    fun `提示文案区分原因且含上下文`() {
        assertTrue(smbFailureMessage(SmbFailureKind.UNREACHABLE, "nas").contains("nas"))
        assertTrue(smbFailureMessage(SmbFailureKind.AUTH, "nas").contains("认证失败"))
        assertTrue(smbFailureMessage(SmbFailureKind.TIMEOUT, "nas").contains("超时"))
        assertTrue(smbFailureMessage(SmbFailureKind.NOT_FOUND, "nas", "/comics/x").contains("/comics/x"))
        assertTrue("路径失败也要带主机，否则不知道连的是哪台", smbFailureMessage(SmbFailureKind.NOT_FOUND, "nas", "/comics/x").contains("nas"))
        assertTrue(smbFailureMessage(SmbFailureKind.OTHER, "nas").contains("nas"))
    }
}
