package com.cc3301.comicviewer.core.source

import com.cc3301.comicviewer.core.order.WindowsNameOrder
import com.cc3301.comicviewer.core.source.fs.FsBackend
import com.cc3301.comicviewer.core.source.fs.FsNode
import java.util.Locale

/** 图片扩展名（spec：jpg/jpeg/png/webp/gif；gif 读静态首帧） */
val IMAGE_EXTENSIONS: Set<String> = setOf("jpg", "jpeg", "png", "webp", "gif")

fun FsNode.isImageFile(): Boolean = !isDirectory && name.substringAfterLast('.', "").lowercase(Locale.ROOT) in IMAGE_EXTENSIONS

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
) : Source {

    private val rootNode: FsNode = backend.root
    private val resolve: (String) -> FsNode? = backend::resolve

    override val type: SourceType get() = SourceType.LOCAL

    override suspend fun listEntries(containerId: String?, sort: SortMode): List<BrowseEntry> {
        val dir = resolveNode(containerId)
        val imageFiles = listImagesSorted(dir)
        val subDirs = listDirsSorted(dir)

        val entries = mutableListOf<BrowseEntry>()

        // 子目录条目：直接含图片 → 书；否则容器。封面：书=第一张图；容器=逐级下取第一张图
        for (sub in subDirs) {
            val images = listImagesSorted(sub)
            if (images.isNotEmpty()) {
                entries += BrowseEntry(
                    id = sub.id,
                    name = sub.name,
                    isBook = true,
                    coverUri = images.first().imageUri,
                    pageCount = images.size,
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

        return sortEntries(entries, sort)
    }

    override suspend fun openBook(bookId: String): BookHandle {
        val pages = pagesOfBook(bookId)
        if (pages.isEmpty()) throw IllegalArgumentException("不是一本书：$bookId")
        return object : BookHandle {
            override val pageCount: Int = pages.size
            override suspend fun loadPage(index: Int): PageData {
                val node = pages.getOrNull(index)
                    ?: throw IndexOutOfBoundsException("页码越界：$index / ${pages.size}")
                return PageData(node.readBytes(), mimeTypeOf(node.name))
            }
        }
    }

    override suspend fun readProgress(bookId: String): ReadingProgress? =
        progressStore.read(bookId)

    override suspend fun writeProgress(bookId: String, pageIndex: Int, totalPages: Int) =
        progressStore.write(bookId, pageIndex, totalPages)

    // ---------- 内部 ----------

    /** 越界/无效 id 的唯一防护点（后端 resolve 返回 null 即拒绝） */
    private fun resolveNode(id: String?): FsNode =
        if (id == null) rootNode else resolve(id) ?: throw IllegalArgumentException("无效或越界引用：$id")

    /** 书的页面序列：目录书=全部图片自然序；混合列表图片条目=从该图到末图（自然序） */
    private fun pagesOfBook(bookId: String): List<FsNode> {
        val node = resolveNode(bookId)
        return when {
            node.isDirectory -> listImagesSorted(node)
            node.isImageFile() -> {
                val siblings = node.parent()?.let(::listImagesSorted) ?: emptyList()
                siblings.dropWhile { it.id != node.id }
            }
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
            // 修改时间：目录书/容器取目录 mtime，图片条目取文件 mtime；发布时间语义票 10 完善
            SortMode.MODIFIED_TIME, SortMode.RELEASE_TIME ->
                entries.sortedWith(compareByDescending { resolve(it.id)?.lastModifiedMs ?: 0L })
        }
}
