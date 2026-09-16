package com.cc3301.comicviewer.core.source.fs

import com.cc3301.comicviewer.core.source.zip.RandomAccessBytes

/**
 * 文件树节点轻量抽象：本地 File 与 SAF 文档树的双后端共同原语。
 * 判定/封面/分页逻辑（DocumentTreeSource）只依赖此接口。
 */
interface FsNode {
    /** 来源内不透明唯一 id（file 绝对路径 / content uri），从 listEntries 原样回传使用 */
    val id: String
    val name: String
    val isDirectory: Boolean
    /** 修改时间毫秒；后端不可得时为 null（时间排序末位） */
    val lastModifiedMs: Long?
    /** 供 UI 解码显示的引用（file:// 或 content:// uri 字符串） */
    val imageUri: String

    /** 仅目录有意义：子节点（无序） */
    fun children(): List<FsNode>
    /** 父节点；根节点返回 null */
    fun parent(): FsNode?
    fun readBytes(): ByteArray

    /**
     * 随机访问（票 10：CBZ/ZIP 需中央目录 + 按条目解压，不能整包读入内存）。
     * 后端不支持时抛 [UnsupportedOperationException]。
     */
    fun openRandomAccess(): RandomAccessBytes
}

/** 由 id 找回节点（越界/无效返回 null），后端各自保证不逃出授权根 */
interface FsBackend {
    val root: FsNode
    fun resolve(id: String): FsNode?
}
