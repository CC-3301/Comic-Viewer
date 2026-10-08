package com.cc3301.comicviewer.core.source.fs

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.content.pm.ProviderInfo
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import android.provider.DocumentsContract.Document
import org.robolectric.shadows.ShadowContentResolver
import java.io.File
import java.io.FileNotFoundException
import java.util.Locale

/**
 * 假文档提供者：把一棵临时目录当 SAF 授权树，供 [SafBackend] 走真实的 DocumentsContract 通路。
 *
 * 文档 id 与目录的相对路径一一对应（根 = [ROOT_DOCUMENT_ID]，子项 = `root/<相对路径>`）：
 * tree/document uri 的形状、id 前缀推导都与设备上同形。
 *
 * 不继承 `DocumentsProvider`：它在 O 之后把字符串查询口封成一律抛 `UnsupportedOperationException`，
 * 而 Robolectric 下 `ContentResolver` 的字符串查询口直接落到提供者的同名方法上——`DocumentFile`
 * 的属性读取与列子项都走那个口。因此这里自己按 uri 形状分派（[query]）并实现 [openFile]。
 */
class FakeDocumentsProvider : ContentProvider() {

    /** 当前供应的目录：每个用例一份临时目录，由绑定在造来源之前设置 */
    private var servedRoot: File? = null

    fun serve(root: File) {
        servedRoot = root
    }

    /** 注册到 ContentResolver（测试绑定在用例开始前调一次） */
    fun register(context: Context) {
        attachInfo(
            context,
            ProviderInfo().apply {
                authority = AUTHORITY
                exported = true
                applicationInfo = context.applicationInfo
            },
        )
        ShadowContentResolver.registerProviderInternal(AUTHORITY, this)
    }

    override fun onCreate(): Boolean = true

    override fun query(
        uri: Uri,
        projection: Array<String>?,
        selection: String?,
        selectionArgs: Array<String>?,
        sortOrder: String?,
    ): Cursor {
        val segments = uri.pathSegments
        return when {
            segments.size >= 5 && segments[4] == PATH_CHILDREN ->
                queryChildDocuments(segments[3], projection)

            segments.size >= 4 && segments[2] == PATH_DOCUMENT -> queryDocument(segments[3], projection)
            else -> cursorOf(projection, emptyList())
        }
    }

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        val documentId = DocumentsContract.getDocumentId(uri)
        val file = fileOf(documentId)
        if (file == null || file.isDirectory) throw FileNotFoundException("无法打开：$documentId")
        return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
    }

    override fun getType(uri: Uri): String? = fileOf(DocumentsContract.getDocumentId(uri))?.let(::mimeTypeOf)

    /** 单条文档：列的属性按调用方点名的投影摆放；不存在给空结果（`DocumentFile.exists` 按行数判定） */
    private fun queryDocument(documentId: String, projection: Array<String>?): Cursor {
        val file = fileOf(documentId)
        return cursorOf(projection, if (file == null) emptyList() else listOf(rowOf(documentId, file)))
    }

    private fun queryChildDocuments(parentDocumentId: String, projection: Array<String>?): Cursor {
        val parent = fileOf(parentDocumentId) ?: return cursorOf(projection, emptyList())
        val rows = (parent.listFiles() ?: emptyArray())
            .sortedBy { it.name }
            .map { rowOf("$parentDocumentId/${it.name}", it) }
        return cursorOf(projection, rows)
    }

    /** 文档 id → 目录里的文件；越界或不存在返回 null */
    private fun fileOf(documentId: String): File? {
        val root = servedRoot ?: return null
        if (documentId == ROOT_DOCUMENT_ID) return root
        val prefix = "$ROOT_DOCUMENT_ID/"
        if (!documentId.startsWith(prefix)) return null
        val file = File(root, documentId.removePrefix(prefix))
        return if (file.exists()) file else null
    }

    private fun rowOf(documentId: String, file: File): Map<String, Any?> = mapOf(
        Document.COLUMN_DOCUMENT_ID to documentId,
        Document.COLUMN_DISPLAY_NAME to file.name,
        Document.COLUMN_MIME_TYPE to mimeTypeOf(file),
        Document.COLUMN_LAST_MODIFIED to file.lastModified(),
        Document.COLUMN_SIZE to if (file.isDirectory) 0L else file.length(),
    )

    private fun cursorOf(projection: Array<String>?, rows: List<Map<String, Any?>>): Cursor {
        val columns = projection?.takeIf { it.isNotEmpty() } ?: DEFAULT_PROJECTION
        val cursor = MatrixCursor(columns)
        for (row in rows) {
            val builder = cursor.newRow()
            for (column in columns) builder.add(row[column])
        }
        return cursor
    }

    private fun mimeTypeOf(file: File): String = if (file.isDirectory) {
        Document.MIME_TYPE_DIR
    } else {
        when (file.extension.lowercase(Locale.ROOT)) {
            "jpg", "jpeg" -> "image/jpeg"
            "png" -> "image/png"
            "webp" -> "image/webp"
            "gif" -> "image/gif"
            "xml" -> "text/xml"
            "cbz", "zip" -> "application/zip"
            else -> "application/octet-stream"
        }
    }

    override fun insert(uri: Uri, values: ContentValues?): Uri? =
        throw UnsupportedOperationException("假文档提供者只读")

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<String>?): Int =
        throw UnsupportedOperationException("假文档提供者只读")

    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<String>?): Int =
        throw UnsupportedOperationException("假文档提供者只读")

    companion object {
        const val AUTHORITY: String = "com.cc3301.comicviewer.test.documents"

        /** 授权树根目录的文档 id */
        const val ROOT_DOCUMENT_ID: String = "root"

        private const val PATH_TREE = "tree"
        private const val PATH_DOCUMENT = "document"
        private const val PATH_CHILDREN = "children"

        private val DEFAULT_PROJECTION = arrayOf(
            Document.COLUMN_DOCUMENT_ID,
            Document.COLUMN_DISPLAY_NAME,
            Document.COLUMN_MIME_TYPE,
            Document.COLUMN_LAST_MODIFIED,
            Document.COLUMN_SIZE,
        )

        /** 供来源使用的授权树 uri（形状同设备上 ACTION_OPEN_DOCUMENT_TREE 的返回） */
        fun treeUri(): Uri = Uri.Builder()
            .scheme("content")
            .authority(AUTHORITY)
            .appendPath(PATH_TREE)
            .appendPath(ROOT_DOCUMENT_ID)
            .build()
    }
}
