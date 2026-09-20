package com.cc3301.comicviewer.ui

import com.cc3301.comicviewer.core.source.SourceReadTimeoutException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 阅读器打开失败的界面文案（票 #97 接缝 = [readerOpenErrorMessage]）。
 *
 * 「不是一本书：<本机绝对路径>」这类异常文本带实现细节：直接展示会让用户看到自己的磁盘路径和一句没有行动含义的话。
 * 本文件钉住「这类失败一律换成中文提示」，以及「票 #91 的超时提示（设计成可直接展示）原样透传、不被这次收口改掉」。
 */
class ReaderOpenErrorTest {

    @Test
    fun `不是一本书：不显示异常原文与本机绝对路径`() {
        val raw = "不是一本书：C:\\Users\\Administrator\\Comics\\A"

        val shown = readerOpenErrorMessage(IllegalArgumentException(raw))

        assertFalse("绝对路径绝不能出现在界面上：$shown", shown.contains("C:\\"))
        assertFalse("异常原文也不出现：$shown", shown.contains("不是一本书"))
        assertTrue("给中文提示 + 下一步：$shown", shown.contains("返回上一页"))
    }

    @Test
    fun `越界或已删除的引用同样不显示引用本身`() {
        val shown = readerOpenErrorMessage(IllegalArgumentException("无效或越界引用：content://com.android.externalstorage.documents/tree/x"))

        assertFalse("引用原文不出现：$shown", shown.contains("content://"))
    }

    @Test
    fun `超时失败沿用 #91 的中文提示（可直接展示）`() {
        val timeout = SourceReadTimeoutException("打开超时（60 秒）：root/A；请检查网络/服务器后重试")

        assertEquals(timeout.message, readerOpenErrorMessage(timeout))
    }

    @Test
    fun `没有 message 的失败退回打开失败`() {
        assertEquals("打开失败", readerOpenErrorMessage(RuntimeException()))
    }
}
