package com.cc3301.comicviewer.core.source.komga

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Komga 连接配置（票 13）：JSON 往返、校验与展示名（JSON 走 Android 自带 org.json，故用 Robolectric） */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class KomgaConnectionConfigTest {

    @Test
    fun `JSON 往返保留全部字段`() {
        val config = KomgaConnectionConfig(
            baseUrl = "https://komga.example.com",
            username = "me@example.com",
            password = "pw",
            apiKey = "key",
        )
        assertEquals(config, KomgaConnectionConfig.fromJson(config.toJson()))
    }

    @Test
    fun `缺失地址或非法 JSON 返回 null 不崩溃`() {
        assertNull(KomgaConnectionConfig.fromJson("{}"))
        assertNull(KomgaConnectionConfig.fromJson("not json"))
        assertNotNull(KomgaConnectionConfig.fromJson("""{"baseUrl":"http://komga:25600"}"""))
    }

    @Test
    fun `校验要求 http 前缀 合法主机 且凭据二选一`() {
        assertEquals("请填写服务器地址", KomgaConnectionConfig.validate(KomgaConnectionConfig(baseUrl = " ")))
        assertTrue(
            KomgaConnectionConfig.validate(KomgaConnectionConfig(baseUrl = "komga:25600"))!!.contains("http"),
        )
        assertNotNull(KomgaConnectionConfig.validate(KomgaConnectionConfig(baseUrl = "http://")))

        // 无凭据：Komga 会 401，提前拦下
        assertTrue(
            KomgaConnectionConfig.validate(KomgaConnectionConfig(baseUrl = "http://komga:25600"))!!.contains("API Key"),
        )
        // 只有邮箱没密码 / 只有密码没邮箱都不行
        assertNotNull(
            KomgaConnectionConfig.validate(
                KomgaConnectionConfig(baseUrl = "http://komga:25600", username = "me@example.com"),
            ),
        )
        assertNotNull(
            KomgaConnectionConfig.validate(KomgaConnectionConfig(baseUrl = "http://komga:25600", password = "pw")),
        )
        // 合法组合
        assertNull(
            KomgaConnectionConfig.validate(KomgaConnectionConfig(baseUrl = "http://komga:25600", apiKey = "k")),
        )
        assertNull(
            KomgaConnectionConfig.validate(
                KomgaConnectionConfig(baseUrl = "http://komga:25600", username = "me@example.com", password = "pw"),
            ),
        )
    }

    @Test
    fun `展示名含 scheme 主机与端口 且凭据模式可区分`() {
        assertEquals("http://komga:25600", KomgaConnectionConfig(baseUrl = "http://komga:25600").displayName)
        assertEquals("https://komga/dav", KomgaConnectionConfig(baseUrl = "https://komga/dav/").displayName)
        assertTrue(KomgaConnectionConfig(baseUrl = "http://k", apiKey = "x").usesApiKey)
        assertTrue(!KomgaConnectionConfig(baseUrl = "http://k", username = "a", password = "b").usesApiKey)
    }
}
