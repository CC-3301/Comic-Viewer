package com.cc3301.comicviewer.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Looper
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.ComposeView
import com.cc3301.comicviewer.core.source.BookHandle
import com.cc3301.comicviewer.core.source.PageData
import com.cc3301.comicviewer.core.view.ReaderMenuLayout
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.util.concurrent.TimeUnit
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * 预览格的**橙框必须贴住图**（当前页那一格）：竖图左右要框住、横图上下要框住。
 *
 * 走真组件 [ReaderMenu]（它是 public）：假书每页给一张**纯品红**的合成图（品红只可能来自图本身，
 * 面板底色/书名/页码都不会是品红），真渲染成位图后量两件事：
 * ① **包含**：图的包围盒必须落在橙像素的包围盒里（图越出框就是「框没包住图」）；
 * ② **贴合**：框不能离图太远（框外扩量 ≤ 框粗 + 容差）——否则就是「框画在格子边、图在里面」那种错。
 *
 * 位图口径的「推到读数稳定」见 `RobolectricComposeTestSupport` 的 `renderUntilStable`。
 *
 * 阈值为什么是「外扩 ≥ 一个框宽」：框要**整个落在图外**（内缘贴图缘），外扩因此至少一个框宽；
 * 只比图大 1–2px 的抗锯齿差意味着框压在图的外圈上，判红。上限「≤ 2×框宽 + 2」表示框没脱离图。
 * 注意：Robolectric 的 `Image` 走另一条渲染路径（它会按内边距缩图），本地渲染不会出现
 * 「图跟着节点长到框盒那么大」那种形态——本用例钉的是不变量的本身。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PreviewFrameEnclosesImageTest {

    private companion object {
        /** **当前页**的颜色：品红——面板底色/书名/页码都不会是品红，扫到它一定是当前页那张图 */
        const val MAGENTA = 0xFFFF00FF.toInt()

        /** 其余页的颜色：绿色——量包围盒时只认品红，别的格就不会混进当前格的读数 */
        const val OTHER = 0xFF00B000.toInt()
    }

    private val density: Float = RuntimeEnvironment.getApplication().resources.displayMetrics.density

    /** 框粗（px）：生产值 × 本机 density */
    private val framePx: Int = (ReaderMenuLayout.PREVIEW_FRAME_DP * density).roundToInt()

    /** 假书：每页一张纯品红、指定像素尺寸的合成 PNG */
    private class FakeBook(
        override val id: String,
        private val sizes: List<Pair<Int, Int>>,
        /** 哪一页刷品红（包围盒只认品红 ⇒ 量到的就是这一页那张图；其余页刷绿色，免得混进读数） */
        private val highlightIndex: Int = 0,
    ) : BookHandle {
        override val pageCount: Int = sizes.size

        override suspend fun loadPage(index: Int): PageData {
            val (w, h) = sizes[index]
            val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            bitmap.eraseColor(if (index == highlightIndex) MAGENTA else OTHER)
            val bytes = ByteArrayOutputStream().use { out ->
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
                out.toByteArray()
            }
            return PageData(bytes, "image/png")
        }
    }

    private class Box(val left: Int, val top: Int, val right: Int, val bottom: Int) {
        val width get() = right - left + 1
        val height get() = bottom - top + 1
    }

    /** 品红（图）与**图附近窗口内**的橙（当前页框环）包围盒 —— 不限制窗口的话，页码/按钮的橙会污染包围盒 */
    private class Measured(val image: Box?, val ring: Box?)

    private class Dump(
        val image: Box,
        val ring: Box,
        val growLeft: Int,
        val growRight: Int,
        val growTop: Int,
        val growBottom: Int,
    )

    private fun measure(bitmap: Bitmap): Measured {
        val w = bitmap.width
        val h = bitmap.height
        val pixels = IntArray(w * h)
        bitmap.getPixels(pixels, 0, w, 0, 0, w, h)
        var il = Int.MAX_VALUE; var it = Int.MAX_VALUE; var ir = -1; var ib = -1
        fun magenta(c: Int): Boolean {
            val r = (c shr 16) and 0xFF; val g = (c shr 8) and 0xFF; val b = c and 0xFF
            return r > 200 && g < 80 && b > 200
        }
        // 强调橙 #FF9800（含抗锯齿的近似值）：r 高、g 中、b 低
        fun orange(c: Int): Boolean {
            val r = (c shr 16) and 0xFF; val g = (c shr 8) and 0xFF; val b = c and 0xFF
            return r > 200 && g in 100..200 && b < 90
        }
        for (y in 0 until h) for (x in 0 until w) if (magenta(pixels[y * w + x])) {
            il = min(il, x); it = min(it, y); ir = max(ir, x); ib = max(ib, y)
        }
        if (ir < 0) return Measured(null, null)
        val image = Box(il, it, ir, ib)
        // 只在图附近这一圈里找框（远离图的橙是别的部件）
        val pad = 4 * framePx + 8
        val y0 = max(0, it - pad); val y1 = min(h - 1, ib + pad)
        val x0 = max(0, il - pad); val x1 = min(w - 1, ir + pad)
        // 框是**四条长线**：整行/整列的橙像素要够长才算线，当前页那格橙色页码这种小块因此进不来
        //（只按包围盒量的话，页码会把下边界拉到图下方十几个像素）
        var fl = Int.MAX_VALUE; var ft = Int.MAX_VALUE; var fr = -1; var fb = -1
        for (y in y0..y1) {
            var count = 0
            for (x in x0..x1) if (orange(pixels[y * w + x])) count++
            if (count > image.width / 2) { ft = min(ft, y); fb = max(fb, y) }
        }
        for (x in x0..x1) {
            var count = 0
            for (y in y0..y1) if (orange(pixels[y * w + x])) count++
            if (count > image.height / 2) { fl = min(fl, x); fr = max(fr, x) }
        }
        return Measured(image, if (fr < 0) null else Box(fl, ft, fr, fb))
    }

    /** 图与框环的逐边关系（框环在图外多少像素） */
    private fun dump(bitmap: Bitmap): Dump {
        val m = measure(bitmap)
        val image = m.image ?: error("图没画出来")
        val ring = m.ring ?: error("图附近没有橙框")
        return Dump(image, ring, image.left - ring.left, ring.right - image.right, image.top - ring.top, ring.bottom - image.bottom)
    }

    /**
     * 渲染到「品红像素数连续两轮不变」为止，返回最终位图。
     * 每轮让出一点**真实**时间（同 `RobolectricComposeTestSupport.layoutUntil` 的 `Thread.sleep(5)` 口径）：
     * 解图在真线程上跑，只推假时钟会把它饿死。
     */
    private fun renderMenu(view: ComposeView, widthPx: Int, heightPx: Int): Bitmap {
        var last: Bitmap = view.renderStep()
        var previous = -1
        repeat(40) {
            val bitmap = view.renderStep()
            val count = countMagenta(bitmap)
            last = bitmap
            if (count == previous && count > 0) return last
            previous = count
            shadowOf(Looper.getMainLooper()).idleFor(16, TimeUnit.MILLISECONDS)
            Thread.sleep(5)
        }
        return last
    }

    private fun countMagenta(bitmap: Bitmap): Int {
        val pixels = IntArray(bitmap.width * bitmap.height)
        bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        return pixels.count { c ->
            val r = (c shr 16) and 0xFF; val g = (c shr 8) and 0xFF; val b = c and 0xFF
            r > 200 && g < 80 && b > 200
        }
    }

    /** 取数/解码两个模块：本用例一份，按生产口径沿组合树提供给 `ReaderMenu`（提供点在 MainActivity） */
    private val images = PageImages(log = {}, cache = decodedImageCache())
    private val pageBytes = PageBytes(
        PageDiskCache(Files.createTempDirectory("preview-frame").toFile(), log = {}),
        log = {},
    )

    private fun menuView(book: BookHandle, bookId: String, currentPage: Int): ComposeView = composeViewInActivity {
        MaterialTheme {
            CompositionLocalProvider(
                LocalPageImages provides images,
                LocalPageBytes provides pageBytes,
            ) {
                ReaderMenu(
                    title = "合成书名",
                    currentPage = currentPage,
                    pageCount = book.pageCount,
                    handle = book,
                    bookId = bookId,
                    onSeek = {},
                    onPrevBook = {},
                    onNextBook = {},
                    onDismiss = {},
                )
            }
        }
    }

    private val screenWidthPx: Int
        get() = RuntimeEnvironment.getApplication().resources.displayMetrics.widthPixels

    private val screenHeightPx: Int
        get() = RuntimeEnvironment.getApplication().resources.displayMetrics.heightPixels

    /**
     * 生产级的「当前页摆在预览区正中」：真 `ReaderMenu`（不是复刻）里，当前页那张图的中心必须落在
     * 预览条（= 面板内宽，左右对称）的中心上。条子左右各内缩一个框宽之后，居中偏移若没把内缩量算进去，
     * 这里会偏出一个框宽（本机 density 下 = 2px）而红。
     */
    @Test
    fun `当前页摆在预览区正中`() {
        val book = FakeBook("frame-center", List(20) { 800 to 1600 }, highlightIndex = 8)
        val view = menuView(book, "frame-center", currentPage = 8)
        view.layoutOnce(screenWidthPx, screenHeightPx)
        val bitmap = renderMenu(view, screenWidthPx, screenHeightPx)

        val image = measure(bitmap).image ?: error("图没画出来")
        val imageCenter = (image.left + image.right + 1) / 2f
        assertTrue(
            "当前页中心 ${imageCenter}px 必须落在屏幕中心 ${screenWidthPx / 2f}px 上（±1px）",
            kotlin.math.abs(imageCenter - screenWidthPx / 2f) <= 1f,
        )
    }

    /** 竖图（宽高比 1:2）：**左右**必须被框住 */
    @Test
    fun `竖图左右被橙框框住`() {
        // 单页书：这一格被条子居中排布，贴不到条子边界（窄箱本来也碰不到边界），量的是「框贴图」本身
        val book = FakeBook("frame-tall", listOf(800 to 1600))
        val view = menuView(book, "frame-tall", currentPage = 0)
        view.layoutOnce(screenWidthPx, screenHeightPx)
        val bitmap = renderMenu(view, screenWidthPx, screenHeightPx)

        val d = dump(bitmap)
        // 四边都量：外扩必须 ≈ 一个框宽。太小 = 框压在图上/被条子裁掉；太大 = 框与图之间露了缝
        assertGrown(d.growLeft, framePx, "左")
        assertGrown(d.growRight, framePx, "右")
        assertGrown(d.growTop, framePx, "上")
        assertGrown(d.growBottom, framePx, "下")
    }

    /**
     * [grown]（框环在图外多少像素）必须落在 [framePx] 附近：下界 [framePx]−1 允许一格取整，
     * 上界 [framePx]+2 表示框与图之间最多 1px 取整缝。
     * 注：本地渲染量不到「框与图之间那条 1px 的背景缝」（取整差在本地不咬人），这两条是**不变量闸**，
     * 现场形态的判别靠截图读数。
     */
    private fun assertGrown(grown: Int, framePx: Int, side: String) {
        assertTrue("框的$side 边压在图上或被裁掉了（外扩 ${grown}px，必须 ≥ ${framePx - 1}px）", grown >= framePx - 1)
        assertTrue("框与图的$side 边之间露了背景缝（外扩 ${grown}px，应当 ≤ ${framePx + 2}px）", grown <= framePx + 2)
    }

    /**
     * 横图（宽高比 2:1）：**上下**必须被框住；而且**左右也不能被裁**——
     * 横图的箱宽 = 格宽，框的左右两条整个落在格子外，那一格又贴着条子边界（当前页是第 1 页、滚不动）
     * ⇒ 条子必须给自己留出一个框宽，否则左边那条会被条子边界裁掉。
     */
    @Test
    fun `横图上下被橙框框住`() {
        val book = FakeBook("frame-wide", List(20) { 1600 to 800 })
        val view = menuView(book, "frame-wide", currentPage = 0)
        view.layoutOnce(screenWidthPx, screenHeightPx)
        val bitmap = renderMenu(view, screenWidthPx, screenHeightPx)

        val d = dump(bitmap)
        // 上下：框贴图。左右：这一格的箱宽 = 格宽，框的两侧整个落在格子外——
        // 格子贴着条子边界时（当前页是第 1 页、不能再往左滚），条子不给框留位置就会把它裁掉
        assertGrown(d.growTop, framePx, "上")
        assertGrown(d.growBottom, framePx, "下")
        assertGrown(d.growLeft, framePx, "左")
        assertGrown(d.growRight, framePx, "右")
    }
}
