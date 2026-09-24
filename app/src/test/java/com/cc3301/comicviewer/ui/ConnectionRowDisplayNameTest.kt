package com.cc3301.comicviewer.ui

import com.cc3301.comicviewer.core.data.ConnectionEntity
import com.cc3301.comicviewer.core.source.SourceAssembly
import com.cc3301.comicviewer.core.source.SourceType
import com.cc3301.comicviewer.core.source.TestCredentialCipherRule
import com.cc3301.comicviewer.core.source.smb.SmbConnectionConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 连接行的名字与来源配置的展示名（票 #72 r2，评审 spec P2）：错误文案取 `config.displayName`（按规则重算），
 * 而连接列表/书柜取 `connections.displayName` 列——存量行在用户重存前，列里还是旧口径
 * （SMB `共享名 @ 主机`、WebDAV / Komga 带 scheme），重算已是新口径，两个消费点本来会不一致。
 * [SourceAssembly] 构造来源配置时把列名作为**运行期载体**带上（各 config 的 `rowDisplayName`），两者因此恒等。
 *
 * 载体不进 configJson（`toJson` 里没有它）：不是持久化重复，也不影响存量行（不用迁移、不重算历史行）。
 * 报错文案本身（`config.displayName + path` 那 12 处）不在本测试射程内，这里锁的是它读的那个值。
 *
 * 断言打在装配路径上（[SourceAssembly] 的三个带凭据 spec 的 `resolve`）——票 #136 修复轮 b1/2 起
 * `ServiceLocator` 的三个 `*ConfigOf` 纯转发行已删除（生产零调用点，见下），来源配置的解析只此一处；
 * `sourceForConnection` 走的是同一份 spec（[SourceAssembly.build]），因此这里锁的就是 App 实际读到的那份。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ConnectionRowDisplayNameTest {

    @get:Rule
    val credentialCipher = TestCredentialCipherRule()

    @Test
    fun `存量行的旧口径列名进得了来源配置的展示名`() {
        val smb = row(
            type = SourceType.SMB.name,
            displayName = "comics @ nas.local",
            configJson = """{"host":"nas.local","share":"comics","rootPath":"manga","port":4450}""",
        )
        assertEquals("comics @ nas.local", SourceAssembly.smb.resolve(smb).displayName)

        val dav = row(
            type = SourceType.WEBDAV.name,
            displayName = "https://nas:5006/dav/comics",
            configJson = """{"baseUrl":"https://nas:5006/dav","rootPath":"comics","username":"reader"}""",
        )
        assertEquals("https://nas:5006/dav/comics", SourceAssembly.webDav.resolve(dav).displayName)

        val komga = row(
            type = SourceType.KOMGA.name,
            displayName = "https://komga.example.com",
            configJson = """{"baseUrl":"https://komga.example.com","apiKey":"key"}""",
        )
        assertEquals("https://komga.example.com", SourceAssembly.komga.resolve(komga).displayName)
    }

    @Test
    fun `载体是运行期值 不进 configJson 也不改变重算规则`() {
        val smb = row(
            type = SourceType.SMB.name,
            displayName = "我家 NAS",
            configJson = """{"host":"nas","share":"comics"}""",
        )
        val carried = SourceAssembly.smb.resolve(smb)

        assertEquals("我家 NAS", carried.displayName)
        assertFalse("行名是运行期载体，不得落回 configJson：" + carried.toJson(), carried.toJson().contains("rowDisplayName"))
        // 解回来（新会话 / 重存路径）时载体为空 → 回到「名称优先、留空回落自动名」的规则
        val reloaded = SmbConnectionConfig.fromJson(carried.toJson())!!
        assertNull(reloaded.rowDisplayName)
        assertEquals("nas/comics", reloaded.displayName)
        // 重存后的连接（configJson 里带 name）：载体与重算结果同值——载体只对存量行起「保持列名」的作用
        val renamed = smb.copy(
            displayName = "我家 NAS",
            configJson = """{"host":"nas","share":"comics","name":"我家 NAS"}""",
        )
        assertEquals("我家 NAS", SourceAssembly.smb.resolve(renamed).displayName)
        assertEquals("我家 NAS", SmbConnectionConfig.fromJson(renamed.configJson)!!.displayName)

        // 列名意外为空时不接管：仍走「名称优先、留空回落自动名」的规则，不会带出空白标题
        val blankRow = row(type = SourceType.SMB.name, displayName = "", configJson = """{"host":"nas","share":"comics"}""")
        assertEquals("nas/comics", SourceAssembly.smb.resolve(blankRow).displayName)
    }

    private fun row(type: String, displayName: String, configJson: String) = ConnectionEntity(
        sourceType = type,
        displayName = displayName,
        configJson = configJson,
    )
}
