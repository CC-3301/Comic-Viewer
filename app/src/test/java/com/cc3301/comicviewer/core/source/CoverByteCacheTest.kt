package com.cc3301.comicviewer.core.source

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 封面字节缓存的**字节上界**口径（票 #145）。
 *
 * 票面取数结论：8 MiB 按实测字节量级（每张 430~537 KB）只装得下约 15~19 张，一屏 12 张就顶满 ⇒
 * 冷缓存期必然重取；本票提到 32 MiB（≈ 60~78 张）。判别点：上界值本身、以及**越字节上界时也是
 * 按插入序淘汰最旧**（不是整仓清空、也不是丢最新那几张）——上界调大之后仍然依赖这条。
 */
class CoverByteCacheTest {

    @Test
    fun `默认字节上界是 32 MiB`() {
        assertEquals("票 #145 的口径（从 8 MiB 提高）", 32L * 1024 * 1024, CoverByteCache.DEFAULT_MAX_BYTES)
        assertEquals("条目数上界未动", 64, CoverByteCache.DEFAULT_MAX_ENTRIES)
    }

    @Test
    fun `越字节上界时淘汰最旧的一条`() {
        val cache = CoverByteCache(maxEntries = 64, maxBytes = 100)
        val bytes = ByteArray(40)

        cache.put("first", bytes)
        cache.put("second", bytes)
        cache.put("third", bytes) // 120 > 100：最旧的 first 出局

        assertNull("最旧的一条被字节上界淘汰", cache.get("first"))
        assertNotNull("中间那条仍在", cache.get("second"))
        assertNotNull("刚写的那条在", cache.get("third"))
    }
}
