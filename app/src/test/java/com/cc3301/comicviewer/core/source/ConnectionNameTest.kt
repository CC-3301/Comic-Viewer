package com.cc3301.comicviewer.core.source

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 连接名的形态规则（票 #72）：名称去首尾空白 + 超长截断，留空回落到来源自动拼名，
 * 自动名也拿不到时回落兜底名。三个网络来源表单、本地重命名与错误提示都走同一份实现
 * （[connectionDisplayName] / [sanitizeConnectionName]），这里只锁这两个纯函数。
 */
class ConnectionNameTest {

    @Test
    fun `留空或只有空白时回落到自动拼名`() {
        assertEquals("192.168.1.10/1/2/3", connectionDisplayName("", "192.168.1.10/1/2/3"))
        assertEquals("192.168.1.10/1/2/3", connectionDisplayName("   ", "192.168.1.10/1/2/3"))
        assertEquals("192.168.1.10/1/2/3", connectionDisplayName("\t\n", "192.168.1.10/1/2/3"))
    }

    @Test
    fun `填了名称就用它 首尾空白去掉`() {
        assertEquals("我家 NAS", connectionDisplayName("  我家 NAS  ", "192.168.1.10/1/2/3"))
    }

    @Test
    fun `超长名称截断到上限 且不劈开代理对`() {
        val long = "名".repeat(CONNECTION_NAME_MAX_LENGTH + 5)

        assertEquals(CONNECTION_NAME_MAX_LENGTH, connectionDisplayName(long, "auto").length)
        // emoji 是代理对：按码点数截断，末位不会留下半个字符
        val emoji = "🙂".repeat(CONNECTION_NAME_MAX_LENGTH + 1)
        assertEquals("🙂".repeat(CONNECTION_NAME_MAX_LENGTH), connectionDisplayName(emoji, "auto"))
        // 自动拼名同样受上限约束（长 DAV 路径不得把列表行与柜名撑破）
        assertEquals(CONNECTION_NAME_MAX_LENGTH, connectionDisplayName("", "长".repeat(CONNECTION_NAME_MAX_LENGTH + 5)).length)
    }

    @Test
    fun `自动拼名也拿不到时回落到兜底名 不显示空白标题`() {
        assertEquals("未知连接", connectionDisplayName("", ""))
        assertEquals("未知连接", connectionDisplayName("   ", "   "))
        assertEquals(FALLBACK_CONNECTION_NAME, "未知连接")
    }

    @Test
    fun `sanitizeConnectionName 给出落库那一份 去空白与截断`() {
        assertEquals("我家 NAS", sanitizeConnectionName("  我家 NAS "))
        assertEquals("", sanitizeConnectionName("   "))
        assertEquals(CONNECTION_NAME_MAX_LENGTH, sanitizeConnectionName("a".repeat(CONNECTION_NAME_MAX_LENGTH + 20)).length)
    }
}
