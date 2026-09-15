package com.cc3301.comicviewer.core.source.fs

import java.io.File
import java.net.URI

/** java.io.File 后端：契约测试 temp dir 与无 SAF 场景使用 */
class FileNode(val file: File) : FsNode {
    override val id: String = file.absolutePath
    override val name: String = file.name
    override val isDirectory: Boolean = file.isDirectory
    override val lastModifiedMs: Long? = file.lastModified().takeIf { it > 0L }
    override val imageUri: String = file.toURI().toString()
    override fun children(): List<FsNode> = file.listFiles()?.map { FileNode(it) } ?: emptyList()
    override fun parent(): FsNode? = file.parentFile?.let { FileNode(it) }
    override fun readBytes(): ByteArray = file.readBytes()
}

class FileBackend(private val rootDir: File) : FsBackend {
    private val rootPath: java.nio.file.Path = rootDir.absoluteFile.toPath().normalize()

    override val root: FsNode = FileNode(rootDir.absoluteFile)

    override fun resolve(id: String): FsNode? {
        return try {
            val path = java.nio.file.Paths.get(id).normalize()
            // 按路径组件比较（字符串前缀可被兄弟目录绕过）
            if (!path.startsWith(rootPath)) return null
            val f = path.toFile()
            if (f.exists()) FileNode(f) else null
        } catch (e: Exception) {
            null
        }
    }
}
