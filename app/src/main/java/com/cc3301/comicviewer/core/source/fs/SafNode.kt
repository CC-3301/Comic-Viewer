package com.cc3301.comicviewer.core.source.fs

import android.content.Context
import androidx.documentfile.provider.DocumentFile

/**
 * SAF 文档树后端（票 04）：ACTION_OPEN_DOCUMENT_TREE 授权的目录树。
 * document uri 即节点 id；resolve 直接由 uri 重建节点，provider 保证不逃出授权树。
 */
class SafNode(private val context: Context, private val doc: DocumentFile) : FsNode {
    override val id: String = doc.uri.toString()
    override val name: String = doc.name ?: doc.uri.lastPathSegment ?: ""
    override val isDirectory: Boolean = doc.isDirectory
    override val lastModifiedMs: Long? = doc.lastModified().takeIf { it > 0L }
    override val imageUri: String = doc.uri.toString()
    override fun children(): List<FsNode> = doc.listFiles().map { SafNode(context, it) }
    override fun parent(): FsNode? = doc.parentFile?.let { SafNode(context, it) }
    override fun readBytes(): ByteArray =
        context.contentResolver.openInputStream(doc.uri)?.use { it.readBytes() }
            ?: throw IllegalStateException("无法打开：$id")
}

class SafBackend(private val context: Context, treeUri: android.net.Uri) : FsBackend {

    private val rootDoc: DocumentFile = DocumentFile.fromTreeUri(context, treeUri)
        ?: throw IllegalArgumentException("无效的授权目录树：$treeUri")

    override val root: FsNode = SafNode(context, rootDoc)

    override fun resolve(id: String): FsNode? {
        return try {
            val uri = android.net.Uri.parse(id)
            if (!isWithinTree(uri)) return null
            val doc = DocumentFile.fromSingleUri(context, uri) ?: return null
            if (doc.exists()) SafNode(context, doc) else null
        } catch (e: Exception) {
            null
        }
    }

    /** 节点 id 必须携带同一棵授权树（tree 衍生 document uri） */
    private fun isWithinTree(uri: android.net.Uri): Boolean {
        val treeId = android.provider.DocumentsContract.getTreeDocumentId(rootDoc.uri)
        val docId = android.provider.DocumentsContract.getDocumentId(uri) ?: return false
        return docId == treeId || docId.startsWith("$treeId/")
    }
}
