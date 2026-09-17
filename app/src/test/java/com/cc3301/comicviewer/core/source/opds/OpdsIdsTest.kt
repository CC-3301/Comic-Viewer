package com.cc3301.comicviewer.core.source.opds

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** OPDS 节点 id 规则（票 15）：URL 编进 id，可反解且不碰撞 */
class OpdsIdsTest {

    private val prefix = OpdsIds.prefix("http://opds.example.com/opds")

    @Test
    fun `前缀带 scheme 与路径`() {
        assertEquals("opds-http://opds.example.com/opds", prefix)
        assertEquals("opds-https://nas:8080/opds", OpdsIds.prefix("https://nas:8080/opds/"))
    }

    @Test
    fun `feed 与书 id 可反解 且 URL 里的特殊字符不影响`() {
        val feedUrl = "http://opds.example.com/opds/中文 目录?x=1&y=2"
        val acqUrl = "http://opds.example.com/opds/第 1 话.cbz"

        val feedId = OpdsIds.feedId(prefix, feedUrl)
        val bookId = OpdsIds.bookId(prefix, acqUrl)

        assertEquals(feedUrl, OpdsIds.feedUrl(prefix, feedId))
        assertEquals(acqUrl, OpdsIds.acquisitionUrl(prefix, bookId))
        assertTrue("id 里不应出现路径分隔符与空格（要能当导航参数）", !feedId.contains(" ") && !feedId.contains("?"))
    }

    @Test
    fun `不同连接的 id 互不认账 格式不对返回 null`() {
        val other = OpdsIds.prefix("http://other.example.com/opds")
        val bookId = OpdsIds.bookId(prefix, "http://opds.example.com/a.cbz")

        assertNull(OpdsIds.acquisitionUrl(other, bookId))
        assertNull(OpdsIds.feedUrl(prefix, bookId))
        assertNull(OpdsIds.acquisitionUrl(prefix, prefix + "/book/"))
        assertNull(OpdsIds.acquisitionUrl(prefix, prefix + "/book/!!!not-base64!!!"))
        assertNull(OpdsIds.feedUrl(prefix, "http://opds.example.com/opds"))
    }
}
