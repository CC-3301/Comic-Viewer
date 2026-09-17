package com.cc3301.comicviewer.core.source.smb

import com.cc3301.comicviewer.core.source.zip.RandomAccessBytes

/** 目录项（票 11）：path 为共享内规范路径（以 / 开头，根 = /） */
data class SmbEntry(
    val path: String,
    val name: String,
    val isDirectory: Boolean,
    val lastModifiedMs: Long?,
    val size: Long,
)

/**
 * SMB 访问窄接口（票 11）：把协议细节（连接/认证/读写）压在这一层之下，
 * 上层（SmbBackend/DocumentTreeSource）只处理路径与节点。
 *
 * 实现：`SmbjTransport`（真实 SMB2/3）；测试：`FakeSmbTransport`（文件系统伪装，
 * 让 SMB 后端能跑与本地同一套 SourceBehaviorContract）。
 */
interface SmbTransport : AutoCloseable {
    /** 目录项；目录不存在或不可读时抛异常（不返回空列表，避免把错误伪装成“空目录”） */
    fun list(path: String): List<SmbEntry>
    /** 元数据；不存在返回 null，其它失败（认证/超时/网络）必须抛异常 */
    fun stat(path: String): SmbEntry?

    fun readBytes(path: String): ByteArray

    fun openRandomAccess(path: String): RandomAccessBytes
}

/**
 * 失败归类装饰器（票 11）：把底层库/系统异常统一转成 [SmbException]，
 * 使 UI 的错误提示能区分 地址不通 / 认证失败 / 超时（issue #12 AC1）。
 */
class ClassifyingTransport(
    private val delegate: SmbTransport,
    private val config: SmbConnectionConfig,
) : SmbTransport {

    // 各操作都带上路径：提示要能指到具体共享内路径，而不是只报共享名
    override fun list(path: String): List<SmbEntry> = classify(path) { delegate.list(path) }

    override fun stat(path: String): SmbEntry? = classify(path) { delegate.stat(path) }

    override fun readBytes(path: String): ByteArray = classify(path) { delegate.readBytes(path) }

    override fun openRandomAccess(path: String): RandomAccessBytes = classify(path) { delegate.openRandomAccess(path) }

    override fun close() {
        classify { delegate.close() }
    }

    /** 已是 SmbException 的原样抛出（避免重复包装丢失原因） */
    private fun <T> classify(path: String? = null, block: () -> T): T = try {
        block()
    } catch (t: Throwable) {
        throw asSmbException(t, path)
    }

    private fun asSmbException(t: Throwable, path: String?): SmbException {
        (t as? SmbException)?.let { return it }
        val kind = classifySmbFailure(t)
        val where = config.share + path.orEmpty()
        return SmbException(kind, smbFailureMessage(kind, config.host, where), t)
    }
}
