package com.cc3301.comicviewer.core.source.opds

import com.cc3301.comicviewer.core.order.WindowsNameOrder
import com.cc3301.comicviewer.core.source.BookHandle
import com.cc3301.comicviewer.core.source.BrowseEntry
import com.cc3301.comicviewer.core.source.DownloadProgress
import com.cc3301.comicviewer.core.source.Neighbors
import com.cc3301.comicviewer.core.source.PageData
import com.cc3301.comicviewer.core.source.ProgressStore
import com.cc3301.comicviewer.core.source.ReadingProgress
import com.cc3301.comicviewer.core.source.SortMode
import com.cc3301.comicviewer.core.source.Source
import com.cc3301.comicviewer.core.source.SourceType
import com.cc3301.comicviewer.core.source.isArchiveImageEntry
import com.cc3301.comicviewer.core.source.mimeTypeOf
import com.cc3301.comicviewer.core.source.zip.FileRandomAccess
import com.cc3301.comicviewer.core.source.zip.ZipArchive
import com.cc3301.comicviewer.core.source.zip.ZipEntry
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/** 分页伪条目的显示名（点它进入 feed 的下一页） */
private const val NEXT_PAGE_LABEL = "下一页 →"

/**
 * OPDS 1.x 来源（票 15）：feed 浏览 + 获取链接下载到本地缓存后阅读。
 *
 * 与其它来源的差异：
 * - 排序只在这边做（OPDS 没有服务器端排序参数）：名称用 Windows 名称序；修改时间用 Atom `updated`；
 *   发布时间用 `published`（缺失时回退 `updated`）。
 * - 书必须先下载：命缓存直接用；未命中则下载（[downloadProgress] 给出进度），完成后再打开；
 *   下载完成后调用缓存 LRU 清理（上限可配，默认 2GB）。
 * - 无封面 uri（缩略图需要认证头），封面走 [coverBytes]（列表里见过的条目才有）。
 * - 相邻书按「最近浏览过的那个 feed」的名称序判定（OPDS 没有全局目录概念）。
 */
class OpdsSource(
    private val api: OpdsApi,
    private val config: OpdsConnectionConfig,
    private val progressStore: ProgressStore,
    private val cache: OpdsCache,
    private val nameComparator: Comparator<String> = WindowsNameOrder.COMPARATOR,
) : Source {

    private val prefix: String = OpdsIds.prefix(config.feedUrl)

    private val progressFlow = MutableStateFlow<DownloadProgress?>(null)

    override val downloadProgress: StateFlow<DownloadProgress?> get() = progressFlow

    override val type: SourceType get() = SourceType.OPDS

    /** 条目 id → 缩略图/封面地址（列表里见过才记；会话级，不落盘） */
    private val coverUrls = ConcurrentHashMap<String, String>()

    /** 最近一次浏览的 feed（用于相邻书判定）：id → 显示名，已按名称序排好 */
    private var lastFeedBooks: List<Pair<String, String>> = emptyList()

    override suspend fun listEntries(containerId: String?, sort: SortMode): List<BrowseEntry> {
        val feedUrl = containerId?.let {
            OpdsIds.feedUrl(prefix, it) ?: throw IllegalArgumentException("无效的 OPDS 容器：$it")
        } ?: config.feedUrl

        val xml = api.fetchFeed(feedUrl)
        val feed = try {
            parseOpdsFeed(xml, feedUrl)
        } catch (t: Throwable) {
            // 不是 Atom（例如 OPDS 2.x 的 JSON feed）：给出可行动的中文提示，而不是 SAX 解析异常原文
            throw OpdsException(
                OpdsFailureKind.OTHER,
                "这不是 OPDS 1.x feed（可能是 OPDS 2.x JSON，首版不支持）：" + feedUrl,
                t,
            )
        }
        // 条目与 Atom 条目成对保存：排序键（updated/published）在 Atom 条目上，不能靠标题反查
        val paired = mutableListOf<Pair<OpdsEntry, BrowseEntry>>()
        val named = mutableListOf<Pair<String, String>>()

        feed.entries.forEach { entry ->
            val cover = entry.thumbnailHref
            entry.navigationHref?.let { href ->
                val id = OpdsIds.feedId(prefix, href)
                cover?.let { coverUrls[id] = it }
                val browse = BrowseEntry(id = id, name = entry.title, isBook = false, coverUri = null, pageCount = null)
                paired += entry to browse
            }
            val acquisition = entry.acquisition
            if (acquisition != null && OpdsEntry.isReadable(acquisition)) {
                val id = OpdsIds.bookId(prefix, acquisition.href)
                cover?.let { coverUrls[id] = it }
                // 页数只有下载后才知道：OPDS 不在 feed 里给页数
                val browse = BrowseEntry(id = id, name = entry.title, isBook = true, coverUri = null, pageCount = null)
                paired += entry to browse
                named += id to entry.title
            }
        }

        lastFeedBooks = named.sortedWith(compareBy(nameComparator) { it.second })
        val sorted = sortEntries(paired, sort)
        // 分页（OPDS 1.x 的 rel="next"）：作为最后一个伪容器条目，排序不改变它的位置
        val nextPage = feed.nextPageUrl
        return if (nextPage == null) {
            sorted
        } else {
            val id = OpdsIds.feedId(prefix, nextPage)
            sorted + BrowseEntry(id = id, name = NEXT_PAGE_LABEL, isBook = false, coverUri = null, pageCount = null)
        }
    }

    override suspend fun openBook(bookId: String): BookHandle {
        val url = OpdsIds.acquisitionUrl(prefix, bookId)
            ?: throw IllegalArgumentException("无效的 OPDS 书：$bookId")
        val file = cachedOrDownload(bookId, url)
        return when {
            isArchiveFile(file) -> archiveHandle(bookId, file)
            isImageFile(file) -> imageHandle(bookId, file)
            else -> throw OpdsException(
                OpdsFailureKind.OTHER,
                "下载到的内容既不是压缩包也不是图片（服务器可能返回了错误页）：" + file.name,
                null,
            )
        }
    }

    override suspend fun readProgress(bookId: String): ReadingProgress? = progressStore.read(bookId)

    override suspend fun writeProgress(bookId: String, pageIndex: Int, totalPages: Int) =
        progressStore.write(bookId, pageIndex, totalPages)

    override suspend fun neighbors(bookId: String): Neighbors {
        val index = lastFeedBooks.indexOfFirst { it.first == bookId }
        if (index < 0) return Neighbors(null, null)
        return Neighbors(
            prev = lastFeedBooks.getOrNull(index - 1)?.first,
            next = lastFeedBooks.getOrNull(index + 1)?.first,
        )
    }

    /** 封面（票 15）：缩略图需要认证头，系统解码器拿不到，因此按需取字节；失败吞成 null */
    override suspend fun coverBytes(entryId: String): ByteArray? = runCatching {
        val url = coverUrls[entryId] ?: return@runCatching null
        val file = cache.fileFor("cover_" + entryId, url)
        if (file.isFile && file.length() > 0L) {
            cache.touch(file)
            return@runCatching file.readBytes()
        }
        api.download(url, file) { _, _ -> }
        cache.enforceLimit()
        file.readBytes()
    }.getOrNull()

    override fun close() {
        api.close()
        progressFlow.value = null
    }

    // ---------- 内部 ----------

    /** 缓存命中直接用；否则下载（带进度）并做 LRU 清理 */
    private fun cachedOrDownload(bookId: String, url: String): File {
        cache.cached(bookId, url)?.let { return it }
        val target = cache.fileFor(bookId, url)
        progressFlow.value = DownloadProgress(bookId = bookId, bytesDownloaded = 0L, totalBytes = null)
        try {
            api.download(url, target) { downloaded, total ->
                progressFlow.value = DownloadProgress(bookId = bookId, bytesDownloaded = downloaded, totalBytes = total)
            }
            cache.touch(target)
            cache.enforceLimit()
            return target
        } finally {
            // 无论成功失败都收起进度条（失败时异常向上抛，由阅读页给出重试入口）
            progressFlow.value = null
        }
    }

    private fun archiveEntries(file: File): List<ZipEntry> =
        FileRandomAccess(file).use { bytes ->
            ZipArchive(bytes).use { zip -> zip.entries.filter { isArchiveImageEntry(it.name) } }
        }.sortedWith(compareBy(nameComparator) { it.name })

    private fun archiveHandle(bookId: String, file: File): BookHandle {
        val pages = archiveEntries(file)
        if (pages.isEmpty()) throw IllegalArgumentException("压缩包里没有图片：$bookId")
        return object : BookHandle {
            override val id: String = bookId
            override val pageCount: Int = pages.size
            override suspend fun loadPage(index: Int): PageData {
                val page = pages.getOrNull(index)
                    ?: throw IndexOutOfBoundsException("页码越界：$index / ${pages.size}")
                val bytes = FileRandomAccess(file).use { random ->
                    ZipArchive(random).use { it.read(page) }
                }
                return PageData(bytes, mimeTypeOf(page.name))
            }
        }
    }

    private fun imageHandle(bookId: String, file: File): BookHandle = object : BookHandle {
        override val id: String = bookId
        override val pageCount: Int = 1
        override suspend fun loadPage(index: Int): PageData {
            if (index != 0) throw IndexOutOfBoundsException("页码越界：$index / 1")
            return PageData(file.readBytes(), mimeTypeOf(file.name))
        }
    }

    /** OPDS 没有服务器端排序参数：三种排序都在本地做（SPEC 故事 14 的五来源一致性要求） */
    private fun sortEntries(paired: List<Pair<OpdsEntry, BrowseEntry>>, sort: SortMode): List<BrowseEntry> =
        when (sort) {
            SortMode.NAME -> paired.sortedWith(compareBy(nameComparator) { it.second.name }).map { it.second }
            SortMode.MODIFIED_TIME -> paired.sortedByDescending { it.first.updatedMs ?: 0L }.map { it.second }
            // 发布时间：Atom published（缺失回退 updated）；没有日期的排最后
            SortMode.RELEASE_TIME -> paired
                .sortedByDescending { it.first.publishedMs ?: it.first.updatedMs ?: 0L }
                .map { it.second }
        }
}

/** 压缩包魔数：PK\x03\x04（普通 zip）与 PK\x05\x06（空 zip） */
private fun isArchiveFile(file: File): Boolean {
    val head = readHead(file, 4) ?: return false
    return head[0] == 0x50.toByte() && head[1] == 0x4B.toByte() &&
        ((head[2] == 0x03.toByte() && head[3] == 0x04.toByte()) || (head[2] == 0x05.toByte() && head[3] == 0x06.toByte()))
}

/** 图片魔数：JPEG / PNG / GIF / WEBP（服务器给的 type 不可信，按内容判更稳） */
private fun isImageFile(file: File): Boolean {
    val head = readHead(file, 12) ?: return false
    fun byteAt(index: Int) = head.getOrNull(index)?.toInt()?.and(0xFF) ?: -1
    return when {
        byteAt(0) == 0xFF && byteAt(1) == 0xD8 -> true
        byteAt(0) == 0x89 && byteAt(1) == 0x50 && byteAt(2) == 0x4E && byteAt(3) == 0x47 -> true
        byteAt(0) == 0x47 && byteAt(1) == 0x49 && byteAt(2) == 0x46 -> true
        byteAt(0) == 0x52 && byteAt(1) == 0x49 && byteAt(2) == 0x46 && byteAt(3) == 0x46 &&
            byteAt(8) == 0x57 && byteAt(9) == 0x45 && byteAt(10) == 0x42 && byteAt(11) == 0x50 -> true
        else -> false
    }
}

private fun readHead(file: File, length: Int): ByteArray? = runCatching {
    file.inputStream().use { input ->
        val buffer = ByteArray(length)
        var read = 0
        while (read < length) {
            val current = input.read(buffer, read, length - read)
            if (current <= 0) break
            read += current
        }
        // 短读（文件比魔数还短）也要返回实际读到的字节：否则小于 12 字节的图片会被判成非图片
        if (read == 0) null else buffer.copyOf(read)
    }
}.getOrNull()
