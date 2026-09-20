package com.cc3301.comicviewer.core.source.zip

import java.io.Closeable
import java.util.zip.Inflater

/** ZIP 中央目录条目（只保留读取所需字段） */
data class ZipEntry(
    val name: String,
    /** 0 = stored，8 = deflate */
    val method: Int,
    val compressedSize: Long,
    val size: Long,
    val localHeaderOffset: Long,
)

/**
 * ZIP/CBZ 读取器（票 10，spec 故事 54）：解析中央目录后按需解压单个条目，
 * 不整包解压；支持 stored(0) 与 deflate(8) —— CBZ 的实际范围。
 *
 * 限制（记录）：不支持 ZIP64（>4GB）与加密条目；文件名按 UTF-8 解码。
 */
class ZipArchive(private val source: RandomAccessBytes) : Closeable {

    val entries: List<ZipEntry> = parseCentralDirectory()

    override fun close() {
        source.close()
    }

    /** 按名精确查找 */
    fun entry(name: String): ZipEntry? = entries.firstOrNull { it.name == name }

    /** 解压一个条目的完整内容 */
    fun read(entry: ZipEntry): ByteArray {
        val nameLen = u16(entry.localHeaderOffset + 26)
        val extraLen = u16(entry.localHeaderOffset + 28)
        val dataOffset = entry.localHeaderOffset + 30 + nameLen + extraLen
        if (entry.compressedSize == 0L) return ByteArray(0)
        val raw = source.read(dataOffset, entry.compressedSize.toInt())
        return when (entry.method) {
            0 -> raw
            8 -> inflate(raw, entry.size)
            else -> throw IllegalStateException("不支持的压缩方式：${entry.method}")
        }
    }

    private fun parseCentralDirectory(): List<ZipEntry> {
        val eocd = findEocd() ?: throw IllegalStateException("不是有效的 ZIP（缺少中央目录结束记录）")
        val count = u16(eocd + 10)
        var offset = u32(eocd + 16)
        val result = ArrayList<ZipEntry>(count)
        repeat(count) {
            if (u32(offset) != CENTRAL_SIGNATURE) return@repeat
            val method = u16(offset + 10)
            val compressed = u32(offset + 20)
            val size = u32(offset + 24)
            val nameLen = u16(offset + 28)
            val extraLen = u16(offset + 30)
            val commentLen = u16(offset + 32)
            val localOffset = u32(offset + 42)
            val name = String(source.read(offset + 46, nameLen), Charsets.UTF_8)
            result += ZipEntry(name, method, compressed, size, localOffset)
            offset += 46 + nameLen + extraLen + commentLen
        }
        return result
    }

    /** 从尾部反向查找 EOCD（注释最长 64KB）；`size` 只取一次——远程来源上每次读它都是一次网络往返（票 #91） */
    private fun findEocd(): Long? {
        val total = source.size
        val tailLen = minOf(total, MAX_COMMENT + EOCD_SIZE.toLong()).toInt()
        val tail = source.read(total - tailLen, tailLen)
        for (i in tail.size - EOCD_SIZE downTo 0) {
            if (u32From(tail, i) == EOCD_SIGNATURE) return total - tailLen + i
        }
        return null
    }

    private fun inflate(data: ByteArray, expectedSize: Long): ByteArray {
        val inflater = Inflater(true)   // raw deflate（ZIP 无 zlib 头）
        try {
            inflater.setInput(data)
            val out = ByteArray(expectedSize.toInt())
            var written = 0
            while (!inflater.finished() && written < out.size) {
                val n = inflater.inflate(out, written, out.size - written)
                if (n == 0 && (inflater.needsInput() || inflater.needsDictionary())) break
                written += n
            }
            return if (written == out.size) out else out.copyOf(written)
        } finally {
            inflater.end()
        }
    }

    private fun u16(offset: Long): Int = u32(offset).toInt() and 0xFFFF

    private fun u32(offset: Long): Long = u32From(source.read(offset, 4), 0)

    private fun u32From(bytes: ByteArray, index: Int): Long {
        if (bytes.size < index + 4) return -1
        return (bytes[index].toLong() and 0xFF) or
            ((bytes[index + 1].toLong() and 0xFF) shl 8) or
            ((bytes[index + 2].toLong() and 0xFF) shl 16) or
            ((bytes[index + 3].toLong() and 0xFF) shl 24)
    }

    private companion object {
        const val EOCD_SIGNATURE = 0x06054b50L
        const val CENTRAL_SIGNATURE = 0x02014b50L
        const val EOCD_SIZE = 22
        const val MAX_COMMENT = 65535
    }
}
