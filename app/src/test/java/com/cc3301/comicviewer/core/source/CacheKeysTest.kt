package com.cc3301.comicviewer.core.source

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * 键格式钉住：把每个函数的返回值与改动前的字面量逐字比对。
 *
 * 键值一变，升级安装后既有的封面缓存 / 页磁盘缓存 / 列表快照 / 滚动位置就全量失效，因此这里的字面量是硬判据。
 */
class CacheKeysTest {

    @Test
    fun `条目版本键是 id 加 at 加 mtime`() {
        assertEquals("book-1@1700000000000", CacheKeys.coverBytes("book-1", 1_700_000_000_000L))
        assertEquals("book-1@0", CacheKeys.coverBytes("book-1", null))
        assertEquals("book-1@1700000000000", CacheKeys.releaseCache("book-1", 1_700_000_000_000L))
        assertEquals("book-1@0", CacheKeys.archiveEntries("book-1", null))
    }

    @Test
    fun `条目自身 mtime 变了就换键（封面失效判据）`() {
        assertNotEquals(CacheKeys.coverBytes("book-1", 1L), CacheKeys.coverBytes("book-1", 2L))
        assertNotEquals(CacheKeys.archiveEntries("book-1", 1L), CacheKeys.archiveEntries("book-1", null))
    }

    @Test
    fun `封面缓存文件名与旧命名逐字一致`() {
        assertEquals("cbz_cover_c8d8d674_1700000000000.img", CacheKeys.coverCacheFileName("root/a", 1_700_000_000_000L))
        assertEquals("cbz_cover_c8d8d674_0.img", CacheKeys.coverCacheFileName("root/a", null))
        assertEquals("cbz_cover_c8d8d674_", CacheKeys.coverCacheFilePrefix("root/a"))
    }

    @Test
    fun `列表快照文件名与旧命名逐字一致`() {
        assertEquals("conn7_4813494d137e1631bba301d5acab6e7b.txt", CacheKeys.listingSnapshotFileName(7L, "root"))
        assertEquals("conn7_", CacheKeys.listingSnapshotConnectionPrefix(7L))
    }

    @Test
    fun `列表快照按连接与容器分键（快照失效判据）`() {
        assertEquals(
            CacheKeys.listingSnapshotFileName(7L, "root"),
            CacheKeys.listingSnapshotFileName(7L, "root"),
        )
        assertNotEquals(
            CacheKeys.listingSnapshotFileName(7L, "root"),
            CacheKeys.listingSnapshotFileName(7L, "root/a"),
        )
        assertNotEquals(
            CacheKeys.listingSnapshotFileName(7L, "root"),
            CacheKeys.listingSnapshotFileName(8L, "root"),
        )
    }

    @Test
    fun `Komga 会话内列表键是容器加排序`() {
        assertEquals("|NAME", CacheKeys.komgaListing(null, SortMode.NAME))
        assertEquals("c|RELEASE_TIME", CacheKeys.komgaListing("c", SortMode.RELEASE_TIME))
        assertEquals("c|", CacheKeys.komgaListingPrefix("c"))
        assertNotEquals(
            CacheKeys.komgaListing("c", SortMode.NAME),
            CacheKeys.komgaListing("c", SortMode.MODIFIED_TIME),
        )
    }

    @Test
    fun `页缓存键是书 id 加井号加页位`() {
        assertEquals("book-1#3", CacheKeys.pageDisk("book-1", 3))
        assertEquals("book-1#3@448", CacheKeys.pageMemory("book-1", 3, 448))
        assertEquals("book-1#3@h512", CacheKeys.pageMemoryByHeight("book-1", 3, 512))
    }

    @Test
    fun `浏览滚动位置的偏好键与旧键名一致`() {
        assertEquals("browse_scroll", CacheKeys.BROWSE_SCROLL_PREFS_NAME)
        assertEquals("scroll_conn", CacheKeys.BROWSE_SCROLL_KEY_CONN)
        assertEquals("scroll_container", CacheKeys.BROWSE_SCROLL_KEY_CONTAINER)
        assertEquals("scroll_index", CacheKeys.BROWSE_SCROLL_KEY_INDEX)
    }
}
