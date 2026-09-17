package com.cc3301.comicviewer.core.source.webdav

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** WebDAV 连接配置（票 12）：JSON 往返、校验与展示名（JSON 走 Android 自带 org.json，故用 Robolectric） */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class WebDavConnectionConfigTest {

    @Test
    fun `JSON 往返保留全部字段`() {
        val config = WebDavConnectionConfig(
            baseUrl = "https://nas:5006/dav",
            rootPath = "comics",
            username = "reader",
            password = "secret",
        )
        assertEquals(config, WebDavConnectionConfig.fromJson(config.toJson()))
    }

    @Test
    fun `缺失必填字段或非法 JSON 返回 null 不崩溃`() {
        assertNull(WebDavConnectionConfig.fromJson("{}"))
        assertNull(WebDavConnectionConfig.fromJson("不是 json"))
        assertNull(WebDavConnectionConfig.fromJson("""{"rootPath":"x"}"""))
        assertNotNull(WebDavConnectionConfig.fromJson("""{"baseUrl":"http://nas/dav"}"""))
    }

    @Test
    fun `校验拦截空地址 非 http 地址 非法主机与上跳目录`() {
        assertEquals("请填写服务器地址", WebDavConnectionConfig.validate(WebDavConnectionConfig(baseUrl = " ")))
        assertTrue(
            WebDavConnectionConfig.validate(WebDavConnectionConfig(baseUrl = "nas/dav"))!!.contains("http"),
        )
        assertNotNull(WebDavConnectionConfig.validate(WebDavConnectionConfig(baseUrl = "http://")))
        assertNotNull(
            WebDavConnectionConfig.validate(WebDavConnectionConfig(baseUrl = "http://nas/dav", rootPath = "../x")),
        )
        assertNull(WebDavConnectionConfig.validate(WebDavConnectionConfig(baseUrl = "http://nas:5006/dav")))
    }

    @Test
    fun `展示名含 scheme 主机 端口与 DAV 根路径`() {
        assertEquals(
            "http://nas:5006/dav/comics",
            WebDavConnectionConfig(baseUrl = "http://nas:5006/dav", rootPath = "/comics").displayName,
        )
        assertEquals("http://nas/dav", WebDavConnectionConfig(baseUrl = "http://nas/dav").displayName)
        // 同主机的 http 与 https 必须能区分（否则列表与报错分不清是哪条连接）
        assertEquals("https://nas/dav", WebDavConnectionConfig(baseUrl = "https://nas/dav").displayName)
        // 非法 rootPath 不能让展示名抛异常（列表要能显示并让人删除这条坏配置）
        assertNotNull(WebDavConnectionConfig(baseUrl = "http://nas/dav", rootPath = "..").displayName)
    }
}
