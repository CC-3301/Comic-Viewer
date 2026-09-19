package com.cc3301.comicviewer.core.source

import com.cc3301.comicviewer.core.meta.parseReleaseDate
import com.cc3301.comicviewer.core.meta.toEpochMillis
import com.cc3301.comicviewer.core.order.WindowsNameOrder
import com.cc3301.comicviewer.core.source.fs.FsBackend
import com.cc3301.comicviewer.core.source.fs.FsNode
import com.cc3301.comicviewer.core.source.zip.ZipArchive
import com.cc3301.comicviewer.core.source.zip.ZipEntry
import java.io.File
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

/** 图片扩展名（spec：jpg/jpeg/png/webp/gif；gif 读静态首帧） */
val IMAGE_EXTENSIONS: Set<String> = setOf("jpg", "jpeg", "png", "webp", "gif")

/** 压缩包扩展名（spec 故事 54：CBZ/ZIP） */
val ARCHIVE_EXTENSIONS: Set<String> = setOf("cbz", "zip")

/**
 * 子目录属性探测的并发上限（票 #30）：每条探测 = 一次 list/PROPFIND 往返（SMB/WebDAV）
 * 或一次 provider IPC（SAF），无上限会把 NAS/网盘打爆，故设固定上限。
 */
const val SUBDIR_PROBE_LIMIT: Int = 8

/** 会话级列表缓存里「来源根容器」的键（`listEntries(null)` 的 containerId）：来源内的条目 id 恒非空 */
private const val ROOT_CONTAINER_ID: String = ""

/**
 * 会话级列表缓存的对象量级上界：一条 = 一个「容器 × 排序方式」，与一次会话浏览到的目录数同阶（百级）。
 * 来源实例自票 #30 起跨页面存活（见 `ServiceLocator.browsingSourceFor`），故给个简单上界防止长会话无上限增长；
 * 超出即整体清空，代价只是下次进入重列一次。
 */
private const val LIST_CACHE_MAX_ENTRIES: Int = 256

/**
 * 封面字节会话缓存的上界（票 #51 F2）：条目数上界与总字节上界任一越线就整体清空。
 * 封面是一整张原始图片字节，条目数上界单独用会吃掉过多内存，因此另有 [COVER_CACHE_MAX_BYTES]。
 */
private const val COVER_CACHE_MAX_ENTRIES: Int = 64
private const val COVER_CACHE_MAX_BYTES: Long = 8L * 1024 * 1024

fun FsNode.isImageFile(): Boolean = !isDirectory && name.substringAfterLast('.', "").lowercase(Locale.ROOT) in IMAGE_EXTENSIONS

/** 压缩包文件（CBZ/ZIP）：整包作为一本书，页序 = 包内图片文件名自然序 */
fun FsNode.isArchiveFile(): Boolean = !isDirectory && name.substringAfterLast('.', "").lowercase(Locale.ROOT) in ARCHIVE_EXTENSIONS

/** 包内条目是否算一页（目录条目不算） */
fun isArchiveImageEntry(entryName: String): Boolean {
    if (entryName.endsWith("/")) return false
    val ext = entryName.substringAfterLast('.', "").lowercase(Locale.ROOT)
    return ext in IMAGE_EXTENSIONS
}

fun mimeTypeOf(fileName: String): String = when (fileName.substringAfterLast('.', "").lowercase(Locale.ROOT)) {
    "jpg", "jpeg" -> "image/jpeg"
    "png" -> "image/png"
    "webp" -> "image/webp"
    "gif" -> "image/gif"
    else -> "application/octet-stream"
}

/**
 * 文档树来源：本地 File 与 SAF 文档树的统一实现（票 04）。
 *
 * 目录判定规则（spec）：
 * - 目录直接含图片 → 该目录是书（出现在父列表 isBook=true，从第 1 页按名称自然序打开）
 * - 目录只含子目录 → 容器（isBook=false，继续浏览）
 * - 目录图片+子目录混排 → 混合列表：图片条目 isBook=true（bookId=该图，从该图连读到列表末图），
 *   含图子文件夹 isBook=true（按顺序整本读）
 *
 * 枚举性能（票 #30）：列目录只在**本层**一次 `children()` 之上并发探测各子目录是书还是容器
 * （[SUBDIR_PROBE_LIMIT] 上限），枚举期不做封面相关的额外往返（不逐级下取容器封面位置、
 * 不解压压缩包取首页），封面字节一律走按需通路 [coverBytes]；同一目录在同一会话内二次进入命中 [listCache]。
 *
 * 「会话」= 本实例被复用的那段生命周期：界面侧由 `ServiceLocator.browsingSourceFor` 按连接复用实例
 * （票 #30 P1），因此「进子目录 → 返回上级」与柜页/浏览页互切都命中同一份缓存，而不是每次进页面新建实例。
 */
class DocumentTreeSource(
    private val backend: FsBackend,
    private val progressStore: ProgressStore,
    /** Windows 自然排序（票 #3）；测试可注入自定义比较器 */
    private val nameComparator: Comparator<String> = WindowsNameOrder.COMPARATOR,
    /** 封面落盘缓存目录（票 10「封面生成后缓存」）；null 时不落盘、每次按需解出 */
    private val coverCacheDir: File? = null,
    /** 来源类型（票 11：本地与 SMB 复用同一实现，仅类型与后端不同） */
    private val sourceType: SourceType = SourceType.LOCAL,
    /**
     * 阻塞读的看门狗时长（票 #91，毫秒）：打开书 / 取页必须在有限时间内给出结果或报错。
     * 默认 [DEFAULT_READ_DEADLINE_MS]；测试注入小值来跑失败路径。语义与代价见 [readWithinDeadline]。
     */
    private val readDeadlineMs: Long = DEFAULT_READ_DEADLINE_MS,
) : Source {

    private val rootNode: FsNode = backend.root
    private val resolve: (String) -> FsNode? = backend::resolve

    /** 发布时间排序键缓存（键含 mtime：文件更新后自动失效） */
    private val releaseCache = ConcurrentHashMap<String, Long>()

    /**
     * 包内条目缓存（键含 mtime：文件更新后自动失效）：
     * 一次列表里同一个 CBZ 的消费点有两个（压缩包封面、发布时间排序键——后者仅在发布时间排序下发生），
     * 每个消费点都要读中央目录（封面字节解出与 ComicInfo.xml 读取还会各开一次包）；
     * SMB 上这是实实在在的网络往返（票 11 review P1）。页数不再参与（票 #36）。
     */
    private val archiveEntryCache = ConcurrentHashMap<String, List<ZipEntry>>()

    /**
     * 会话级列表快照里的一条：对外条目 + 拿到它的那个节点（票 #51）。
     *
     * 保留节点是本票的关键：节点带着列目录时顺手拿到的元数据（mtime），因此
     * - 时间类排序不必再按 id 取节点（[resolve] 在网络来源上就是一次往返）；
     * - 相邻书判定与探测重试可以直接用这些节点，不必重新列目录；
     * [probed] = 该子目录探测成功（false 的条目下次进入只重试它自己）。
     */
    private class ListingEntry(val entry: BrowseEntry, val node: FsNode, val probed: Boolean = true)

    /**
     * 会话级列表快照：一个容器一份（**与排序方式无关**——排序在快照之上进行，不再为换排序重列目录）。
     *
     * [mtimeMs] 为 null = 该容器拿不到 mtime（SMB 共享根是常态）：这时快照是**会话内缓存**，
     * 只由手动刷新 [invalidateListCache] 失效，二次进入连一次取节点都不发（票 #51 F3）。
     */
    private class ListingSnapshot(val mtimeMs: Long?, val entries: List<ListingEntry>)

    /**
     * 会话级列表快照表（票 #30 起，票 #51 改为按容器一份）：同一目录二次进入（含从子目录返回上级）
     * 不再重发同一批 list/PROPFIND，也不再为换排序方式重列。
     * 失效有三条路：容器 mtime 变化、显式刷新 [invalidateListCache]、[close]（随实例释放）。
     * 不落盘（票面 Out of scope：跨重启缓存另议）；规模假设见 [LIST_CACHE_MAX_ENTRIES]。
     */
    private val listings = ConcurrentHashMap<String, ListingSnapshot>()

    /**
     * 封面字节的会话级缓存（票 #51 F2）：键含条目 id 与 mtime（文件换过就换键）。
     * 之前只缓存**解码后的位图**，位图命中也要先向来源要字节——「返回上级再进来」会重下封面；
     * 现在字节层面直接命中，二次进入读字节 0 次。有上界（见 [COVER_CACHE_MAX_ENTRIES]/[COVER_CACHE_MAX_BYTES]），
     * [invalidateListCache]（手动刷新）与 [close] 都清空。
     */
    private val coverBytesCache = ConcurrentHashMap<String, ByteArray>()

    /** 封面字节缓存当前占用的字节数（配合 [coverBytesCache] 的上界） */
    private val coverBytesCachedTotal = java.util.concurrent.atomic.AtomicLong(0L)

    /**
     * 探测期已知的「目录内首图」（票 #51 F2）：键 = 子目录 id + mtime。
     *
     * 探测「这个子目录是不是书」时本来就把它的子节点列了出来，首图就在手里；不记下来的话，
     * 可见行取封面时还要再列一次同一目录（SMB 上就是一次多余往返）。
     */
    private val probedFirstImages = ConcurrentHashMap<String, FsNode>()

    override val type: SourceType get() = sourceType

    /**
     * 释放本实例：清列表缓存并释放后端会话（SMB）；本地后端无资源，忽略。
     * 清缓存与释放会话同步发生——实例被丢掉后缓存不再有消费者（票 #30 P2）。
     */
    override fun close() {
        clearSessionCaches()
        (backend as? AutoCloseable)?.let { runCatching { it.close() } }
    }

    /** 清空本实例的全部会话级缓存（[close] 与手动刷新共用；换连接/编辑连接即随实例释放） */
    private fun clearSessionCaches() {
        listings.clear()
        coverBytesCache.clear()
        coverBytesCachedTotal.set(0L)
        probedFirstImages.clear()
        releaseCache.clear()
        archiveEntryCache.clear()
    }

    override suspend fun listEntries(containerId: String?, sort: SortMode): List<BrowseEntry> {
        // 真机打点（票 #51 验收协议）：默认关闭，`adb shell setprop log.tag.ComicViewerPerf DEBUG` 后可见
        val startedNanos = System.nanoTime()
        val cachedBefore = listings[snapshotKeyOf(containerId)]
        val snapshot = snapshotOf(containerId)
        // 排序在快照之上进行（票 #51 F1）：键一次性取齐，比较器里不做任何 I/O——旧实现在选择器里
        // 按 id 取节点，而 Kotlin 的 compareBy* 每次比较都调用选择器，百级目录就是上千次网络往返
        val sorted = sortEntries(snapshot.entries, sort).map { it.entry }
        PerfTiming.log {
            val ms = (System.nanoTime() - startedNanos) / 1_000_000
            "listEntries container=" + (containerId ?: "<root>") + " sort=" + sort +
                " entries=" + sorted.size + " snapshot=" + (cachedBefore != null) + " ms=" + ms
        }
        return sorted
    }

    /**
     * 取某个容器的会话级快照（票 #30 起，票 #51 改为复用节点 + 支持部分失败）：
     * - 命中且未过期 → 直接返回；其中**探测失败**的那几条只重试它们自己（票 #51 F4）；
     * - 未命中/已过期 → 列一次本层 + 并发探测各子目录（[SUBDIR_PROBE_LIMIT]），落快照。
     *
     * 「是否过期」只看容器 mtime：拿不到 mtime 的层（SMB 共享根）走会话内缓存，只由手动刷新失效
     * （票 #51 F3）；拿得到 mtime 的层每次进入花一次取节点比对（与条目数无关，不再是 O(n log n)）。
     * 根容器的节点是构造期快照，因此它的 mtime 在**已有缓存需要比对**时才现取：首次枚举零取节点，
     * 而「往共享根/授权根新增书后能看到」的语义不丢（票 #30 的既有约束）。
     */
    private suspend fun snapshotOf(containerId: String?): ListingSnapshot {
        val key = snapshotKeyOf(containerId)
        val cached = listings[key]
        var freshMtime: Long? = null
        var mtimeKnown = false
        if (cached != null) {
            if (cached.mtimeMs == null) return refreshFailedProbes(key, cached)
            freshMtime = currentMtimeOf(containerId)
            mtimeKnown = true
            if (freshMtime == null || freshMtime == cached.mtimeMs) return refreshFailedProbes(key, cached)
        }
        val dir = resolveNode(containerId)
        // 快照的 mtime：已经现取到就用现取值（根容器是构造期快照，拿它当键会让「下一次比对」永远不等、
        // 每次进入都白重列一次）；首次枚举没有现取值时才用节点自带的值——下次进入自然对齐。
        val snapshot = ListingSnapshot(
            mtimeMs = if (mtimeKnown) freshMtime else dir.lastModifiedMs,
            entries = listingOf(dir),
        )
        cacheSnapshot(key, snapshot)
        return snapshot
    }

    /**
     * 快照键：根容器无论用 `null`（浏览页路由）还是它的真实节点 id（相邻书判定从 `parent()` 拿到的）
     * 都是同一份快照——否则「根列表」会被枚举两次、缓存形同虚设。
     */
    private fun snapshotKeyOf(containerId: String?): String =
        if (containerId == null || containerId == rootNode.id) ROOT_CONTAINER_ID else containerId

    /** 容器当前的修改时间：根容器用 [rootNode] 的 id 现取（构造期快照不可信，票 #30） */
    private fun currentMtimeOf(containerId: String?): Long? =
        resolve(containerId ?: rootNode.id)?.lastModifiedMs

    /**
     * 只重试快照里探测失败的子目录（票 #51 F4）：其余条目照常命中缓存。
     * 一条探测失败不再让整层缓存作废——旧行为下 100 个子目录里挂 1 个，每次重返都要全量重探。
     */
    private suspend fun refreshFailedProbes(key: String, cached: ListingSnapshot): ListingSnapshot {
        val failed = cached.entries.filter { !it.probed }
        if (failed.isEmpty()) return cached
        val retried = probeSubdirs(failed.map { it.node }).associateBy { it.node.id }
        val merged = cached.entries.map { entry ->
            retried[entry.node.id]?.let { probe -> ListingEntry(probe.entry, probe.node, probe.probed) } ?: entry
        }
        val updated = ListingSnapshot(cached.mtimeMs, merged)
        cacheSnapshot(key, updated)
        return updated
    }

    /** 快照表写入；达到上界先整体清空（见 [LIST_CACHE_MAX_ENTRIES]） */
    private fun cacheSnapshot(key: String, snapshot: ListingSnapshot) {
        if (listings.size >= LIST_CACHE_MAX_ENTRIES) listings.clear()
        listings[key] = snapshot
    }

    /**
     * 会话级列表缓存的显式失效/刷新入口（票 #30；票 #51 起也清封面字节缓存）：
     * 传容器 id 清该容器，null 清来源根容器。手动刷新（下拉更新）走这里。
     */
    override fun invalidateListCache(containerId: String?) {
        listings.remove(snapshotKeyOf(containerId))
        // 刷新要真刷封面：字节缓存一并清掉（否则还会把同一条目的旧封面还回去）
        coverBytesCache.clear()
        coverBytesCachedTotal.set(0L)
    }

    /**
     * 枚举一个目录（票 #30；票 #51 起结果带节点）。枚举期不统计页数（票 #36）：不为页数读压缩包中央目录、
     * 不为页数列子目录，列表条目的 pageCount 一律为 null（页数只在打开书后由 BookHandle.pageCount 给出）。
     * 本层只一次 `children()`（SAF = 一次 provider IPC、SMB = 一次 list），分区后各自排序。
     *
     * 每条都带上列目录时拿到的那个节点（票 #51）：排序、邻位判定与探测重试都复用它，
     * 不再按 id 取节点——在网络来源上每次 `resolve` 就是一次往返。
     */
    private suspend fun listingOf(dir: FsNode): List<ListingEntry> {
        val kids = dir.children()
        val imageFiles = kids.filter { it.isImageFile() }.sortedWith(compareBy(nameComparator) { it.name })
        val subDirs = kids.filter { it.isDirectory }.sortedWith(compareBy(nameComparator) { it.name })
        val archives = kids.filter { it.isArchiveFile() }.sortedWith(compareBy(nameComparator) { it.name })

        val entries = mutableListOf<ListingEntry>()

        // 子目录条目：直接含图片或压缩包 → 书；否则容器。属性探测并发受控（见 probeSubdirs）
        probeSubdirs(subDirs).forEach { entries += ListingEntry(it.entry, it.node, it.probed) }

        // 压缩包（CBZ/ZIP）：整包作为一本书（spec 故事 54），封面按需取（枚举期不解包取首页）
        for (arc in archives) {
            entries += ListingEntry(
                entry = BrowseEntry(
                    id = arc.id,
                    name = arc.name,
                    isBook = true,
                    coverUri = null,
                    pageCount = null,
                ),
                node = arc,
            )
        }

        // 本目录直接含图片时：混合列表中的图片条目，从该图连读到列表末图
        for (img in imageFiles) {
            entries += ListingEntry(
                entry = BrowseEntry(
                    id = img.id,
                    name = img.name,
                    isBook = true,
                    coverUri = img.imageUri,
                    pageCount = null,
                ),
                node = img,
            )
        }

        return entries
    }

    /** 子目录属性探测结果：条目 + 探测用的节点 + 是否探测成功（失败的下次只重试这一条） */
    private class SubdirProbe(val entry: BrowseEntry, val node: FsNode, val probed: Boolean)

    /**
     * 子目录属性探测并发执行（票 #30）：并发上限 [SUBDIR_PROBE_LIMIT]，结果按子目录名称序返回
     * （拼接顺序与旧实现一致，条目集合/排序/isBook 语义不受影响）。
     *
     * `children()` 是阻塞调用（SMB list / SAF provider IPC），固定在 [Dispatchers.IO] 上跑才是真并发；
     * 所有探测都挂在本次调用的 coroutineScope 上，调用方（离开页面即取消的界面协程）被取消时，
     * 尚未开始的探测立即放弃、正在等并发额度的也随之退出，不会继续发请求。
     */
    private suspend fun probeSubdirs(subDirs: List<FsNode>): List<SubdirProbe> = coroutineScope {
        val gate = Semaphore(SUBDIR_PROBE_LIMIT)
        subDirs.map { sub ->
            async(Dispatchers.IO) { gate.withPermit { probeSubdir(sub) } }
        }.awaitAll()
    }

    /**
     * 单个子目录：直接含图片或压缩包 → 书（封面 = 目录内首图的 uri，零额外往返：图已经列出来了）；
     * 否则容器（封面按需，见 [coverBytes]）。
     * 探测失败（目录不可读/损坏等）降级为容器并标记未探测成功：一行探测不到不能拖垮整表，
     * 条目仍可点（进得去会重新枚举、刷新入口可重试）；
     * 传输层故障（断链/认证失效）必须冒泡，不能伪装成「这个文件夹是容器」。
     */
    private fun probeSubdir(sub: FsNode): SubdirProbe = try {
        val subKids = sub.children()
        val images = subKids.filter { it.isImageFile() }.sortedWith(compareBy(nameComparator) { it.name })
        // 探测时本来就把首图列出来了（票 #51 F2）：记下来，可见行取封面时不必再列一次这个目录
        images.firstOrNull()?.let { first -> rememberProbedFirstImage(sub, first) }
        SubdirProbe(
            entry = BrowseEntry(
                id = sub.id,
                name = sub.name,
                isBook = images.isNotEmpty() || subKids.any { it.isArchiveFile() },
                coverUri = images.firstOrNull()?.imageUri,
                pageCount = null,
            ),
            node = sub,
            probed = true,
        )
    } catch (t: CancellationException) {
        throw t
    } catch (t: Throwable) {
        // 网络/传输故障必须冒泡（core/source/TransportFailure.kt 的类型契约，与 [archiveEntries] 同式）：
        // 断链时整批子目录都列不出来，静默降级会得到一份「全是容器、封面全空、不报错」的假列表
        if (t is TransportFailure) throw t
        SubdirProbe(
            entry = BrowseEntry(id = sub.id, name = sub.name, isBook = false, coverUri = null, pageCount = null),
            node = sub,
            probed = false,
        )
    }

    /**
     * 封面字节（票 11/票 #30）：无系统可解码 uri 的来源（SMB/WebDAV）由 UI 回退到这里；
     * 本地/SAF 也走这里——列表只为「目录内首图」「图片本身」这类零开销的封面给 uri，
     * 容器封面与压缩包封面一律按需取（只为可见行发生，枚举期不再发生）。
     * 规则（spec 故事 9）：压缩包取包内首页，图片取本身，目录取第一页（含图 → 首图；只含压缩包 →
     * 首个包的首帧；纯容器 → 逐级下取第一张图）；取不到返回 null（界面显示占位底色）。
     */
    override suspend fun coverBytes(entryId: String): ByteArray? = runCatching {
        val startedNanos = System.nanoTime()
        val node = resolve(entryId) ?: return null
        // 字节级会话缓存（票 #51 F2）：键含 mtime，文件换过就是另一个键
        val cacheKey = coverBytesKey(node)
        coverBytesCache[cacheKey]?.let {
            PerfTiming.log { "coverBytes id=$entryId bytes=${it.size} cached=true ms=0" }
            return it
        }
        val bytes = when {
            node.isArchiveFile() -> archiveCoverBytes(node)
            node.isImageFile() -> node.readBytes()
            // 库「一个子文件夹一本书」是常见布局：探测期已经列过这个目录，首图就在手里（票 #51 F2）
            node.isDirectory -> probedFirstImageBytes(node) ?: directoryCoverBytes(node)
            else -> null
        } ?: return null
        putCoverBytes(cacheKey, bytes)
        PerfTiming.log {
            val ms = (System.nanoTime() - startedNanos) / 1_000_000
            "coverBytes id=$entryId bytes=${bytes.size} cached=false ms=$ms"
        }
        return bytes
    }.getOrNull()

    /** 封面字节缓存的键：条目 id + mtime（mtime 不可得时用 0，随手动刷新失效） */
    private fun coverBytesKey(node: FsNode): String = node.id + "@" + (node.lastModifiedMs ?: 0L)

    /** 写入封面字节缓存；条目数或总字节超上界即整体清空（与列表快照同一套上界思路） */
    private fun putCoverBytes(key: String, bytes: ByteArray) {
        if (coverBytesCache.size >= COVER_CACHE_MAX_ENTRIES ||
            coverBytesCachedTotal.get() > COVER_CACHE_MAX_BYTES
        ) {
            coverBytesCache.clear()
            coverBytesCachedTotal.set(0L)
        }
        if (coverBytesCache.put(key, bytes) == null) coverBytesCachedTotal.addAndGet(bytes.size.toLong())
    }

    /** 记下探测期看到的「目录内首图」（键含目录 mtime，目录一变就换键，不会拿旧首图当封面） */
    private fun rememberProbedFirstImage(dir: FsNode, firstImage: FsNode) {
        if (probedFirstImages.size >= COVER_CACHE_MAX_ENTRIES) probedFirstImages.clear()
        probedFirstImages[coverBytesKey(dir)] = firstImage
    }

    /** 探测期已知的目录内首图字节：命中即 0 次列目录（票 #51 F2） */
    private fun probedFirstImageBytes(dir: FsNode): ByteArray? =
        probedFirstImages[coverBytesKey(dir)]?.let { runCatching { it.readBytes() }.getOrNull() }

    override suspend fun openBook(bookId: String): BookHandle {
        val startedNanos = System.nanoTime()
        val pages = readWithinDeadline("打开", bookId) { pagesOfBook(bookId) }
        if (pages.isEmpty()) throw IllegalArgumentException("不是一本书：$bookId")
        PerfTiming.log { "openBook id=$bookId pages=${pages.size} ms=${(System.nanoTime() - startedNanos) / 1_000_000}" }
        return object : BookHandle {
            override val id: String = bookId
            override val pageCount: Int = pages.size
            override suspend fun loadPage(index: Int): PageData {
                val page = pages.getOrNull(index)
                    ?: throw IndexOutOfBoundsException("页码越界：$index / ${pages.size}")
                val startedNanos = System.nanoTime()
                val bytes = readWithinDeadline("取第 " + (index + 1) + " 页", bookId) { page.bytes() }
                PerfTiming.log {
                    "loadPage id=$bookId page=$index bytes=${bytes.size} ms=${(System.nanoTime() - startedNanos) / 1_000_000}"
                }
                return PageData(bytes, mimeTypeOf(page.name))
            }
        }
    }

    /**
     * 阻塞读的看门狗（票 #91 AC「失败必须可失败」）：打开的压缩包 / 取的一页必须在有限时间内给出结果。
     *
     * 为什么需要它：这些读是本进程内的**阻塞** I/O（smbj 的 `File.read`、OkHttp 的同步 call）。
     * 协程取消在阻塞段里没有可观察的挂起点，因此「限时等待」是唯一能在有限时间内给用户答复的手段。
     * 超时抛 [SourceReadTimeoutException]——**不是** `CancellationException`：阅读器把取消当成「换书」静默处理
     * （`ReaderScreen` 的 `catch (c: CancellationException) { throw c }`），用取消表达超时会变成「永远转圈」。
     *
     * 两个承重细节（改这里先看这两条）：
     * - 工作挂在 [deadlineScope] 上而不是本协程的子作用域：`withContext`/`async` 的子协程必须全部结束
     *   父作用域才能返回，卡住的读会把等待者一起拖住，看门狗就形同虚设。
     * - 计时发生在 `withContext(Dispatchers.IO)` 之后：`withTimeout` 取上下文的 Delay 元素，IO 调度器没有 Delay
     *   → 走真实时钟（若继承测试里的虚拟时钟，任何一次真实读都会被当成超时）。
     *
     * 代价（记录）：超时只解除**等待**，那根仍在阻塞的线程要等底层传输自身超时才回收
     * （SMB 读 15s / WebDAV 调用 90s），期间它继续占着一根线程。不为此给 `RandomAccessBytes`
     * 加取消协议：闭包（socket/句柄）在调用方线程手上，新协议的破坏面比收益大。
     */
    private suspend fun <T> readWithinDeadline(what: String, bookId: String, block: () -> T): T =
        withContext(Dispatchers.IO) {
            val work = deadlineScope.async { block() }
            try {
                withTimeout(readDeadlineMs) { work.await() }
            } catch (t: TimeoutCancellationException) {
                work.cancel()
                // 生产恒为整秒（60 秒）；测试注入毫秒级值，别显示成「0 秒」
                val limit = if (readDeadlineMs % 1000L == 0L) {
                    "${readDeadlineMs / 1000} 秒"
                } else {
                    "$readDeadlineMs 毫秒"
                }
                throw SourceReadTimeoutException(
                    what + "超时（" + limit + "）：" + bookId + "；请检查网络/服务器后重试",
                )
            }
        }

    override suspend fun readProgress(bookId: String): ReadingProgress? =
        progressStore.read(bookId)

    override suspend fun writeProgress(bookId: String, pageIndex: Int, totalPages: Int) =
        progressStore.write(bookId, pageIndex, totalPages)

    /**
     * 相邻书（票 07 口径；票 #93 起**只读会话快照**）：同一容器内 isBook 条目按名称自然序的前后邻位。
     *
     * 票 #93：本方法不得触发父层的列目录与子目录探测——SMB/WebDAV 上「这一层每个子目录是不是书」
     * 就是每个子目录多次往返，打开一本书顺手付掉这一层的观感就是维护者说的
     * 「一次打开所有子文件夹的所有书」。因此快照缺失（本层本次会话没被列过，例如启动页直接进阅读器；
     * 或快照已被 [LIST_CACHE_MAX_ENTRIES] 腾掉）时**降级为本次不给邻居**（`Neighbors(null, null)`，
     * 界面照 SPEC 故事 28 的既有口径提示「无上一本/无下一本」），而不是去列/探这一层；
     * 浏览页点开书这条主路径层都已被列过（[listEntries] 落的快照），因此邻位照常。
     */
    override suspend fun neighbors(bookId: String): Neighbors {
        val startedNanos = System.nanoTime()
        val node = resolveNode(bookId)
        // 目录书 / 图片条目 / 压缩包书三种形态都从父目录取同一个"同目录 isBook 序列"语义：
        // 浏览列表认这三种条目是书（[listingOf]），换书必须用同一套口径，否则压缩包在列表里有邻位、菜单里却是 null
        val listParent = when {
            node.isDirectory -> node.parent() ?: return Neighbors(null, null)
            node.isImageFile() -> node.parent() ?: return Neighbors(null, null)
            node.isArchiveFile() -> node.parent() ?: return Neighbors(null, null)
            else -> return Neighbors(null, null)
        }
        val books = cachedBookEntriesOf(listParent)
        // 真机验收打点（票 #91 协议，默认关闭）：`snapshot=false` 即降级路径，两者都应当是 0 次列目录/探测
        PerfTiming.log {
            "neighbors id=" + bookId + " snapshot=" + (books != null) + " books=" + (books?.size ?: 0) +
                " ms=" + ((System.nanoTime() - startedNanos) / 1_000_000)
        }
        if (books == null) return Neighbors(null, null)
        val idx = books.indexOfFirst { it.id == bookId }
        if (idx < 0) return Neighbors(null, null)
        return Neighbors(
            prev = books.getOrNull(idx - 1)?.id,
            next = books.getOrNull(idx + 1)?.id,
        )
    }

    /**
     * 相邻书判定用的书列表（票 #51 F5 起**读会话快照**，票 #93 起**只读**快照）：isBook 集合与 [listingOf] 一致
     * （子目录直接含图=书；本目录图片条目/压缩包=书），按全局名称序排序
     * （review P1：分区拼接顺序会与浏览列表名称序不一致）。
     *
     * 这就是「主路径复用」（票 #93 AC2）：浏览页枚举当前层时落的快照即这份数据（[listEntries] 的枚举结果），
     * 因此点开书时邻位来自已经算过的列表，**不重列、不重探**。
     *
     * 三条承重细节（改这里先看这三条）：
     * - 不走 [snapshotOf]：那个函数在快照未命中时会真去列这一层（含逐子目录探测），正是本票要拆的东西。
     * - 不在这里比对 mtime：比对要按 id 取一次节点（网络来源上就是一次往返），而邻位只是「上一本/下一本」
     *   的提示——用本层本次会话已列出的那份即可；本层被改动时浏览页下一次进入会重新枚举并替换快照。
     * - 快照里的探测失败条目（[probeSubdir] 降级为容器）不算书、也不在这里重试：与浏览页显示的是同一份事实。
     *
     * 快照缺失返回 null（调用方 [neighbors] 因此给出空邻位），**绝不**在这里回退到列目录。
     */
    private fun cachedBookEntriesOf(dir: FsNode): List<BrowseEntry>? {
        val snapshot = listings[snapshotKeyOf(dir.id)] ?: return null
        return sortEntries(snapshot.entries.filter { it.entry.isBook }, SortMode.NAME).map { it.entry }
    }

    // ---------- 内部 ----------

    /** 越界/无效 id 的唯一防护点（后端 resolve 返回 null 即拒绝） */
    private fun resolveNode(id: String?): FsNode =
        if (id == null) rootNode else resolve(id) ?: throw IllegalArgumentException("无效或越界引用：$id")

    /**
     * 书的页面序列：
     * - 目录书 = 直接图片 + 压缩包展开页，按条目名称自然序合并
     * - 混合列表图片条目 = 从该图到末图（自然序）
     * - 压缩包 = 包内图片条目自然序
     */
    private fun pagesOfBook(bookId: String): List<PageRef> {
        val node = resolveNode(bookId)
        return when {
            node.isDirectory -> {
                val kids = node.children()
                val named = mutableListOf<Pair<String, PageRef>>()
                kids.filter { it.isImageFile() }.forEach { named += it.name to FilePageRef(it) }
                kids.filter { it.isArchiveFile() }.forEach { arc ->
                    archiveEntries(arc).forEach { named += it.name to ZipPageRef(arc, it) }
                }
                named.sortedWith(compareBy(nameComparator) { it.first }).map { it.second }
            }
            node.isImageFile() -> {
                val siblings = node.parent()?.let(::listImagesSorted) ?: emptyList()
                siblings.dropWhile { it.id != node.id }.map { FilePageRef(it) }
            }
            node.isArchiveFile() -> archiveEntries(node).map { ZipPageRef(node, it) }
            else -> emptyList()
        }
    }

    private fun listFilesSorted(dir: FsNode, keep: (FsNode) -> Boolean): List<FsNode> =
        dir.children().filter(keep).sortedWith(compareBy(nameComparator) { it.name })

    private fun listImagesSorted(dir: FsNode): List<FsNode> = listFilesSorted(dir) { it.isImageFile() }

    // ---------- 按需封面（票 #30：封面字节只在可见行取，枚举期不发生）----------

    /**
     * 目录封面字节（spec 故事 9）：含图 → 目录内首图；只含压缩包（目录书）→ 首个压缩包的首帧；
     * 纯容器 → 逐级下取第一张图。每层目录只列一次。
     *
     * 探测期已知首图的情形（书文件夹）在 [coverBytes] 里就返回了，不会走到这里（票 #51 F2）。
     */
    private fun directoryCoverBytes(dir: FsNode): ByteArray? {
        val kids = dir.children()
        firstImageBytes(kids)?.let { return it }
        kids.filter { it.isArchiveFile() }
            .sortedWith(compareBy(nameComparator) { it.name })
            .firstOrNull()?.let { arc -> archiveCoverBytes(arc)?.let { return it } }
        return firstImageDeepBytes(kids)
    }

    /** 逐级下取第一张图（容器封面规则）：按子目录名称序深度优先，每层目录只列一次 */
    private fun findFirstImageDeepBytes(dir: FsNode): ByteArray? = firstImageDeepBytes(dir.children())

    private fun firstImageDeepBytes(kids: List<FsNode>): ByteArray? {
        firstImageBytes(kids)?.let { return it }
        for (sub in kids.filter { it.isDirectory }.sortedWith(compareBy(nameComparator) { it.name })) {
            findFirstImageDeepBytes(sub)?.let { return it }
        }
        return null
    }

    private fun firstImageBytes(kids: List<FsNode>): ByteArray? =
        kids.filter { it.isImageFile() }
            .sortedWith(compareBy(nameComparator) { it.name })
            .firstOrNull()?.readBytes()

    /**
     * 排序（票 #51 F1）：**键一次性取齐**，比较器里不做任何 I/O。
     *
     * Kotlin 的 `compareBy*` 每次比较都会调用选择器，所以旧实现的 `compareByDescending { resolve(it.id)… }`
     * 在百级目录上一次排序就是上千次「按 id 取节点」（SMB 上每次都是 folderExists + 文件信息两次往返）。
     * 现在修改时间直接用列目录时顺手拿到的节点 mtime；发布时间键每个条目只算一次（[releaseKey] 自己按 mtime 缓存）。
     * 拿不到元数据的条目按既有回退口径处理：时间类排序末位（0）。
     */
    private fun sortEntries(entries: List<ListingEntry>, sort: SortMode): List<ListingEntry> = when (sort) {
        SortMode.NAME -> entries.sortedWith(compareBy(nameComparator) { it.entry.name })
        SortMode.MODIFIED_TIME -> sortByDescendingKey(entries) { it.node.lastModifiedMs ?: 0L }
        // 发布时间（spec 故事 12/13）：CBZ 读 ComicInfo.xml，无元数据（图片文件夹等）回退 mtime
        SortMode.RELEASE_TIME -> sortByDescendingKey(entries) { releaseKey(it.node) }
    }

    /** 一次性构建排序键再排序：比较阶段没有任何 I/O（见 [sortEntries]） */
    private inline fun <T> sortByDescendingKey(items: List<T>, keyOf: (T) -> Long): List<T> {
        val keys = items.associateWith(keyOf)
        return items.sortedWith(compareByDescending { keys[it] ?: 0L })
    }

    /** 发布时间排序键：优先 ComicInfo.xml 的日期（转毫秒与 mtime 同量纲），缺失回退修改时间 */
    private fun releaseKey(node: FsNode): Long {
        val cacheKey = node.id + "@" + (node.lastModifiedMs ?: 0L)
        releaseCache[cacheKey]?.let { return it }
        val key = if (node.isArchiveFile()) {
            runCatching {
                withArchive(node) { zip ->
                    zip.entry(COMIC_INFO)?.let { entry ->
                        parseReleaseDate(zip.read(entry))?.toEpochMillis()
                    }
                }
            }.getOrNull() ?: node.lastModifiedMs ?: 0L
        } else {
            node.lastModifiedMs ?: 0L
        }
        releaseCache[cacheKey] = key
        return key
    }

    // ---------- 压缩包（CBZ/ZIP，票 10）----------

    private fun <T> withArchive(node: FsNode, block: (ZipArchive) -> T): T =
        node.openRandomAccess().use { bytes -> ZipArchive(bytes).use(block) }

    /** 包内图片条目：按文件名自然序（spec 故事 54：页序 = ZIP 内文件名自然排序） */
    private fun archiveEntries(node: FsNode): List<ZipEntry> {
        val cacheKey = node.id + "@" + (node.lastModifiedMs ?: 0L)
        archiveEntryCache[cacheKey]?.let { return it }
        val entries = try {
            withArchive(node) { zip -> zip.entries.filter { isArchiveImageEntry(it.name) } }
                .sortedWith(compareBy(nameComparator) { it.name })
        } catch (t: Throwable) {
            // 网络/传输故障必须冒泡：不能伪装成「这本不是压缩包」（否则断链时静默 0 页）
            if (t is TransportFailure) throw t
            // 损坏的 ZIP：当作非压缩包，不影响同目录其他书
            emptyList()
        }
        archiveEntryCache[cacheKey] = entries
        return entries
    }

    /** 压缩包封面字节（按需，票 #30 从枚举期移到可见行）：包内首页的原始字节，由界面解码 */
    private fun archiveFirstPageBytes(node: FsNode): ByteArray? {
        val entry = archiveEntries(node).firstOrNull() ?: return null
        return withArchive(node) { zip -> zip.read(entry) }
    }

    /**
     * 压缩包封面字节 + 落盘缓存（票 10 的「封面生成后缓存」：票 #30 只是把它从枚举期搬到按需通路）：
     * 第一次取时解出并写缓存文件，后续滚动/回看不重开包；无缓存目录（或读写失败）时每次解出。
     */
    private fun archiveCoverBytes(node: FsNode): ByteArray? {
        val cached = coverCacheFile(node)
        if (cached != null && cached.exists() && cached.length() > 0L) {
            runCatching { cached.readBytes() }.getOrNull()?.let { return it }
        }
        val bytes = archiveFirstPageBytes(node) ?: return null
        if (cached != null) writeCoverCacheFile(cached, bytes)
        return bytes
    }

    /**
     * 落盘走「临时文件 + 原子改名」（票 #30 P2）：同一个 entry 的封面可被界面并发触发
     * （可见行重进组合、刷新时 LaunchedEffect 重启），直接 writeBytes 会先截断再写，
     * 并发的读者可能读到半截文件而解码失败。写失败只是白解一次，下次仍会重试。
     * 改名成功后再清同一本书的旧封面文件（票 #30 P2）：文件名带 mtime，不清理的话每改一次多留一份。
     */
    private fun writeCoverCacheFile(target: File, bytes: ByteArray) {
        runCatching {
            val dir = target.parentFile ?: return
            val tmp = File.createTempFile(target.name, ".tmp", dir)
            try {
                tmp.writeBytes(bytes)
                if (!tmp.renameTo(target)) {
                    tmp.delete()
                    return
                }
            } catch (t: Throwable) {
                tmp.delete()
                throw t
            }
            pruneCoverCacheSiblings(target)
        }
    }

    /**
     * 删掉同一本书的旧封面文件（同 id 前缀，只认当前命名格式；上一版留下的旧名交给系统清理缓存目录）。
     * 在新文件就位后才删，因此不会留下半截文件；并发场景下最坏是删掉刚写入的「更新一轮」文件，
     * 下次取封面重新解一次（缓存目录，可自愈）。
     */
    private fun pruneCoverCacheSiblings(target: File) {
        val dir = target.parentFile ?: return
        val prefix = target.name.substringBeforeLast('_') + "_"
        dir.listFiles { f ->
            f.isFile && f.name != target.name && f.name.endsWith(".img") && f.name.startsWith(prefix)
        }?.forEach { it.delete() }
    }

    /**
     * 封面缓存文件（沿用票 10 的命名，键并入 node.id 与 mtime）：内容变了（mtime 变）就是另一个文件名，
     * 手动刷新后不会再命中旧封面（票 #30 P2）；无缓存目录时为 null。
     */
    private fun coverCacheFile(node: FsNode): File? = coverCacheDir?.let { dir ->
        val key = node.id.hashCode().toUInt().toString(16) + "_" + (node.lastModifiedMs ?: 0L)
        File(dir, "cbz_cover_$key.img")
    }

    /** 页面引用：普通图片页与压缩包内页的统一抽象（票 10） */
    private sealed interface PageRef {
        val name: String
        fun bytes(): ByteArray
    }

    private class FilePageRef(private val node: FsNode) : PageRef {
        override val name: String get() = node.name
        override fun bytes(): ByteArray = node.readBytes()
    }

    private class ZipPageRef(private val node: FsNode, private val entry: ZipEntry) : PageRef {
        override val name: String get() = entry.name
        override fun bytes(): ByteArray =
            node.openRandomAccess().use { bytes -> ZipArchive(bytes).use { it.read(entry) } }
    }

    private companion object {
        const val COMIC_INFO = "ComicInfo.xml"

        /**
         * 看门狗默认时长（票 #91）：本轮修复后正常开包是个位数网络往返，60 秒远超任何可用的等待体验，
         * 只用来兜住「传输层自身不设超时 / 被锁住 / 卡在死循环」这类**不返回**的形态。
         */
        const val DEFAULT_READ_DEADLINE_MS = 60_000L

        /**
         * 看门狗用的作用域：**故意**不挂在调用者的协程树上（见 [readWithinDeadline]），应用级长存活。
         * 最坏情况留下一根卡住的线程，代价有界；挂到调用者树下则看门狗失效。
         */
        val deadlineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    }
}

/**
 * 阻塞读超时（票 #91）：见 [DocumentTreeSource.readWithinDeadline]。
 * 不是 `CancellationException`（阅读器把取消当换书静默处理），message 即可直接展示的中文提示。
 */
class SourceReadTimeoutException(message: String) : Exception(message)
