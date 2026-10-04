package com.cc3301.comicviewer.core.order

import java.nio.charset.Charset
import java.nio.charset.CharsetEncoder
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * GB2312 表外字判定的口径自检：[isOutOfTableHan] 认的表外字就是 GB2312 编不出的汉字。
 *
 * 历史上本类还住着一条读 `references/name-order-expected.txt` 的本地探针用例
 * （真实排序结果与本实现逐条比对，文件不在即 skip）；该用例已删，文件与相关辅助随之移除，
 * 本类只剩这条不依赖本地文件、干净检出下照常跑的口径用例。
 */
class WindowsNameOrderLocalProbeTest {

    @Test
    fun `GB2312 表外字判定与分类口径一致`() {
        // 题面点名的表外字（許 U+8A31 / 嬢 U+5B22 / 獣 U+7363）＋位移条目里的 師 U+5E2B
        assertTrue("許 应判为 GB2312 表外汉字", isOutOfTableHan('許'.code))
        assertTrue("嬢 应判为 GB2312 表外汉字", isOutOfTableHan('嬢'.code))
        assertTrue("獣 应判为 GB2312 表外汉字", isOutOfTableHan('獣'.code))
        assertTrue("師 应判为 GB2312 表外汉字", isOutOfTableHan('師'.code))
        // 表内字不得被误分类
        assertTrue("阿 是 GB2312 表内字", !isOutOfTableHan('阿'.code))
        assertTrue("叩 是 GB2312 表内字", !isOutOfTableHan('叩'.code))
        assertTrue("猫 是 GB2312 表内字", !isOutOfTableHan('猫'.code))
        // 非汉字（长音符 ー、字母、符号）不落在汉字段分类里
        assertTrue("ー 不是汉字", !isOutOfTableHan('ー'.code))
        assertTrue("A 不是汉字", !isOutOfTableHan('A'.code))
        assertTrue("- 不是汉字", !isOutOfTableHan('-'.code))
    }
}

/**
 * GB2312 编不出的汉字（[Character.isIdeographic] 排掉假名与长音符等非汉字字母）。
 * `internal` 而非 `private`：排序「表外字 / 其余」的分类切分靠它，
 * 该口径由 `WindowsNameOrderLocalProbeTest.GB2312 表外字判定与分类口径一致` 直接断言（仓库既有接缝惯例）。
 */
internal fun isOutOfTableHan(codePoint: Int): Boolean =
    Character.isIdeographic(codePoint) && !gb2312Encoder.canEncode(String(Character.toChars(codePoint)))

/** 单测单线程，编码器复用即可 */
private val gb2312Encoder: CharsetEncoder = Charset.forName("GB2312").newEncoder()
