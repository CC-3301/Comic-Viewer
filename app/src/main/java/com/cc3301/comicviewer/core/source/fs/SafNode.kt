package com.cc3301.comicviewer.core.source.fs

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import androidx.documentfile.provider.DocumentFile

/**
 * SAF 文档树后端（票 04）：ACTION_OPEN_DOCUMENT_TREE 授权的目录树。
 * document uri 即节点 id；resolve 直接由 uri 重建节点，provider 保证不逃出授权树。
 *
 * parent() 由 documentId 前缀推导父 uri（fromSingleUri 重建的节点 parentFile 为 null，
 * 不推导则混合目录图片条目的连读页序列为空——code review P1 修复）。
 */
class SafNode(
    private val context: Context,
    private val treeUri: Uri,
    private val doc: DocumentFile,
) : FsNode {
    override val id: String = doc.uri.toString()
    override val name: String = doc.name ?: DocumentsContract.getDocumentId(doc.uri) ?: ""
    override val isDirectory: Boolean = doc.isDirectory
    override val lastModifiedMs: Long? = doc.lastModified().takeIf { it > 0L }
    override val imageUri: String = doc.uri.toString()

    private val docId: String = DocumentsContract.getDocumentId(doc.uri) ?: id

    override fun children(): List<FsNode> = doc.listFiles().map { SafNode(context, treeUri, it) }

    override fun parent(): FsNode? {
        val treeId = DocumentsContract.getTreeDocumentId(treeUri)
        if (docId == treeId) return null
        // "primary:DCIM/sub" → "primary:DCIM"；授权根的直接子项 "primary:Download" → "primary:"
        val parentDocId = when {
            docId.contains('/') -> docId.substringBeforeLast('/')
            docId.contains(':') -> docId.substringBefore(':') + ":"
            else -> return null
        }
        if (parentDocId == docId) return null
        val parentUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, parentDocId)
        val parentDoc = DocumentFile.fromSingleUri(context, parentUri) ?: return null
        return if (parentDoc.exists()) SafNode(context, treeUri, parentDoc) else null
    }

    override fun readBytes(): ByteArray =
        context.contentResolver.openInputStream(doc.uri)?.use { it.readBytes() }
            ?: throw IllegalStateException("无法打开：$id")
}

class SafBackend(private val context: Context, treeUri: Uri) : FsBackend {

    private val treeUri: Uri = treeUri
    private val rootDoc: DocumentFile = DocumentFile.fromTreeUri(context, treeUri)
        ?: throw IllegalArgumentException("无效的授权目录树：$treeUri")

    override val root: FsNode = SafNode(context, treeUri, rootDoc)

    override fun resolve(id: String): FsNode? {
        return try {
            val uri = Uri.parse(id)
            if (!isWithinTree(uri)) return null
            val doc = DocumentFile.fromSingleUri(context, uri) ?: return null
            if (doc.exists()) SafNode(context, treeUri, doc) else null
        } catch (e: Exception) {
            null
        }
    }

    /** 节点 id 必须携带同一棵授权树（tree 衍生 document uri） */
    private fun isWithinTree(uri: Uri): Boolean {
        val treeId = DocumentsContract.getTreeDocumentId(rootDoc.uri)
        val docId = DocumentsContract.getDocumentId(uri) ?: return false
        return docId == treeId || docId.startsWith("$treeId/")
    }
}
