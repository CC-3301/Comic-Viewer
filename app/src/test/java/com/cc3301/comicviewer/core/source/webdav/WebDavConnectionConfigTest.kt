package com.cc3301.comicviewer.core.source.webdav

import com.cc3301.comicviewer.core.source.ForeignKeyCredentialCipher
import com.cc3301.comicviewer.core.source.TestCredentialCipherRule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * WebDAV 连接配置（票 12）：JSON 往返、校验与展示名（JSON 走 Android 自带 org.json，故用 Robolectric）；
 * 密码加密存储与存量明文兼容见票 #27 的几条用例。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class WebDavConnectionConfigTest {

    @get:Rule
    val credentialCipher = TestCredentialCipherRule()

    private val full = WebDavConnectionConfig(
        baseUrl = "https://nas:5006/dav",
        rootPath = "comics",
        username = "reader",
        password = "secret",
    )

    @Test
    fun `JSON 往返保留全部字段`() {
        assertEquals(full, WebDavConnectionConfig.fromJson(full.toJson()))
    }

    @Test
    fun `密码落库为密文 解回来还是原密码`() {
        val json = full.toJson()

        assertFalse("落库文本不得含明文密码：" + json, json.contains("secret"))
        assertEquals("secret", WebDavConnectionConfig.fromJson(json)!!.password)
    }

    @Test
    fun `票前的明文 configJson 照旧解析 存量连接不动也能连`() {
        val legacy = """{"baseUrl":"https://nas:5006/dav","rootPath":"comics","username":"reader","password":"secret"}"""

        assertEquals(full, WebDavConnectionConfig.fromJson(legacy))
        assertEquals("https://nas:5006/dav/comics", WebDavConnectionConfig.fromJson(legacy)!!.displayName)
    }

    @Test
    fun `密文解不出来时降级为需重填密码 不崩`() {
        val foreign = ForeignKeyCredentialCipher.encrypt("secret")
        val broken = """{"baseUrl":"https://nas:5006/dav","username":"reader","password":"enc:v1:$foreign"}"""

        val config = WebDavConnectionConfig.fromJson(broken)!!

        assertEquals("", config.password)
        assertTrue(config.credentialsNeedReentry)
        assertEquals("https://nas:5006/dav", config.displayName)
    }

    @Test
    fun `存量迁移把明文密码改写成密文 且可重复执行`() {
        val legacy = """{"baseUrl":"https://nas:5006/dav","rootPath":"comics","username":"reader","password":"secret"}"""

        val migrated = WebDavConnectionConfig.protectSecrets(legacy)!!

        assertFalse(migrated.contains("secret"))
        assertEquals(full, WebDavConnectionConfig.fromJson(migrated))
        assertEquals(migrated, WebDavConnectionConfig.protectSecrets(migrated))
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
