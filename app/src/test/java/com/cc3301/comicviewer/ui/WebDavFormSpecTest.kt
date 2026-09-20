package com.cc3301.comicviewer.ui

import com.cc3301.comicviewer.core.source.TestCredentialCipherRule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * WebDAV 连接表单的标签与提示（票 #76）：「服务器地址」标签只写字段名，
 * 原标签里的地址格式说明与「不写端口时的默认端口」改由输入框下方的提示承载（同票 #52 的真机理由）；
 * 默认端口只是提示，存储值与本连接名都不得凭空多出端口。
 *
 * 编码走 org.json，故用 Robolectric。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class WebDavFormSpecTest {

    @get:Rule
    val credentialCipher = TestCredentialCipherRule()

    @Test
    fun `服务器地址标签只写字段名 格式与默认端口改由提示承载`() {
        val labels = WebDavFormSpec.fields.associate { it.key to it.label }
        assertEquals("服务器地址", labels["baseUrl"])
        assertFalse(
            "服务器地址标签不再带括号说明：" + labels["baseUrl"],
            labels["baseUrl"]!!.contains("（") || labels["baseUrl"]!!.contains("http"),
        )
        // 原标签的格式说明不丢：搬到输入框下方的提示
        val hint = WebDavFormSpec.fields.first { it.key == "baseUrl" }.hint
        assertTrue("提示要写出地址格式：" + hint, hint.contains("http(s)://主机:端口/路径"))
        assertTrue("提示要明示不写端口时的默认值：" + hint, hint.contains("80") && hint.contains("443"))
    }

    @Test
    fun `其余字段标签不动`() {
        val labels = WebDavFormSpec.fields.associate { it.key to it.label }
        assertEquals(
            listOf("name", "baseUrl", "rootPath", "username", "password"),
            WebDavFormSpec.fields.map { it.key },
        )
        assertEquals("起始目录（可空）", labels["rootPath"])
        assertEquals("用户名（可空）", labels["username"])
        assertEquals("密码（可空）", labels["password"])
    }

    @Test
    fun `不写端口时存储值与连接名都不多出端口`() {
        val saved = savedConnection(WebDavFormSpec, mapOf("baseUrl" to "https://nas/dav", "rootPath" to "comics"))

        assertEquals("nas/dav/comics", saved.displayName)
        assertFalse("存储值不得补默认端口：" + saved.configJson, saved.configJson.contains(":443"))
    }
}
