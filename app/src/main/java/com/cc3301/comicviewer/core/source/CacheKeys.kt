package com.cc3301.comicviewer.core.source

/**
 * 缓存与落盘的键：**一处定义、一处改**。
 *
 * 键值是对外的持久约定——升级安装后既有的封面缓存、页磁盘缓存、列表快照、滚动位置必须照旧命中，
 * 因此每个函数产出的字符串逐字固定（`CacheKeysTest` 用字面量钉住）。键的**语义**（哪些字段参与、
 * 什么时候算另一把键）与格式一同写在各函数的 KDoc 里。
 *
 * 各 store 只接受本模块产出的键，自己不再拼串：同一份格式被两处拼时，改一处不会漏另一处。
 */
object CacheKeys {

    // ---------- 条目版本键（条目 id + 条目自身 mtime）----------

    /** 封面字节缓存的键：条目 id + 条目**自身**的 mtime */
    fun coverBytes(entryId: String, mtimeMs: Long?): String = entryVersion(entryId, mtimeMs)

    /** 发布时间排序键缓存的键：条目 id + 条目自身 mtime */
    fun releaseCache(entryId: String, mtimeMs: Long?): String = entryVersion(entryId, mtimeMs)

    /** 压缩包条目缓存的键：压缩包 id + 它自身 mtime */
    fun archiveEntries(entryId: String, mtimeMs: Long?): String = entryVersion(entryId, mtimeMs)

    /**
     * 条目版本键：id + `@` + 该条目**自身**的修改时间（取不到记 0）。
     * 文件换过（mtime 变）就是另一把键，旧值自然失效——「条目变了要失效」的判据因此只落在这一处。
     */
    private fun entryVersion(id: String, mtimeMs: Long?): String = id + "@" + (mtimeMs ?: 0L)

    // ---------- 封面落盘缓存文件名 ----------

    /**
     * 封面落盘缓存的文件名：前缀 + mtime + [COVER_CACHE_FILE_SUFFIX]。
     * mtime 进文件名 ⇒ 内容变了就是另一个文件，手动刷新后不会再命中旧封面。
     */
    fun coverCacheFileName(nodeId: String, mtimeMs: Long?): String =
        coverCacheFilePrefix(nodeId) + (mtimeMs ?: 0L) + COVER_CACHE_FILE_SUFFIX

    /** 同一本书封面文件的共同前缀（清理旧封面按它匹配）：与 [coverCacheFileName] 共用这一处规则 */
    fun coverCacheFilePrefix(nodeId: String): String =
        COVER_CACHE_FILE_PREFIX + nodeId.hashCode().toUInt().toString(16) + COVER_CACHE_FILE_NAME_SEPARATOR

    /** 封面缓存文件的后缀：清理时按它把写入中途的临时文件排除在外 */
    const val COVER_CACHE_FILE_SUFFIX: String = ".img"

    private const val COVER_CACHE_FILE_PREFIX = "cbz_cover_"
    private const val COVER_CACHE_FILE_NAME_SEPARATOR = "_"

    // ---------- 落盘列表快照文件名 ----------

    /**
     * 落盘列表快照的文件名：连接前缀 + 容器 id 的 SHA-256 前 32 位十六进制 + [LISTING_SNAPSHOT_FILE_SUFFIX]。
     * 容器 id 里可能含 `/`、`:` 等路径/查询字符，因此进文件名前先哈希（与页磁盘缓存同一套 [sha256Hex]）。
     */
    fun listingSnapshotFileName(connectionId: Long, containerId: String): String =
        listingSnapshotConnectionPrefix(connectionId) +
            sha256Hex(containerId, hexChars = LISTING_SNAPSHOT_HASH_CHARS) +
            LISTING_SNAPSHOT_FILE_SUFFIX

    /** 某连接名下快照文件名的前缀（连接级清理按它前缀匹配）：与 [listingSnapshotFileName] 共用这一处规则 */
    fun listingSnapshotConnectionPrefix(connectionId: Long): String =
        LISTING_SNAPSHOT_CONNECTION_PREFIX + connectionId + LISTING_SNAPSHOT_NAME_SEPARATOR

    private const val LISTING_SNAPSHOT_CONNECTION_PREFIX = "conn"
    private const val LISTING_SNAPSHOT_NAME_SEPARATOR = "_"
    private const val LISTING_SNAPSHOT_HASH_CHARS = 32

    // ---------- Komga 会话内列表键 ----------

    /**
     * Komga 会话内列表的键：容器 id + 排序方式。
     * Komga 的排序在服务端，同一层换排序是另一份列表，因此排序必须进键（与文件源不同）。
     */
    fun komgaListing(containerId: String?, sort: SortMode): String =
        komgaListingPrefix(containerId) + sort.name

    /** Komga 会话内列表键的前缀（下拉更新按它前缀匹配清理）：与 [komgaListing] 共用这一处规则 */
    fun komgaListingPrefix(containerId: String?): String =
        containerId.orEmpty() + KOMGA_LISTING_SEPARATOR

    private const val KOMGA_LISTING_SEPARATOR = "|"

    // ---------- 页缓存键（书 id + 页位）----------

    /** 页磁盘缓存的键（页字节磁盘缓存再把它哈希成文件名） */
    fun pageDisk(bookId: String, index: Int): String = pageRef(bookId, index)

    /** 页面位图内存缓存的键（按目标宽度） */
    fun pageMemory(bookId: String, index: Int, targetWidthPx: Int): String =
        pageRef(bookId, index) + "@" + targetWidthPx

    /** 页面位图内存缓存的键（按目标高度）：`h` 前缀与按宽度的键分开，两条通路不互相串图 */
    fun pageMemoryByHeight(bookId: String, index: Int, targetHeightPx: Int): String =
        pageRef(bookId, index) + "@h" + targetHeightPx

    /** 页位坐标（书 id + 页位）：磁盘键与两条内存键共用这一处 */
    private fun pageRef(bookId: String, index: Int): String = bookId + "#" + index

    // ---------- 浏览滚动位置的偏好键 ----------

    /** 浏览滚动位置落盘用的 SharedPreferences 名 */
    const val BROWSE_SCROLL_PREFS_NAME: String = "browse_scroll"

    /** 偏好键：连接 id */
    const val BROWSE_SCROLL_KEY_CONN: String = "scroll_conn"

    /** 偏好键：容器 id（根层写空串——SharedPreferences 存不了 null） */
    const val BROWSE_SCROLL_KEY_CONTAINER: String = "scroll_container"

    /** 偏好键：项索引 */
    const val BROWSE_SCROLL_KEY_INDEX: String = "scroll_index"
}
