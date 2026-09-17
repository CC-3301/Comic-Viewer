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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

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

    /** 会话级列表缓存里的一条：枚举时该容器的 mtime，读取时比对（文件改动过就当未命中） */
    private class CachedListing(val mtimeMs: Long, val entries: List<BrowseEntry>)

    /** 缓存键 = 容器标识 + 排序方式（票面 AC）；containerId=null（来源根）用 [ROOT_CONTAINER_ID] */
    private data class ListCacheKey(val container: String, val sort: SortMode)

    /**
     * 会话级列表缓存（票 #30）：同一目录二次进入（含从子目录返回上级）不再重发同一批 list/PROPFIND。
     * 失效有两条路：容器 mtime 变化（与 [releaseCache]/[archiveEntryCache] 同一套思路），
     * 以及显式刷新 [invalidateListCache]；取不到 mtime 的容器（SMB 共享根）不落缓存，该层每次进入整层重列。
     * 不落盘（票面 Out of scope：跨重启缓存另议）；[close] 时清空，规模假设见 [LIST_CACHE_MAX_ENTRIES]。
     */
    private val listCache = ConcurrentHashMap<ListCacheKey, CachedListing>()

    override val type: SourceType get() = sourceType

    /**
     * 释放本实例：清列表缓存并释放后端会话（SMB）；本地后端无资源，忽略。
     * 清缓存与释放会话同步发生——实例被丢掉后缓存不再有消费者（票 #30 P2）。
     */
    override fun close() {
        listCache.clear()
        (backend as? AutoCloseable)?.let { runCatching { it.close() } }
    }

    override suspend fun listEntries(containerId: String?, sort: SortMode): List<BrowseEntry> {
        val dir = resolveNode(containerId)
        // 根容器的 mtime 必须现取：rootNode 是构造期快照（lastModifiedMs 是构造期 val），而来源实例自票 #30 起
        // 会话级长期存活——用快照等于「根列表再也不会自动失效」（往共享根/授权根新增书后看不到）。
        // 只重取元数据，列目录仍用构造期那个根节点（SAF 的 fromSingleUri 节点列目录能力受限）。
        // SMB 共享根本身 stat 不到 mtime（null）→ 不落缓存，行为与之前一致。
        val mtime = if (containerId == null) resolve(rootNode.id)?.lastModifiedMs else dir.lastModifiedMs
        val key = ListCacheKey(containerId ?: ROOT_CONTAINER_ID, sort)
        if (mtime != null) listCache[key]?.let { if (it.mtimeMs == mtime) return it.entries }

        val listing = listingOf(dir)
        val entries = sortEntries(listing.entries, sort)
        // 落缓存的两个前提：子目录全部探测成功（否则下次进入要重试）、容器 mtime 可得（否则「按 mtime 失效」
        // 恒成立，文件改动永远命中旧缓存）。SMB 共享根的 mtime 硬编码为 null，因此该层不落缓存、每次进入整层重列。
        if (listing.fullyProbed && mtime != null) cacheListing(key, mtime, entries)
        return entries
    }

    /** 写入列表缓存；达到上界先整体清空（见 [LIST_CACHE_MAX_ENTRIES]） */
    private fun cacheListing(key: ListCacheKey, mtime: Long, entries: List<BrowseEntry>) {
        if (listCache.size >= LIST_CACHE_MAX_ENTRIES) listCache.clear()
        listCache[key] = CachedListing(mtime, entries)
    }

    /**
     * 会话级列表缓存的显式失效/刷新入口（票 #30）：传容器 id 清该容器的全部排序方式，
     * null 清来源根容器。文件改动由 mtime 自动失效，用户手动刷新（列表页刷新按钮）走这里。
     */
    override fun invalidateListCache(containerId: String?) {
        val container = containerId ?: ROOT_CONTAINER_ID
        listCache.keys.filter { it.container == container }.forEach { listCache.remove(it) }
    }

    /** 一次枚举的结果：条目 + 子目录是否全部探测成功（没全成功的不留缓存） */
    private class Listing(val entries: List<BrowseEntry>, val fullyProbed: Boolean)

    /**
     * 枚举一个目录（票 #30）。枚举期不统计页数（票 #36）：不为页数读压缩包中央目录、不为页数列子目录，
     * 列表条目的 pageCount 一律为 null（页数只在打开书后由 BookHandle.pageCount 给出）。
     * 本层只一次 `children()`（SAF = 一次 provider IPC、SMB = 一次 list），分区后各自排序。
     */
    private suspend fun listingOf(dir: FsNode): Listing {
        val kids = dir.children()
        val imageFiles = kids.filter { it.isImageFile() }.sortedWith(compareBy(nameComparator) { it.name })
        val subDirs = kids.filter { it.isDirectory }.sortedWith(compareBy(nameComparator) { it.name })
        val archives = kids.filter { it.isArchiveFile() }.sortedWith(compareBy(nameComparator) { it.name })

        val entries = mutableListOf<BrowseEntry>()

        // 子目录条目：直接含图片或压缩包 → 书；否则容器。属性探测并发受控（见 probeSubdirs）
        val probes = probeSubdirs(subDirs)
        probes.forEach { entries += it.entry }

        // 压缩包（CBZ/ZIP）：整包作为一本书（spec 故事 54），封面按需取（枚举期不解包取首页）
        for (arc in archives) {
            entries += BrowseEntry(
                id = arc.id,
                name = arc.name,
                isBook = true,
                coverUri = null,
                pageCount = null,
            )
        }

        // 本目录直接含图片时：混合列表中的图片条目，从该图连读到列表末图
        for (img in imageFiles) {
            entries += BrowseEntry(
                id = img.id,
                name = img.name,
                isBook = true,
                coverUri = img.imageUri,
                pageCount = null,
            )
        }

        return Listing(entries, probes.all { it.probed })
    }

    /** 子目录属性探测结果：条目 + 是否探测成功（失败的不留缓存，下次进入重试） */
    private class SubdirProbe(val entry: BrowseEntry, val probed: Boolean)

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
        SubdirProbe(
            entry = BrowseEntry(
                id = sub.id,
                name = sub.name,
                isBook = images.isNotEmpty() || subKids.any { it.isArchiveFile() },
                coverUri = images.firstOrNull()?.imageUri,
                pageCount = null,
            ),
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
        val node = resolve(entryId) ?: return null
        when {
            node.isArchiveFile() -> archiveCoverBytes(node)
            node.isImageFile() -> node.readBytes()
            node.isDirectory -> directoryCoverBytes(node)
            else -> null
        }
    }.getOrNull()

    override suspend fun openBook(bookId: String): BookHandle {
        val pages = pagesOfBook(bookId)
        if (pages.isEmpty()) throw IllegalArgumentException("不是一本书：$bookId")
        return object : BookHandle {
            override val id: String = bookId
            override val pageCount: Int = pages.size
            override suspend fun loadPage(index: Int): PageData {
                val page = pages.getOrNull(index)
                    ?: throw IndexOutOfBoundsException("页码越界：$index / ${pages.size}")
                return PageData(page.bytes(), mimeTypeOf(page.name))
            }
        }
    }

    override suspend fun readProgress(bookId: String): ReadingProgress? =
        progressStore.read(bookId)

    override suspend fun writeProgress(bookId: String, pageIndex: Int, totalPages: Int) =
        progressStore.write(bookId, pageIndex, totalPages)

    override suspend fun neighbors(bookId: String): Neighbors {
        val node = resolveNode(bookId)
        // 目录书与其父列表中的图片条目共用同一个"同目录 isBook 序列"语义
        val listParent = when {
            node.isDirectory -> node.parent() ?: return Neighbors(null, null)
            node.isImageFile() -> node.parent() ?: return Neighbors(null, null)
            else -> return Neighbors(null, null)
        }
        val books = bookEntriesOf(listParent)
        val idx = books.indexOfFirst { it.id == bookId }
        if (idx < 0) return Neighbors(null, null)
        return Neighbors(
            prev = books.getOrNull(idx - 1)?.id,
            next = books.getOrNull(idx + 1)?.id,
        )
    }

    /**
     * 相邻书判定专用的轻量书列表：isBook 集合与 [listingOf] 一致（子目录直接含图=书；本目录图片条目=书），
     * 但**不计算封面/页数**（封面字节走按需通路，这里连封面位置都不探），且按全局名称序排序
     * （review P1：分区拼接顺序会与浏览列表名称序不一致）。
     *
     * 子目录判定与列目录共用 [probeSubdirs]（票 #30 P2）：并发受控 + 单条失败降级 + 传输故障冒泡同一套策略，
     * 大目录下「上一本/下一本」不再逐子目录串行往返。
     */
    private suspend fun bookEntriesOf(dir: FsNode): List<BrowseEntry> {
        val kids = dir.children()
        val books = mutableListOf<BrowseEntry>()
        probeSubdirs(kids.filter { it.isDirectory }.sortedWith(compareBy(nameComparator) { it.name }))
            .forEach { probe ->
                if (probe.entry.isBook) {
                    books += BrowseEntry(probe.entry.id, probe.entry.name, isBook = true, coverUri = null, pageCount = null)
                }
            }
        kids.filter { it.isImageFile() }.forEach { img ->
            books += BrowseEntry(img.id, img.name, isBook = true, coverUri = null, pageCount = null)
        }
        kids.filter { it.isArchiveFile() }.forEach { arc ->
            books += BrowseEntry(arc.id, arc.name, isBook = true, coverUri = null, pageCount = null)
        }
        return sortedByName(books) { it.name }
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

    private fun <T> sortedByName(items: List<T>, nameOf: (T) -> String): List<T> =
        items.sortedWith(compareBy(nameComparator, nameOf))

    private fun listFilesSorted(dir: FsNode, keep: (FsNode) -> Boolean): List<FsNode> =
        dir.children().filter(keep).sortedWith(compareBy(nameComparator) { it.name })

    private fun listImagesSorted(dir: FsNode): List<FsNode> = listFilesSorted(dir) { it.isImageFile() }

    // ---------- 按需封面（票 #30：封面字节只在可见行取，枚举期不发生）----------

    /**
     * 目录封面字节（spec 故事 9）：含图 → 目录内首图；只含压缩包（目录书）→ 首个压缩包的首帧；
     * 纯容器 → 逐级下取第一张图。每层目录只列一次。
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

    private fun sortEntries(entries: List<BrowseEntry>, sort: SortMode): List<BrowseEntry> =
        when (sort) {
            SortMode.NAME -> sortedByName(entries) { it.name }
            SortMode.MODIFIED_TIME ->
                entries.sortedWith(compareByDescending { resolve(it.id)?.lastModifiedMs ?: 0L })
            // 发布时间（spec 故事 12/13）：CBZ 读 ComicInfo.xml，无元数据（图片文件夹等）回退 mtime
            SortMode.RELEASE_TIME ->
                entries.sortedWith(compareByDescending { releaseKey(it.id) })
        }

    /** 发布时间排序键：优先 ComicInfo.xml 的日期（转毫秒与 mtime 同量纲），缺失回退修改时间 */
    private fun releaseKey(entryId: String): Long {
        val node = resolve(entryId) ?: return 0L
        val cacheKey = entryId + "@" + (node.lastModifiedMs ?: 0L)
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
    }
}
