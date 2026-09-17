package com.cc3301.comicviewer.core.source.opds

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** OPDS 连接配置（票 15）：JSON 往返、校验与展示名 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class OpdsConnectionConfigTest {

    @Test
    fun `JSON 往返保留全部字段`() {
        val config = OpdsConnectionConfig(
            feedUrl = "https://opds.example.com/opds",
            username = "reader",
            password = "pw",
        )
        assertEquals(config, OpdsConnectionConfig.fromJson(config.toJson()))
    }

    @Test
    fun `缺失地址或非法 JSON 返回 null`() {
        assertNull(OpdsConnectionConfig.fromJson("{}"))
        assertNull(OpdsConnectionConfig.fromJson("nope"))
        assertNotNull(OpdsConnectionConfig.fromJson("""{"feedUrl":"http://x/opds"}"""))
    }

    @Test
    fun `校验要求 http 前缀与合法主机`() {
        assertEquals("请填写 feed 地址", OpdsConnectionConfig.validate(OpdsConnectionConfig(feedUrl = " ")))
        assertTrue(OpdsConnectionConfig.validate(OpdsConnectionConfig(feedUrl = "opds.example.com"))!!.contains("http"))
        assertNotNull(OpdsConnectionConfig.validate(OpdsConnectionConfig(feedUrl = "http://")))
        assertNull(OpdsConnectionConfig.validate(OpdsConnectionConfig(feedUrl = "http://opds.example.com/opds")))
    }

    @Test
    fun `展示名含 scheme 主机与路径`() {
        assertEquals(
            "http://opds.example.com/opds",
            OpdsConnectionConfig(feedUrl = "http://opds.example.com/opds/").displayName,
        )
        assertEquals("https://nas:8080/opds", OpdsConnectionConfig(feedUrl = "https://nas:8080/opds").displayName)
    }
}
