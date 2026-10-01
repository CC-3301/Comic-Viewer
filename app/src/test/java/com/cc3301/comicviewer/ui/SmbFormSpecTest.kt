package com.cc3301.comicviewer.ui

import com.cc3301.comicviewer.core.source.ForeignKeyCredentialCipher
import com.cc3301.comicviewer.core.source.SourceType
import com.cc3301.comicviewer.core.source.TestCredentialCipherRule
import com.cc3301.comicviewer.core.source.smb.ClassifyingTransport
import com.cc3301.comicviewer.core.source.smb.FakeSmbTransport
import com.cc3301.comicviewer.core.source.smb.SmbBackend
import com.cc3301.comicviewer.core.source.smb.SmbConnectionConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.nio.file.Files

/**
 * SMB 连接表单（票 #38）：字段收成「服务器地址（可含端口）+ 路径（共享名/子目录）」，
 * 老配置（share 与 rootPath 分开存）编辑时回填成一条路径、保存后仍能建后端浏览。
 * 密码自票 #27 起落密文，编辑回填拿到的仍是明文（表单不感知加密）。
 * 编码走 org.json，故用 Robolectric。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SmbFormSpecTest {

    @get:Rule
    val credentialCipher = TestCredentialCipherRule()

    /** 表单值：只给两个目标字段，凭据留空（与「可空」字段一致） */
    private fun values(address: String, path: String) = mapOf("address" to address, "path" to path)

    @Test
    fun `表单只有名称 地址 路径 用户名 密码 域六个字段 端口与共享名不再出现`() {
        assertEquals("SMB", SmbFormSpec.title)
        assertEquals(SourceType.SMB, SmbFormSpec.sourceType)
        assertEquals(
            listOf("name", "address", "path", "username", "password", "domain"),
            SmbFormSpec.fields.map { it.key },
        )
    }

    @Test
    fun `两个长标签收成服务器地址与路径 其余字段保持可空`() {
        // 票 #52：带括号说明的长标签在真机上换行、第一行被输入框边框缺口截掉（references/9.jpg）
        val labels = SmbFormSpec.fields.associate { it.key to it.label }
        assertEquals("服务器地址", labels["address"])
        assertEquals("路径", labels["path"])
        // 其余四个字段短，保留「（可空）」
        assertEquals("名称（可空）", labels["name"])
        assertEquals("用户名（可空）", labels["username"])
        assertEquals("密码（可空）", labels["password"])
        assertEquals("域（可空）", labels["domain"])
        // 端口与路径格式的说明改由报错文案承载（见下面的校验提示）
        assertTrue(
            "标签不再携带格式说明（如…/共享名/端口）",
            SmbFormSpec.fields.none { it.label.contains("如") || it.label.contains("共享名") || it.label.contains("端口") },
        )
    }

    @Test
    fun `校验按新字段给中文提示`() {
        assertEquals("请填写服务器地址", SmbFormSpec.validate(values(address = " ", path = "comics")))
        assertEquals("请填写路径（格式：共享名/子目录）", SmbFormSpec.validate(values(address = "nas", path = " / ")))
        assertEquals("路径不能包含 ..", SmbFormSpec.validate(values(address = "nas", path = "comics/../etc")))
        assertEquals("端口必须在 1–65535 之间", SmbFormSpec.validate(values(address = "nas:0", path = "comics")))
        assertEquals("端口必须在 1–65535 之间", SmbFormSpec.validate(values(address = "nas:65536", path = "comics")))
        // 不写端口 = 默认 445：不再有「端口留空 → 0 → 报错」
        assertNull(SmbFormSpec.validate(values(address = "192.168.1.10", path = "comics")))
        assertNull(SmbFormSpec.validate(values(address = " smb://192.168.1.10:1445 ", path = "/comics/第1话/")))
        assertNull(SmbFormSpec.validate(values(address = "[fe80::1]:1445", path = "comics")))
    }

    @Test
    fun `地址写端口时展示名 存储字段与节点 id 前缀都带端口`() {
        val fields = values(address = "192.168.1.10:1445", path = "comics/第1话")
        // 票 #72：展示名改成 `主机[:端口]/共享名/子目录`（不再用 `共享名 @ 主机`），端口只在显式配置过时出现
        assertEquals("192.168.1.10:1445/comics/第1话", SmbFormSpec.displayName(fields))

        val config = SmbConnectionConfig.fromJson(SmbFormSpec.encode(fields))!!
        assertEquals(
            // 表单里写了端口 → 存下「显式写过」标志（票 #72 r2），展示名因此带出端口
            SmbConnectionConfig(host = "192.168.1.10", share = "comics", rootPath = "第1话", port = 1445, portExplicit = true),
            config,
        )

        val root = Files.createTempDirectory("smb-form-port").toFile().apply { File(this, "第1话").mkdirs() }
        val backend = SmbBackend(
            ClassifyingTransport(FakeSmbTransport(root, config.host, config.share), config),
            config,
        )
        // 非默认端口必须进 id 前缀：同主机同共享的两个端口不得共用 id 与进度键
        assertEquals("smb://192.168.1.10:1445/comics/第1话", backend.root.id)
    }

    @Test
    fun `老配置编辑回填成一条路径 保存后除密码外都不变`() {
        // 票 #38 之前 UI 写出的形状：host / share / rootPath / 凭据 / port 分开存
        val legacy = """{"host":"nas.local","share":"comics","rootPath":"manga","username":"reader","password":"s3cret","domain":"WORKGROUP","port":4450}"""
        val fields = SmbFormSpec.decode(legacy)

        assertEquals("nas.local:4450", fields["address"])
        assertEquals("comics/manga", fields["path"])
        assertEquals("reader", fields["username"])
        assertEquals("s3cret", fields["password"])
        assertEquals("WORKGROUP", fields["domain"])

        // 票 #27 起密码落密文：改动只在密码的值上，其余字段与回填文本逐字不变，
        // 编辑一次不改任何东西再保存也不会把连接改坏（解回来的配置与票前形状一致）
        val saved = SmbFormSpec.encode(fields)
        assertFalse("保存后不得有明文密码：" + saved, saved.contains("s3cret"))
        assertEquals(
            SmbConnectionConfig(
                host = "nas.local",
                share = "comics",
                rootPath = "manga",
                username = "reader",
                password = "s3cret",
                domain = "WORKGROUP",
                port = 4450,
                // 回填文本带端口，保存后记为「显式写过」（票 #72 r2）：除密码与这个标志外逐字段不变
                portExplicit = true,
            ),
            SmbConnectionConfig.fromJson(saved),
        )
    }

    @Test
    fun `表单保存后密码落密文 编辑回填拿到的仍是明文`() {
        val fields = values(address = "nas.local", path = "comics") +
            mapOf("username" to "reader", "password" to "s3cret")

        val saved = SmbFormSpec.encode(fields)

        assertFalse("落库文本不得含明文密码：" + saved, saved.contains("s3cret"))
        // 展示层（编辑回填）看到的仍是明文：界面不感知加密，改动只在存储层
        assertEquals("s3cret", SmbFormSpec.decode(saved)["password"])
        // 展示名不受加密影响
        assertEquals("nas.local/comics", SmbFormSpec.displayName(fields))
        assertEquals("nas.local/comics", SmbFormSpec.displayName(SmbFormSpec.decode(saved)))
    }

    @Test
    fun `密文解不出来时表单回填的密码为空 其余字段照旧`() {
        val foreign = ForeignKeyCredentialCipher.encrypt("s3cret")
        val broken =
            """{"host":"nas.local","share":"comics","username":"reader","password":"enc:v1:$foreign"}"""

        val fields = SmbFormSpec.decode(broken)

        assertEquals("", fields["password"])
        assertEquals("nas.local", fields["address"])
        assertEquals("comics", fields["path"])
        assertEquals("reader", fields["username"])
    }

    @Test
    fun `IPv6 主机加非默认端口的存量配置编辑保存后主机与端口都不变`() {
        // 评审 P1 复现路径：fe80::1 + 1445 曾被回填成 `fe80::1:1445` 再解析成「主机 fe80::1:1445 + 445」
        val legacy = """{"host":"fe80::1","share":"comics","port":1445}"""
        val fields = SmbFormSpec.decode(legacy)

        assertEquals("[fe80::1]:1445", fields["address"])
        val saved = SmbFormSpec.encode(fields)
        assertEquals(
            SmbConnectionConfig(host = "fe80::1", share = "comics", port = 1445, portExplicit = true),
            SmbConnectionConfig.fromJson(saved),
        )
        // 再打开一次编辑框看到的地址不变（编辑-不改-保存是幂等的）
        assertEquals(fields["address"], SmbFormSpec.decode(saved)["address"])
        assertEquals("[fe80::1]:1445/comics", SmbFormSpec.displayName(fields))
    }

    @Test
    fun `存量里已带方括号的 IPv6 主机保存后去掉方括号 地址含义不变`() {
        val legacy = """{"host":"[fe80::1]","share":"comics","port":1445}"""
        val fields = SmbFormSpec.decode(legacy)

        assertEquals("[fe80::1]:1445", fields["address"])
        // 方括号是表单语法（smbj 要的 host 不带方括号）：保存时规范化掉，主机与端口都不变
        assertEquals(
            SmbConnectionConfig(host = "fe80::1", share = "comics", port = 1445, portExplicit = true),
            SmbConnectionConfig.fromJson(SmbFormSpec.encode(fields)),
        )
    }

    @Test
    fun `老配置省略端口与凭据时按默认值回填并保存`() {
        val fields = SmbFormSpec.decode("""{"host":"nas","share":"comics"}""")

        assertEquals("nas", fields["address"])
        assertEquals("comics", fields["path"])
        assertEquals(
            SmbConnectionConfig(host = "nas", share = "comics"),
            SmbConnectionConfig.fromJson(SmbFormSpec.encode(fields)),
        )
    }

    @Test
    fun `老配置编辑保存后仍能建后端浏览原共享内目录`() {
        val root = Files.createTempDirectory("smb-form-legacy").toFile().apply {
            File(this, "manga").mkdirs()
            File(this, "manga/series-a").mkdirs()
            File(this, "manga/series-a/page1.jpg").writeBytes("p1".toByteArray())
        }
        val legacy = """{"host":"nas","share":"comics","rootPath":"manga","port":445}"""
        val saved = SmbFormSpec.encode(SmbFormSpec.decode(legacy))
        val config = SmbConnectionConfig.fromJson(saved)!!
        val backend = SmbBackend(
            ClassifyingTransport(FakeSmbTransport(root, config.host, config.share), config),
            config,
        )

        assertEquals("smb://nas/comics/manga", backend.root.id)
        assertEquals(listOf("series-a"), backend.root.children().map { it.name })
        assertEquals(
            "smb://nas/comics/manga/series-a/page1.jpg",
            backend.resolve("smb://nas/comics/manga/series-a/page1.jpg")!!.id,
        )
    }
}
