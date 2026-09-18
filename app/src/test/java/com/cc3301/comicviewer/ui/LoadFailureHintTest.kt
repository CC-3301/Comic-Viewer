package com.cc3301.comicviewer.ui

import com.cc3301.comicviewer.core.source.SourceType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 列表与柜页的失败提示（票 11 浏览列表 + 票 31 柜内同款）：
 * 本地是授权失效、网络来源是连接或认证问题，两种措辞不能混用——用户据此决定是重新授权还是检查网络。
 * 柜页连接离线时显示的就是这条提示（票 31 决策 7：内联失败 + 重试，不用 Toast）。
 *
 * 本地那条（票 #40）还要求「可执行」：界面上的修法是删掉这条连接再重新授权文件夹，
 * 因此提示里必须出现「删除」这一动作，而不只是含糊的「请重新添加」。
 */
class LoadFailureHintTest {

    @Test
    fun `本地提示授权失效 其余来源统一提示检查网络或服务器`() {
        assertEquals(
            "授权可能已失效，请在首页「本地」删除这条连接后重新添加文件夹",
            loadFailureHint(SourceType.LOCAL),
        )
        // 遍历枚举（而不是硬编码四种来源）：将来新增来源时必须表态它属于哪一类措辞
        val networkSources = SourceType.entries.filter { it != SourceType.LOCAL }
        assertTrue("除本地外的来源都必须给网络类措辞", networkSources.isNotEmpty())
        networkSources.forEach { type ->
            assertEquals("检查网络或服务器后重试", loadFailureHint(type))
        }
        assertNotEquals(
            "本地与网络来源的提示不得混用",
            loadFailureHint(SourceType.LOCAL),
            loadFailureHint(networkSources.first()),
        )
    }

    @Test
    fun `本地提示含删除这一动作 用户能照着操作`() {
        val hint = loadFailureHint(SourceType.LOCAL)
        // 本地根列表自票 #40 起每行有「删除」入口；提示里的动作必须与界面入口同名，用户才找得到
        assertTrue("提示要给出「删除」这个动作：" + hint, hint.contains("删除"))
        assertTrue("删除之后还要指导重新添加文件夹：" + hint, hint.contains("重新添加文件夹"))
        assertTrue("提示要说清去哪一页删（首页「本地」）：" + hint, hint.contains("本地"))
    }
}
