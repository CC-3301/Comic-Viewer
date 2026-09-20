package com.cc3301.comicviewer.core.source

import java.io.File

/**
 * 落盘列表快照（票 #74）：把「一个容器这一层列出来的条目」写进 APP 私有缓存目录，
 * 键 = 连接 id + 容器 id，因此 APP 退出（会话来源被释放）后再次进入同一目录
 * 不必重新列目录、也不必逐个子目录探测。
 *
 * 失效规则（维护者已确认，票面 Desired behavior）：
 * - 写下的每条快照带**该目录自身的 mtime**、写入时间与格式版本号；**不落盘封面字节**（封面缓存口径不变）。
 * - 进入目录时按 mtime 比对：一致 → 直接用（0 次列目录、0 次探测）；不一致 → 这份快照先当显示来源，
 *   再重新列目录并做增量重探（票 #75：只重探新增或自身 mtime 变化的子目录，其余行沿用快照里的结论）。
 * - 取不到 mtime（SMB 共享根这类层）→ 用快照 + [LISTING_SNAPSHOT_TTL_MS] 兜底；
 *   取 mtime 失败（离线/服务器不可达）由来源侧决定「仍用快照把列表显示出来」，本存储不参与。
 * - TTL 7 天：超时强制重列一次。
 * - 容量上限 [LISTING_SNAPSHOT_MAX_COUNT] 条或 [LISTING_SNAPSHOT_MAX_BYTES] 字节（先到者为准），
 *   按**最后使用时间**（文件的 lastModified，命中时刷新）淘汰。
 * - 格式版本号不匹配 → **整片作废重来**（该目录下所有连接一起清），不崩溃。
 * - 缓存目录被系统清掉只是回到旧行为：本类所有落盘操作都吞掉 IO 异常，绝不向上抛。
 * - 每次读都是一次清理时机：顺手清掉写入中途被杀留下的 `*.tmp`。
 *
 * 淘汰与容量的判定抽成纯函数（[listingSnapshotExpired] / [listingSnapshotOverCapacity]），容量阈值可注入，
 * 因此单测能钉住 TTL 与淘汰而不用真的造 2000 个文件。
 */

/** 快照格式版本（票 #74）：不匹配即整片作废重来 */
const val LISTING_SNAPSHOT_FORMAT_VERSION: Int = 1

/** 快照 TTL（票 #74）：超过它强制重列一次 */
const val LISTING_SNAPSHOT_TTL_MS: Long = 7L * 24 * 60 * 60 * 1000

/** 快照容量上限（票 #74）：条数或总字节先到者为准 */
const val LISTING_SNAPSHOT_MAX_COUNT: Int = 2000
const val LISTING_SNAPSHOT_MAX_BYTES: Long = 20L * 1024 * 1024

/** 快照目录名（放在 APP 私有 cacheDir 下，随便被系统清理） */
private const val LISTING_SNAPSHOT_DIR_NAME = "listing-snapshots"

const val LISTING_SNAPSHOT_FILE_SUFFIX: String = ".txt"

/** 落盘列表快照的根目录（票 #74） */
fun listingSnapshotDir(cacheDir: File): File = File(cacheDir, LISTING_SNAPSHOT_DIR_NAME)

/** 纯函数（票 #74）：快照是否超 TTL；[writtenAtMs] 是写入时间，与最后使用时间无关 */
fun listingSnapshotExpired(
    writtenAtMs: Long,
    nowMs: Long,
    ttlMs: Long = LISTING_SNAPSHOT_TTL_MS,
): Boolean = nowMs - writtenAtMs > ttlMs

/** 纯函数（票 #74）：条数或字节数是否超过容量上限（先到者为准） */
fun listingSnapshotOverCapacity(
    count: Int,
    bytes: Long,
    maxCount: Int = LISTING_SNAPSHOT_MAX_COUNT,
    maxBytes: Long = LISTING_SNAPSHOT_MAX_BYTES,
): Boolean = count > maxCount || bytes > maxBytes

/** 落盘快照里的一条条目（[BrowseEntry] 的可序列化形态 + 排序/重探要的 mtime 与探测结论） */
internal data class PersistedListingEntry(
    val id: String,
    val name: String,
    val isBook: Boolean,
    val coverUri: String?,
    val pageCount: Int?,
    val mtimeMs: Long?,
    val probed: Boolean,
)

/** 落盘快照：容器自身的 mtime + 条目清单；写入时间由 [ListingSnapshotStore.write] 自己盖章（TTL 用它） */
internal data class PersistedListing(
    val mtimeMs: Long?,
    val entries: List<PersistedListingEntry>,
)

/**
 * 一条连接名下的落盘快照表（票 #74）。方法都是阻塞文件 IO，调用方负责放到 IO 线程上
 * （来源的枚举本来就在 `Dispatchers.IO` 上跑）。
 */
class ListingSnapshotStore(
    private val dir: File,
    private val connectionId: Long,
    /** 容量/TTL 阈值可注入（单测用小值钉住淘汰与过期） */
    private val nowMs: () -> Long = System::currentTimeMillis,
    private val ttlMs: Long = LISTING_SNAPSHOT_TTL_MS,
    private val maxCount: Int = LISTING_SNAPSHOT_MAX_COUNT,
    private val maxBytes: Long = LISTING_SNAPSHOT_MAX_BYTES,
) {

    /**
     * 读一条快照：不存在 / 读坏 / 版本不匹配 / 超 TTL 都返回 null（并顺手清掉坏的那份）。
     * 每次读都是一次**清理时机**：清掉进程在写入中途被杀而残留的 `*.tmp`（它们不在容量口径里，见 [deleteStaleTemps]）。
     */
    internal fun read(containerId: String): PersistedListing? {
        val file = fileFor(containerId)
        return runCatching {
            deleteStaleTemps()
            if (!file.isFile) return null
            val parsed = parse(file.readText()) ?: run {
                file.delete()
                return null
            }
            if (parsed.version != LISTING_SNAPSHOT_FORMAT_VERSION) {
                // 版本不匹配：**整片作废重来**（票 #74 第 7 条）——清该目录下所有连接的快照，不只当前这条连接
                clearAllFiles(dir)
                return null
            }
            if (listingSnapshotExpired(parsed.writtenAtMs, nowMs(), ttlMs)) {
                file.delete()
                return null
            }
            file.setLastModified(nowMs()) // 最后使用时间：容量淘汰按它从旧到新
            parsed.listing
        }.getOrNull()
    }

    /**
     * 清掉残留的 `*.tmp`（写入是「临时文件 + 改名」，进程在两者之间被杀会永久留下它们）：
     * 这些文件不在 2000 条/20MB 的容量口径里，所以每次进入（[read]）都要有清理时机。
     * 代价：并发写入中的那一份临时文件可能被一起删掉（那次写因此不落盘）；缓存可自愈，接受。
     */
    private fun deleteStaleTemps() {
        runCatching {
            dir.listFiles { f -> f.isFile && f.name.endsWith(TEMP_FILE_SUFFIX) }?.forEach { it.delete() }
        }
    }

    /** 写一条快照：临时文件 + 原子改名；写失败只是这次没落盘，下次再写 */
    internal fun write(containerId: String, listing: PersistedListing) {
        runCatching {
            dir.mkdirs()
            val target = fileFor(containerId)
            val tmp = File.createTempFile("listing", TEMP_FILE_SUFFIX, dir)
            try {
                tmp.writeText(serialize(listing, nowMs()))
                if (!tmp.renameTo(target)) {
                    tmp.delete()
                    return
                }
            } catch (t: Throwable) {
                tmp.delete()
                throw t
            }
            target.setLastModified(nowMs())
            evictOverCapacity()
        }
    }

    /** 清一条快照（下拉更新的显式失效入口背后，票 #74） */
    internal fun remove(containerId: String) {
        runCatching { fileFor(containerId).delete() }
    }

    /** 容量淘汰：按最后使用时间从旧到新删，直到回到上限内 */
    private fun evictOverCapacity() {
        val files = dir.listFiles { f -> f.isFile && f.name.endsWith(LISTING_SNAPSHOT_FILE_SUFFIX) }
            ?.toList() ?: return
        var count = files.size
        var bytes = files.sumOf { it.length() }
        if (!listingSnapshotOverCapacity(count, bytes, maxCount, maxBytes)) return
        for (file in files.sortedBy { it.lastModified() }) {
            if (!listingSnapshotOverCapacity(count, bytes, maxCount, maxBytes)) break
            val size = file.length()
            if (file.delete()) {
                count--
                bytes -= size
            }
        }
    }

    /** 快照文件：文件名规则见 [fileNameFor]（与连接级清理的前缀共用同一处，不会两处走样） */
    private fun fileFor(containerId: String): File =
        File(dir, fileNameFor(connectionId, containerId))

    private class Parsed(val version: Int, val writtenAtMs: Long, val listing: PersistedListing)

    private fun serialize(listing: PersistedListing, writtenAtMs: Long): String = buildString {
        append(HEADER_PREFIX).append(LISTING_SNAPSHOT_FORMAT_VERSION).append('\n')
        append(listing.mtimeMs?.toString() ?: NULL_FIELD).append('|').append(writtenAtMs).append('\n')
        append(listing.entries.size).append('\n')
        for (e in listing.entries) {
            append(if (e.probed) 1 else 0).append('|')
            append(if (e.isBook) 1 else 0).append('|')
            append(e.mtimeMs?.toString() ?: NULL_FIELD).append('|')
            append(e.pageCount?.toString() ?: NULL_FIELD).append('|')
            append(escape(e.id)).append('|')
            append(escape(e.name)).append('|')
            append(escapeNullable(e.coverUri)).append('\n')
        }
    }

    private fun parse(text: String): Parsed? {
        val lines = text.split('\n')
        if (lines.size < 3) return null
        val header = lines[0]
        if (!header.startsWith(HEADER_PREFIX)) return null
        val version = header.removePrefix(HEADER_PREFIX).toIntOrNull() ?: return null
        val meta = lines[1].split('|')
        if (meta.size != 2) return null
        val mtimeMs = meta[0].toLongOrNull()
        val writtenAtMs = meta[1].toLongOrNull() ?: return null
        val count = lines[2].toIntOrNull() ?: return null
        if (count < 0) return null
        val entries = ArrayList<PersistedListingEntry>(count)
        for (i in 0 until count) {
            val fields = lines.getOrNull(3 + i)?.split('|') ?: return null
            if (fields.size != 7) return null
            entries += PersistedListingEntry(
                id = unescape(fields[4]) ?: return null,
                name = unescape(fields[5]) ?: return null,
                isBook = fields[1] == "1",
                coverUri = unescape(fields[6]),
                pageCount = fields[3].toIntOrNull(),
                mtimeMs = fields[2].toLongOrNull(),
                probed = fields[0] == "1",
            )
        }
        return Parsed(version, writtenAtMs, PersistedListing(mtimeMs, entries))
    }

    companion object {
        private const val HEADER_PREFIX = "CVLS"
        private const val NULL_FIELD = "-"

        /** 文件名里连接 id 与容器哈希之间的分隔符（票 #74） */
        private const val FILE_NAME_SEPARATOR = "_"

        /** 写入中途的临时文件后缀（写入 = 临时文件 + 原子改名） */
        private const val TEMP_FILE_SUFFIX = ".tmp"

        /** 快照文件名（票 #74，**单一出处**）：实例读写用它 */
        internal fun fileNameFor(connectionId: Long, containerId: String): String =
            connectionFileNamePrefix(connectionId) + hashOf(containerId) + LISTING_SNAPSHOT_FILE_SUFFIX

        /** 某连接名下快照文件名的前缀（连接级清理按它前缀匹配；与 [fileNameFor] 共用这一处规则） */
        internal fun connectionFileNamePrefix(connectionId: Long): String =
            "conn" + connectionId + FILE_NAME_SEPARATOR

        /** 清某连接名下的全部快照（连接被编辑/删除，票 #74）；目录不存在时什么都不做 */
        internal fun clearConnection(dir: File, connectionId: Long) {
            runCatching {
                val prefix = connectionFileNamePrefix(connectionId)
                dir.listFiles { f -> f.isFile && f.name.startsWith(prefix) }?.forEach { it.delete() }
            }
        }

        /**
         * 整片作废（票 #74 第 7 条：格式版本不匹配）：清该目录下**所有连接**的快照文件与残留临时文件，
         * 否则别的连接的旧格式文件会各自滞留到被读到为止、白占容量额度。
         */
        internal fun clearAllFiles(dir: File) {
            runCatching {
                dir.listFiles { f -> f.isFile && (f.name.endsWith(LISTING_SNAPSHOT_FILE_SUFFIX) || f.name.endsWith(TEMP_FILE_SUFFIX)) }
                    ?.forEach { it.delete() }
            }
        }

        /** 容器 id → 文件名用哈希（id 里可能含 `/`、`:` 等路径/查询字符），实现与页缓存键共用 [sha256Hex] */
        private fun hashOf(containerId: String): String = sha256Hex(containerId, hexChars = 32)
    }
}

/** 字段转义：只动分隔符与转义符本身，非 ASCII 原样（文件按 UTF-8 读写） */
private fun escape(value: String): String = buildString(value.length) {
    for (c in value) {
        when (c) {
            '%' -> append("%25")
            '\n' -> append("%0A")
            '\r' -> append("%0D")
            '|' -> append("%7C")
            else -> append(c)
        }
    }
}

private fun unescape(value: String): String? {
    if (value == "%00") return null
    val out = StringBuilder(value.length)
    var i = 0
    while (i < value.length) {
        val c = value[i]
        if (c == '%' && i + 2 < value.length) {
            val code = value.substring(i + 1, i + 3).toIntOrNull(16)
            if (code != null) {
                out.append(code.toChar())
                i += 3
                continue
            }
        }
        out.append(c)
        i++
    }
    return out.toString()
}

private fun escapeNullable(value: String?): String = value?.let(::escape) ?: "%00"
