package com.cc3301.comicviewer.ui

import com.cc3301.comicviewer.core.data.ConnectionEntity
import com.cc3301.comicviewer.core.source.SourceType
import com.cc3301.comicviewer.core.source.TestCredentialCipherRule
import com.cc3301.comicviewer.core.source.komga.KomgaConnectionConfig
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
 * Komga 连接表单（票 #76）：只保留邮箱 + 密码认证——API Key 字段从表单/表单值/校验里删除；
 * 「服务器地址」「邮箱」「密码」标签不带括号说明，地址格式与默认端口改由提示承载；
 * 存量 API Key 连接仍走 `KomgaConnectionConfig.apiKey` 读取路径连接与浏览，
 * 但编辑保存时必须改填邮箱+密码（校验给中文提示，不静默失败）。
 *
 * 编码走 org.json，故用 Robolectric；「保存」走真实写点 [savedConnection]。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class KomgaFormSpecTest {

    @get:Rule
    val credentialCipher = TestCredentialCipherRule()

    @Test
    fun `表单不再有 API Key 字段 只剩邮箱与密码认证`() {
        assertEquals(
            listOf("name", "baseUrl", "browsePath", "username", "password"),
            KomgaFormSpec.fields.map { it.key },
        )
        assertTrue(
            "表单不得再出现 API Key：" + KomgaFormSpec.fields.map { it.label },
            KomgaFormSpec.fields.none { it.key == "apiKey" || it.label.contains("API Key") },
        )
    }

    @Test
    fun `路径字段只读 默认根路径 只能点选`() {
        val path = KomgaFormSpec.fields.first { it.key == "browsePath" }

        // 用户可见文案不变（票 #78 修复轮：只是字段键/落库键改名 browsePath，与 baseUrl 里的 URL 路径区分）
        assertEquals("路径", path.label)
        assertTrue("键盘输入必须无效（只读）", path.readOnly)
        assertTrue("要有图标按钮的无障碍描述", path.pickerDescription.isNotBlank())
        assertEquals("新建连接的默认路径是 `/`（四入口）", "/", path.defaultValue)
        assertTrue("要有说明文字：" + path.hint, path.hint.isNotBlank())
        // 三个网络来源里只有 Komga 有路径选择器
        assertNotNull(KomgaFormSpec.pathPicker(mapOf("baseUrl" to "https://komga.example.com")))
        assertNull("SMB 没有路径选择器", SmbFormSpec.pathPicker(emptyMap()))
        assertNull("WebDAV 没有路径选择器", WebDavFormSpec.pathPicker(emptyMap()))
    }

    @Test
    fun `路径随表单保存落库并能回填`() {
        val saved = savedConnection(
            KomgaFormSpec,
            mapOf(
                "baseUrl" to "https://komga.example.com",
                "browsePath" to "/collections/c1",
                "username" to "me@example.com",
                "password" to "pw",
            ),
        )

        assertEquals("/collections/c1", KomgaConnectionConfig.fromJson(saved.configJson)!!.browsePath)
        assertEquals(
            "/collections/c1",
            KomgaFormSpec.decode(saved.configJson)["browsePath"],
        )
        // r1 的中文段名仍认（存量连接的起点不丢，票 #78 修复轮）
        assertEquals(
            "/collections/c1",
            KomgaConnectionConfig.fromJson(
                savedConnection(
                    KomgaFormSpec,
                    mapOf(
                        "baseUrl" to "https://komga.example.com",
                        "browsePath" to "/收藏/c1",
                        "username" to "me@example.com",
                        "password" to "pw",
                    ),
                ).configJson,
            )!!.browsePath,
        )
        // 非法/空值回落 `/`（票 #78：选择器只产规范值，手改/旧值也得能存）
        assertEquals(
            "/",
            KomgaConnectionConfig.fromJson(
                savedConnection(
                    KomgaFormSpec,
                    mapOf(
                        "baseUrl" to "https://komga.example.com",
                        "browsePath" to "/不认识的类别",
                        "username" to "me@example.com",
                        "password" to "pw",
                    ),
                ).configJson,
            )!!.browsePath,
        )
    }

    @Test
    fun `服务器地址与邮箱密码标签不带括号说明 格式与默认端口移到提示`() {
        val labels = KomgaFormSpec.fields.associate { it.key to it.label }
        assertEquals("服务器地址", labels["baseUrl"])
        assertEquals("邮箱", labels["username"])
        assertEquals("密码", labels["password"])

        val hint = KomgaFormSpec.fields.first { it.key == "baseUrl" }.hint
        assertTrue("提示要写出地址格式：" + hint, hint.contains("http(s)://主机:端口"))
        assertTrue("提示要明示不写端口时的默认值：" + hint, hint.contains("80") && hint.contains("443"))
    }

    @Test
    fun `校验只要求邮箱与密码 缺失时给中文提示`() {
        val missing = KomgaFormSpec.validate(mapOf("baseUrl" to "https://komga.example.com"))
        assertNotNull(missing)
        assertTrue("提示要写清该填什么：" + missing, missing!!.contains("邮箱") && missing.contains("密码"))
        assertFalse("提示不得再提 API Key：" + missing, missing.contains("API Key"))

        // 只有邮箱没密码 / 只有密码没邮箱都不行
        assertNotNull(
            KomgaFormSpec.validate(mapOf("baseUrl" to "https://komga.example.com", "username" to "me@example.com")),
        )
        assertNotNull(
            KomgaFormSpec.validate(mapOf("baseUrl" to "https://komga.example.com", "password" to "pw")),
        )
        // 邮箱 + 密码 → 合法
        assertNull(
            KomgaFormSpec.validate(
                mapOf("baseUrl" to "https://komga.example.com", "username" to "me@example.com", "password" to "pw"),
            ),
        )
    }

    @Test
    fun `表单保存不产生 apiKey 存储值与连接名也不多出端口`() {
        val saved = savedConnection(
            KomgaFormSpec,
            mapOf("baseUrl" to "https://komga.example.com", "username" to "me@example.com", "password" to "pw"),
        )
        val config = KomgaConnectionConfig.fromJson(saved.configJson)!!

        assertEquals("", config.apiKey)
        assertFalse("表单不该产生 API Key 认证：" + saved.configJson, config.usesApiKey)
        assertEquals("komga.example.com", saved.displayName)
        assertFalse("存储值不得补默认端口：" + saved.configJson, saved.configJson.contains(":443"))
    }

    @Test
    fun `存量 API Key 连接仍能进入浏览`() {
        val row = ConnectionEntity(
            sourceType = SourceType.KOMGA.name,
            displayName = "komga.example.com",
            configJson = """{"baseUrl":"https://komga.example.com","apiKey":"legacy-key"}""",
        )

        val config = ServiceLocator.komgaConfigOf(row)

        assertTrue("存量 API Key 连接必须仍按 API Key 认证", config.usesApiKey)
        assertEquals("legacy-key", config.apiKey)
    }

    @Test
    fun `存量 API Key 连接编辑保存要求填邮箱与密码`() {
        // 编辑框只回填表单字段：邮箱/密码为空，apiKey 不再进表单值
        val fields = KomgaFormSpec.decode("""{"baseUrl":"https://komga.example.com","apiKey":"legacy-key"}""")

        assertFalse("API Key 不再是表单值", fields.containsKey("apiKey"))
        val message = KomgaFormSpec.validate(fields)
        assertNotNull("保存必须被拦下并要求填邮箱与密码", message)
        assertTrue("提示要写清该填什么：" + message, message!!.contains("邮箱") && message.contains("密码"))
    }
}
