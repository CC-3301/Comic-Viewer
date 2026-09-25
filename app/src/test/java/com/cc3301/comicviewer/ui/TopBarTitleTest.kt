package com.cc3301.comicviewer.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 顶栏标题（票 #79）：各屏 `TopAppBar` 的 title 槽恒**单行**、放不下时**末尾省略**，任何长度的名字
 * 都不改变顶栏高度。
 *
 * 怎么测的：本仓库没有 compose-ui-test（`androidTest` 只有一条冒烟用例），但有 Robolectric
 * ——走 `ui` 包共用的组合测量脚手架（票 #115 起 [composeViewInActivity] + [layoutOnce]，与
 * ReaderMenuTitleTest / ReaderOverlayInsetsTest 同源）：把 [TopBarTitle] 放进**定宽**盒子里
 * 组合、测量、布局，读它**放置后的真实高度**（`onGloballyPositioned`）。**只量高度**：本类不读生产代码
 * 交给排版引擎的 `maxLines` / `overflow`——那两项要反射读 compose-ui 的内部状态（`AndroidComposeView`
 * 在 compose-ui 里是 internal、无公开入口），是全仓唯一一处反射，票 #118 按维护者裁决删掉（只测外部行为）。
 *
 * 行数上限的判据是**显式换行**的合成名字：Robolectric 的文本测量**不按宽度换行**
 * （[EntryNameTextTest] 的同一句说明），所以「长名字实际折了几行」在单测里量不出来；能造出来的是
 * 「文本自己就要占三行」——行数上限若不在，标题高度会跟着顶高。
 *
 * 判别力（票 #118 两次实测）：把 [TopBarTitle] 的 `maxLines = 1` 拿掉（改回 `Text` 的默认值），
 * `文字占三行的名字也只占一行高度` 变红（`expected:<35> but was:<105>`，即一行名高 → 三行名高）；
 * `overflow` 从 `Ellipsis` 改成 `Clip` 时**本类两条用例都还是绿的**——Robolectric 不按宽度换行
 * ⇒ 不会触发省略，高度上量不出差别，末尾省略号的最后一关只能靠真机目视（票 #118 残余风险）。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class TopBarTitleTest {

    /** 短名字（连接名通常就是这个量级：`サンプル接続`） */
    private val shortName = "サンプル接続"

    /**
     * 中等长度（24 字符）：只是「放得下的长标题」样本——本用例的自变量是长度，
     * 与连接名口径无关（票 #72 已把默认名的 scheme 去掉，这两个样本值保留原样不影响本测试）。
     */
    private val mediumName = "http://10.10.10.201:25600"

    /** 60+ 字符（75 字符）：长路径形状的标题 */
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
     */
    private fun placeTitle(name: String): Int {
        var height = -1
        val view = composeViewInActivity {
            Box(Modifier.width(titleBoxWidth)) {
                TopBarTitle(name, Modifier.onGloballyPositioned { height = it.size.height })
            }
        }
        view.layoutOnce(widthPx = 1000)
        assertTrue("标题没被放置（测量没生效），本次断言无意义", height > 0)
        return height
    }

    @Test
    fun `三档长度的顶栏标题在同一宽度下同高`() {
        val heights = listOf(shortName, mediumName, longName).map { placeTitle(it) }

        // 高度在本环境里锁的是「不随名字长度变化」这一条不变式：Robolectric 不按宽度换行，
        // 行数上限的判别力由下面「文字占三行」那条承担（见类注释）。
        assertEquals("短 / 中等 / 60+ 字符三档在同一宽度下同高，顶栏高度不随名字长度变化", 1, heights.distinct().size)
    }

    @Test
    fun `文字占三行的名字也只占一行高度`() {
        val oneLineNameHeight = placeTitle(shortName)
        val wrappedNameHeight = placeTitle(wrappedName)

        assertEquals(
            "行数上限若失效，占三行的名字会把标题顶成三倍高",
            oneLineNameHeight,
            wrappedNameHeight,
        )
    }
}
