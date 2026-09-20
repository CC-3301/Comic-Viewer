package com.cc3301.comicviewer.ui

import com.cc3301.comicviewer.core.source.CONNECTION_NAME_MAX_LENGTH
import com.cc3301.comicviewer.core.source.TestCredentialCipherRule
import com.cc3301.comicviewer.core.source.komga.KomgaConnectionConfig
import com.cc3301.comicviewer.core.source.smb.SmbConnectionConfig
import com.cc3301.comicviewer.core.source.webdav.WebDavConnectionConfig
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 三个网络来源表单的「名称（可空）」字段（票 #72）：填了就显示填的名称、留空回落到 `主机[:端口]/路径`；
 * 名称去首尾空白 + 超长截断；`decode` 把已存名称回填到字段。
 *
 * 「保存」在这里复刻 `SourceConnectionsScreen` 的 onSave 那三行（`decode` → `displayName` → `encode`）：
 * 界面只有真机能跑，因此「保存后写进 `connections.displayName` 列的名字」与「落库 configJson 里的 name」
 * 是否一致这条不变式只能在这一层钉住；存量连接（configJson 里没有 name 键）编辑保存时按新口径重算
 * 也走这一层（[save]）。
 *
 * 编码走 org.json，故用 Robolectric。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ConnectionNameFormSpecTest {

    @get:Rule
    val credentialCipher = TestCredentialCipherRule()

    /** 一次保存的结果：写进 `connections.displayName` 列的名字 + 落库的 configJson */
    private data class Saved(val displayName: String, val configJson: String)

    private fun save(spec: ConnectionFormSpec, values: Map<String, String>): Saved =
        Saved(displayName = spec.displayName(values), configJson = spec.encode(values))

    @Test
    fun `三个来源表单都有名称字段 标签写可空`() {
        listOf(SmbFormSpec, WebDavFormSpec, KomgaFormSpec).forEach { spec ->
            val field = spec.fields.firstOrNull { it.key == CONNECTION_NAME_FIELD }
            assertEquals(spec.title + " 要有「名称（可空）」字段", "名称（可空）", field?.label)
        }
    }

    @Test
    fun `SMB 留空回落到 主机端口与共享路径`() {
        // 维护者的例子：服务器地址 192.168.1.10 + 路径 1/2/3 → 192.168.1.10/1/2/3
        assertEquals(
            "192.168.1.10/1/2/3",
            SmbFormSpec.displayName(mapOf("address" to "192.168.1.10", "path" to "1/2/3")),
        )
        // 端口只在用户显式配置过时显示（不写即 445，不补默认端口）
        assertEquals(
            "192.168.1.10:1445/1/2/3",
            SmbFormSpec.displayName(mapOf("address" to "192.168.1.10:1445", "path" to "1/2/3")),
        )
        assertEquals("nas/comics", SmbFormSpec.displayName(mapOf("address" to "nas", "path" to "comics")))
    }

    @Test
    fun `WebDAV 留空回落到 主机端口与 DAV 根 不含 scheme`() {
        assertEquals(
            "nas:5006/dav/comics",
            WebDavFormSpec.displayName(mapOf("baseUrl" to "http://nas:5006/dav", "rootPath" to "comics")),
        )
        // 没显式写端口：不补默认端口，也不显示 scheme
        assertEquals("nas/dav", WebDavFormSpec.displayName(mapOf("baseUrl" to "https://nas/dav")))
    }

    @Test
    fun `Komga 留空回落到 主机端口与路径 不含 scheme`() {
        assertEquals("komga:25600", KomgaFormSpec.displayName(mapOf("baseUrl" to "http://komga:25600")))
        assertEquals("komga/dav", KomgaFormSpec.displayName(mapOf("baseUrl" to "https://komga/dav/")))
    }

    @Test
    fun `填了名称就显示名称 首尾空白去掉 超长截断`() {
        val fields = mapOf(
            "address" to "nas",
            "path" to "comics",
            CONNECTION_NAME_FIELD to "  我家 NAS  ",
        )
        assertEquals("我家 NAS", SmbFormSpec.displayName(fields))

        val tooLong = "名".repeat(CONNECTION_NAME_MAX_LENGTH + 3)
        assertEquals(
            CONNECTION_NAME_MAX_LENGTH,
            SmbFormSpec.displayName(fields + (CONNECTION_NAME_FIELD to tooLong)).length,
        )
    }

    @Test
    fun `保存后 configJson 里的 name 与写进列的名字一致`() {
        val filled = save(
            SmbFormSpec,
            mapOf("address" to "nas", "path" to "comics", CONNECTION_NAME_FIELD to " 我家 NAS "),
        )
        assertEquals("我家 NAS", filled.displayName)
        assertEquals(
            "写进列的名字必须等于 configJson 里的 name（同一份结果，别处不再拼一遍）",
            filled.displayName,
            SmbConnectionConfig.fromJson(filled.configJson)!!.name,
        )

        // 留空那一支：configJson 的 name 为空、列名 = 自动拼名（列才是消费点）
        val blank = save(SmbFormSpec, mapOf("address" to "nas", "path" to "comics", CONNECTION_NAME_FIELD to "   "))
        assertEquals("", SmbConnectionConfig.fromJson(blank.configJson)!!.name)
        assertEquals("nas/comics", blank.displayName)

        // 三个来源同一口径
        val dav = save(
            WebDavFormSpec,
            mapOf("baseUrl" to "http://nas:5006/dav", "rootPath" to "comics", CONNECTION_NAME_FIELD to "我家 DAV"),
        )
        assertEquals(dav.displayName, WebDavConnectionConfig.fromJson(dav.configJson)!!.name)

        val komga = save(
            KomgaFormSpec,
            mapOf("baseUrl" to "http://komga:25600", "apiKey" to "k", CONNECTION_NAME_FIELD to "我家 Komga"),
        )
        assertEquals(komga.displayName, KomgaConnectionConfig.fromJson(komga.configJson)!!.name)
    }

    @Test
    fun `decode 把已存名称回填到字段 存量行没有 name 键则字段为空`() {
        val saved = save(
            SmbFormSpec,
            mapOf("address" to "nas", "path" to "comics", CONNECTION_NAME_FIELD to "我家 NAS"),
        )

        assertEquals("我家 NAS", SmbFormSpec.decode(saved.configJson)[CONNECTION_NAME_FIELD])
        // 票 #72 之前写的 configJson：没有 name 键 → 名称字段为空（编辑一次不改名字即按新口径重算）
        assertEquals("", SmbFormSpec.decode("""{"host":"nas","share":"comics"}""")[CONNECTION_NAME_FIELD])
        assertEquals(
            "",
            WebDavFormSpec.decode("""{"baseUrl":"http://nas/dav","username":"reader"}""")[CONNECTION_NAME_FIELD],
        )
    }

    @Test
    fun `存量连接编辑保存后才切到新口径`() {
        // 存量行：configJson 里没有 name 键，`connections.displayName` 列还是旧口径
        // （SMB = `共享名 @ 主机`，WebDAV / Komga 带 scheme）。维护者口径（与 #38 一致）：不批量重算，
        // 编辑保存时才按新口径重算——decode 只读到空名称，于是列名落到自动拼名。
        val legacySmb = """{"host":"nas.local","share":"comics","rootPath":"manga","port":4450}"""
        assertEquals(
            "nas.local:4450/comics/manga",
            save(SmbFormSpec, SmbFormSpec.decode(legacySmb)).displayName,
        )

        val legacyDav = """{"baseUrl":"https://nas:5006/dav","rootPath":"comics","username":"reader","password":"secret"}"""
        assertEquals("nas:5006/dav/comics", save(WebDavFormSpec, WebDavFormSpec.decode(legacyDav)).displayName)

        val legacyKomga = """{"baseUrl":"https://komga.example.com","apiKey":"key"}"""
        assertEquals("komga.example.com", save(KomgaFormSpec, KomgaFormSpec.decode(legacyKomga)).displayName)
    }
}
