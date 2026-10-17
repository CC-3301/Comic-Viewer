package com.cc3301.comicviewer.core.source

import com.cc3301.comicviewer.core.source.fs.FsNode
import java.util.Locale

/** 图片扩展名（spec：jpg/jpeg/png/webp/gif；gif 读静态首帧） */
val IMAGE_EXTENSIONS: Set<String> = setOf("jpg", "jpeg", "png", "webp", "gif")

/** 压缩包扩展名（spec 故事 54：CBZ/ZIP） */
val ARCHIVE_EXTENSIONS: Set<String> = setOf("cbz", "zip")

fun FsNode.isImageFile(): Boolean = !isDirectory && name.substringAfterLast('.', "").lowercase(Locale.ROOT) in IMAGE_EXTENSIONS

/** 压缩包文件（CBZ/ZIP）：整包作为一本书，页序 = 包内图片文件名自然序 */
fun FsNode.isArchiveFile(): Boolean = !isDirectory && name.substringAfterLast('.', "").lowercase(Locale.ROOT) in ARCHIVE_EXTENSIONS

/**
 * 一个目录**这一层**的内容分区：本层图片、本层子目录、本层压缩包，三者都按条目名称自然序排好。
 *
 * 这是「一个目录里有什么」的**唯一判定点**（浏览列表的 `isBook` 与页序列装配要的「本层图片」都由它给出，
 * 因此不会有两套口径）；打开容器只读本层图片、不并入下级（`pagesOfBook` 的目录分支）。
 * 分区只看本层，不递归下取。
 */
internal data class DirContents(
    val images: List<FsNode>,
    val subDirs: List<FsNode>,
    val archives: List<FsNode>,
) {
    /**
     * 这个目录本身是不是一本书：**本层只有图片**才算。
     *
     * 本层有子目录或压缩包 ⇒ 它是容器：子目录与压缩包在浏览列表里各是一条独立条目（点一个读一个、各自是一本），
     * 本层图片也各自是条目（点一张 = 从该张连读本层其余图片）。
     * 「本层只有图片」这条判据挡住的是把本层压缩包并进这一本：并进来的后果是「打开 A = B+C 的页数」，
     * 100+ 本 1000+ 页的库一打开就加载上千页。
     */
    val isBook: Boolean get() = images.isNotEmpty() && subDirs.isEmpty() && archives.isEmpty()
}

/** 按本层分区一个目录的子节点（判定接缝；纯函数，不做任何 I/O） */
internal fun dirContentsOf(kids: List<FsNode>, nameComparator: Comparator<String>): DirContents = DirContents(
    images = kids.filter { it.isImageFile() }.sortedWith(compareBy(nameComparator) { it.name }),
    subDirs = kids.filter { it.isDirectory }.sortedWith(compareBy(nameComparator) { it.name }),
    archives = kids.filter { it.isArchiveFile() }.sortedWith(compareBy(nameComparator) { it.name }),
)
