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
     * 可取消地读字节：调用方在等这条读时被取消 → **尽力中断这次传输**（关掉正在下行的连接，
     * 从此不再占着取数名额与带宽）。默认就是不可中断的 [readBytes]。
     *
     * 只有网络传输层有等价的中断手段（WebDAV 的 OkHttp `call.cancel()`）；
     * 本地/SAF 的 provider 调用与 SMB 的阻塞读没有，它们照旧读到底——
     * 那时取消只意味着「结果没人要」，不意味着这次传输停了。
     */
    suspend fun readBytesCancellable(): ByteArray = readBytes()

    /**
     * 随机访问（CBZ/ZIP 需中央目录 + 按条目解压，不能整包读入内存）。
     * 后端不支持时抛 [UnsupportedOperationException]。
     */
    fun openRandomAccess(): RandomAccessBytes
}

/** 由 id 找回节点（越界/无效返回 null），后端各自保证不逃出授权根 */
interface FsBackend {
    val root: FsNode
    fun resolve(id: String): FsNode?

    /**
     * 由 id 与**已知的**元数据构造节点（越界/无效返回 null）：类型与 mtime 由调用方给，
     * 因此**不再向来源问一次元数据**——网络来源上 `resolve` 就是一次 stat/PROPFIND 往返，
     * 每张封面的取数都白付一次。
     *
     * [isDirectory]、[lastModifiedMs] 来自列目录时同批拿到的字段（含落盘快照恢复的那份）。
     * 默认回落 [resolve]：本地后端的 stat 是本地/provider 调用，不值得为它另开一条构造路径。
     */
    fun nodeFor(id: String, isDirectory: Boolean, lastModifiedMs: Long?): FsNode? = resolve(id)
}
