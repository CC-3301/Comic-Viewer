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
 */
class LoadFailureHintTest {

    @Test
    fun `本地提示授权失效 其余来源统一提示检查网络或服务器`() {
        assertEquals("授权可能已失效，请重新添加", loadFailureHint(SourceType.LOCAL))
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
}
