package com.cc3301.comicviewer.core.source.komga

import com.cc3301.comicviewer.core.source.StoredCredential
import com.cc3301.comicviewer.core.source.connectionDisplayName
import com.cc3301.comicviewer.core.source.remote.endpointParts
import org.json.JSONObject
import java.net.URI

/**
 * Komga 连接配置（票 13）：连接 CRUD 的持久化载体，存进 Room 的 connections.configJson。
 *
 * 认证二选一：`apiKey`（请求头 `X-API-Key`，推荐）或 邮箱 + 密码（Basic 认证）。
 * 与 SMB/WebDAV 一样，两个敏感字段自票 #27 起经存储层加密（Android Keystore + AES-GCM，
 * [StoredCredential]）后才落库（票面把 Komga 列为本票的评估项：同一列同一套封装，
 * 凭据同样不得落明文）；旧库明文读路径照旧认，v4 → v5 迁移用 [protectSecrets] 改成密文。
 */
data class KomgaConnectionConfig(
    val baseUrl: String,
    val username: String = "",
    val password: String = "",
    val apiKey: String = "",
    /**
     * 用户配置的连接名（票 #72；configJson 的 `name` 键，**非敏感**）：空 = 用自动拼名。
     * 存量行没有这个键，解出来即空 → 编辑保存时按新口径重算（不批量重算，见 `docs/SPEC.md`）。
     */
    val name: String = "",
    /**
     * API Key / 密码的密文解不出来（票 #27：换机 / 密钥失效 / 密文损坏）：两个字段按空处理，
     * 进连接前提示「重新填写凭据」，编辑框里能重填；地址等其余字段照旧可用。
     */
    val credentialsNeedReentry: Boolean = false,
    /**
     * 连接行 `displayName` 列的名字（票 #72 r2）：**运行期载体，不进 configJson**——报错文案与列表
     * 因此恒等（票 #72 r2 前，错误文案是重算值：存量行重存前列里是旧口径，提示却是新口径）。
     * 列名意外为空时不接管（兜底名规则不会被它带出空白标题）。
     */
    val rowDisplayName: String? = null,
) {
    /** 自动拼名（票 #72）：`主机[:端口][/路径]`，**不带 scheme**（默认名按维护者口径一律去掉 scheme） */
    private val autoName: String get() = endpointParts(baseUrl).hostAndPath

    /**
     * 列表展示名（票 #72）：连接行列名（运行期载体，存量行与列表恒等）优先，否则用户配置的 [name]，
     * 留空回落到 [autoName]（规则见 [connectionDisplayName]）。
     */
    val displayName: String get() =
        rowDisplayName?.takeIf { it.isNotBlank() } ?: connectionDisplayName(name, autoName)

    /** 使用 API Key 还是 Basic 认证 */
    val usesApiKey: Boolean get() = apiKey.isNotBlank()

    /** 落库文本：API Key 与密码经 [StoredCredential.protect] 加密（票 #27），其余字段原样；加密失败抛出，绝不落明文 */
    fun toJson(): String = JSONObject()
        .put(KEY_BASE_URL, baseUrl)
        .put(KEY_USERNAME, username)
        .put(KEY_PASSWORD, StoredCredential.protect(password))
        .put(KEY_API_KEY, StoredCredential.protect(apiKey))
        .put(KEY_NAME, name)
        .toString()

    companion object {
        private const val KEY_BASE_URL = "baseUrl"
        private const val KEY_USERNAME = "username"
        private const val KEY_PASSWORD = "password"
        private const val KEY_API_KEY = "apiKey"

        /** 连接名（票 #72）：非敏感，明文落库（与 [StoredCredential] 保护的凭据字段不同） */
        private const val KEY_NAME = "name"

        /** 解析失败或必填字段缺失返回 null（配置损坏时由 UI 提示，不崩溃） */
        fun fromJson(json: String): KomgaConnectionConfig? = try {
            val obj = JSONObject(json)
            val baseUrl = obj.optString(KEY_BASE_URL, "")
            if (baseUrl.isEmpty()) {
                null
            } else {
                // 旧库的明文与票 #27 之后的密文都认；密文解不出来（换机/密钥失效）时为 null
                val password = StoredCredential.reveal(obj.optString(KEY_PASSWORD, ""))
                val apiKey = StoredCredential.reveal(obj.optString(KEY_API_KEY, ""))
                KomgaConnectionConfig(
                    baseUrl = baseUrl,
                    username = obj.optString(KEY_USERNAME, ""),
                    password = password.orEmpty(),
                    apiKey = apiKey.orEmpty(),
                    name = obj.optString(KEY_NAME, ""),
                    credentialsNeedReentry = password == null || apiKey == null,
                )
            }
        } catch (t: Throwable) {
            null
        }

        /**
         * 存量迁移（票 #27，v4 → v5 用）：configJson 里的 API Key 与密码改写成密文。
         * 幂等（已是密文/空值不动）；解不出 JSON 或加密不可用时返回 null，由调用方保留原行。
         */
        fun protectSecrets(json: String): String? =
            StoredCredential.protectSecrets(json, listOf(KEY_PASSWORD, KEY_API_KEY))

        /** 校验：合法返回 null，否则返回中文错误提示 */
        fun validate(config: KomgaConnectionConfig): String? = when {
            config.baseUrl.isBlank() -> "请填写服务器地址"
            !config.baseUrl.trim().startsWith("http://") && !config.baseUrl.trim().startsWith("https://") ->
                "地址要以 http:// 或 https:// 开头"
            runCatching { URI(config.baseUrl.trim()) }.getOrNull()?.host.isNullOrEmpty() -> "地址不合法，请检查主机名"
            // 凭据二选一：API Key 或 邮箱+密码；两者都没有时 Komga 会返回 401
            !config.usesApiKey && (config.username.isBlank() || config.password.isBlank()) ->
                "请填写 API Key，或同时填写邮箱与密码"
            else -> null
        }
    }
}
