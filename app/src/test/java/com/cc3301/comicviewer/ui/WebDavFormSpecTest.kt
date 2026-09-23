package com.cc3301.comicviewer.ui

import com.cc3301.comicviewer.core.source.TestCredentialCipherRule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * WebDAV 连接表单的标签与提示（票 #76 / #131）：「服务器地址」标签只写字段名，字段下方**不再有说明文字**
 * （票 #131 删掉了 hint 机制：真机上带括号的长标签会换行并被输入框边框缺口截掉，理由同票 #52）；
 * 默认端口不在界面上显示，存储值与本连接名都不得凭空多出端口。
 *
 * 编码走 org.json，故用 Robolectric。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class WebDavFormSpecTest {

    @get:Rule
    val credentialCipher = TestCredentialCipherRule()

    @Test
    fun `服务器地址标签只写字段名`() {
        val labels = WebDavFormSpec.fields.associate { it.key to it.label }
        assertEquals("服务器地址", labels["baseUrl"])
        assertFalse(
            "服务器地址标签不再带括号说明：" + labels["baseUrl"],
            labels["baseUrl"]!!.contains("（") || labels["baseUrl"]!!.contains("http"),
        )
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
