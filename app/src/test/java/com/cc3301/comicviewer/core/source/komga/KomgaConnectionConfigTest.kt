package com.cc3301.comicviewer.core.source.komga

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
 * Komga 连接配置（票 13）：JSON 往返、校验与展示名（JSON 走 Android 自带 org.json，故用 Robolectric）；
 * API Key 与密码的加密存储（票 #27，票面把 Komga 列为评估项：同一列不得落明文）。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class KomgaConnectionConfigTest {

    @get:Rule
    val credentialCipher = TestCredentialCipherRule()

    private val full = KomgaConnectionConfig(
        baseUrl = "https://komga.example.com",
        username = "me@example.com",
        password = "pw",
        apiKey = "key",
    )

    @Test
    fun `JSON 往返保留全部字段`() {
        assertEquals(full, KomgaConnectionConfig.fromJson(full.toJson()))
    }

    @Test
    fun `API Key 与密码都落密文 解回来还是原值`() {
        val json = full.toJson()

        assertFalse("落库文本不得含明文凭据：" + json, json.contains("\"key\""))
        assertFalse(json.contains("\"pw\""))
        assertEquals(full, KomgaConnectionConfig.fromJson(json))
    }

    @Test
    fun `票前的明文 configJson 照旧解析 存量连接不动也能连`() {
        val legacy =
            """{"baseUrl":"https://komga.example.com","username":"me@example.com","password":"pw","apiKey":"key"}"""

        assertEquals(full, KomgaConnectionConfig.fromJson(legacy))
        assertTrue(KomgaConnectionConfig.fromJson(legacy)!!.usesApiKey)
    }

    @Test
    fun `密文解不出来时降级为需重填凭据 不崩`() {
        val foreign = ForeignKeyCredentialCipher.encrypt("key")
        val broken =
            """{"baseUrl":"https://komga.example.com","username":"me@example.com","password":"","apiKey":"enc:v1:$foreign"}"""

        val config = KomgaConnectionConfig.fromJson(broken)!!

        assertEquals("", config.apiKey)
        assertTrue(config.credentialsNeedReentry)
        assertEquals("komga.example.com", config.displayName)
    }

    @Test
    fun `存量迁移把 API Key 与密码都改写成密文 且可重复执行`() {
        val legacy =
            """{"baseUrl":"https://komga.example.com","username":"me@example.com","password":"pw","apiKey":"key"}"""

        val migrated = KomgaConnectionConfig.protectSecrets(legacy)!!

        assertFalse(migrated.contains("\"key\""))
        assertFalse(migrated.contains("\"pw\""))
        assertEquals(full, KomgaConnectionConfig.fromJson(migrated))
        assertEquals(migrated, KomgaConnectionConfig.protectSecrets(migrated))
    }

    @Test
    fun `缺失地址或非法 JSON 返回 null 不崩溃`() {
        assertNull(KomgaConnectionConfig.fromJson("{}"))
        assertNull(KomgaConnectionConfig.fromJson("not json"))
        assertNotNull(KomgaConnectionConfig.fromJson("""{"baseUrl":"http://komga:25600"}"""))
    }

    @Test
    fun `校验要求 http 前缀 合法主机 且邮箱密码都要填`() {
        assertEquals("请填写服务器地址", KomgaConnectionConfig.validate(KomgaConnectionConfig(baseUrl = " ")))
        assertTrue(
            KomgaConnectionConfig.validate(KomgaConnectionConfig(baseUrl = "komga:25600"))!!.contains("http"),
        )
        assertNotNull(KomgaConnectionConfig.validate(KomgaConnectionConfig(baseUrl = "http://")))

        // 无凭据：Komga 会 401，提前拦下；票 #76 起表单只提供邮箱+密码，提示不再提 API Key
        assertEquals(
            "请填写邮箱与密码",
            KomgaConnectionConfig.validate(KomgaConnectionConfig(baseUrl = "http://komga:25600")),
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
        // 合法组合：邮箱+密码；存量 API Key 连接（usesApiKey）同样不被拦（仍能连接与浏览）
        assertNull(
            KomgaConnectionConfig.validate(
                KomgaConnectionConfig(baseUrl = "http://komga:25600", username = "me@example.com", password = "pw"),
            ),
        )
    }

    @Test
    fun `展示名含主机与端口 不含 scheme 且凭据模式可区分`() {
        assertEquals("komga:25600", KomgaConnectionConfig(baseUrl = "http://komga:25600").displayName)
        // 票 #72：一律去掉 scheme（维护者裁决）——显式写的端口照旧出现，去尾斜杠的口径不变
        assertEquals("komga/dav", KomgaConnectionConfig(baseUrl = "https://komga/dav/").displayName)
        assertTrue(KomgaConnectionConfig(baseUrl = "http://k", apiKey = "x").usesApiKey)
        assertTrue(!KomgaConnectionConfig(baseUrl = "http://k", username = "a", password = "b").usesApiKey)
    }

    @Test
    fun `连接名落 configJson 的 name 键 留空则展示名回落到自动拼名`() {
        val named = full.copy(name = "我家 Komga")

        assertEquals("我家 Komga", named.displayName)
        assertEquals(named, KomgaConnectionConfig.fromJson(named.toJson()))
        // 存量行没有该键 → 名称为空 → 展示名回落到自动拼名
        assertEquals("komga.example.com", KomgaConnectionConfig.fromJson(full.toJson())!!.displayName)
        assertEquals("", KomgaConnectionConfig.fromJson(full.toJson())!!.name)
    }
}
