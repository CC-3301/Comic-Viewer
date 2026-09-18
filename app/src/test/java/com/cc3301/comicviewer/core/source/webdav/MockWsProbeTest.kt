package com.cc3301.comicviewer.core.source.webdav

import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MockWsProbeTest {
    private fun multistatus(vararg body: String) =
        """<?xml version="1.0" encoding="utf-8"?>
<D:multistatus xmlns:D="DAV:">
${body.joinToString("\n")}
</D:multistatus>"""

    private fun responseXml(href: String, directory: Boolean, length: Long? = null) = """
  <D:response>
    <D:href>$href</D:href>
    <D:propstat>
      <D:prop>
        <D:resourcetype>${if (directory) "<D:collection/>" else ""}</D:resourcetype>
        ${if (length != null) "<D:getcontentlength>$length</D:getcontentlength>" else ""}
      </D:prop>
      <D:status>HTTP/1.1 200 OK</D:status>
    </D:propstat>
  </D:response>"""

    @Test
    fun probe() {
        val server = MockWebServer()
        server.start()
        val config = WebDavConnectionConfig(baseUrl = server.url("/dav").toString().trimEnd('/'), username = "reader", password = "pw")
        server.enqueue(MockResponse().setResponseCode(207).setBody(multistatus(responseXml("/dav/", true))))
        server.enqueue(MockResponse().setResponseCode(207).setBody(multistatus(responseXml("/dav/", true), responseXml("/dav/a.cbz", false, 10))))
        val transport = HttpWebDavTransport(config)
        val stat = transport.stat("/")
        println("PROBE stat=" + stat)
        val list = transport.list("/")
        println("PROBE list=" + list)
        println("PROBE requests=" + server.requestCount)
        assertEquals(2, server.requestCount)
        server.shutdown()
    }
}
