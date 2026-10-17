package com.cc3301.comicviewer.core.source

import com.cc3301.comicviewer.core.concurrent.catchingNonCancellation
import com.cc3301.comicviewer.core.source.fs.FsNode
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/** 探测期首图结点缓存的上界（量级同封面字节缓存）：只记「目录内首图是哪个结点」，不记字节。 */
private const val PROBED_FIRST_IMAGE_MAX_ENTRIES: Int = CoverByteCache.DEFAULT_MAX_ENTRIES

/**
 * 封面取数：目录封面的**逐级下取**、压缩包封面的落盘缓存、会话级字节缓存。
 *
 * 只依赖 `FsNode` 那条接缝（[FsNode] 与顶层判定 [dirContentsOf]），不反向依赖文件源适配器；
 * 适配器只负责按条目 id 找到节点、打点、把结果交给界面。
 *
 * 三条与调用方相关的规则：
 * - 缓存键一律出自 [CacheKeys]（条目 id + 条目自身 mtime）：文件换过就换键，旧值自然失效。
 * - 逐级下取只发生在按需取封面时（枚举期不发生）：每层目录只列一次，每层最多开一个包（名称序第一个）。
 * - 取不到返回 null（界面显示占位底色），不抛出。
 */
internal class CoverDescent(
    /** 封面落盘缓存目录（「封面生成后缓存」）；null 时不落盘、每次按需解出 */
    private val coverCacheDir: File?,
    /** 名称自然序：每层分区的次序与下取子目录的次序都按它 */
    private val nameComparator: Comparator<String>,
    /** 包内首页的原始字节（包内条目缓存不在本模块，由适配器注入） */
    private val archiveFirstPageBytes: (FsNode) -> ByteArray?,
) {

    /**
     * 封面字节的会话级缓存：键含条目 id 与 mtime（文件换过就换键），字节层面直接命中，
     * 二次进入读字节 0 次。
     * 上界与淘汰口径（越界按插入序淘汰最旧，不是整仓清空）收在 [CoverByteCache]：
     * Komga 源共用同一份实现（预取结果必须真被可见行复用）。[clear] 仍**整体清空**。
     */
    private val coverBytesCache = CoverByteCache()

    /**
     * 条目 id → 当前字节缓存的键。[isCached] 要在**不做 IO**（不 resolve）的前提下回答
     * 「缓存里有没有这一条」，而字节缓存的键含 mtime，只有把索引记下来才知道该查哪把键。
     *
     * 索引可能指已不在缓存里的键（被淘汰、或文件 mtime 变了）：查缓存得到 null，[isCached]
     * 因此返回 false（下一次预取会重取）——这是正确结果，不需要联动失效。
     * 上界（它只是缓存的一个索引，与缓存同量级）：超了就整体清空。
     */
    private val coverCacheKeyByEntry = ConcurrentHashMap<String, String>()

    /**
     * 探测期已知的「目录内首图」：键 = 子目录 id + mtime。
     *
     * 探测「这个子目录是不是书」时本来就把它的子节点列了出来，首图就在手里；不记下来的话，
     * 可见行取封面时还要再列一次同一目录（SMB 上就是一次多余往返）。
     */
    private val probedFirstImages = ConcurrentHashMap<String, FsNode>()

    /** 节点对应的字节缓存键（[FsNode.id] 与条目 id 同源） */
    fun cacheKeyOf(node: FsNode): String = CacheKeys.coverBytes(node.id, node.lastModifiedMs)

    /** 键上已有的字节；命中即 0 次来源往返 */
    fun cached(key: String): ByteArray? = coverBytesCache.get(key)

    /** 记下刚解出的字节 */
    fun put(key: String, bytes: ByteArray) {
        coverBytesCache.put(key, bytes)
    }

    /** 记下「这个条目当下对应的缓存键」（索引超上界就整体清空：它只是缓存的索引） */
    fun rememberCacheKey(entryId: String, cacheKey: String) {
        if (coverCacheKeyByEntry.size >= CoverByteCache.DEFAULT_MAX_ENTRIES * 4) coverCacheKeyByEntry.clear()
        coverCacheKeyByEntry[entryId] = cacheKey
    }

    /**
     * 缓存里**已有**这一条的字节吗：只查内存与索引，不 resolve、不发请求
     * （它在滚动路径上被逐条调用，SMB 上 resolve 是一次以上往返）。
     */
    fun isCached(entryId: String): Boolean =
        coverCacheKeyByEntry[entryId]?.let { coverBytesCache.get(it) != null } ?: false

    /**
     * 整体清空字节缓存与其索引（手动刷新 / 会话释放）：两处必须一起清，否则索引会指空键。
     * 返回清掉的量（打点取它，不清两遍）。
     */
    fun clear(): CoverByteCache.Cleared {
        val cleared = coverBytesCache.clear()
        coverCacheKeyByEntry.clear()
        return cleared
    }

    /** 记下探测期看到的「目录内首图」（键含目录 mtime，目录一变就换键，不会拿旧首图当封面） */
    fun rememberProbedFirstImage(dir: FsNode, firstImage: FsNode) {
        if (probedFirstImages.size >= PROBED_FIRST_IMAGE_MAX_ENTRIES) probedFirstImages.clear()
        probedFirstImages[cacheKeyOf(dir)] = firstImage
    }

    /** 清掉探测期首图（实例被释放时：缓存不再有消费者） */
    fun clearProbedFirstImages() {
        probedFirstImages.clear()
    }

    /** 节点 → 封面字节：压缩包取包内首页、图片取本身、目录逐级下取；取不到返回 null */
    suspend fun bytesFor(node: FsNode): ByteArray? = when {
        node.isArchiveFile() -> archiveCoverBytes(node)
        node.isImageFile() -> node.readBytesCancellable()
        // 库「一个子文件夹一本书」是常见布局：探测期已经列过这个目录，首图就在手里
        node.isDirectory -> probedFirstImageBytes(node) ?: directoryCoverBytes(node)
        else -> null
    }

    /**
     * 目录封面字节（spec 故事 9）：从本层开始**逐级下取**，**每一层都走同一套优先级**（[layerCoverBytes]）——
     * 本层图片 → 本层首个压缩包的首帧 → 子目录（名称序、深度优先）；取到即停，取不到返回 null。
     *
     * 代价有上界：每层目录只列一次；每层最多开一个包（本层没有图时才开名称序第一个包，同层其余包不碰）。
     */
    private suspend fun directoryCoverBytes(dir: FsNode): ByteArray? = firstCoverBytesDeep(dir.children())

    /** 逐级下取（容器封面规则）：先按本层优先级取，取不到才下取子目录（名称序、深度优先、每层目录只列一次） */
    private suspend fun firstCoverBytesDeep(kids: List<FsNode>): ByteArray? {
        // 本层分区走 [dirContentsOf]（「一个目录里有什么」的唯一判定点）
        val contents = dirContentsOf(kids, nameComparator)
        layerCoverBytes(contents)?.let { return it }
        for (sub in contents.subDirs) {
            firstCoverBytesDeep(sub.children())?.let { return it }
        }
        return null
    }

    /**
     * 一层目录的封面优先级（「单层目录」与「逐级下取」共用这一处）：
     * ① [DirContents.images] 首图 → ② [DirContents.archives] 首个（名称序）的首帧 → null。
     * ②只开名称序那一个包；同层的其余包一个都不开（取封面不为同层每个包买单）。
     * 分区由 [dirContentsOf] 给出，与浏览列表/页序列同一套口径。
     */
    private suspend fun layerCoverBytes(contents: DirContents): ByteArray? =
        contents.images.firstOrNull()?.readBytesCancellable()
            ?: contents.archives.firstOrNull()?.let { archiveCoverBytes(it) }

    /** 探测期已知的目录内首图字节：命中即 0 次列目录。读走可取消那条（拖滑条时一次下拉不再白耗） */
    private suspend fun probedFirstImageBytes(dir: FsNode): ByteArray? {
        val first = probedFirstImages[cacheKeyOf(dir)] ?: return null
        // 取消照常传播（[catchingNonCancellation]）：只有真失败才当作「这一条没有字节」
        return catchingNonCancellation { first.readBytesCancellable() }.getOrNull()
    }

    /**
     * 压缩包封面字节 + 落盘缓存（「封面生成后缓存」：只在按需通路上解）：
     * 第一次取时解出并写缓存文件，后续滚动/回看不重开包；无缓存目录（或读写失败）时每次解出。
     */
    private fun archiveCoverBytes(node: FsNode): ByteArray? {
        val cached = coverCacheFile(node)
        if (cached != null && cached.exists() && cached.length() > 0L) {
            runCatching { cached.readBytes() }.getOrNull()?.let { return it }
        }
        val bytes = archiveFirstPageBytes(node) ?: return null
        if (cached != null) writeCoverCacheFile(cached, node.id, bytes)
        return bytes
    }

    /**
     * 落盘走「临时文件 + 原子改名」：同一个 entry 的封面可被界面并发触发
     * （可见行重进组合、刷新时 LaunchedEffect 重启），直接 writeBytes 会先截断再写，
     * 并发的读者可能读到半截文件而解码失败。**改名是覆盖式的**：`File.renameTo` 在 Windows 上
     * 不覆盖，同键第二次写会静默失效（同一个 id + mtime 再解一次时旧文件挡住新字节）。
     * 写失败只是白解一次，下次仍会重试。
     * 改名成功后再清同一条目的旧封面文件：文件名带 mtime，不清理的话每改一次多留一份。
     */
    private fun writeCoverCacheFile(target: File, entryId: String, bytes: ByteArray) {
        runCatching {
            val dir = target.parentFile ?: return
            val tmp = File.createTempFile(target.name, ".tmp", dir)
            try {
                tmp.writeBytes(bytes)
                moveOverExistingTarget(tmp, target)
            } catch (t: Throwable) {
                tmp.delete()
                throw t
            }
            pruneCoverCacheSiblings(target, entryId)
        }
    }

    /**
     * 删掉同一条目的旧封面文件（判据见 [CacheKeys.isCoverCacheFileOf]，只认当前命名格式；
     * 旧名交给系统清理缓存目录）。
     * 在新文件就位后才删，因此不会留下半截文件；并发场景下最坏是删掉刚写入的「更新一轮」文件，
     * 下次取封面重新解一次（缓存目录，可自愈）。
     */
    private fun pruneCoverCacheSiblings(target: File, entryId: String) {
        val dir = target.parentFile ?: return
        dir.listFiles { f ->
            f.isFile && f.name != target.name && CacheKeys.isCoverCacheFileOf(f.name, entryId)
        }?.forEach { it.delete() }
    }

    /**
     * 封面缓存文件（沿用既有命名，键并入 node.id 与 mtime）：内容变了（mtime 变）就是另一个文件名，
     * 手动刷新后不会再命中旧封面；无缓存目录时为 null。
     */
    private fun coverCacheFile(node: FsNode): File? = coverCacheDir?.let { dir ->
        File(dir, CacheKeys.coverCacheFileName(node.id, node.lastModifiedMs))
    }
}
