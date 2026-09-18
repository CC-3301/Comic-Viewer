package com.cc3301.comicviewer.ui

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.cc3301.comicviewer.core.data.AppDatabase
import com.cc3301.comicviewer.core.data.ConnectionEntity
import com.cc3301.comicviewer.core.source.ForeignKeyCredentialCipher
import com.cc3301.comicviewer.core.source.SortMode
import com.cc3301.comicviewer.core.source.StoredCredential
import com.cc3301.comicviewer.core.source.TestCredentialCipherRule
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.Base64

/**
 * 存量凭据升级的端到端证据（票 #27）：票前形状的 v4 旧库（明文凭据）→ Room 迁移 →
 * 经**生产入口** [ServiceLocator.sourceForConnection] 建源 → 真的带原凭据浏览
 * （MockWebServer 断言认证头）。WebDAV 与 Komga 各一条：两者经传输层送凭据的方式不同
 * （Basic 头 / `X-API-Key` 头），都用票前形状的明文 configJson 起手。
 *
 * 这一条把「旧库升级后旧连接仍可浏览」串成一条链：迁移改密文 + 存储层解密 + 传输层认证，
 * 而不是只断言「解出来的配置对象相等」；另一条覆盖降级路径（密文解不出来 → 中文提示，不崩）。
 * 真实服务器与真机 Keystore 链路见票 #27 真机清单。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class LegacyCredentialUpgradeTest {

    @get:Rule
    val credentialCipher = TestCredentialCipherRule()

    private lateinit var context: Context
    private lateinit var server: MockWebServer

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        server = MockWebServer()
        server.start()
        ServiceLocator.init(context)
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun `旧库明文 WebDAV 连接升级后仍带原凭据浏览`() = runTest {
        val password = "dav-s3cret"
        val legacy =
            """{"baseUrl":"${davBaseUrl()}","rootPath":"","username":"reader","password":"$password"}"""
        createLegacyDatabase(
            listOf(
                "INSERT INTO connections (sourceType, displayName, configJson) VALUES ('WEBDAV', '$WEBDAV_NAME', '$legacy')",
            ),
        )
        // 应答按请求自身现算（而不是排固定队列）：建源 stat、列目录前重取根 mtime、children 共三次 PROPFIND，
        // 队列少一条就会让最后一次请求挂到超时（不是断言失败，是测试卡死）
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val href = request.path.orEmpty()
                val dir = if (href.endsWith("/")) href else href + "/"
                val self = responseXml(dir, directory = true)
                val body = if (request.getHeader("Depth") == "1") {
                    multistatus(self, responseXml(dir + BOOK_NAME, directory = false, length = 1024))
                } else {
                    multistatus(self)
                }
                return MockResponse().setResponseCode(207).setBody(body)
            }
        }

        val db = openDatabase()
        try {
            val row = db.connectionDao().observeAll().first().first { it.displayName == WEBDAV_NAME }

            // 升级后：库里只剩密文（明文密码整串消失），行本身（展示名、地址）不变
            assertFalse("迁移后不得残留明文：" + row.configJson, row.configJson.contains(password))
            assertTrue("密码要落成密文：" + row.configJson, row.configJson.contains(StoredCredential.ENCRYPTED_PREFIX))
            assertTrue(row.configJson.contains(server.hostName + ":" + server.port))
            assertEquals(5, db.openHelper.writableDatabase.version)

            // 生产路径建源 + 浏览（同界面一样在 IO 线程调）
            val names = withContext(Dispatchers.IO) {
                ServiceLocator.sourceForConnection(row).listEntries(null, SortMode.NAME).map { it.name }
            }
            assertEquals(listOf(BOOK_NAME), names)

            // 凭据原样送到服务器：升级没有让用户重填，也没有掉成匿名访问
            assertTrue("至少要发生建源 stat 与列目录两次请求：" + server.requestCount, server.requestCount >= 2)
            val expected = "Basic " + Base64.getEncoder().encodeToString("reader:$password".toByteArray())
            var listings = 0
            repeat(server.requestCount) {
                val request = server.takeRequest()
                assertEquals("PROPFIND", request.method)
                assertEquals(
                    "每次请求都要带原凭据的 Basic 头：" + request.path,
                    expected,
                    request.getHeader("Authorization"),
                )
                if (request.getHeader("Depth") == "1") listings++
            }
            assertEquals("列目录必须发生过（否则上面断言的是别的请求）", 1, listings)
        } finally {
            db.close()
        }
    }

    @Test
    fun `旧库明文 Komga 连接升级后仍带原 API Key 浏览`() = runTest {
        val apiKey = "komga-key-1"
        val password = "komga-pw"
        val legacy =
            """{"baseUrl":"${komgaBaseUrl()}","username":"me@example.com","password":"$password","apiKey":"$apiKey"}"""
        createLegacyDatabase(
            listOf(
                "INSERT INTO connections (sourceType, displayName, configJson) VALUES ('KOMGA', '$KOMGA_NAME', '$legacy')",
            ),
        )
        server.enqueue(MockResponse().setResponseCode(200).setBody(seriesPage("s1")))

        val db = openDatabase()
        try {
            val row = db.connectionDao().observeAll().first().first { it.displayName == KOMGA_NAME }

            assertFalse("迁移后不得残留明文 API Key：" + row.configJson, row.configJson.contains(apiKey))
            assertFalse("迁移后不得残留明文密码：" + row.configJson, row.configJson.contains(password))
            assertTrue(row.configJson.contains(StoredCredential.ENCRYPTED_PREFIX))

            val names = withContext(Dispatchers.IO) {
                ServiceLocator.sourceForConnection(row).listEntries(null, SortMode.NAME).map { it.name }
            }
            // 系列名取 metadata.title（缺失才回退 name，见 HttpKomgaApi 的 seriesTitle）：本 fixture 两个字段都给了，因此取 Title
            assertEquals(listOf("Title s1"), names)

            // 凭据原样送到服务器：升级没有让用户重填 API Key
            val request = server.takeRequest()
            assertEquals("/api/v1/series/list", request.path!!.substringBefore('?'))
            assertEquals("升级后仍要用原 API Key", apiKey, request.getHeader("X-API-Key"))
        } finally {
            db.close()
        }
    }

    @Test
    fun `密文解不出来时提示重新填写密码 不崩`() {
        // 换机 / Keystore 条目失效：库里的密文是另一把密钥加密的
        val foreign = ForeignKeyCredentialCipher.encrypt("dav-s3cret")
        val db = openDatabase()
        try {
            val id = runBlocking {
                db.connectionDao().insert(
                    ConnectionEntity(
                        sourceType = "WEBDAV",
                        displayName = "NAS DAV（换机）",
                        configJson =
                            """{"baseUrl":"http://nas:5006/dav","username":"reader","password":"enc:v1:$foreign"}""",
                    ),
                )
            }
            val row = runBlocking { db.connectionDao().byId(id) }!!

            val thrown = assertThrows(IllegalArgumentException::class.java) {
                runBlocking { ServiceLocator.sourceForConnection(row) }
            }

            val message = thrown.message.orEmpty()
            assertTrue("要告诉用户能自己修：" + message, message.contains("重新填写密码"))
            assertFalse("提示里不得回显密文：" + message, message.contains(foreign))
        } finally {
            db.close()
        }
    }

    private fun davBaseUrl(): String = server.url("/dav").toString().trimEnd('/')

    private fun komgaBaseUrl(): String = server.url("/").toString().trimEnd('/')

    /** 与 ServiceLocator 同一个库文件：让「迁移后建源」跑在真实迁移结果上 */
    private fun openDatabase(): AppDatabase =
        Room.databaseBuilder(context, AppDatabase::class.java, DB_NAME)
            .addMigrations(
                AppDatabase.MIGRATION_1_2,
                AppDatabase.MIGRATION_2_3,
                AppDatabase.MIGRATION_3_4,
                AppDatabase.MIGRATION_4_5,
            )
            .allowMainThreadQueries()
            .build()

    /** 建票前形状的 v4 旧库（只有 connections 与 reading_progress 两张表） */
    private fun createLegacyDatabase(inserts: List<String>) {
        val file = context.getDatabasePath(DB_NAME)
        file.parentFile?.mkdirs()
        val raw = SQLiteDatabase.openOrCreateDatabase(file, null)
        raw.execSQL(
            "CREATE TABLE IF NOT EXISTS `connections` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                "`sourceType` TEXT NOT NULL, `displayName` TEXT NOT NULL, `configJson` TEXT NOT NULL)",
        )
        raw.execSQL(
            "CREATE TABLE IF NOT EXISTS `reading_progress` (`bookId` TEXT NOT NULL, `pageIndex` INTEGER NOT NULL, " +
                "`totalPages` INTEGER NOT NULL, `updatedAtMs` INTEGER NOT NULL, PRIMARY KEY(`bookId`))",
        )
        inserts.forEach { raw.execSQL(it) }
        raw.version = 4
        raw.close()
    }

    private fun multistatus(vararg body: String) =
        """<?xml version="1.0" encoding="utf-8"?>
<D:multistatus xmlns:D="DAV:">
${body.joinToString("\n")}
</D:multistatus>"""

    private fun responseXml(href: String, directory: Boolean, length: Long? = null) = """
  <D:response>
    <D:href>$href</D:href>
    <D:propstat>
      <D:prop>
        <D:resourcetype>${if (directory) "<D:collection/>" else ""}</D:resourcetype>
        ${if (length != null) "<D:getcontentlength>$length</D:getcontentlength>" else ""}
        <D:getlastmodified>Wed, 21 Oct 2015 07:28:00 GMT</D:getlastmodified>
      </D:prop>
      <D:status>HTTP/1.1 200 OK</D:status>
    </D:propstat>
  </D:response>"""

    /** Komga 系列列表页（形状同 HttpKomgaApiTest：`last=true` 表示没有下一页） */
    private fun seriesPage(vararg ids: String) = """
{"content":[
${ids.joinToString(",") { """{"id":"$it","name":"Name $it","booksCount":7,"metadata":{"title":"Title $it","titleSort":"Title $it"}}""" }}
],"page":0,"size":500,"totalElements":${ids.size},"totalPages":1,"last":true}
"""

    private companion object {
        const val DB_NAME = "comic-viewer.db"
        const val WEBDAV_NAME = "NAS DAV（旧库）"
        const val KOMGA_NAME = "NAS Komga（旧库）"
        const val BOOK_NAME = "ch01.cbz"
    }
}
