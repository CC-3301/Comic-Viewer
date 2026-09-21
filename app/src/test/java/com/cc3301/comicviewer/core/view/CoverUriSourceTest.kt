package com.cc3301.comicviewer.core.view

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 封面字节从哪来（票 #108 r3，评审 P1）：判据只此一处，`CoverThumb` 的渲染通路与浏览页的预取共用它。
 *
 * 为什么要有这条判据：本地/SAF 的封面行带系统可解码的 uri，渲染走 `PageDecoder.decodeCoverUri`，
 * **从不调 `Source.coverBytes`**。对这类条目预取是白读整张图（没人复用），还会占同一份会话字节缓存的
 * 字节帐、把真正要用字节的条目挤出缓存——所以「可见行会不会走字节通路」必须能被预取侧问到。
 *
 * 未覆盖：「真机上本地图片行是否确实都不调 coverBytes」由渲染侧代码保证（同一处判据），JVM 侧只钉判据本身。
 */
class CoverUriSourceTest {

    @Test
    fun `系统可解码的两种 scheme 走 uri 通路`() {
        assertEquals("content://media/1", CoverUriSource.decodable("content://media/1"))
        assertEquals("file:///sdcard/a.jpg", CoverUriSource.decodable("file:///sdcard/a.jpg"))

        assertEquals("content:// 行不预取", false, CoverUriSource.viaSourceBytes("content://media/1"))
        assertEquals("file:// 行不预取", false, CoverUriSource.viaSourceBytes("file:///sdcard/a.jpg"))
    }

    @Test
    fun `其余（含来源标识串与空值）走来源字节通路`() {
        assertNull("SMB 的标识串系统解不了", CoverUriSource.decodable("smb://host/share/a.jpg"))
        assertNull("WebDAV 的标识串同理", CoverUriSource.decodable("webdav-http://host/a.jpg"))
        assertNull("空串等于没有 uri", CoverUriSource.decodable(""))
        assertNull("null 等于没有 uri", CoverUriSource.decodable(null))

        assertEquals("容器行（无 uri）要预取", true, CoverUriSource.viaSourceBytes(null))
        assertEquals("空串同容器行", true, CoverUriSource.viaSourceBytes(""))
        assertEquals("SMB 行要预取", true, CoverUriSource.viaSourceBytes("smb://host/share/a.jpg"))
        assertEquals("WebDAV 行要预取", true, CoverUriSource.viaSourceBytes("webdav-http://host/a.jpg"))
    }

    @Test
    fun `两条判据互为反面`() {
        // 「走 uri 解」与「走来源字节」是本判据的两种取值，不允许出现第三条（否则预取与渲染会各按一套走）
        listOf("content://media/1", "file:///a.jpg", "smb://h/a", "", null).forEach { uri ->
            assertEquals("uri=$uri", CoverUriSource.decodable(uri) == null, CoverUriSource.viaSourceBytes(uri))
        }
    }
}
