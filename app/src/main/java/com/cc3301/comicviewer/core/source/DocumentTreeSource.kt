package com.cc3301.comicviewer.core.source

import com.cc3301.comicviewer.core.meta.parseReleaseDate
import com.cc3301.comicviewer.core.meta.toEpochMillis
import com.cc3301.comicviewer.core.order.WindowsNameOrder
import com.cc3301.comicviewer.core.source.fs.FsBackend
import com.cc3301.comicviewer.core.source.fs.FsNode
import com.cc3301.comicviewer.core.source.zip.ZipArchive
import com.cc3301.comicviewer.core.source.zip.ZipEntry
import java.io.File
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/** 图片扩展名（spec：jpg/jpeg/png/webp/gif；gif 读静态首帧） */
val IMAGE_EXTENSIONS: Set<String> = setOf("jpg", "jpeg", "png", "webp", "gif")

/** 压缩包扩展名（spec 故事 54：CBZ/ZIP） */
val ARCHIVE_EXTENSIONS: Set<String> = setOf("cbz", "zip")

fun FsNode.isImageFile(): Boolean = !isDirectory && name.substringAfterLast('.', "").lowercase(Locale.ROOT) in IMAGE_EXTENSIONS

/** 压缩包文件（CBZ/ZIP）：整包作为一本书，页序 = 包内图片文件名自然序 */
fun FsNode.isArchiveFile(): Boolean = !isDirectory && name.substringAfterLast('.', "").lowercase(Locale.ROOT) in ARCHIVE_EXTENSIONS

/** 包内条目是否算一页（目录条目不算） */
fun isArchiveImageEntry(entryName: String): Boolean {
    if (entryName.endsWith("/")) return false
    val ext = entryName.substringAfterLast('.', "").lowercase(Locale.ROOT)
    return ext in IMAGE_EXTENSIONS
}

fun mimeTypeOf(fileName: String): String = when (fileName.substringAfterLast('.', "").lowercase(Locale.ROOT)) {
    "jpg", "jpeg" -> "image/jpeg"
    "png" -> "image/png"
    "webp" -> "image/webp"
    "gif" -> "image/gif"
    else -> "application/octet-stream"
}

/**
 * 文档树来源：本地 File 与 SAF 文档树的统一实现（票 04）。
 *
 * 目录判定规则（spec）：
 * - 目录直接含图片 → 该目录是书（出现在父列表 isBook=true，从第 1 页按名称自然序打开）
 * - 目录只含子目录 → 容器（isBook=false，继续浏览）
 * - 目录图片+子目录混排 → 混合列表：图片条目 isBook=true（bookId=该图，从该图连读到列表末图），
 *   含图子文件夹 isBook=true（按顺序整本读）
 */
class DocumentTreeSource(
    backend: FsBackend,
    private val progressStore: ProgressStore,
    /** Windows 自然排序（票 #3）；测试可注入自定义比较器 */
    private val nameComparator: Comparator<String> = WindowsNameOrder.COMPARATOR,
    /** 压缩包封面解压目录（票 10）；null 时不生成 CBZ 封面 */
    private val coverCacheDir: File? = null,
) : Source {

    private val rootNode: FsNode = backend.root
    private val resolve: (String) -> FsNode? = backend::resolve

    /** 发布时间排序键缓存（键含 mtime：文件更新后自动失效） */
    private val releaseCache = ConcurrentHashMap<String, Long>()

    override val type: SourceType get() = SourceType.LOCAL

    override suspend fun listEntries(containerId: String?, sort: SortMode): List<BrowseEntry> =
        sortEntries(entriesOf(resolveNode(containerId)), sort)

    private fun entriesOf(dir: FsNode): List<BrowseEntry> {
        // 单次 children()（SAF 每次都是 provider IPC），分区后各自排序
        val kids = dir.children()
        val imageFiles = kids.filter { it.isImageFile() }.sortedWith(compareBy(nameComparator) { it.name })
        val subDirs = kids.filter { it.isDirectory }.sortedWith(compareBy(nameComparator) { it.name })

        val entries = mutableListOf<BrowseEntry>()

        // 子目录条目：直接含图片或压缩包 → 书；否则容器。封面：书=首个可读条目；容器=逐级下取第一张图
        for (sub in subDirs) {
            val subKids = sub.children()
            val images = subKids.filter { it.isImageFile() }.sortedWith(compareBy(nameComparator) { it.name })
            val archives = subKids.filter { it.isArchiveFile() }.sortedWith(compareBy(nameComparator) { it.name })
            if (images.isNotEmpty() || archives.isNotEmpty()) {
                entries += BrowseEntry(
                    id = sub.id,
                    name = sub.name,
                    isBook = true,
                    coverUri = images.firstOrNull()?.imageUri
                        ?: archives.firstOrNull()?.let { archiveCover(it) },
                    pageCount = images.size + archives.sumOf { archiveEntries(it).size },
                )
            } else {
                entries += BrowseEntry(
                    id = sub.id,
                    name = sub.name,
                    isBook = false,
                    coverUri = findFirstImageDeep(sub)?.imageUri,
                    pageCount = null,
                )
            }
        }

        // 压缩包（CBZ/ZIP）：整包作为一本书（spec 故事 54）
        val archives = kids.filter { it.isArchiveFile() }.sortedWith(compareBy(nameComparator) { it.name })
        for (arc in archives) {
            entries += BrowseEntry(
                id = arc.id,
                name = arc.name,
                isBook = true,
                coverUri = archiveCover(arc),
                pageCount = archiveEntries(arc).size,
            )
        }

        // 本目录直接含图片时：混合列表中的图片条目，从该图连读到列表末图
        if (imageFiles.isNotEmpty()) {
            imageFiles.forEachIndexed { index, img ->
                entries += BrowseEntry(
                    id = img.id,
                    name = img.name,
                    isBook = true,
                    coverUri = img.imageUri,
                    pageCount = imageFiles.size - index,
                )
            }
        }

        return entries
    }

    override suspend fun openBook(bookId: String): BookHandle {
        val pages = pagesOfBook(bookId)
        if (pages.isEmpty()) throw IllegalArgumentException("不是一本书：$bookId")
        return object : BookHandle {
            override val id: String = bookId
            override val pageCount: Int = pages.size
            override suspend fun loadPage(index: Int): PageData {
                val page = pages.getOrNull(index)
                    ?: throw IndexOutOfBoundsException("页码越界：$index / ${pages.size}")
                return PageData(page.bytes(), mimeTypeOf(page.name))
            }
        }
    }

    override suspend fun readProgress(bookId: String): ReadingProgress? =
        progressStore.read(bookId)

    override suspend fun writeProgress(bookId: String, pageIndex: Int, totalPages: Int) =
        progressStore.write(bookId, pageIndex, totalPages)

    override suspend fun neighbors(bookId: String): Neighbors {
        val node = resolveNode(bookId)
        // 目录书与其父列表中的图片条目共用同一个"同目录 isBook 序列"语义
        val listParent = when {
            node.isDirectory -> node.parent() ?: return Neighbors(null, null)
            node.isImageFile() -> node.parent() ?: return Neighbors(null, null)
            else -> return Neighbors(null, null)
        }
        val books = bookEntriesOf(listParent)
        val idx = books.indexOfFirst { it.id == bookId }
        if (idx < 0) return Neighbors(null, null)
        return Neighbors(
            prev = books.getOrNull(idx - 1)?.id,
            next = books.getOrNull(idx + 1)?.id,
        )
    }

    /**
     * 相邻书判定专用的轻量书列表：isBook 集合与 [entriesOf] 一致（子目录直接含图=书；本目录图片条目=书），
     * 但**不计算封面/页数**（跳过 findFirstImageDeep 递归，省 SAF provider IPC），且按全局名称序排序
     * （review P1：分区拼接顺序会与浏览列表名称序不一致）。
     */
    private fun bookEntriesOf(dir: FsNode): List<BrowseEntry> {
        val kids = dir.children()
        val books = mutableListOf<BrowseEntry>()
        kids.filter { it.isDirectory }.forEach { sub ->
            val subKids = sub.children()
            if (subKids.any { it.isImageFile() || it.isArchiveFile() }) {
                books += BrowseEntry(sub.id, sub.name, isBook = true, coverUri = null, pageCount = null)
            }
        }
        kids.filter { it.isImageFile() }.forEach { img ->
            books += BrowseEntry(img.id, img.name, isBook = true, coverUri = null, pageCount = null)
        }
        kids.filter { it.isArchiveFile() }.forEach { arc ->
            books += BrowseEntry(arc.id, arc.name, isBook = true, coverUri = null, pageCount = null)
        }
        return sortedByName(books) { it.name }
    }

    // ---------- 内部 ----------

    /** 越界/无效 id 的唯一防护点（后端 resolve 返回 null 即拒绝） */
    private fun resolveNode(id: String?): FsNode =
        if (id == null) rootNode else resolve(id) ?: throw IllegalArgumentException("无效或越界引用：$id")

    /**
     * 书的页面序列：
     * - 目录书 = 直接图片 + 压缩包展开页，按条目名称自然序合并
     * - 混合列表图片条目 = 从该图到末图（自然序）
     * - 压缩包 = 包内图片条目自然序
     */
    private fun pagesOfBook(bookId: String): List<PageRef> {
        val node = resolveNode(bookId)
        return when {
            node.isDirectory -> {
                val kids = node.children()
                val named = mutableListOf<Pair<String, PageRef>>()
                kids.filter { it.isImageFile() }.forEach { named += it.name to FilePageRef(it) }
                kids.filter { it.isArchiveFile() }.forEach { arc ->
                    archiveEntries(arc).forEach { named += it.name to ZipPageRef(arc, it) }
                }
                named.sortedWith(compareBy(nameComparator) { it.first }).map { it.second }
            }
            node.isImageFile() -> {
                val siblings = node.parent()?.let(::listImagesSorted) ?: emptyList()
                siblings.dropWhile { it.id != node.id }.map { FilePageRef(it) }
            }
            node.isArchiveFile() -> archiveEntries(node).map { ZipPageRef(node, it) }
            else -> emptyList()
        }
    }

    private fun <T> sortedByName(items: List<T>, nameOf: (T) -> String): List<T> =
        items.sortedWith(compareBy(nameComparator, nameOf))

    private fun listFilesSorted(dir: FsNode, keep: (FsNode) -> Boolean): List<FsNode> =
        dir.children().filter(keep).sortedWith(compareBy(nameComparator) { it.name })

    private fun listImagesSorted(dir: FsNode): List<FsNode> = listFilesSorted(dir) { it.isImageFile() }

    private fun listDirsSorted(dir: FsNode): List<FsNode> = listFilesSorted(dir) { it.isDirectory }

    /** 逐级下取：深度优先找第一张图（封面规则） */
    private fun findFirstImageDeep(dir: FsNode): FsNode? {
        listImagesSorted(dir).firstOrNull()?.let { return it }
        for (sub in listDirsSorted(dir)) {
            findFirstImageDeep(sub)?.let { return it }
        }
        return null
    }

    private fun sortEntries(entries: List<BrowseEntry>, sort: SortMode): List<BrowseEntry> =
        when (sort) {
            SortMode.NAME -> sortedByName(entries) { it.name }
            SortMode.MODIFIED_TIME ->
                entries.sortedWith(compareByDescending { resolve(it.id)?.lastModifiedMs ?: 0L })
            // 发布时间（spec 故事 12/13）：CBZ 读 ComicInfo.xml，无元数据（图片文件夹等）回退 mtime
            SortMode.RELEASE_TIME ->
                entries.sortedWith(compareByDescending { releaseKey(it.id) })
        }

    /** 发布时间排序键：优先 ComicInfo.xml 的日期（转毫秒与 mtime 同量纲），缺失回退修改时间 */
    private fun releaseKey(entryId: String): Long {
        val node = resolve(entryId) ?: return 0L
        val cacheKey = entryId + "@" + (node.lastModifiedMs ?: 0L)
        releaseCache[cacheKey]?.let { return it }
        val key = if (node.isArchiveFile()) {
            runCatching {
                withArchive(node) { zip ->
                    zip.entry(COMIC_INFO)?.let { entry ->
                        parseReleaseDate(zip.read(entry))?.toEpochMillis()
                    }
                }
            }.getOrNull() ?: node.lastModifiedMs ?: 0L
        } else {
            node.lastModifiedMs ?: 0L
        }
        releaseCache[cacheKey] = key
        return key
    }

    // ---------- 压缩包（CBZ/ZIP，票 10）----------

    private fun <T> withArchive(node: FsNode, block: (ZipArchive) -> T): T =
        node.openRandomAccess().use { bytes -> ZipArchive(bytes).use(block) }

    /** 包内图片条目：按文件名自然序（spec 故事 54：页序 = ZIP 内文件名自然排序） */
    private fun archiveEntries(node: FsNode): List<ZipEntry> =
        runCatching {
            withArchive(node) { zip -> zip.entries.filter { isArchiveImageEntry(it.name) } }
        }.getOrElse { emptyList() }
            .sortedWith(compareBy(nameComparator) { it.name })

    /** 封面 = 包内第一页：解压到缓存目录供 UI 解码（无缓存目录或失败则为 null） */
    private fun archiveCover(node: FsNode): String? {
        val dir = coverCacheDir ?: return null
        val entry = archiveEntries(node).firstOrNull() ?: return null
        return runCatching {
            val out = File(dir, "cbz_cover_" + node.id.hashCode().toUInt().toString(16) + ".img")
            if (!out.exists() || out.length() == 0L) {
                withArchive(node) { zip -> out.writeBytes(zip.read(entry)) }
            }
            out.toURI().toString()
        }.getOrNull()
    }

    /** 页面引用：普通图片页与压缩包内页的统一抽象（票 10） */
    private sealed interface PageRef {
        val name: String
        fun bytes(): ByteArray
    }

    private class FilePageRef(private val node: FsNode) : PageRef {
        override val name: String get() = node.name
        override fun bytes(): ByteArray = node.readBytes()
    }

    private class ZipPageRef(private val node: FsNode, private val entry: ZipEntry) : PageRef {
        override val name: String get() = entry.name
        override fun bytes(): ByteArray =
            node.openRandomAccess().use { bytes -> ZipArchive(bytes).use { it.read(entry) } }
    }

    private companion object {
        const val COMIC_INFO = "ComicInfo.xml"
    }
}
