package com.cc3301.comicviewer.core.source

import java.io.File
import java.util.Locale

/** 图片扩展名（spec：jpg/jpeg/png/webp/gif；gif 读静态首帧） */
val IMAGE_EXTENSIONS: Set<String> =
    setOf("jpg", "jpeg", "png", "webp", "gif")

fun File.isImageFile(): Boolean =
    isFile && extension.lowercase(Locale.ROOT) in IMAGE_EXTENSIONS

fun mimeTypeOf(fileName: String): String = when (fileName.substringAfterLast('.', "").lowercase(Locale.ROOT)) {
    "jpg", "jpeg" -> "image/jpeg"
    "png" -> "image/png"
    "webp" -> "image/webp"
    "gif" -> "image/gif"
    else -> "application/octet-stream"
}

/**
 * 本地文件来源（票 01：temp-dir fixture 驱动；SAF 适配在票 03 接入）。
 *
 * 目录判定规则（spec）：
 * - 目录直接含图片 → 该目录是书（出现在父列表中 isBook=true，从第 1 页按名称自然序打开）
 * - 目录只含子目录 → 容器（isBook=false，继续浏览）
 * - 目录图片+子目录混排 → 混合列表：图片条目 isBook=true（bookId=该图，从该图连读到列表末图），
 *   含图子文件夹 isBook=true（按顺序整本读）
 */
class LocalSource(
    rootDir: File,
    private val progressStore: ProgressStore,
    /** 票 02 将替换为 Windows 自然排序比较器；默认大小写不敏感字典序 */
    private val nameComparator: Comparator<String> = compareBy(String.CASE_INSENSITIVE_ORDER) { it },
) : Source {

    private val root: File = rootDir.absoluteFile
    /** 按组件比较的根路径（Windows 大小写不敏感；字符串前缀比较可被兄弟目录绕过） */
    private val rootPath: java.nio.file.Path = root.toPath().normalize()

    override val type: SourceType get() = SourceType.LOCAL

    override suspend fun listEntries(containerId: String?, sort: SortMode): List<BrowseEntry> {
        val dir = resolveDir(containerId)
        val imageFiles = listImagesSorted(dir)
        val subDirs = listDirsSorted(dir)

        val entries = mutableListOf<BrowseEntry>()

        // 子目录条目：直接含图片 → 书；否则容器。封面：书=第一张图；容器=逐级下取第一张图
        for (sub in subDirs) {
            val images = listImagesSorted(sub)
            if (images.isNotEmpty()) {
                entries += BrowseEntry(
                    id = sub.absolutePath,
                    name = sub.name,
                    isBook = true,
                    coverUri = images.first().toURI().toString(),
                    pageCount = images.size,
                )
            } else {
                entries += BrowseEntry(
                    id = sub.absolutePath,
                    name = sub.name,
                    isBook = false,
                    coverUri = findFirstImageDeep(sub)?.toURI().toString(),
                    pageCount = null,
                )
            }
        }

        // 本目录直接含图片时：混合列表中的图片条目，从该图连读到列表末图
        if (imageFiles.isNotEmpty()) {
            val sorted = imageFiles
            sorted.forEachIndexed { index, img ->
                entries += BrowseEntry(
                    id = img.absolutePath,
                    name = img.name,
                    isBook = true,
                    coverUri = img.toURI().toString(),
                    pageCount = sorted.size - index,
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
                val file = pages.getOrNull(index)
                    ?: throw IndexOutOfBoundsException("页码越界：$index / ${pages.size}")
                return PageData(file.readBytes(), mimeTypeOf(file.name))
            }
        }
    }

    override suspend fun readProgress(bookId: String): ReadingProgress? =
        progressStore.read(bookId)

    override suspend fun writeProgress(bookId: String, pageIndex: Int, totalPages: Int) =
        progressStore.write(bookId, pageIndex, totalPages)

    // ---------- 内部 ----------

    /** 根内路径解析（含越界防护，按路径组件比较） */
    private fun resolveDir(containerId: String?): File {
        val path = if (containerId == null) rootPath else java.nio.file.Paths.get(containerId).normalize()
        if (!path.startsWith(rootPath)) throw IllegalArgumentException("越出来源根：$containerId")
        return path.toFile()
    }

    /** 书的页面序列：目录书=全部图片自然序；混合列表图片条目=从该图到末图（自然序） */
    private fun pagesOfBook(bookId: String): List<File> {
        val f = File(bookId)
        return when {
            f.isDirectory -> listImagesSorted(f)
            f.isFile -> {
                val siblings = listImagesSorted(f.parentFile ?: root)
                siblings.dropWhile { it.absolutePath != f.absolutePath }
            }
            else -> emptyList()
        }.also { pages ->
            // openBook 的根校验：书与页面都必须在根内
            pages.forEach { page ->
                if (!page.toPath().normalize().startsWith(rootPath)) {
                    throw IllegalArgumentException("越出来源根：$bookId")
                }
            }
        }
    }

    private fun <T> sortedByName(items: List<T>, nameOf: (T) -> String): List<T> =
        items.sortedWith(compareBy(nameComparator, nameOf))

    private fun listFilesSorted(dir: File, keep: (File) -> Boolean): List<File> =
        (dir.listFiles { f -> keep(f) } ?: emptyArray()).sortedWith(compareBy(nameComparator) { it.name })

    private fun listImagesSorted(dir: File): List<File> = listFilesSorted(dir) { it.isImageFile() }

    private fun listDirsSorted(dir: File): List<File> = listFilesSorted(dir) { it.isDirectory }

    /** 逐级下取：深度优先找第一张图（封面规则） */
    private fun findFirstImageDeep(dir: File): File? {
        val direct = listImagesSorted(dir).firstOrNull()
        if (direct != null) return direct
        val subDirs = (dir.listFiles { f -> f.isDirectory } ?: emptyArray())
            .sortedWith(compareBy(nameComparator) { it.name })
        for (sub in subDirs) {
            findFirstImageDeep(sub)?.let { return it }
        }
        return null
    }

    private fun sortEntries(entries: List<BrowseEntry>, sort: SortMode): List<BrowseEntry> =
        when (sort) {
            SortMode.NAME -> sortedByName(entries) { it.name }
            // 修改时间：目录书/容器取目录 mtime；图片条目取文件 mtime。票 10 完成发布时间语义
            SortMode.MODIFIED_TIME, SortMode.RELEASE_TIME ->
                entries.sortedWith(compareByDescending { File(it.id).lastModified() })
        }
}
