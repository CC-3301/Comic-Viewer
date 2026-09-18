package com.cc3301.comicviewer.ui

import androidx.test.core.app.ApplicationProvider
import com.cc3301.comicviewer.core.data.ConnectionEntity
import com.cc3301.comicviewer.core.source.SourceType
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 来源解析（票 33）：OPDS 删除后 [SourceType] 只剩四种来源；旧库残留（或手工改库、降级安装）
 * 造成的未知 sourceType 必须给出「配置损坏」类提示而不是崩溃——界面侧统一 `runCatching`
 * 后展示 message（BrowserScreen / BookshelfScreen / SourceConnectionsScreen 同一手法）。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ServiceLocatorSourceRoutingTest {

    @Before
    fun setUp() {
        ServiceLocator.init(ApplicationProvider.getApplicationContext())
    }

    @Test
    fun `SourceType 只剩本地 SMB WebDAV Komga 四种`() {
        assertEquals(listOf("LOCAL", "SMB", "WEBDAV", "KOMGA"), SourceType.entries.map { it.name })
    }

    @Test
    fun `未知 sourceType 提示配置损坏而不是崩溃`() {
        val thrown = assertThrows(IllegalArgumentException::class.java) {
            runBlocking {
                ServiceLocator.sourceForConnection(
                    ConnectionEntity(sourceType = "OPDS", displayName = "旧 OPDS 书库", configJson = "{}"),
                )
            }
        }

        val message = thrown.message.orEmpty()
        assertTrue("提示要能让用户自行修正：" + message, message.contains("配置损坏"))
    }
}
