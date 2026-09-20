package com.cc3301.comicviewer.core.source.smb

import com.cc3301.comicviewer.core.source.FailingCredentialCipher
import com.cc3301.comicviewer.core.source.ForeignKeyCredentialCipher
import com.cc3301.comicviewer.core.source.StoredCredential
import com.cc3301.comicviewer.core.source.TestCredentialCipherRule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * SMB 连接配置序列化与校验（票 11）+ 表单两字段解析（票 #38）+ 密码加密存储（票 #27）；
 * JSON 走 Android 自带 org.json，故用 Robolectric。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SmbConnectionConfigTest {

    @get:Rule
    val credentialCipher = TestCredentialCipherRule()

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
    fun `密码落库为密文 解回来还是原密码`() {
        val json = full.toJson()

        assertFalse("落库文本不得含明文密码：" + json, json.contains("s3cret"))
        assertEquals("s3cret", SmbConnectionConfig.fromJson(json)!!.password)
    }

    @Test
    fun `票前的明文 configJson 照旧解析成同样的配置`() {
        // 票 #27 之前的库形状：密码明文
        val legacy =
            """{"host":"nas.local","share":"comics","rootPath":"manga","username":"reader","password":"s3cret","domain":"WORKGROUP","port":4450}"""

        assertEquals(full, SmbConnectionConfig.fromJson(legacy))
    }

    @Test
    fun `密文解不出来时降级为需重填密码 其余字段照旧可用`() {
        // 换机/密钥失效：密文是另一把密钥加密的
        val foreign = ForeignKeyCredentialCipher.encrypt("s3cret")
        val broken =
            """{"host":"nas.local","share":"comics","rootPath":"manga","username":"reader","password":"enc:v1:$foreign","domain":"WORKGROUP","port":4450}"""

        val config = SmbConnectionConfig.fromJson(broken)!!

        assertEquals("", config.password)
        assertTrue("要标记为需重新填写凭据", config.credentialsNeedReentry)
        assertEquals("nas.local", config.host)
        // 票 #72：展示名 = `主机[:端口]/共享名/子目录`
        assertEquals("nas.local:4450/comics/manga", config.displayName)
        // 解不出来的标志不进 JSON：只有密码的值变
        assertFalse(SmbConnectionConfig(host = "nas", share = "c", credentialsNeedReentry = true).toJson()
            .contains("credentialsNeedReentry"))
    }

    @Test
    fun `存量迁移把明文密码改写成密文 且可重复执行`() {
        val legacy =
            """{"host":"nas.local","share":"comics","rootPath":"manga","username":"reader","password":"s3cret","domain":"WORKGROUP","port":4450}"""

        val migrated = SmbConnectionConfig.protectSecrets(legacy)!!

        assertFalse("迁移后不得含明文：" + migrated, migrated.contains("s3cret"))
        // 只改密码的值：解出来的配置与票前形状逐字段一致（连接不用重填）
        assertEquals(full, SmbConnectionConfig.fromJson(migrated))
        // 幂等：拿迁移结果再跑一次不变（不会二次加密）
        assertEquals(migrated, SmbConnectionConfig.protectSecrets(migrated))
        // 没有敏感字段的行原样返回
        assertEquals("""{"host":"nas"}""", SmbConnectionConfig.protectSecrets("""{"host":"nas"}"""))
        // 解不出 JSON 返回 null（调用方保留原行，不阻断升级）
        assertNull(SmbConnectionConfig.protectSecrets("不是 json"))
    }

    @Test
    fun `加密不可用时迁移放弃该行 而不是落明文`() {
        StoredCredential.cipher = FailingCredentialCipher

        assertNull(SmbConnectionConfig.protectSecrets("""{"host":"nas","share":"comics","password":"s3cret"}"""))
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
    fun `展示名为主机端口与共享路径 非默认端口带端口`() {
        assertEquals("nas.local:4450/comics/manga", full.displayName)
        assertEquals("nas.local/comics/manga", full.copy(port = 445).displayName)
    }

    @Test
    fun `连接名落 configJson 的 name 键 留空则展示名回落到自动拼名`() {
        val named = full.copy(name = "我家 NAS")

        assertEquals("我家 NAS", named.displayName)
        // 存的是非敏感的明文 name 键（凭据字段才加密）
        assertTrue("configJson 里要有 name 键：" + named.toJson(), named.toJson().contains("\"name\":\"我家 NAS\""))
        assertEquals(named, SmbConnectionConfig.fromJson(named.toJson()))
        // 存量行没有该键 → 名称为空 → 展示名回落到自动拼名
        assertEquals("nas.local:4450/comics/manga", SmbConnectionConfig.fromJson(full.toJson())!!.displayName)
        assertEquals("", SmbConnectionConfig.fromJson(full.toJson())!!.name)
    }

    @Test
    fun `存量迁移（protectSecrets）不动连接名`() {
        val legacy =
            """{"host":"nas.local","share":"comics","name":"我家 NAS","password":"s3cret"}"""

        val migrated = SmbConnectionConfig.protectSecrets(legacy)!!

        assertFalse("迁移后不得含明文：" + migrated, migrated.contains("s3cret"))
        assertEquals("我家 NAS", SmbConnectionConfig.fromJson(migrated)!!.name)
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
            // smb:// 之后还带空白（评审 P2）：不清掉会静默存成带空格的主机名
            Triple("smb:// nas.local", "comics", SmbFormTarget(host = "nas.local", share = "comics")),
            Triple("smb:// 192.168.1.10:1445", "comics", SmbFormTarget(host = "192.168.1.10", port = 1445, share = "comics")),
            // 路径里的 "." 段一并折叠
            Triple("nas", ".//comics/./sub", SmbFormTarget(host = "nas", share = "comics", rootPath = "sub")),
            // IPv6 字面量：方括号（带/不带端口）与存量里未加方括号的裸串都要能解析
            Triple("[fe80::1]", "comics", SmbFormTarget(host = "fe80::1", share = "comics")),
            Triple("[fe80::1]:1445", "comics", SmbFormTarget(host = "fe80::1", port = 1445, share = "comics")),
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
        // 方括号后只允许 ":端口"；空方括号缺主机
        assertEquals("端口必须在 1–65535 之间", SmbConnectionConfig.parseFormTarget("[fe80::1]x", "comics").message)
        assertEquals("端口必须在 1–65535 之间", SmbConnectionConfig.parseFormTarget("[fe80::1]:", "comics").message)
        assertEquals("请填写服务器地址", SmbConnectionConfig.parseFormTarget("[]", "comics").message)
        // 地址问题优先于路径问题（表单按字段顺序报第一个）
        assertEquals("请填写服务器地址", SmbConnectionConfig.parseFormTarget("", "comics/../x").message)
    }

    @Test
    fun `地址回填与解析互为逆运算 主机与端口都还原`() {
        // 评审 P1：非默认端口 + IPv6 主机曾被 formatAddress 写成 `fe80::1:1445`，
        // 再解析回来变成「主机 fe80::1:1445 + 默认端口」——编辑一次就把连接改坏
        val hosts = listOf("nas.local", "192.168.1.10", "fe80::1", "[fe80::1]")
        for (host in hosts) {
            for (port in listOf(445, 1445)) {
                val address = SmbConnectionConfig.formatAddress(host, port)
                val parsed = SmbConnectionConfig.parseFormTarget(address, "comics")
                // 存量里已带方括号的 host 解析回裸字面量：方括号是表单语法（smbj 要的写法不带方括号）
                val expectedHost = if (host.startsWith("[")) "fe80::1" else host

                assertNull(address, parsed.message)
                assertEquals(address, expectedHost, parsed.host)
                assertEquals(address, port, parsed.port)
                // 回填文本本身稳定：再打开编辑框看到同一串，不改任何字段直接保存也不改配置
                assertEquals(address, SmbConnectionConfig.formatAddress(parsed.host, parsed.port))
            }
        }
    }

    @Test
    fun `连接字段回填成表单文本`() {
        assertEquals("nas.local", SmbConnectionConfig.formatAddress("nas.local", 445))
        assertEquals("nas.local:1445", SmbConnectionConfig.formatAddress("nas.local", 1445))
        // IPv6 字面量加方括号，否则 ":端口" 与地址本身分不开
        assertEquals("[fe80::1]", SmbConnectionConfig.formatAddress("fe80::1", 445))
        assertEquals("[fe80::1]:1445", SmbConnectionConfig.formatAddress("fe80::1", 1445))
        assertEquals("[fe80::1]:1445", SmbConnectionConfig.formatAddress("[fe80::1]", 1445))
        assertEquals("comics", SmbConnectionConfig.formatPath("comics", ""))
        assertEquals("comics", SmbConnectionConfig.formatPath("comics", "/"))
        assertEquals("comics/manga", SmbConnectionConfig.formatPath("comics", "manga"))
        assertEquals("comics/manga", SmbConnectionConfig.formatPath("comics", "/manga/"))
        // 非法起始目录原样带出：列表与编辑要能显示这份坏配置并让人改，而不是抛异常
        assertEquals("comics/../x", SmbConnectionConfig.formatPath("comics", "../x"))
    }
}
