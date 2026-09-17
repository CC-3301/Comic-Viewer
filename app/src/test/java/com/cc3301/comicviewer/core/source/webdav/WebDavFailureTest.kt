package com.cc3301.comicviewer.core.source.webdav

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.FileNotFoundException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

/** WebDAV 失败归类与提示文案（票 12 AC：错误提示明确） */
class WebDavFailureTest {

    @Test
    fun `地址不通归类`() {
        assertEquals(WebDavFailureKind.UNREACHABLE, classifyWebDavFailure(UnknownHostException("nas.local")))
        assertEquals(WebDavFailureKind.UNREACHABLE, classifyWebDavFailure(ConnectException("Connection refused")))
    }

    @Test
    fun `认证失败归类 覆盖 401 与 403`() {
        assertEquals(WebDavFailureKind.AUTH, classifyWebDavFailure(RuntimeException("HTTP 401 Unauthorized")))
        assertEquals(WebDavFailureKind.AUTH, classifyWebDavFailure(RuntimeException("HTTP 403 Forbidden")))
        assertEquals(WebDavFailureKind.AUTH, classifyWebDavFailure(RuntimeException("Unauthorized")))
    }

    @Test
    fun `超时与路径不存在归类`() {
        assertEquals(WebDavFailureKind.TIMEOUT, classifyWebDavFailure(SocketTimeoutException("timeout")))
        assertEquals(WebDavFailureKind.NOT_FOUND, classifyWebDavFailure(FileNotFoundException("/dav/x")))
        assertEquals(WebDavFailureKind.NOT_FOUND, classifyWebDavFailure(RuntimeException("HTTP 404 Not Found")))
    }

    @Test
    fun `沿 cause 链递归`() {
        val wrapper = RuntimeException("包装层")
        wrapper.initCause(ConnectException("Connection refused"))
        assertEquals(WebDavFailureKind.UNREACHABLE, classifyWebDavFailure(wrapper))
    }

    @Test
    fun `未知失败归 OTHER`() {
        assertEquals(WebDavFailureKind.OTHER, classifyWebDavFailure(RuntimeException("boom")))
    }

    @Test
    fun `HTTP 状态码映射`() {
        assertEquals(WebDavFailureKind.AUTH, httpFailureKind(401))
        assertEquals(WebDavFailureKind.AUTH, httpFailureKind(403))
        assertEquals(WebDavFailureKind.NOT_FOUND, httpFailureKind(404))
        assertEquals(WebDavFailureKind.TIMEOUT, httpFailureKind(504))
        assertEquals(WebDavFailureKind.OTHER, httpFailureKind(500))
        assertEquals(WebDavFailureKind.OTHER, httpFailureKind(302))
    }

    @Test
    fun `证书不受信任单独归类 与网络错误区分`() {
        // 自签 https 的握手失败：不能退化成「WebDAV 错误」，否则用户无从下手
        val ssl = javax.net.ssl.SSLHandshakeException("Trust anchor for certification path not found")
        assertEquals(WebDavFailureKind.TLS, classifyWebDavFailure(ssl))
        assertTrue(webDavFailureMessage(WebDavFailureKind.TLS, "nas").contains("证书"))
    }

    @Test
    fun `提示文案区分原因且含上下文`() {
        assertTrue(webDavFailureMessage(WebDavFailureKind.UNREACHABLE, "nas").contains("nas"))
        assertTrue(webDavFailureMessage(WebDavFailureKind.AUTH, "nas").contains("认证失败"))
        assertTrue(webDavFailureMessage(WebDavFailureKind.TIMEOUT, "nas").contains("超时"))
        assertTrue(webDavFailureMessage(WebDavFailureKind.NOT_FOUND, "nas/dav/x").contains("nas/dav/x"))
        assertTrue(webDavFailureMessage(WebDavFailureKind.OTHER, "nas").contains("WebDAV"))
    }

    @Test
    fun `已是本类异常时不重复包装`() {
        val original = WebDavException(WebDavFailureKind.AUTH, "认证失败或访问被拒绝 nas")
        val wrapped = asWebDavException(original, "nas")
        assertEquals(original, wrapped)
    }
}
