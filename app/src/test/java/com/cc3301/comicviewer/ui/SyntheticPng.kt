package com.cc3301.comicviewer.ui

import java.io.ByteArrayOutputStream
import java.util.zip.CRC32
import java.util.zip.Deflater

/**
 * 合成的单色 PNG（测试共用，仓库不存二进制 fixture）：只为给解码器一份**真实尺寸**的图。
 *
 * 票 #105 从 `CoverDecodeBytesTest` 的私有实现提出来（两处测试共用一份，PNG 容器细节因此只写一遍）；
 * 该测试的两个私有函数现在只是转发到这里，调用点不变。
 */
internal object SyntheticPng {

    /** 单色灰度 PNG（无过滤 + Deflater） */
    fun of(width: Int, height: Int): ByteArray {
        val ihdr = ByteArrayOutputStream().apply {
            writeInt(width)
            writeInt(height)
            write(8)   // 位深
            write(0)   // 颜色类型：灰度
            write(0)   // 压缩方法
            write(0)   // 过滤方法
            write(0)   // 隔行扫描
        }.toByteArray()
        val raw = ByteArray(height * (width + 1))  // 每行 1 字节过滤标记 + width 像素
        val deflater = Deflater(9)
        val compressed = ByteArray(raw.size + 1024)
        val compressedSize = try {
            deflater.setInput(raw)
            deflater.finish()
            deflater.deflate(compressed)
        } finally {
            // native 资源：无论成败都必须 end()（题面点名的缺口）
            deflater.end()
        }
        return ByteArrayOutputStream().apply {
            write(byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A))
            write(chunk("IHDR", ihdr))
            write(chunk("IDAT", compressed.copyOf(compressedSize)))
            write(chunk("IEND", ByteArray(0)))
        }.toByteArray()
    }

    /** PNG 分块：长度 + 类型 + 数据 + CRC32 */
    fun chunk(type: String, data: ByteArray): ByteArray {
        val body = type.toByteArray() + data
        val crc = CRC32().apply { update(body) }
        return ByteArrayOutputStream().apply {
            writeInt(data.size)
            write(body)
            writeInt(crc.value.toInt())
        }.toByteArray()
    }
}

/** 大端 32 位（PNG 分块长度与 CRC 用） */
internal fun ByteArrayOutputStream.writeInt(value: Int) {
    write(value ushr 24)
    write(value ushr 16)
    write(value ushr 8)
    write(value)
}
