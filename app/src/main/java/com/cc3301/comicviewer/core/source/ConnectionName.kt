package com.cc3301.comicviewer.core.source

/**
 * 连接名（票 #72）：用户可配置的展示名，落 `connections.displayName` 列——书柜柜名、浏览页根层标题、
 * 连接列表与错误提示都读那一列（唯一消费点）。
 *
 * 形态规则只有这一处实现：
 *  - [sanitizeConnectionName]：用户填写的名称 → 落库文本。去首尾空白，超长按
 *    [CONNECTION_NAME_MAX_LENGTH] 截断（顶栏的长名由票 #79 的单行省略负责，这里只挡住列表行与柜名
 *    被超长名撑破）。
 *  - [connectionDisplayName]：留空（或只有空白）时回落到来源自动拼出的名字（`主机[:端口]/路径`）；
 *    自动名也拿不到（地址为空、配置损坏）时回落到 [FALLBACK_CONNECTION_NAME]——任何情况下都不显示空白标题。
 *
 * 存量行按票 #38 的口径处理：不批量重算，用户编辑保存时才按本规则算一次（见 `SourceConnectionsScreen`）。
 */
const val CONNECTION_NAME_MAX_LENGTH: Int = 40

/**
 * 名称在 configJson 里的键（票 #72）：四个声明过的字面量收成这一处——三个网络来源配置类与表单字段键
 * （`ui/ConnectionForm.kt` 的 `CONNECTION_NAME_FIELD`）都引它，不另写 `"name"`。
 */
const val CONNECTION_NAME_KEY: String = "name"

/** 名称与自动拼名都拿不到时的兜底名（票 #72：不显示空白标题） */
const val FALLBACK_CONNECTION_NAME: String = "未知连接"

/** 用户填写的名称 → 落库文本（票 #72）：去首尾空白、超长截断 */
fun sanitizeConnectionName(written: String): String = truncateToLimit(written.trim())

/** 展示名（票 #72）：用户填的名称优先，留空回落到 [autoName]，两者都拿不到时用 [FALLBACK_CONNECTION_NAME] */
fun connectionDisplayName(written: String, autoName: String): String =
    sanitizeConnectionName(written)
        .ifEmpty { sanitizeConnectionName(autoName).ifEmpty { FALLBACK_CONNECTION_NAME } }

/**
 * 三个网络来源共用的展示名解析（票 #72 r2）：连接行的列名（运行期载体，存量行与列表恒等）优先，
 * 否则按 [connectionDisplayName] 的规则由用户配置的名称与自动拼名推导；列名意外为空时不接管。
 */
fun connectionDisplayNameFromRow(rowDisplayName: String?, written: String, autoName: String): String =
    rowDisplayName?.takeIf { it.isNotBlank() } ?: connectionDisplayName(written, autoName)

/** 按**码点数**截断：代理对（emoji 一类）不会被劈成半个字符 */
private fun truncateToLimit(text: String): String =
    if (text.codePointCount(0, text.length) <= CONNECTION_NAME_MAX_LENGTH) {
        text
    } else {
        text.substring(0, text.offsetByCodePoints(0, CONNECTION_NAME_MAX_LENGTH))
    }
