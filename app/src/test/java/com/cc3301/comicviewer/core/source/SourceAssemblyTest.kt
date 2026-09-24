package com.cc3301.comicviewer.core.source

import com.cc3301.comicviewer.core.data.ConnectionEntity
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 装配说明的三条出路（票 #136）：三个带凭据的来源原先各自「解析 → 凭据重入 → 校验」逐字同形，
 * 失败一律是 `IllegalArgumentException` + 一句中文；现在每条出路各有类型（[SourceAssemblyFailure]），
 * 提示文本逐字不变（表现零变化）。
 *
 * 三条出路各测一遍三个来源：类型分得开，用户能照做的提示也一句不少。
 * 装配出的来源实例本身（backend / 进度存储 / 落盘快照）由各来源既有的契约测试覆盖，
 * 这里只钉「一行连接记录 → 配置」这一段。
 *
 * 跑在 Robolectric 下：各 `fromJson` 用的是 `org.json`，JVM 单测里 android.jar 的桩会抛「Stub!」
 *（与其它走配置 JSON 的用例同一前提）。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SourceAssemblyTest {

    private val previous = StoredCredential.cipher

    @Before
    fun installCipher() {
        StoredCredential.cipher = TestCredentialCipher
    }

    @After
    fun restoreCipher() {
        StoredCredential.cipher = previous
    }

    // ---------- 出路一：配置损坏 ----------

    @Test
    fun `解不出 JSON 或必填字段缺失 = 配置损坏 提示点名来源`() {
        assertEquals(
            "SMB 连接配置损坏，请重新添加",
            corruptMessageOf { SourceAssembly.smb.resolve(smbRow("不是 JSON")) },
        )
        assertEquals(
            "WebDAV 连接配置损坏，请重新添加",
            corruptMessageOf { SourceAssembly.webDav.resolve(davRow("{}")) },
        )
        assertEquals(
            "Komga 连接配置损坏，请重新添加",
            corruptMessageOf { SourceAssembly.komga.resolve(komgaRow("{}")) },
        )
    }

    // ---------- 出路二：凭据重入 ----------

    @Test
    fun `密文解不出来 = 凭据重入 提示叫人重填而不是重新添加`() {
        // 换机 / 密钥失效的仿真：另一把密钥加密出来的密文，本机解不出来
        val foreign = StoredCredential.ENCRYPTED_PREFIX + ForeignKeyCredentialCipher.encrypt("s3cret")

        assertEquals(
            "SMB 连接的密码已无法解密（密钥失效或换了设备），请在首页点「SMB」进入连接列表，编辑该连接后重新填写密码",
            reentryMessageOf {
                SourceAssembly.smb.resolve(smbRow("""{"host":"nas.local","share":"comics","password":"$foreign"}"""))
            },
        )
        assertEquals(
            "WebDAV 连接的密码已无法解密（密钥失效或换了设备），请在首页点「WebDAV」进入连接列表，编辑该连接后重新填写密码",
            reentryMessageOf {
                SourceAssembly.webDav.resolve(
                    davRow("""{"baseUrl":"https://dav.example.com","password":"$foreign"}"""),
                )
            },
        )
        assertEquals(
            "Komga 连接的凭据已无法解密（密钥失效或换了设备），请在首页点「Komga」进入连接列表，编辑该连接后重新填写凭据",
            reentryMessageOf {
                SourceAssembly.komga.resolve(
                    komgaRow("""{"baseUrl":"https://komga.example.com","password":"$foreign"}"""),
                )
            },
        )
    }

    // ---------- 出路三：校验失败 ----------

    @Test
    fun `字段能解出来但不合法 = 校验失败 提示沿用来源自己的那句`() {
        assertEquals(
            "端口必须在 1–65535 之间",
            invalidMessageOf {
                SourceAssembly.smb.resolve(smbRow("""{"host":"nas.local","share":"comics","port":70000}"""))
            },
        )
        assertEquals(
            "地址要以 http:// 或 https:// 开头",
            invalidMessageOf { SourceAssembly.webDav.resolve(davRow("""{"baseUrl":"ftp://dav.example.com"}""")) },
        )
        assertEquals(
            "请填写邮箱与密码",
            invalidMessageOf { SourceAssembly.komga.resolve(komgaRow("""{"baseUrl":"https://komga.example.com"}""")) },
        )
    }

    // ---------- 通过装配说明的其余契约 ----------

    @Test
    fun `解析通过时带上连接行的名字 报错文案与列表里的名字因此恒等`() {
        val config = SourceAssembly.smb.resolve(smbRow("""{"host":"nas.local","share":"comics"}"""))

        assertEquals("我家 NAS", config.displayName)
    }

    @Test
    fun `未知来源类型按配置损坏抛出 提示与旧文案一致 且不碰任何依赖`() {
        // 依赖四样都是取值闭包而不是值：这条出路原先就不碰 Context/Room，收口后照旧
        //（下面是「碰了就地失败」的依赖，因此「碰了」会看得见）
        val failure = assertThrows(SourceAssemblyFailure.ConfigCorrupt::class.java) {
            SourceAssembly.build(
                ConnectionEntity(sourceType = "OPDS", displayName = "旧 OPDS 书库", configJson = "{}"),
                deps = unusedDeps,
            )
        }

        val message = failure.message.orEmpty()
        assertTrue("提示要能让用户自行修正：" + message, message.contains("配置损坏"))
    }

    // ---------- 工具 ----------

    private fun smbRow(json: String) =
        ConnectionEntity(sourceType = SourceType.SMB.name, displayName = "我家 NAS", configJson = json)

    private fun davRow(json: String) =
        ConnectionEntity(sourceType = SourceType.WEBDAV.name, displayName = "书房 WebDAV", configJson = json)

    private fun komgaRow(json: String) =
        ConnectionEntity(sourceType = SourceType.KOMGA.name, displayName = "家里 Komga", configJson = json)

    private fun corruptMessageOf(block: () -> Unit): String =
        assertThrows(SourceAssemblyFailure.ConfigCorrupt::class.java) { block() }.message.orEmpty()

    private fun reentryMessageOf(block: () -> Unit): String =
        assertThrows(SourceAssemblyFailure.CredentialReentry::class.java) { block() }.message.orEmpty()

    private fun invalidMessageOf(block: () -> Unit): String =
        assertThrows(SourceAssemblyFailure.InvalidConfig::class.java) { block() }.message.orEmpty()

    /** 未知来源那条出路不该用到任何依赖：真被调用就地失败，于是「碰了」会看得见 */
    private val unusedDeps = SourceDeps(
        progressStore = { error("未知来源类型不该建进度存储") },
        coverCacheDir = { error("未知来源类型不该取缓存目录") },
        listingSnapshots = { error("未知来源类型不该取落盘快照") },
        safBackend = { error("未知来源类型不该建 SAF 后端") },
    )
}
