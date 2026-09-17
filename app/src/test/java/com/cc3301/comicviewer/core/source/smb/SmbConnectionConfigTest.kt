package com.cc3301.comicviewer.core.source.smb

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * SMB 连接配置序列化与校验（票 11）+ 表单两字段解析（票 #38）；
 * JSON 走 Android 自带 org.json，故用 Robolectric。
 */
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
    fun `展示名为共享加主机 非默认端口带端口`() {
        assertEquals("comics @ nas.local:4450", full.displayName)
        assertEquals("comics @ nas.local", full.copy(port = 445).displayName)
    }

    @Test
    fun `校验空值与端口边界`() {
        assertNull(SmbConnectionConfig.validate(full))
        assertEquals("请填写服务器地址", SmbConnectionConfig.validate(full.copy(host = " ")))
        assertEquals("请填写路径（格式：共享名/子目录）", SmbConnectionConfig.validate(full.copy(share = "")))
        assertEquals("端口必须在 1–65535 之间", SmbConnectionConfig.validate(full.copy(port = 0)))
        assertEquals("端口必须在 1–65535 之间", SmbConnectionConfig.validate(full.copy(port = 70000)))
    }

    @Test
    fun `表单地址与路径解析成连接字段`() {
        val cases = listOf(
            // 不带端口 = 默认端口；路径只有共享名 = 共享根
            Triple("192.168.1.10", "comics", SmbFormTarget(host = "192.168.1.10", share = "comics")),
            // 带端口 + 共享内子目录
            Triple(
                "192.168.1.10:1445",
                "comics/第1话",
                SmbFormTarget(host = "192.168.1.10", port = 1445, share = "comics", rootPath = "第1话"),
            ),
            // 前后空白、smb:// 前缀、前导/尾随/重复斜杠
            Triple(
                " smb://nas.local/ ",
                "/comics//第1话/",
                SmbFormTarget(host = "nas.local", share = "comics", rootPath = "第1话"),
            ),
            // 反斜杠与混合斜杠
            Triple(
                "\\\\192.168.1.10\\",
                "comics\\第1话\\",
                SmbFormTarget(host = "192.168.1.10", share = "comics", rootPath = "第1话"),
            ),
            Triple("SMB://nas.local:4450", "comics", SmbFormTarget(host = "nas.local", port = 4450, share = "comics")),
            // 路径里的 "." 段一并折叠
            Triple("nas", ".//comics/./sub", SmbFormTarget(host = "nas", share = "comics", rootPath = "sub")),
            // 未加方括号的 IPv6 字面量含多个冒号：不拆端口，整串当主机（存量 host 可能就是它）
            Triple("fe80::1", "comics", SmbFormTarget(host = "fe80::1", share = "comics")),
        )
        for ((address, path, expected) in cases) {
            assertEquals(address + " / " + path, expected, SmbConnectionConfig.parseFormTarget(address, path))
        }
    }

    @Test
    fun `表单非法输入给中文提示`() {
        assertEquals("请填写服务器地址", SmbConnectionConfig.parseFormTarget("", "comics").message)
        assertEquals("请填写服务器地址", SmbConnectionConfig.parseFormTarget("  ", "comics").message)
        assertEquals("请填写服务器地址", SmbConnectionConfig.parseFormTarget("smb://", "comics").message)
        assertEquals("请填写服务器地址", SmbConnectionConfig.parseFormTarget(":1445", "comics").message)
        assertEquals("请填写路径（格式：共享名/子目录）", SmbConnectionConfig.parseFormTarget("nas", "").message)
        assertEquals("请填写路径（格式：共享名/子目录）", SmbConnectionConfig.parseFormTarget("nas", "//").message)
        assertEquals("路径不能包含 ..", SmbConnectionConfig.parseFormTarget("nas", "comics/../etc").message)
        assertEquals("路径不能包含 ..", SmbConnectionConfig.parseFormTarget("nas", "..").message)
        // 端口非数字 / 越界 / 写成空：都提示范围，不再把空端口当 0
        for (address in listOf("nas:0", "nas:65536", "nas:14a5", "nas:")) {
            assertEquals(address, "端口必须在 1–65535 之间", SmbConnectionConfig.parseFormTarget(address, "comics").message)
        }
        // 地址问题优先于路径问题（表单按字段顺序报第一个）
        assertEquals("请填写服务器地址", SmbConnectionConfig.parseFormTarget("", "comics/../x").message)
    }

    @Test
    fun `连接字段回填成表单文本`() {
        assertEquals("nas.local", SmbConnectionConfig.formatAddress("nas.local", 445))
        assertEquals("nas.local:1445", SmbConnectionConfig.formatAddress("nas.local", 1445))
        assertEquals("comics", SmbConnectionConfig.formatPath("comics", ""))
        assertEquals("comics", SmbConnectionConfig.formatPath("comics", "/"))
        assertEquals("comics/manga", SmbConnectionConfig.formatPath("comics", "manga"))
        assertEquals("comics/manga", SmbConnectionConfig.formatPath("comics", "/manga/"))
        // 非法起始目录原样带出：列表与编辑要能显示这份坏配置并让人改，而不是抛异常
        assertEquals("comics/../x", SmbConnectionConfig.formatPath("comics", "../x"))
    }
}
