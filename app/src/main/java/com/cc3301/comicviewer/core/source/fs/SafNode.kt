package com.cc3301.comicviewer.core.source.fs

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import androidx.documentfile.provider.DocumentFile
import com.cc3301.comicviewer.core.source.zip.ChannelRandomAccess
import com.cc3301.comicviewer.core.source.zip.RandomAccessBytes

/**
 * SAF 文档树后端：ACTION_OPEN_DOCUMENT_TREE 授权的目录树。
 * document uri 即节点 id；resolve 直接由 uri 重建节点，provider 保证不逃出授权树。
 *
 * 子项与父层都由 docId 自造（列子项问 provider 要子文档 id、父层按 docId 前缀推导），
 * 不经过 `DocumentFile` 的列目录与父层推导：`fromSingleUri` 造出的节点只作单条文档读属性，
 * 同一份代码对授权根与树内任意节点都成立。名称/是否目录/修改时间仍读 `DocumentFile`。
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

    /** 列子项：一次 query 取齐全层子文档 id，再各自造节点 */
    override fun children(): List<FsNode> = childDocIds().mapNotNull { nodeOf(it) }

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
        return nodeOf(parentDocId)?.takeIf { it.doc.exists() }
    }

    /** 自造节点：uri 由授权树与该 docId 拼出（授权根与树内任意节点同形，见类注释） */
    private fun nodeOf(nodeDocId: String): SafNode? {
        val uri = DocumentsContract.buildDocumentUriUsingTree(treeUri, nodeDocId)
        val nodeDoc = DocumentFile.fromSingleUri(context, uri) ?: return null
        return SafNode(context, treeUri, nodeDoc)
    }

    /** 本层的子文档 id：列目录的唯一出口（`DocumentFile.listFiles()` 只在 tree 型节点上有实现） */
    private fun childDocIds(): List<String> {
        val childUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, docId)
        val ids = mutableListOf<String>()
        context.contentResolver
            .query(childUri, arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID), null, null, null)
            ?.use { cursor ->
                while (cursor.moveToNext()) ids += cursor.getString(0)
            }
        return ids
    }

    override fun readBytes(): ByteArray =
        context.contentResolver.openInputStream(doc.uri)?.use { it.readBytes() }
            ?: throw IllegalStateException("无法打开：$id")

    /**
     * SAF 随机访问：openFileDescriptor 的 FileDescriptor 通道可 seek（本地 provider）。
     * 若 provider 返回不可 seek 的通道（部分云盘），读取会失败——由调用方按“容器不可随机读”处理。
     */
    override fun openRandomAccess(): RandomAccessBytes {
        val pfd = context.contentResolver.openFileDescriptor(doc.uri, "r")
            ?: throw IllegalStateException("无法打开：$id")
        val stream = java.io.FileInputStream(pfd.fileDescriptor)
        return ChannelRandomAccess(stream.channel, pfd)
    }
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
