package com.cc3301.comicviewer.core.source.zip

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.zip.CRC32
import java.util.zip.ZipEntry as JavaZipEntry
import java.util.zip.ZipOutputStream

/** ZIP 中央目录解析与按条目解压（票 10，spec 故事 54：CBZ 直接按页阅读） */
class ZipArchiveTest {

    private fun zipOf(entries: List<Pair<String, ByteArray>>, stored: Boolean = false): File {
        val file = File.createTempFile("test", ".cbz")
        ZipOutputStream(file.outputStream()).use { zos ->
            entries.forEach { (name, bytes) ->
                val entry = if (stored) {
                    // STORED 需显式声明 method/size/crc（setLevel(0) 仍是 deflate）
                    JavaZipEntry(name).apply {
                        method = JavaZipEntry.STORED
                        size = bytes.size.toLong()
                        compressedSize = bytes.size.toLong()
                        crc = CRC32().apply { update(bytes) }.value
                    }
                } else {
                    JavaZipEntry(name)
                }
                zos.putNextEntry(entry)
                zos.write(bytes)
                zos.closeEntry()
            }
        }
        file.deleteOnExit()
        return file
    }

    private fun open(file: File): ZipArchive = ZipArchive(FileRandomAccess(file))

    @Test
    fun `列出条目并保持中央目录顺序`() {
        val file = zipOf(
            listOf(
                "page2.jpg" to "第二页".toByteArray(),
                "page10.jpg" to "第十页".toByteArray(),
            ),
        )
        open(file).use { zip ->
            assertEquals(listOf("page2.jpg", "page10.jpg"), zip.entries.map { it.name })
        }
    }

    @Test
    fun `deflate 条目可完整解压`() {
        val content = ("漫画内容".repeat(500)).toByteArray()
        val file = zipOf(listOf("001.jpg" to content))
        open(file).use { zip ->
            val entry = zip.entry("001.jpg")!!
            assertEquals(8, entry.method)
            assertArrayEquals(content, zip.read(entry))
        }
    }

    @Test
    fun `stored 条目可完整解压`() {
        val content = ByteArray(2048) { (it % 251).toByte() }
        val file = zipOf(listOf("001.jpg" to content), stored = true)
        open(file).use { zip ->
            val entry = zip.entry("001.jpg")!!
            assertEquals(0, entry.method)
            assertArrayEquals(content, zip.read(entry))
        }
    }

    @Test
    fun `子目录内的条目按完整路径命名`() {
        val file = zipOf(
            listOf(
                "chapter1/001.jpg" to "a".toByteArray(),
                "chapter1/002.jpg" to "b".toByteArray(),
                "ComicInfo.xml" to "<ComicInfo/>".toByteArray(),
            ),
        )
        open(file).use { zip ->
            assertEquals(3, zip.entries.size)
            assertArrayEquals("b".toByteArray(), zip.read(zip.entry("chapter1/002.jpg")!!))
            assertNull(zip.entry("002.jpg"))
        }
    }

    @Test
    fun `中文文件名按 UTF-8 解码`() {
        val file = zipOf(listOf("第一话/001.jpg" to "x".toByteArray()))
        open(file).use { zip ->
            assertEquals("第一话/001.jpg", zip.entries.first().name)
        }
    }

    @Test
    fun `空条目解压为零字节`() {
        val file = zipOf(listOf("empty.jpg" to ByteArray(0)))
        open(file).use { zip ->
            assertEquals(0, zip.read(zip.entry("empty.jpg")!!).size)
        }
    }

    @Test
    fun `非 ZIP 内容报错`() {
        val file = File.createTempFile("notzip", ".bin").apply { writeBytes(ByteArray(4096) { 1 }) }
        file.deleteOnExit()
        val failure = runCatching { open(file) }.exceptionOrNull()
        assertTrue(failure is IllegalStateException)
    }

    @Test
    fun `随机访问越界自动截短`() {
        val file = File.createTempFile("raw", ".bin").apply { writeBytes(ByteArray(10) { it.toByte() }) }
        file.deleteOnExit()
        FileRandomAccess(file).use { src ->
            assertEquals(10L, src.size)
            assertEquals(4, src.read(6, 100).size)     // 截到文件尾
            assertEquals(0, src.read(10, 4).size)      // 起点越界
            assertEquals(0, src.read(0, 0).size)
            assertArrayEquals(byteArrayOf(3, 4), src.read(3, 2))
        }
    }
}
