package com.cc3301.comicviewer.core.order

import java.io.File
import java.nio.charset.Charset
import java.nio.charset.CharsetEncoder
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * 本地探针（票 #83）：把维护者导出的 Windows 资源管理器真实排序结果
 * （`references/name-order-expected.txt`，每行一个名字，行序即期望顺序）与本实现逐条比对，
 * 差异表打到 stdout（落 `build/test-results/.../TEST-*.xml` 的 system-out）。
 *
 * 真实书库数据只经这条读入路径进来，仓库里不留任何真实书名。
 * 文件不存在（干净检出 / CI）时**数据类测试** skipped，不影响全量单测；
 * `GB2312 表外字判定与分类口径一致` 不读文件，照常跑。
 * Gradle 单测的工作目录是模块目录 `app/`，所以探针路径从 `../references/` 起算。
 *
 * 断言口径＝**棘轮基线**（票 #83 裁决）：位次不一致条数与反序对数各有一个实测基线常量，
 * 只拦「比基线更差」。基线数值随本地样本文件与比较器一起失效——样本换文件或比较器改动后，
 * 先重跑探针看新数值，再重新标定常量（不要放宽，只按实测改）。
 * 题面点名的表外字差异属已登记的已知限制（SPEC「已知限制（票 #83）」），
 * 因此 [isOutOfTableHanDifference] 只用来给报告里的反序对**分类**，不参与断言门槛——
 * 门槛是总数，任何一类变差都会顶破基线。
 */
class WindowsNameOrderLocalProbeTest {

    private val cmp = WindowsNameOrder.COMPARATOR

    @Test
    fun `本地库期望序与本实现逐条比对`() {
        val expected = readExpectedOrderOrSkip()
        val actual = expected.sortedWith(cmp)
        val mismatchedPositions = expected.indices.filter { expected[it] != actual[it] }
        val pairs = pairsInExpectedOrder(expected)
        val reversed = pairs.filter { (first, second) -> cmp.compare(first, second) > 0 }
        val excused = pairs.count { (first, second) -> isOutOfTableHanDifference(first, second) }
        val unexplained = reversed.filterNot { (first, second) -> isOutOfTableHanDifference(first, second) }

        val summary = summaryReport(
            expected = expected,
            actual = actual,
            mismatchedPositions = mismatchedPositions,
            reversedCount = reversed.size,
            checkedPairCount = pairs.size - excused,
            excusedPairCount = excused,
        )
        val reversedDiff = reversedReport(reversed, unexplained)
        println(summary)
        println(reversedDiff)

        assertTrue(
            "本地探针：位次不一致 ${mismatchedPositions.size} 条，超过基线 $EXPECTED_MISMATCH_BASELINE 条" +
                "（基线来源见常量注释；样本文件或比较器改动后需重新标定）\n$summary",
            mismatchedPositions.size <= EXPECTED_MISMATCH_BASELINE,
        )
        assertTrue(
            "本地探针：反序对 ${reversed.size} 对，超过基线 $EXPECTED_REVERSED_PAIR_BASELINE 对" +
                "（基线来源见常量注释；样本文件或比较器改动后需重新标定）\n$reversedDiff",
            reversed.size <= EXPECTED_REVERSED_PAIR_BASELINE,
        )
    }

    /**
     * 探针前提自检：分类用的 [isOutOfTableHanDifference] 认的表外字就是题面那类
     * GB2312 编不出的汉字；这条不读本地文件，干净检出下也跑。
     *
     * 直接断言 [isOutOfTableHan]（而不是只走公共的 compare）：这条分类闸门决定报告里
     * 「表外字 / 其余」的切分，而那份报告要贴进票面评论，闸门本身得单独钉住；
     * 它是本类之外唯一的口径接缝，故按仓库惯例（如 `GridLayout.gridCellWidth`）声明为 `internal`。
     */
    @Test
    fun `GB2312 表外字判定与分类口径一致`() {
        // 题面点名的表外字（許 U+8A31 / 嬢 U+5B22 / 獣 U+7363）＋实测位移条目里的 師 U+5E2B
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

    /** 读期望顺序；文件不在或为空则 skip（AssumptionViolatedException → JUnit skipped） */
    private fun readExpectedOrderOrSkip(): List<String> {
        assumeTrue("本地探针：$PROBE_PATH 不存在，跳过", probeFile.isFile)
        val names = probeFile.readLines().filter { it.isNotBlank() }
        assumeTrue("本地探针：$PROBE_PATH 无有效行，跳过", names.isNotEmpty())
        return names
    }

    /** 期望序里全部「前者应在前」的对 */
    private fun pairsInExpectedOrder(expected: List<String>): List<Pair<String, String>> = buildList {
        for (i in expected.indices) {
            for (j in i + 1 until expected.size) add(expected[i] to expected[j])
        }
    }

    /**
     * 反序对分类（只影响报告文案，不影响棘轮门槛）：两个名字**首个不同的码点**若是不在 GB2312
     * 的汉字，这一对归入 SPEC 已登记的「已知限制（票 #83）」；其余归入「其它差异」，
     * 是段边界 / 假名浊音 / 符号段空白 / 表内字字表差异那几类。
     * 是否在 GB2312 用 JDK 自带 charset 判定（不自造汉字权重表）。
     */
    private fun isOutOfTableHanDifference(first: String, second: String): Boolean {
        val a = first.codePoints().toArray()
        val b = second.codePoints().toArray()
        for (i in 0 until minOf(a.size, b.size)) {
            if (a[i] != b[i]) return isOutOfTableHan(a[i]) || isOutOfTableHan(b[i])
        }
        return false
    }

    private fun summaryReport(
        expected: List<String>,
        actual: List<String>,
        mismatchedPositions: List<Int>,
        reversedCount: Int,
        checkedPairCount: Int,
        excusedPairCount: Int,
    ): String = buildString {
        appendLine("=== 票 #83 本地探针：$PROBE_PATH ===")
        appendLine(
            "期望条数：${expected.size}　位次不一致条数：${mismatchedPositions.size}" +
                "（基线 $EXPECTED_MISMATCH_BASELINE）",
        )
        appendLine(
            "反序对数：$reversedCount（基线 $EXPECTED_REVERSED_PAIR_BASELINE）　" +
                "非表外字对：$checkedPairCount　表外字对：$excusedPairCount",
        )
        appendLine("--- 首 $MAX_REPORTED 处位次差异（位次 1-based）---")
        mismatchedPositions.take(MAX_REPORTED).forEach { i ->
            appendLine("#${i + 1} 期望：${expected[i]}")
            appendLine("#${i + 1} 实际：${actual[i]}")
        }
        appendLine("--- 位次位移（期望位次 -> 实际位次）---")
        val positionInActual = actual.withIndex().associate { (index, name) -> name to index }
        expected.withIndex()
            .map { (index, name) -> Triple(name, index, positionInActual.getValue(name)) }
            .filter { (_, expectedIndex, actualIndex) -> expectedIndex != actualIndex }
            .take(MAX_REPORTED)
            .forEach { (name, expectedIndex, actualIndex) ->
                appendLine("#${expectedIndex + 1} -> #${actualIndex + 1}　$name")
            }
    }

    private fun reversedReport(
        reversed: List<Pair<String, String>>,
        unexplained: List<Pair<String, String>>,
    ): String = buildString {
        appendLine("=== 票 #83 本地探针：反序对（期望前者在前，本实现相反）共 ${reversed.size} 对 ===")
        reversed.take(MAX_REPORTED).forEach { (first, second) ->
            appendLine("期望在前：$first")
            appendLine("实际在前：$second")
        }
        appendLine("--- 非「首个差异码点是 GB2312 表外汉字」的反序对共 ${unexplained.size} 对 ---")
        unexplained.take(MAX_REPORTED).forEach { (first, second) ->
            appendLine("期望在前：$first")
            appendLine("实际在前：$second")
        }
    }

    private companion object {
        /** 模块目录（`app/`）即工作目录 */
        const val PROBE_PATH = "../references/name-order-expected.txt"
        const val MAX_REPORTED = 20
        val probeFile = File(PROBE_PATH)

        /**
         * 棘轮基线：维护者本地 145 条 Windows 导出顺序，2026-09-19 实测（WindowsNameOrder 未改动的
         * 提交 `feat(#71)` 之后）。本地样本文件换掉或比较器改动后重跑探针重新标定。
         */
        const val EXPECTED_MISMATCH_BASELINE = 96
        const val EXPECTED_REVERSED_PAIR_BASELINE = 556
    }
}

/**
 * GB2312 编不出的汉字（[Character.isIdeographic] 排掉假名与长音符等非汉字字母）。
 * `internal` 而非 `private`：探针报告里「表外字 / 其余」的分类切分靠它，
 * 该口径由 `WindowsNameOrderLocalProbeTest.GB2312 表外字判定与分类口径一致` 直接断言（仓库既有接缝惯例）。
 */
internal fun isOutOfTableHan(codePoint: Int): Boolean =
    Character.isIdeographic(codePoint) && !gb2312Encoder.canEncode(String(Character.toChars(codePoint)))

/** 单测单线程，编码器复用即可 */
private val gb2312Encoder: CharsetEncoder = Charset.forName("GB2312").newEncoder()
