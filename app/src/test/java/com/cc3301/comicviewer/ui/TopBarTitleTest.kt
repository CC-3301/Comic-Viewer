package com.cc3301.comicviewer.ui

import android.os.Looper
import android.view.View
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.ui.InternalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsOwner
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * 顶栏标题（票 #79）：各屏 `TopAppBar` 的 title 槽恒**单行**、放不下时**末尾省略**，任何长度的名字
 * 都不改变顶栏高度。
 *
 * 怎么测的：本仓库没有 compose-ui-test（`androidTest` 只有一条冒烟用例），但有 Robolectric
 * （同 [EntryNameTextTest] 的路子）——起一个 [ComponentActivity]，把 [TopBarTitle] 放进**定宽**盒子里
 * 组合、测量、布局，然后：
 * 1. **读产品代码真的传给了 Text 什么**：从真实组合出来的 `Text` 的排版结果里取
 *    `layoutInput.maxLines` / `layoutInput.overflow` / `lineCount`。Robolectric 的文本测量**不按宽度
 *    换行**（[EntryNameTextTest] 的同一句说明），所以「长名字折了几行」在单测里量不出来；能量的是
 *    **行数上限与省略口径有没有真的接上**——改动前的写法（没有 `maxLines`、没有 `overflow`）实测是
 *    `maxLines = Int.MAX_VALUE` + `overflow = Clip`（见 `.pi-implement/79/evidence-impl.md`）。
 * 2. **量放置后的真实高度**：用**显式换行**的合成名字构造「文字占三行」的情形，行数上限若不在，
 *    标题高度会跟着顶高；三档长度的名字放在同一宽度下高度也必须相等。
 *
 * 判别力：把 `maxLines` / `overflow` 从 [TopBarTitle] 拿掉，本类两条用例一起变红——实测
 * `maxLines = 2147483647`、`overflow = Clip`，且显式换行的名字量到 3 行高（35 → 105）。
 */
@OptIn(InternalComposeUiApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class TopBarTitleTest {

    /** 短名字（连接名通常就是这个量级：`サンプル接続`） */
    private val shortName = "サンプル接続"

    /** 中等长度：默认连接名的形状（`scheme://主机:端口`，票面给出的合成地址） */
    private val mediumName = "http://10.10.10.201:25600"

    /** 60+ 字符（75 字符）：长路径形状的连接名 */
    private val longName =
        "http://10.10.10.201:25600/sample-library/very-long-path-segment-0123456789"

    /**
     * 合成「文字占三行」的名字：Robolectric 不按宽度换行，因此用显式换行构造一个确定占三行的文本
     * ——本用例的自变量是「文字要占几行」，不是真实连接名的折行结果（真实折行由真机验收把最后一关）。
     */
    private val wrappedName = "第一行\n第二行\n第三行"

    /** 定宽盒子：远窄于任何一档名字的排版宽度，放不下这件事因此必然成立 */
    private val titleBoxWidth = 120.dp

    /**
     * 组合 [TopBarTitle]（定宽盒子内）→ 测量 → 布局，返回放置后的真实高度 px。
     * [onLayoutResult] 拿到的是本次组合里那个 `Text` 的排版结果（经语义树的 `GetTextLayoutResult`）。
     */
    private fun placeTitle(name: String, onLayoutResult: (TextLayoutResult) -> Unit = {}): Int {
        var height = -1
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup().get()
        val view = ComposeView(activity)
        activity.setContentView(view)
        view.setContent {
            Box(Modifier.width(titleBoxWidth)) {
                TopBarTitle(name, Modifier.onGloballyPositioned { height = it.size.height })
            }
        }
        view.measure(
            View.MeasureSpec.makeMeasureSpec(1000, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
        )
        view.layout(0, 0, view.measuredWidth, view.measuredHeight)
        shadowOf(Looper.getMainLooper()).idle()

        val results = textLayoutResults(view)
        assertEquals("本次组合里应当恰好有一个标题文本的排版结果", 1, results.size)
        results.forEach(onLayoutResult)
        assertTrue("标题没被放置（测量没生效），本次断言无意义", height > 0)
        return height
    }

    @Test
    fun `三档长度的顶栏标题都是单行末尾省略 且同高`() {
        val heights = mutableListOf<Int>()
        listOf(shortName, mediumName, longName).forEach { name ->
            var layout: TextLayoutResult? = null
            heights += placeTitle(name) { layout = it }
            val result = requireNotNull(layout) { "没拿到「$name」的排版结果" }

            assertEquals("顶栏标题恒占一行（「$name」）", 1, result.layoutInput.maxLines)
            assertEquals(
                "放不下时末尾省略（「$name」）",
                TextOverflow.Ellipsis,
                result.layoutInput.overflow,
            )
            assertEquals("排版结果本身就是一行（「$name」）", 1, result.lineCount)
        }

        // 高度这一条在本环境里不是折行的判据（Robolectric 不按宽度换行，见类注释），
        // 折行的判别由下面「文字占三行」那条用显式换行承担；这里锁的是「三档长度同高」本身。
        assertEquals("短 / 中等 / 60+ 字符三档在同一宽度下同高，顶栏高度不随名字长度变化", 1, heights.distinct().size)
    }

    @Test
    fun `文字占三行的名字也只占一行高度`() {
        val oneLineNameHeight = placeTitle(shortName)
        val wrappedNameHeight = placeTitle(wrappedName)
        var wrappedLayout: TextLayoutResult? = null
        placeTitle(wrappedName) { wrappedLayout = it }

        assertEquals(
            "行数上限若失效，占三行的名字会把标题顶成三倍高",
            oneLineNameHeight,
            wrappedNameHeight,
        )
        assertEquals(
            "占三行的名字在排版上也必须只留一行",
            1,
            requireNotNull(wrappedLayout).lineCount,
        )
    }

    /**
     * 本次组合里所有文本的排版结果：从 [ComposeView] 的 `AndroidComposeView` 拿到 `SemanticsOwner`、
     * 走语义树取 `SemanticsActions.GetTextLayoutResult`。
     *
     * 取 `SemanticsOwner` 这一跳只能反射：`AndroidComposeView` 在 compose-ui 里是 internal、没有公开入口
     * （`RootForTest` 同样是内部接口），compose-ui-test 内部也是这么取的。
     */
    private fun textLayoutResults(view: ComposeView): List<TextLayoutResult> {
        val root = (0 until view.childCount).map { view.getChildAt(it) }.first()
        val getter = root.javaClass.getMethod("getSemanticsOwner").apply { isAccessible = true }
        val owner = getter.invoke(root) as SemanticsOwner

        val results = mutableListOf<TextLayoutResult>()
        val pending = ArrayDeque<SemanticsNode>()
        pending.add(owner.rootSemanticsNode)
        while (pending.isNotEmpty()) {
            val node = pending.removeFirst()
            if (node.config.contains(SemanticsActions.GetTextLayoutResult)) {
                node.config[SemanticsActions.GetTextLayoutResult].action?.invoke(results)
            }
            pending.addAll(node.children)
        }
        return results
    }
}
