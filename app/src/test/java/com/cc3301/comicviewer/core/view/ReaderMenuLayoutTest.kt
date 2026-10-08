package com.cc3301.comicviewer.core.view

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.sp

/**
 * 阅读菜单的**独立算式**用例（本文件即「算式表」）：与视口档位无关的那几组口径。
 * 分档几何（面板高度、预览条保底、固定行、行距与底内边距）在 `ReaderMenuTierGeometryTest`。
 *
 * 覆盖：
 * - 预览项尺寸：高度撑满预览条减页数那一行、宽度 = 高度 × 该页真实比例、超宽页按预览区宽收口、
 *   非正比例回 0 由占位比例兜底、解码高度向上分桶；一屏张数的算例在 `ReaderMenuTierGeometryTest`；
 * - 三档字号：`内宽 × 0.05 / 0.05 / 0.035` 与 18–24 / 16–24 / 12–16sp 上下限，
 *   不变量 **标题 ≥ 页码 ≥ 格内页码**（正常档页码严格大于格内页码；极端 fontScale 取等，见
 *   `渲染页码字号恒不低于格内页码`）；
 * - 页位口径：滑块值 → 最近页（纯函数侧；自接点按手势的比例→页与幂等规则在 `ui/SliderGestureStateTest`）、
 *   页位夹取、格内页码换算；居中的滚动偏移（生产侧何时重算见 `ui/PreviewStripCenterTest`）；
 * - 与视口无关的常量口径（底部行高与命中带、按钮尺寸、字色、预览条间隙）。
 *
 * 设备目视与截图不在 JVM 里测，由 `ReaderMenuFooterTest`（底部行几何）与设备验收覆盖。
 */
class ReaderMenuLayoutTest {

    /**
     * 标题一行的 dp 高。`sp ≠ dp`：换算在 [ReaderMenuLayout.titleLineHeightDp] 里，
     * [fontScale] 显式传入（1 = 常规字体）。
     */
    private fun titleLine(innerWidthDp: Float, fontScale: Float = 1f): Float =
        ReaderMenuLayout.titleLineHeightDp(innerWidthDp, fontScale)

    /** 视口宽（dp）= 面板内宽 + 两侧内边距（档位几何的输入）。 */
    private fun viewportWidth(innerWidthDp: Float): Float =
        innerWidthDp + ReaderMenuLayout.PANEL_HORIZONTAL_PADDING_DP * 2

    /**
     * 预览条高度（dp）= 面板高度 − 固定行合计（档位几何的派生值：`ReaderMenuTierGeometry.previewStripHeightDp`）。
     * 底部 inset 那一项不能漏：面板整块消费 `readerPanelInsets()`，而阅读器是沉浸态，
     * 底部由 [ReaderOverlayLayout.MIN_BOTTOM_DP]（24dp）兜底。
     */
    private fun previewStripHeight(
        viewportHeightDp: Float,
        innerWidthDp: Float,
        fontScale: Float = 1f,
        lineCount: Int = 1,
    ): Float =
        ReaderMenuLayout.tierGeometry(viewportWidth(innerWidthDp), viewportHeightDp, ReaderOverlayLayout.MIN_BOTTOM_DP)
            .previewStripHeightDp(titleLine(innerWidthDp, fontScale), lineCount)

    /**
     * 页数那一行的 dp 高：字号 sp × 行高比例 × fontScale——
     * 走生产的同一条换算（`Density.toDp()`），因此 fontScale ≠ 1 时两个数字能对上。
     */
    private fun labelHeight(innerWidthDp: Float, fontScale: Float = 1f): Float = with(Density(1f, fontScale)) {
        ReaderMenuLayout.previewLabelHeightSp(ReaderMenuLayout.previewPageLabelSp(innerWidthDp)).sp.toDp().value
    }

    // ---------- 滑条手势与绘制 ----------

    @Test
    fun `圆球位置两端夹在半径内 中间线性`() {
        // 绘制口径：比例 0 / 1 分别停在轨道两端（圆球不出轨道），中间线性
        assertEquals(4f, ReaderMenuLayout.sliderThumbCenterXPx(0f, 100f, 8f), 0.01f)
        assertEquals(50f, ReaderMenuLayout.sliderThumbCenterXPx(0.5f, 100f, 8f), 0.01f)
        assertEquals(96f, ReaderMenuLayout.sliderThumbCenterXPx(1f, 100f, 8f), 0.01f)
        assertEquals("越界比例也夹在两端", 4f, ReaderMenuLayout.sliderThumbCenterXPx(-3f, 100f, 8f), 0.01f)
        assertEquals(96f, ReaderMenuLayout.sliderThumbCenterXPx(9f, 100f, 8f), 0.01f)
    }

    @Test
    fun `行上比例到页的映射三段无死区`() {
        // 3 页书（值域 0..2）里 0–25% / 25–75% / 75–100% 分别落到三页，
        // 每个按下位置都有对应页——「只有最左/最中/最右有效」的写法在这条断言下必红
        for ((fraction, page) in listOf(
            0.05f to 0,
            0.24f to 0,
            0.26f to 1,
            0.5f to 1,
            0.74f to 1,
            0.76f to 2,
            0.95f to 2,
        )) {
            assertEquals("行宽 ${fraction * 100}% 处应落到页位 $page", page, ReaderMenuLayout.seekTargetPageForFraction(fraction, 3))
        }
        assertEquals("越界比例夹到首末页", 0, ReaderMenuLayout.seekTargetPageForFraction(-0.5f, 3))
        assertEquals(2, ReaderMenuLayout.seekTargetPageForFraction(1.5f, 3))
        assertEquals("空书恒第 0 页", 0, ReaderMenuLayout.seekTargetPageForFraction(0.9f, 0))
        // 比例 → 值 与 值 → 比例 必须互为逆（拖动时拇指与预览用的是同一映射）
        assertEquals(100f, ReaderMenuLayout.sliderValueForFraction(0.5f, 201), 0.01f)
        assertEquals(0.5f, ReaderMenuLayout.sliderFraction(100f, 201), 0.001f)
        assertEquals("单页书比例恒 0（分母为 0 不炸）", 0f, ReaderMenuLayout.sliderFraction(3f, 1), 0.001f)
    }

    // ---------- 当前页缩略图居中 ----------

    /**
     * 「当前页缩略图始终居中」的**偏移口径**：
     * ① 一格比视口窄时偏移 = `−(视口宽 − 条目宽)/2`，且**必须是负的**——正偏移是「往前滚」那一侧，
     *    会把当前页往视口左外推（符号反了这个函数仍然「有输出」，所以专门断言符号）；
     * ② 居中后条目两侧留白相等（这就是「落在正中」的可算形式，整数除法只允许 1px 误差）；
     * ③ 条目不比视口窄时回 0（没有可居中的空间）。
     * 两端（首页/末页贴边）不在这里判：它是 `LazyList` 夹取滚动量的行为，由 Robolectric 量真几何
     * （`ui/PreviewStripCenterTest`）。
     */
    @Test
    fun `当前页居中偏移是负值 两侧留白相等 不窄于视口时回零`() {
        assertEquals("偶数差：严格一半", -120, ReaderMenuLayout.previewCenterScrollOffsetPx(125, 365))
        assertEquals("奇数差：整数除法把 1px 留给一侧", -120, ReaderMenuLayout.previewCenterScrollOffsetPx(124, 365))
        assertTrue(
            "条目比视口窄时偏移必须是负的（正数会把当前页推出视口）",
            ReaderMenuLayout.previewCenterScrollOffsetPx(124, 365) < 0,
        )
        for (itemWidth in 40..340 step 20) {
            val offset = ReaderMenuLayout.previewCenterScrollOffsetPx(itemWidth, 365)
            val leftGap = -offset
            val rightGap = 365 - (itemWidth - offset)
            assertTrue(
                "条目 ${itemWidth}px：两侧留白 $leftGap / $rightGap 必须相等（±1px）",
                kotlin.math.abs(leftGap - rightGap) <= 1,
            )
        }
        assertEquals("条目与视口同宽：没有可居中的空间", 0, ReaderMenuLayout.previewCenterScrollOffsetPx(365, 365))
        assertEquals("条目比视口宽：同此（不能往正方向推）", 0, ReaderMenuLayout.previewCenterScrollOffsetPx(400, 365))
    }

    /**
     * 居中偏移的输入是**目标项的实际宽**，而它在居中之后还会变 ——
     * 位图到达时真实比例生效（未解码时是占位比例 2:3）、标题行数回填时预览条高度也会变。
     * 本用例把两种比例下的「图片宽」走生产口径算出来，钉两件事：
     * ① 同一条（占位 → 真实）偏移**必须变化**（若某处把宽度当常量、或把居中算在宽度还会变的时刻，
     *    下面这条不等式就红）；② 每个宽度下偏移都让条目两侧留白相等（= 居中）。
     * 生产侧的「何时重算」由 `PreviewStrip` 的 `LaunchedEffect(target, pageCount, visibleItemsSignature,
     * userTookOver)` 实现（**可见项 `(index, size)` 签名**进 key：任何可见格宽度变化都算 —— 目标项自己的
     * 宽，以及居中后必然露出的**前面那几格**的宽）；那条链在本机没有自动用例（见 `ui/PreviewStripCenterTest`
     * 的类 KDoc），设备判据：打开菜单后等一秒（位图解码完成）
     * 看当前页是否仍在预览区正中，尤其是**封面/双页跨页**这类真实比例 ≠ 2:3 的页。
     */
    @Test
    fun `居中偏移随目标项实测宽变化 占位比例与真实比例必须不同`() {
        val height = 852f
        val inner = 365f
        val geometry = ReaderMenuLayout.tierGeometry(viewportWidth(inner), height, ReaderOverlayLayout.MIN_BOTTOM_DP)
        val strip = geometry.previewStripHeightDp(titleLine(inner), 1)
        val label = labelHeight(inner)
        // 同一格在「占位比例 2:3」与「真实比例 2:1（双页跨页）」下的图片宽（都走生产两函数）
        val placeholderImage = ReaderMenuLayout.previewImageHeightDp(
            strip,
            label,
            inner,
            ReaderMenuLayout.PREVIEW_PLACEHOLDER_ASPECT,
        )
        val realImage = ReaderMenuLayout.previewImageHeightDp(strip, label, inner, 2f)
        val placeholderWidth = ReaderMenuLayout.previewItemWidth(placeholderImage, ReaderMenuLayout.PREVIEW_PLACEHOLDER_ASPECT)
        val realWidth = ReaderMenuLayout.previewItemWidth(realImage, 2f)
        assertTrue(
            "同一格在两种比例下的宽度必须不同（占位 $placeholderWidth px → 真实 $realWidth px，否则本用例无判别力）",
            kotlin.math.abs(realWidth - placeholderWidth) > 1f,
        )
        val viewport = inner.toInt()
        val placeholderOffset = ReaderMenuLayout.previewCenterScrollOffsetPx(placeholderWidth.toInt(), viewport)
        val realOffset = ReaderMenuLayout.previewCenterScrollOffsetPx(realWidth.toInt(), viewport)
        assertTrue(
            "宽度变了偏移必须跟着变（占位 $placeholderOffset px → 真实 $realOffset px）：把宽度当常量就会偏 " +
                "|真实宽 − 占位宽| / 2 = ${kotlin.math.abs(realWidth - placeholderWidth) / 2f}dp",
            placeholderOffset != realOffset,
        )
        for ((label2, width) in listOf("占位比例" to placeholderWidth, "真实比例" to realWidth)) {
            val offset = ReaderMenuLayout.previewCenterScrollOffsetPx(width.toInt(), viewport)
            val leftGap = -offset
            val rightGap = viewport - (width.toInt() - offset)
            assertTrue("$label2：两侧留白 $leftGap / $rightGap 必须相等（±1px）", kotlin.math.abs(leftGap - rightGap) <= 1)
        }
    }

    // ---------- 常量口径 ----------

    @Test
    fun `上下一本按钮的可见本体不小于 96 乘 48dp`() {
        // 「按钮加大」的可见尺寸下限（不只可点区域）；96×48 也覆盖了触摸目标下限
        assertTrue("按钮可见宽度 ${ReaderMenuLayout.BOOK_STEP_MIN_WIDTH_DP}dp 必须 ≥ 96dp", ReaderMenuLayout.BOOK_STEP_MIN_WIDTH_DP >= 96f)
        assertTrue("按钮可见高度 ${ReaderMenuLayout.BOOK_STEP_MIN_HEIGHT_DP}dp 必须 ≥ 48dp", ReaderMenuLayout.BOOK_STEP_MIN_HEIGHT_DP >= 48f)
        // 本体 48dp **高于**底部行可见高 36dp（视觉 36、命中 48）：
        // 行高只是布局占位，本体与命中区都不缩（见 ReaderMenuFooterTest）
        assertTrue(
            "底部行可见高必须已压到 A 档 36dp（≤ 本体高度）",
            ReaderMenuLayout.PANEL_FOOTER_HEIGHT_DP <= ReaderMenuLayout.BOOK_STEP_MIN_HEIGHT_DP,
        )
    }

    /** 页数纯白、上/下一本橙——两种颜色不得混（口径都在 [ReaderMenuLayout] / `ui/AccentColor`） */
    @Test
    fun `页数是纯白 上下一本是橙`() {
        assertEquals("页数字色是纯白（补记 8 ④）", 0xFFFFFFFFL, ReaderMenuLayout.PANEL_PAGE_LABEL_COLOR)
        assertEquals(
            "上/下一本的橙必须是仓库强调色（与页数的白分开）",
            0xFFFF9800.toInt(),
            com.cc3301.comicviewer.ui.ACCENT_ORANGE.toArgb(),
        )
        assertTrue(
            "页数不得用橙色（维护者看到的是预览图画错了）",
            ReaderMenuLayout.PANEL_PAGE_LABEL_COLOR != 0xFFFF9800L,
        )
    }

    // ---------- 页码收口 ----------

    /**
     * 口径「不截断优先，但层级不许破」：三等分把页数锁进 1/3 列宽
     * + `maxLines = 1` ⇒ 字号要按列宽收口，否则窄屏 + 大字体下 4 位页码（「1234 / 5678」≈110dp > 列宽 103.7dp）
     * 会把整串截掉；但收口不得把页码压到格内页码之下，因此**下限 = 格内页码字号**。
     *
     * 钉四件事：① 列宽 = 内宽 1/3 减两侧 2dp（三个中心不动）；② 上限生效时字号 = 上限、整串估算宽放得进列宽；
     * ③ 上限落到下限之下时取下限（此时才允许省略号截断，估算宽会超出列宽——本用例把这条取舍显式写出来）；
     * ④ 常规场合（fontScale 1、平板、3 位页码）不生效。
     * 不覆盖的部分：Robolectric 的字体度量是 stub，量不出真实字宽——估算用的是生产同一个占位宽常量
     * （[ReaderMenuLayout.PAGE_LABEL_CHAR_ADVANCE_EM]），设备目视（fs ≥1.5 + 4 位页码到底截不截断）仍待验收。
     */
    @Test
    fun `四位数页码加大字体 先按列宽收口 再保层级下限`() {
        val inner = 323f // 算例那台窄机（363dp 屏 − 两侧 20dp）
        val text = ReaderMenuLayout.pageLabelText(displayPage = 1234, pageCount = 5678)
        assertEquals("格式只有一处（当前页 / 总页数）", "1234 / 5678", text)
        val chars = text.length
        val column = ReaderMenuLayout.pageLabelColumnWidthDp(inner)
        assertEquals("中列可用宽 = 面板内宽 1/3 − 两侧各 2dp", inner / 3f - 4f, column, 0.01f)
        val nominal = ReaderMenuLayout.panelPageLabelSp(inner)
        val floor = ReaderMenuLayout.previewPageLabelSp(inner)
        assertEquals("下限 = 格内页码字号（12sp）", 12f, floor, 0.01f)
        for (fontScale in listOf(1f, 1.2f, 1.5f, 2f)) {
            val sp = ReaderMenuLayout.pageLabelSp(1234, 5678, inner, fontScale)
            val cap = ReaderMenuLayout.pageLabelWidthCapSp(chars, inner, fontScale)
            val renderedWidth = sp * fontScale * chars * ReaderMenuLayout.PAGE_LABEL_CHAR_ADVANCE_EM
            assertTrue("fontScale $fontScale：渲染字号 ${sp}sp 不得小于格内页码 ${floor}sp（层级不破）", sp >= floor - 0.01f)
            assertTrue("fontScale $fontScale：渲染字号 ${sp}sp 不得超过 AC6 标称 ${nominal}sp", sp <= nominal + 0.01f)
            if (cap >= nominal) {
                assertEquals("fontScale $fontScale：上限不低于标称 ⇒ 字号 = 标称", nominal, sp, 0.01f)
            } else if (cap > floor) {
                assertEquals("fontScale $fontScale：上限生效时字号 = 上限", cap, sp, 0.01f)
            } else {
                assertEquals("fontScale $fontScale：上限落到下限之下时取下限", floor, sp, 0.01f)
            }
            if (cap > floor) {
                assertTrue(
                    "fontScale $fontScale：整串估算宽 ${renderedWidth}dp 必须放得进中列 ${column}dp（不截断）",
                    renderedWidth <= column + 0.01f,
                )
            } else {
                assertTrue(
                    "fontScale $fontScale：这一档连下限都放不下（估算宽 ${renderedWidth}dp > 列宽 ${column}dp）⇒ 允许省略号截断（真机判）",
                    renderedWidth > column,
                )
            }
        }
        assertEquals("窄机 + fontScale 1：上限不生效，字号仍是 AC6 的标称值", nominal, ReaderMenuLayout.pageLabelSp(1234, 5678, inner, 1f), 0.01f)
        assertTrue(
            "fontScale 2：字号 ${ReaderMenuLayout.pageLabelSp(1234, 5678, inner, 2f)}sp 必须被压到标称值 ${nominal}sp 之下（收口真会生效）",
            ReaderMenuLayout.pageLabelSp(1234, 5678, inner, 2f) < nominal,
        )
        assertEquals("平板内宽 728 + fontScale 1：不得被无谓压小", ReaderMenuLayout.panelPageLabelSp(728f), ReaderMenuLayout.pageLabelSp(12, 340, 728f, 1f), 0.01f)
        assertEquals("窄机 + 3 位页码 + fontScale 1.5：仍放得下，上限不生效", nominal, ReaderMenuLayout.pageLabelSp(45, 340, inner, 1.5f), 0.01f)
    }

    // ---------- 格内页数那一行 ----------

    @Test
    fun `缩略图高度等于预览条高减页数行高`() {
        // 页数行高是 **sp**：字号 × 行高比例
        assertEquals(15f, ReaderMenuLayout.previewLabelHeightSp(12.5f), 0.01f)
        // 加回页数行必须恰好等于预览条高（缩略图 + 页数 = 一整格，不多不少）
        val image = ReaderMenuLayout.previewImageHeightDp(200f, 15f, 365f, 2f / 3f)
        assertEquals(185f, image, 0.01f)
        assertEquals("缩略图 + 页数行 = 预览条高", 200f, image + 15f, 0.01f)
        // 预览条比页数行还矮时不出现负高度
        assertEquals(0f, ReaderMenuLayout.previewImageHeightDp(10f, 15f, 365f, 2f / 3f), 0.01f)
        // 超宽页仍按宽度收口（比例不变）
        assertEquals(182.5f, ReaderMenuLayout.previewImageHeightDp(224f, 22.5f, 365f, 2f), 0.05f)
    }

    @Test
    fun `sp 与 dp 不等价 标题行与页数行都随 fontScale 变大`() {
        // 把 sp 数值当 dp 用会在系统大字体下把固定行算小。
        // 标题：矮视口内宽 812dp ⇒ 字号 24sp ⇒ 一行 28.8sp；fontScale 1.5 时 = 43.2dp
        assertEquals(28.8f, ReaderMenuLayout.titleLineHeightDp(812f, 1f), 0.01f)
        assertEquals(43.2f, ReaderMenuLayout.titleLineHeightDp(812f, 1.5f), 0.01f)
        // 页数行：同一条换算（走 Density.toDp），fontScale 放大时该行也必须变大
        assertEquals(12.775f, ReaderMenuLayout.previewPageLabelSp(365f), 0.001f)
        assertEquals(15.33f, labelHeight(365f), 0.01f)
        val scaled = labelHeight(365f, 1.5f)
        assertTrue("fontScale 1.5 时页数行 $scaled dp 必须明显高于常规字体下的 ${labelHeight(365f)}dp", scaled > labelHeight(365f) * 1.4f)
        assertTrue("fontScale 1.5 时页数行 $scaled dp 不得超出字号×比例的 1.5 倍", scaled <= labelHeight(365f) * 1.5f + 0.01f)
    }

    // ---------- 横向 inset 的逐行分配 ----------

    @Test
    fun `面板内容区宽度扣掉内边距与左右 inset`() {
        // 面板整块消费 readerPanelInsets()，**四行一致**（标题也在内容区里居中），
        // 与 `docs/SPEC.md` 故事 28「贴底浮层显式消费挖孔 inset」同口径。这里钉的是内容区宽度口径：
        // 屏宽 − 两侧内边距 − 左右 inset；侧边 inset 非 0 时预览区宽度（也就是超宽页的收口算据）必须跟着变窄。
        assertEquals("无 inset：852 − 20 × 2", 812f, ReaderMenuLayout.panelInnerWidthDp(852f, 0f), 0.01f)
        val withCutout = ReaderMenuLayout.panelInnerWidthDp(852f, 44f)
        assertEquals("右侧挖孔 44dp：内容区必须再窄 44dp", 768f, withCutout, 0.01f)
        // 超宽页（宽高比 12:1）按内容区宽收口：宽度口径若退回「未扣 inset 的屏宽」（812dp），
        // 收口高度会是 812/12 = 67.7dp；按内容区宽（768dp）则是 64dp
        // 矮视口（可用高按口径取 360dp）的预览条：固定行按两行标题建模
        val strip = previewStripHeight(360f, withCutout, lineCount = 2)
        assertEquals(
            "12:1 超宽页收口后高度 = 内容区宽 / 12",
            withCutout / 12f,
            ReaderMenuLayout.previewItemHeight(strip, withCutout, 12f),
            0.01f,
        )
        assertTrue(
            "同一页按未扣 inset 的屏宽收口会得到更大的高度（67.7 > 64）——这就是宽度口径要扣 inset 的理由",
            ReaderMenuLayout.previewItemHeight(strip, 812f, 12f) > ReaderMenuLayout.previewItemHeight(strip, withCutout, 12f),
        )
        assertTrue(
            "内容区必须比不扣 inset 时窄（否则收口算据是错的）",
            withCutout < ReaderMenuLayout.panelInnerWidthDp(852f, 0f),
        )
        assertTrue("内容区宽度不得为负", ReaderMenuLayout.panelInnerWidthDp(320f, 900f) >= 0f)
    }

    // ---------- 预览项尺寸 ----------

    @Test
    fun `单格宽度等于高度乘真实比例`() {
        // 方案图的手机档：高 240dp 的 2:3 页 → 160dp 宽
        assertEquals(160f, ReaderMenuLayout.previewItemWidth(240f, 2f / 3f), 0.05f)
        // 方案图的平板档：高 310dp 的 2:3 页 → 207dp 宽
        assertEquals(206.7f, ReaderMenuLayout.previewItemWidth(310f, 2f / 3f), 0.05f)
        // 双页跨页（2:1 横向）：同一高度下更宽
        assertEquals(480f, ReaderMenuLayout.previewItemWidth(240f, 2f), 0.05f)
    }

    @Test
    fun `横向页比竖向页宽 上下不留白`() {
        val portrait = ReaderMenuLayout.previewItemWidth(200f, 2f / 3f)
        val landscape = ReaderMenuLayout.previewItemWidth(200f, 1.4f)
        assertTrue("横向页（$landscape）必须比竖向页（$portrait）宽", landscape > portrait)
        assertTrue("竖向页也窄于预览区高度（上下不留白 ⇒ 宽度 ≤ 高度 × 比例）", portrait < 200f)
        assertTrue("横向页比高度还宽", landscape > 200f)
    }

    @Test
    fun `超宽页按预览区宽度收口 比例不变且不靠左贴边`() {
        // 双页跨页（2:1）在「高度撑满」下宽度会超过预览区（224 × 2 = 448 > 365）：
        // 收口后高度降到 365/2 = 182.5dp、宽度 365dp（铺满预览区、整页可见、不靠左贴边）
        val height = ReaderMenuLayout.previewItemHeight(224f, 365f, 2f)
        assertEquals(182.5f, height, 0.05f)
        assertEquals("收口后宽度 = 预览区宽", 365f, ReaderMenuLayout.previewItemWidth(height, 2f), 0.05f)
        // 常见竖版页不受影响：高度仍撑满预览区
        assertEquals(224f, ReaderMenuLayout.previewItemHeight(224f, 365f, 2f / 3f), 0.05f)
        // 刚好放得下的横向页也不收口（1.6:1 ⇒ 224 × 1.6 = 358.4 ≤ 365）
        assertEquals(224f, ReaderMenuLayout.previewItemHeight(224f, 365f, 1.6f), 0.05f)
        // 比例非法时不收口（回落到占位比例那条路）
        assertEquals(224f, ReaderMenuLayout.previewItemHeight(224f, 365f, 0f), 0.05f)
        assertEquals(224f, ReaderMenuLayout.previewItemHeight(224f, 365f, -1f), 0.05f)
    }

    @Test
    fun `非正比例回 0 由占位比例兜底`() {
        assertEquals(0f, ReaderMenuLayout.previewItemWidth(200f, 0f), 0.001f)
        assertEquals(0f, ReaderMenuLayout.previewItemWidth(200f, -1f), 0.001f)
        assertEquals(0f, ReaderMenuLayout.previewItemWidth(0f, 2f / 3f), 0.001f)
    }

    @Test
    fun `占位比例是常见的 2 比 3 且为正`() {
        assertEquals(2f / 3f, ReaderMenuLayout.PREVIEW_PLACEHOLDER_ASPECT, 0.0001f)
        assertTrue("占位比例必须为正（0 会让格子先塌成一条线）", ReaderMenuLayout.PREVIEW_PLACEHOLDER_ASPECT > 0f)
    }

    @Test
    fun `位图尺寸换算成宽高比`() {
        assertEquals(2f / 3f, ReaderMenuLayout.previewItemAspect(200, 300)!!, 0.0001f)
        assertEquals(1.4f, ReaderMenuLayout.previewItemAspect(140, 100)!!, 0.0001f)
    }

    @Test
    fun `位图尺寸无效时回 null`() {
        assertNull("宽为 0", ReaderMenuLayout.previewItemAspect(0, 300))
        assertNull("高为 0", ReaderMenuLayout.previewItemAspect(200, 0))
        assertNull("负尺寸", ReaderMenuLayout.previewItemAspect(-1, 5))
    }

    @Test
    fun `解码高度按预览区高度向上分桶 过冲不超过一个桶`() {
        val bucket = CoverDecode.BUCKET_PX
        // 手机预览区约 228dp、密度 2：456px → 向上取到 480px
        val decode = ReaderMenuLayout.previewDecodeHeightPx(456f)
        assertEquals(480, decode)
        assertTrue("解码高度必须 ≥ 预览区高度像素", decode.toFloat() >= 456f)
        assertTrue("过冲不得超过 32px", decode - 456f <= 32f)
        assertEquals("桶的整数倍", 0, decode % bucket)
        // 平板预览区更高 → 解得更高，不是固定高度
        assertTrue(ReaderMenuLayout.previewDecodeHeightPx(580f) > decode)
        // 恰好落在桶上不加码
        assertEquals(480, ReaderMenuLayout.previewDecodeHeightPx(480f))
        // 布局还没就绪（0/负）也给一个桶，避免 0 高度解码
        assertEquals(bucket, ReaderMenuLayout.previewDecodeHeightPx(0f))
    }

    // ---------- 三档字号 ----------

    @Test
    fun `标题字号 内宽乘 0_05 夹 18 到 24`() {
        assertEquals(18.25f, ReaderMenuLayout.panelTitleSp(365f), 0.01f)
        assertEquals(24f, ReaderMenuLayout.panelTitleSp(740f), 0.01f)
        assertEquals("极窄面板夹在下限", 18f, ReaderMenuLayout.panelTitleSp(100f), 0.01f)
        assertEquals("宽面板夹在上限", 24f, ReaderMenuLayout.panelTitleSp(1400f), 0.01f)
        assertEquals(0.05f, ReaderMenuLayout.PANEL_TITLE_SP_RATIO, 0.0001f)
    }

    @Test
    fun `页码字号 内宽乘 0_05 夹 16 到 24`() {
        assertEquals(18.25f, ReaderMenuLayout.panelPageLabelSp(365f), 0.01f)
        assertEquals(24f, ReaderMenuLayout.panelPageLabelSp(740f), 0.01f)
        assertEquals("极窄面板夹在下限", 16f, ReaderMenuLayout.panelPageLabelSp(100f), 0.01f)
        assertEquals("宽面板夹在上限", 24f, ReaderMenuLayout.panelPageLabelSp(1400f), 0.01f)
        assertEquals(0.05f, ReaderMenuLayout.PANEL_PAGE_LABEL_SP_RATIO, 0.0001f)
    }

    @Test
    fun `格内页码字号 内宽乘 0_035 夹 12 到 16`() {
        assertEquals(12.775f, ReaderMenuLayout.previewPageLabelSp(365f), 0.01f)
        assertEquals(16f, ReaderMenuLayout.previewPageLabelSp(740f), 0.01f)
        assertEquals("极窄面板夹在下限", 12f, ReaderMenuLayout.previewPageLabelSp(100f), 0.01f)
        assertEquals("宽面板夹在上限", 16f, ReaderMenuLayout.previewPageLabelSp(1400f), 0.01f)
        assertEquals(0.035f, ReaderMenuLayout.PREVIEW_LABEL_SP_RATIO, 0.0001f)
    }

    @Test
    fun `票面表的两个内宽档取值`() {
        // 手机（内宽 365）：18.3 / 18.3 / 12.8sp
        assertEquals(18.3f, ReaderMenuLayout.panelTitleSp(365f), 0.05f)
        assertEquals(18.3f, ReaderMenuLayout.panelPageLabelSp(365f), 0.05f)
        assertEquals(12.8f, ReaderMenuLayout.previewPageLabelSp(365f), 0.05f)
        // 平板（内宽 740）：24 / 24 / 16sp
        assertEquals(24f, ReaderMenuLayout.panelTitleSp(740f), 0.05f)
        assertEquals(24f, ReaderMenuLayout.panelPageLabelSp(740f), 0.05f)
        assertEquals(16f, ReaderMenuLayout.previewPageLabelSp(740f), 0.05f)
    }

    @Test
    fun `三档字号层级恒为 标题大于等于页码 且 页码大于格内页码`() {
        for (width in listOf(0f, 100f, 240f, 320f, 365f, 480f, 600f, 740f, 920f, 1280f, 1400f)) {
            val title = ReaderMenuLayout.panelTitleSp(width)
            val page = ReaderMenuLayout.panelPageLabelSp(width)
            val label = ReaderMenuLayout.previewPageLabelSp(width)
            assertTrue("内宽 ${width}dp：标题 $title 必须 ≥ 页码 $page", title >= page)
            assertTrue("内宽 ${width}dp：页码 $page 必须 > 格内页码 $label", page > label)
        }
        assertTrue(
            "标题下限必须大于等于页码下限",
            ReaderMenuLayout.PANEL_TITLE_MIN_SP >= ReaderMenuLayout.PANEL_PAGE_LABEL_MIN_SP,
        )
        assertTrue(
            "标题上限必须大于等于页码上限",
            ReaderMenuLayout.PANEL_TITLE_MAX_SP >= ReaderMenuLayout.PANEL_PAGE_LABEL_MAX_SP,
        )
        // 页码下限与格内页码上限同为 16sp，层级因此由**比例**保住：格内页码顶到上限时，
        // 页码已经明显更高（下面用生产公式算出那个内宽再比）
        val labelCappedWidth = ReaderMenuLayout.PREVIEW_LABEL_MAX_SP / ReaderMenuLayout.PREVIEW_LABEL_SP_RATIO
        assertTrue(
            "格内页码顶到上限时（内宽 ${labelCappedWidth}dp）页码 ${
                ReaderMenuLayout.panelPageLabelSp(labelCappedWidth)
            }sp 必须高于格内页码上限 ${ReaderMenuLayout.PREVIEW_LABEL_MAX_SP}sp",
            ReaderMenuLayout.panelPageLabelSp(labelCappedWidth) > ReaderMenuLayout.PREVIEW_LABEL_MAX_SP,
        )
        assertTrue(
            "页码下限不得低于格内页码上限（同值时由比例分层）",
            ReaderMenuLayout.PANEL_PAGE_LABEL_MIN_SP >= ReaderMenuLayout.PREVIEW_LABEL_MAX_SP,
        )
        // 三档都比改动前的字号大，且标题档整体降下来了
        assertTrue("标题上限不得再是 #67 的 32sp", ReaderMenuLayout.PANEL_TITLE_MAX_SP <= 24f)
        assertTrue("格内页码下限不得低于原 labelSmall 的 11sp", ReaderMenuLayout.PREVIEW_LABEL_MIN_SP >= 11f)
    }

    /**
     * 口径「不截断优先，但层级不许破」：上面那条不变量读的是**标称**字号
     * （[ReaderMenuLayout.panelPageLabelSp]），而中列渲染用的是 [ReaderMenuLayout.pageLabelSp]（标称 → 按列宽收口 → 夹下限）。
     * 本用例把**渲染值**钉进层级：无论 fontScale 与页码位数如何，`渲染页码 ≥ 格内页码字号` 恒成立。
     * 不覆盖的部分：这是字号（sp）层面的层级，不是渲染后的像素宽度；设备字体度量下是否截断仍归设备。
     */
    @Test
    fun `渲染页码字号恒不低于格内页码`() {
        val cases = listOf(
            12 to 340,
            1234 to 5678,
            99999 to 99999,
        )
        for (inner in listOf(240f, 323f, 365f, 480f, 728f, 984f)) {
            val floor = ReaderMenuLayout.previewPageLabelSp(inner)
            for (fontScale in listOf(0.85f, 1f, 1.3f, 1.5f, 2f)) {
                for ((displayPage, pageCount) in cases) {
                    val rendered = ReaderMenuLayout.pageLabelSp(displayPage, pageCount, inner, fontScale)
                    assertTrue(
                        "内宽 ${inner}dp / fontScale $fontScale / 页码 $displayPage / $pageCount：" +
                            "渲染字号 ${rendered}sp 不得小于格内页码 ${floor}sp",
                        rendered >= floor - 0.01f,
                    )
                    assertTrue(
                        "内宽 ${inner}dp / fontScale $fontScale：渲染字号 ${rendered}sp 不得高于标称 ${ReaderMenuLayout.panelPageLabelSp(inner)}sp",
                        rendered <= ReaderMenuLayout.panelPageLabelSp(inner) + 0.01f,
                    )
                }
            }
        }
        // 下限本身与格内页码同源（同一条公式、同一个内宽）⇒ 不会出现「下限比格内页码大一个档」的怪事
        for (inner in listOf(0f, 240f, 323f, 728f, 1400f)) {
            assertEquals(
                "内宽 ${inner}dp：下限必须就是格内页码字号",
                ReaderMenuLayout.previewPageLabelSp(inner),
                ReaderMenuLayout.pageLabelSp(1, 1, inner, 100f),
                0.01f,
            )
        }
    }

    @Test
    fun `三档字号都随内宽单调不减`() {
        val widths = listOf(240f, 320f, 365f, 480f, 600f, 740f, 920f, 1280f)
        for (index in 0 until widths.size - 1) {
            val (a, b) = widths[index] to widths[index + 1]
            assertTrue("标题：$a → $b", ReaderMenuLayout.panelTitleSp(a) <= ReaderMenuLayout.panelTitleSp(b))
            assertTrue("页码：$a → $b", ReaderMenuLayout.panelPageLabelSp(a) <= ReaderMenuLayout.panelPageLabelSp(b))
            assertTrue(
                "格内页码：$a → $b",
                ReaderMenuLayout.previewPageLabelSp(a) <= ReaderMenuLayout.previewPageLabelSp(b),
            )
        }
    }

    @Test
    fun `页码行高比例不小于一 放大后的数字不被压`() {
        assertTrue(
            "行高比例 ${ReaderMenuLayout.PANEL_TEXT_LINE_HEIGHT_RATIO} 必须 ≥ 1",
            ReaderMenuLayout.PANEL_TEXT_LINE_HEIGHT_RATIO >= 1f,
        )
        for (width in listOf(320f, 740f)) {
            val page = ReaderMenuLayout.panelPageLabelSp(width)
            val title = ReaderMenuLayout.panelTitleSp(width)
            val label = ReaderMenuLayout.previewPageLabelSp(width)
            assertTrue("页码行高必须 ≥ 字号", page * ReaderMenuLayout.PANEL_TEXT_LINE_HEIGHT_RATIO >= page)
            assertTrue("标题行高必须 ≥ 字号", title * ReaderMenuLayout.PANEL_TEXT_LINE_HEIGHT_RATIO >= title)
            assertTrue("格内页码行高必须 ≥ 字号", label * ReaderMenuLayout.PANEL_TEXT_LINE_HEIGHT_RATIO >= label)
        }
    }

    @Test
    fun `标题上方留白不超过 8dp 且为正`() {
        val top = ReaderMenuLayout.PANEL_TITLE_TOP_PADDING_DP
        assertTrue("面板顶边到标题行顶的留白 ${top}dp 必须 ≤ 8dp（票 #67 AC2）", top <= 8f)
        assertTrue("标题不得贴死面板顶边（留白为正）", top > 0f)
        assertTrue("必须比改动前的 16dp 小", top < 16f)
    }

    // ---------- 页位口径 ----------

    @Test
    fun `滑块值四舍五入到最近的页`() {
        assertEquals(150, ReaderMenuLayout.seekTargetPage(150.4f, pageCount = 200))
        assertEquals(151, ReaderMenuLayout.seekTargetPage(150.6f, pageCount = 200))
        assertEquals(0, ReaderMenuLayout.seekTargetPage(0f, pageCount = 200))
        assertEquals(199, ReaderMenuLayout.seekTargetPage(199f, pageCount = 200))
    }

    @Test
    fun `滑块值越界夹到首末页`() {
        assertEquals(0, ReaderMenuLayout.seekTargetPage(-12f, pageCount = 200))
        assertEquals(199, ReaderMenuLayout.seekTargetPage(500f, pageCount = 200))
    }

    @Test
    fun `三页书任意滑块值都落到某一页 没有死区`() {
        // 值域 0f..2f（3 页书的末页页位）：任意位置都四舍五入到最近的页，且三页都够得着
        val hits = mutableSetOf<Int>()
        var previous = -1
        for (step in 0..40) {
            val value = step / 20f // 0f..2f
            val page = ReaderMenuLayout.seekTargetPage(value, pageCount = 3)
            assertTrue("滑块值 $value 落到页 $page，必须在 0..2 内", page in 0..2)
            assertTrue("滑块值单调增时目标页不得回退", page >= previous)
            previous = page
            hits += page
        }
        assertEquals("三页都必须能被点到（不是只有最左/最中/最右三个点）", setOf(0, 1, 2), hits)
        // 每一页都覆盖一段**连续的值区间**（点击位置有容差，不必压在像素边界上）
        assertEquals("左段", 0, ReaderMenuLayout.seekTargetPage(0.4f, pageCount = 3))
        assertEquals("中段", 1, ReaderMenuLayout.seekTargetPage(0.6f, pageCount = 3))
        assertEquals("中段", 1, ReaderMenuLayout.seekTargetPage(1.4f, pageCount = 3))
        assertEquals("右段", 2, ReaderMenuLayout.seekTargetPage(1.6f, pageCount = 3))
    }

    @Test
    fun `三页书按下位置超出轨道两端仍夹到首末页`() {
        assertEquals(0, ReaderMenuLayout.seekTargetPage(-0.7f, pageCount = 3))
        assertEquals(2, ReaderMenuLayout.seekTargetPage(2.4f, pageCount = 3))
    }

    @Test
    fun `末页页位 单页书与空书都是 0`() {
        assertEquals(199, ReaderMenuLayout.lastPage(200))
        assertEquals(0, ReaderMenuLayout.lastPage(1))
        assertEquals(0, ReaderMenuLayout.lastPage(0))
        assertEquals(0, ReaderMenuLayout.seekTargetPage(1f, pageCount = 1))
    }

    @Test
    fun `页位夹取在 0 到末页之间`() {
        assertEquals(0, ReaderMenuLayout.clampPage(-3, pageCount = 10))
        assertEquals(9, ReaderMenuLayout.clampPage(99, pageCount = 10))
        assertEquals(5, ReaderMenuLayout.clampPage(5, pageCount = 10))
        assertEquals(0, ReaderMenuLayout.clampPage(3, pageCount = 0))
    }

    @Test
    fun `格上显示的页码是格位加一`() {
        assertEquals(1, ReaderMenuLayout.previewPageLabel(0))
        assertEquals(10, ReaderMenuLayout.previewPageLabel(9))
        for (cell in 0 until 20) {
            assertEquals("格上显示的页码", cell + 1, ReaderMenuLayout.previewPageLabel(cell))
        }
    }

    @Test
    fun `跳页目标与格上显示的页码一致`() {
        // 同一处换算：点击第 1 格跳到页位 0、显示「1」；末页同理
        assertEquals(1, ReaderMenuLayout.previewPageLabel(ReaderMenuLayout.seekTargetPage(0f, pageCount = 200)))
        assertEquals(200, ReaderMenuLayout.previewPageLabel(ReaderMenuLayout.seekTargetPage(199f, pageCount = 200)))
        assertEquals(
            "三页书点最右",
            3,
            ReaderMenuLayout.previewPageLabel(ReaderMenuLayout.seekTargetPage(1.6f, pageCount = 3)),
        )
    }

    @Test
    fun `预览条间隙与原实现同量级`() {
        assertTrue("间隙必须为正（否则格子连成一片）", ReaderMenuLayout.PREVIEW_GAP_DP > 0f)
        assertTrue("间隙不得大于格宽的量级", ReaderMenuLayout.PREVIEW_GAP_DP <= 16f)
    }
}
