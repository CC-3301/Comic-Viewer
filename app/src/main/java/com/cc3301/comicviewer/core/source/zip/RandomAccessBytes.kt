package com.cc3301.comicviewer.core.source.zip

import java.io.Closeable
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.channels.FileChannel

/**
 * 随机访问字节源（票 10）：ZIP 中央目录解析与按条目解压都基于它，
 * 从而不必整包读入内存（spec：解析中央目录 + 随机访问，直接按页阅读）。
 */
interface RandomAccessBytes : Closeable {
    val size: Long

    /** 读取 [offset, offset+len)；越界部分自动截短，起点越界返回空数组 */
    fun read(offset: Long, len: Int): ByteArray
}

/** File 后端（测试与 file 路径来源） */
class FileRandomAccess(private val file: File) : RandomAccessBytes {

    private val raf = RandomAccessFile(file, "r")

    override val size: Long get() = raf.length()

    override fun read(offset: Long, len: Int): ByteArray {
        if (len <= 0 || offset < 0 || offset >= size) return ByteArray(0)
        val capped = minOf(len.toLong(), size - offset).toInt()
        val out = ByteArray(capped)
        raf.seek(offset)
        raf.readFully(out)
        return out
    }

    override fun close() {
        raf.close()
    }
}

/** 可 seek 通道后端（SAF 的 FileDescriptor 通道）；[alsoClose] 用于同时释放 PFD */
class ChannelRandomAccess(
    private val channel: FileChannel,
    private val alsoClose: Closeable? = null,
) : RandomAccessBytes {

    override val size: Long get() = channel.size()

    override fun read(offset: Long, len: Int): ByteArray {
        if (len <= 0 || offset < 0 || offset >= size) return ByteArray(0)
        val capped = minOf(len.toLong(), size - offset).toInt()
        val buffer = ByteBuffer.allocate(capped)
        channel.position(offset)
        while (buffer.hasRemaining()) {
            if (channel.read(buffer) < 0) break
        }
        return buffer.array().copyOf(buffer.position())
    }

    override fun close() {
        runCatching { channel.close() }
        runCatching { alsoClose?.close() }
    }
}
