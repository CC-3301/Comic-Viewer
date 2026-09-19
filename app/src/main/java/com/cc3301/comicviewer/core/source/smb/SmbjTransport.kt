package com.cc3301.comicviewer.core.source.smb

import com.cc3301.comicviewer.core.source.remote.BlockCachedRandomAccess
import com.cc3301.comicviewer.core.source.remote.ClassifyingRandomAccess
import com.cc3301.comicviewer.core.source.remote.isRecoverableRemoteFailure
import com.cc3301.comicviewer.core.source.remote.retryOnce
import com.cc3301.comicviewer.core.source.zip.RandomAccessBytes
import com.hierynomus.msdtyp.AccessMask
import com.hierynomus.msfscc.FileAttributes
import com.hierynomus.mssmb2.SMB2CreateDisposition
import com.hierynomus.mssmb2.SMB2ShareAccess
import com.hierynomus.protocol.commons.EnumWithValue.EnumUtils.isSet
import com.hierynomus.smbj.SMBClient
import com.hierynomus.smbj.SmbConfig as LibrarySmbConfig
import com.hierynomus.smbj.auth.AuthenticationContext
import com.hierynomus.smbj.connection.Connection
import com.hierynomus.smbj.session.Session
import com.hierynomus.smbj.share.DiskShare
import com.hierynomus.smbj.share.File as SmbFile
import java.io.ByteArrayOutputStream
import java.util.EnumSet
import java.util.concurrent.TimeUnit

/**
 * 真实 SMB 传输（票 11）：基于 smbj（SMB2/3），共享句柄按需建立、断链后自动重连一次。
 *
 * 设计要点：
 * - 只抛底层异常，由 [ClassifyingTransport] 统一归类成「地址不通 / 认证失败 / 超时」等用户可读提示；
 * - [openRandomAccess] 保留一个打开的文件句柄，供 ZIP 中央目录解析与按条目解压做 seek 读，
 *   因此 CBZ 不必整包下载（spec：解析中央目录 + 随机访问）；
 * - 服务端空闲断链/网络闪断时丢弃会话重连一次（issue #12 AC4），
 *   随机访问句柄中途断开则把错误上抛给调用方（下一页会重新建立会话）。
 *
 * 本类依赖 Android 网络栈与真实 SMB 服务器，故不做单元测试：协议之上的行为由
 * SmbSourceContractTest（FakeSmbTransport）覆盖，真机链路走票面验收清单。
 */
class SmbjTransport(private val config: SmbConnectionConfig) : SmbTransport {

    @Volatile
    private var client: SMBClient? = null

    @Volatile
    private var connection: Connection? = null

    @Volatile
    private var session: Session? = null

    @Volatile
    private var share: DiskShare? = null

    override fun list(path: String): List<SmbEntry> = withRetry { sh ->
        val base = SmbPaths.normalize(path)
        sh.list(SmbPaths.toWindows(base))
            .asSequence()
            // smbj 的目录列举会带 "." 与 ".." 伪条目
            .filterNot { SmbPaths.isPseudoEntry(it.fileName) }
            .map { info ->
                SmbEntry(
                    path = SmbPaths.child(base, info.fileName),
                    name = info.fileName,
                    isDirectory = isSet(info.fileAttributes, FileAttributes.FILE_ATTRIBUTE_DIRECTORY),
                    lastModifiedMs = info.lastWriteTime?.toEpochMillis(),
                    size = info.endOfFile,
                )
            }
            .sortedBy { it.name }
            .toList()
    }

    override fun stat(path: String): SmbEntry? = withRetry { sh ->
        val norm = SmbPaths.normalize(path)
        if (norm == SmbPaths.ROOT) {
            // 共享根没有可 stat 的对象：用一次列举确认连接与访问权限
            sh.list("")
            SmbEntry(SmbPaths.ROOT, config.share, isDirectory = true, lastModifiedMs = null, size = 0)
        } else {
            val win = SmbPaths.toWindows(norm)
            val name = norm.substringAfterLast('/')
            when {
                sh.folderExists(win) -> {
                    val info = sh.getFileInformation(win)
                    SmbEntry(norm, name, isDirectory = true, lastModifiedMs = info.basicInformation.lastWriteTime?.toEpochMillis(), size = 0)
                }
                sh.fileExists(win) -> {
                    val info = sh.getFileInformation(win)
                    SmbEntry(
                        path = norm,
                        name = name,
                        isDirectory = false,
                        lastModifiedMs = info.basicInformation.lastWriteTime?.toEpochMillis(),
                        size = info.standardInformation.endOfFile,
                    )
                }
                else -> null
            }
        }
    }

    override fun readBytes(path: String): ByteArray = withRetry { sh ->
        openFile(sh, SmbPaths.toWindows(SmbPaths.normalize(path))).use { file ->
            val size = file.length
            val out = ByteArrayOutputStream(size.coerceIn(0L, PREFETCH_LIMIT_BYTES).toInt())
            val buffer = ByteArray(READ_CHUNK_BYTES)
            var offset = 0L
            while (offset < size) {
                val read = file.read(buffer, offset)
                if (read <= 0) break
                out.write(buffer, 0, read)
                offset += read
            }
            out.toByteArray()
        }
    }

    override fun openRandomAccess(path: String): RandomAccessBytes {
        val win = SmbPaths.toWindows(SmbPaths.normalize(path))
        val file = withRetry { sh -> openFile(sh, win) }
        // 读/关闭发生在后台线程：断链必须归类成 SmbException（TransportFailure）而不是裸 IO 异常，
        // 否则上层会把「网络断了」当成「这本 CBZ 损坏」而静默显示 0 页
        return ClassifyingRandomAccess(SmbRandomAccess(file)) {
            asSmbException(it, config.host, config.share + path)
        }
    }

    override fun close() {
        closeQuietly()
    }

    // ---------- 内部 ----------

    private fun openFile(sh: DiskShare, windowsPath: String): SmbFile =
        sh.openFile(
            windowsPath,
            EnumSet.of(AccessMask.GENERIC_READ),
            null,
            SMB2ShareAccess.ALL,
            SMB2CreateDisposition.FILE_OPEN,
            null,
        )

    /** 共享句柄：未连接/已断开则重建（认证失败等在这里抛出，由装饰器归类） */
    @Synchronized
    private fun connectedShare(): DiskShare {
        share?.takeIf { it.isConnected }?.let { return it }
        closeQuietly()
        val newClient = SMBClient(libraryConfig())
        client = newClient
        val newConnection = newClient.connect(config.host, config.port)
        connection = newConnection
        val newSession = newConnection.authenticate(
            AuthenticationContext(config.username, config.password.toCharArray(), config.domain),
        )
        session = newSession
        val newShare = newSession.connectShare(config.share) as? DiskShare
            ?: throw IllegalStateException("不是磁盘共享：" + config.share)
        share = newShare
        return newShare
    }

    /** 连接层故障重连一次；非连接类错误（认证/不存在/权限）直接上抛，不做无意义重试 */
    private fun <T> withRetry(block: (DiskShare) -> T): T = retryOnce(
        isRecoverable = ::isRecoverableRemoteFailure,
        reconnect = ::closeQuietly,
        block = { block(connectedShare()) },
    )

    @Synchronized
    private fun closeQuietly() {
        runCatching { share?.close() }
        runCatching { session?.close() }
        runCatching { connection?.close() }
        runCatching { client?.close() }
        share = null
        session = null
        connection = null
        client = null
    }

    private fun libraryConfig(): LibrarySmbConfig = LibrarySmbConfig.builder()
        .withTimeout(CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .withSoTimeout(READ_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .build()

    private companion object {
        const val CONNECT_TIMEOUT_SECONDS = 15L
        const val READ_TIMEOUT_SECONDS = 45L
        const val READ_CHUNK_BYTES = 256 * 1024
        const val PREFETCH_LIMIT_BYTES = 4L * 1024 * 1024
    }
}

/**
 * smbj 文件句柄的随机访问适配（ZIP 中央目录与按条目解压都基于它）。
 * 块缓存与「读失败归类」由 core/source/remote 的共享实现提供（与 WebDAV 同一套）。
 *
 * 注意 [size] 不是内存字段：它是 `SmbFile.getLength()`，即一次 QUERY_INFO 往返（smbj 0.15.0）。
 * 因此共享块缓存把长度解析收敛成「每个句柄一次」（票 #91：逐次解析会把一次开包放大成上千次往返）。
 */
internal class SmbRandomAccess(
    private val file: SmbFile,
) : BlockCachedRandomAccess() {

    override val size: Long get() = file.length

    /** 直读（必要时补齐）；句柄中途断开时异常上抛，由上层归类装饰器转成 SmbException */
    override fun fetch(offset: Long, len: Int): ByteArray {
        val out = ByteArray(len)
        var done = 0
        while (done < len) {
            val read = file.read(out, offset + done, done, len - done)
            if (read <= 0) break
            done += read
        }
        return if (done == len) out else out.copyOf(done)
    }

    override fun close() {
        runCatching { file.close() }
    }
}
