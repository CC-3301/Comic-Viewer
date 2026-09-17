package com.cc3301.comicviewer.core.source.smb

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** SMB 连接配置序列化与校验（票 11；JSON 走 Android 自带 org.json，故用 Robolectric） */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SmbConnectionConfigTest {

    private val full = SmbConnectionConfig(
        host = "nas.local",
        share = "comics",
        rootPath = "manga",
        username = "reader",
        password = "s3cret",
        domain = "WORKGROUP",
        port = 4450,
    )

    @Test
    fun `JSON 往返一致`() {
        assertEquals(full, SmbConnectionConfig.fromJson(full.toJson()))
    }

    @Test
    fun `损坏 JSON 或缺失必填字段返回 null`() {
        assertNull(SmbConnectionConfig.fromJson("{not json"))
        assertNull(SmbConnectionConfig.fromJson("{}"))
        assertNull(SmbConnectionConfig.fromJson("{\"host\":\"nas\"}"))
        assertNull(SmbConnectionConfig.fromJson("{\"share\":\"comics\"}"))
    }

    @Test
    fun `只填必填项时用默认端口与空凭据`() {
        val parsed = SmbConnectionConfig.fromJson(SmbConnectionConfig(host = "nas", share = "s").toJson())
        assertEquals(445, parsed!!.port)
        assertEquals("", parsed.username)
        assertEquals("", parsed.password)
        assertEquals("", parsed.domain)
        assertEquals("", parsed.rootPath)
    }

    @Test
    fun `展示名为共享加主机`() {
        assertEquals("comics @ nas.local", full.displayName)
    }

    @Test
    fun `校验空值与端口边界`() {
        assertNull(SmbConnectionConfig.validate(full))
        assertEquals("请填写服务器地址", SmbConnectionConfig.validate(full.copy(host = " ")))
        assertEquals("请填写共享名", SmbConnectionConfig.validate(full.copy(share = "")))
        assertEquals("端口必须在 1–65535 之间", SmbConnectionConfig.validate(full.copy(port = 0)))
        assertEquals("端口必须在 1–65535 之间", SmbConnectionConfig.validate(full.copy(port = 70000)))
    }
}
