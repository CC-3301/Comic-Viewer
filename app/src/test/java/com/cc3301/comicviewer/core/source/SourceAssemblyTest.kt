package com.cc3301.comicviewer.core.source

import com.cc3301.comicviewer.core.data.ConnectionEntity
import com.cc3301.comicviewer.core.source.komga.KomgaSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
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
 * 这里只钉「一行连接记录 → 配置」与「sourceType → 哪份装配说明（`specFor`）」两段。
 *
 * 跑在 Robolectric 下：各 `fromJson` 用的是 `org.json`，JVM 单测里 android.jar 的桩会抛「Stub!」
 *（与其它走配置 JSON 的用例同一前提）。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SourceAssemblyTest {

    /** 仓内既有夹具（`InMemoryCredentialCipher.kt`）：装填测试 cipher、跑完还原——不在这里另写一份装填 */
    @get:Rule
    val credentialCipher = TestCredentialCipherRule()

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
    fun `未知来源类型按配置损坏抛出 提示与旧文案逐字一致 且不碰任何依赖`() {
        // 依赖四样都是取值闭包而不是值：这条出路原先就不碰 Context/Room，收口后照旧
        //（下面是「碰了就地失败」的依赖，因此「碰了」会看得见）
        val failure = assertThrows(SourceAssemblyFailure.ConfigCorrupt::class.java) {
            SourceAssembly.build(
                ConnectionEntity(sourceType = "OPDS", displayName = "旧 OPDS 书库", configJson = "{}"),
                deps = unusedDeps,
            )
        }

        val message = failure.message.orEmpty()
        assertEquals(
            "提示要与票 #136 之前的字面量逐字一致" +
                "（旧代码是 \"来源类型未知（连接配置损坏），请重新添加该连接：\" + sourceType）：" + message,
            "来源类型未知（连接配置损坏），请重新添加该连接：OPDS",
            message,
        )
    }

    // ---------- 装配表：sourceType → 装配说明 ----------

    /**
     * `specFor` 的映射（票 #136）：四个已知 sourceType 各走一遍 [SourceAssembly.build]，
     * 装配说明若串到别的来源（例如 `SMB -> webDav`），下面每一条都会红。
     *
     * 能离线装出实例的两个来源断言「装出来的实例与传入的 sourceType 对得上」；
     * 另两个来源的装配在构造后端时要真连一次起始路径（`SmbBackend` / `WebDavBackend` 的 `root` 是构造期
     * 一次 stat），JVM 单测里装不出成功实例——改为钉「跑到了**哪份说明**的校验」：串味会换成另一份说明
     * 的那句话（SMB 那句不会从 WebDAV/Komga 的说明里出来，反之亦然）。
     */
    @Test
    fun `四个已知 sourceType 各由自己那份装配说明处理 不会串味`() {
        val local = SourceAssembly.build(localRow("content://com.android.externalstorage.documents/tree/root"), usableDeps)
        assertTrue("本地来源要装成文件树来源：" + local::class.java.simpleName, local is DocumentTreeSource)
        assertEquals(SourceType.LOCAL, local.type)

        val komga = SourceAssembly.build(usableKomgaRow(), usableDeps)
        assertTrue("Komga 要装成 KomgaSource：" + komga::class.java.simpleName, komga is KomgaSource)
        assertEquals(SourceType.KOMGA, komga.type)

        assertEquals(
            "端口必须在 1–65535 之间",
            invalidMessageOf {
                SourceAssembly.build(smbRow("""{"host":"nas.local","share":"comics","port":0}"""), unusedDeps)
            },
        )
        assertEquals(
            "地址要以 http:// 或 https:// 开头",
            invalidMessageOf { SourceAssembly.build(davRow("""{"baseUrl":"dav.example.com"}"""), unusedDeps) },
        )
    }

    // ---------- 工具 ----------

    private fun smbRow(json: String) =
        ConnectionEntity(sourceType = SourceType.SMB.name, displayName = "我家 NAS", configJson = json)

    private fun davRow(json: String) =
        ConnectionEntity(sourceType = SourceType.WEBDAV.name, displayName = "书房 WebDAV", configJson = json)

    private fun komgaRow(json: String) =
        ConnectionEntity(sourceType = SourceType.KOMGA.name, displayName = "家里 Komga", configJson = json)

    private fun localRow(uri: String) =
        ConnectionEntity(sourceType = SourceType.LOCAL.name, displayName = "本地漫画", configJson = uri)

    /**
     * 一条能通过解析与校验的 Komga 连接（装配成功的用例要用它）：密码走生产写路径加密
     * （[StoredCredential.protect]，由 [credentialCipher] 在测试期间顶上的实现加密），
     * 于是这条行与库里真实存的那一行同形，也能反过来验证凭据读路径。
     */
    private fun usableKomgaRow() = komgaRow(
        """{"baseUrl":"https://komga.example.com","username":"reader@example.com","password":"${StoredCredential.protect("s3cret")}"}""",
    )

    private fun corruptMessageOf(block: () -> Unit): String =
        assertThrows(SourceAssemblyFailure.ConfigCorrupt::class.java) { block() }.message.orEmpty()

    private fun reentryMessageOf(block: () -> Unit): String =
        assertThrows(SourceAssemblyFailure.CredentialReentry::class.java) { block() }.message.orEmpty()

    private fun invalidMessageOf(block: () -> Unit): String =
        assertThrows(SourceAssemblyFailure.InvalidConfig::class.java) { block() }.message.orEmpty()

    /**
     * 已知来源装配要用到的依赖（与 [unusedDeps] 相对）：进度存储与 SAF 后端都用仓内既有夹具，
     * 不落盘（封面目录与列表快照给 null = 本次装配不涉及落盘）。
     */
    private val usableDeps = SourceDeps(
        progressStore = { InMemoryProgressStore() },
        coverCacheDir = { null },
        listingSnapshots = { null },
        safBackend = { FakeTreeBackend(fakeDir("root")) },
    )

    /** 未知来源那条出路不该用到任何依赖：真被调用就地失败，于是「碰了」会看得见 */
    private val unusedDeps = SourceDeps(
        progressStore = { error("未知来源类型不该建进度存储") },
        coverCacheDir = { error("未知来源类型不该取缓存目录") },
        listingSnapshots = { error("未知来源类型不该取落盘快照") },
        safBackend = { error("未知来源类型不该建 SAF 后端") },
    )
}
