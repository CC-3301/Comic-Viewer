package com.cc3301.comicviewer.core.source

import com.cc3301.comicviewer.core.source.komga.KomgaConnectionConfig
import com.cc3301.comicviewer.core.source.smb.SmbConnectionConfig
import com.cc3301.comicviewer.core.source.webdav.WebDavConnectionConfig
import org.json.JSONObject

/**
 * 凭据加解密实现（票 #27）：生产是 Android Keystore（[KeystoreCredentialCipher]）；
 * 单测的 JVM 里没有 `AndroidKeyStore`，用内存实现顶上（`app/src/test` 的 `InMemoryCredentialCipher`）。
 */
internal interface CredentialCipher {
    /** 明文 → 密文（不含 [StoredCredential.ENCRYPTED_PREFIX]）；失败抛出，**绝不回退成明文** */
    fun encrypt(plaintext: String): String

    /** 密文（不含前缀）→ 明文；解不出来返回 null（换机 / 密钥失效 / 密文损坏） */
    fun decrypt(ciphertext: String): String?
}

/** 加密失败（票 #27）：message 直接给用户看；加密失败时一律不落库，不回退成明文 */
internal class CredentialEncryptionException(message: String, cause: Throwable? = null) :
    IllegalStateException(message, cause)

/**
 * configJson 里敏感字段的存储层编解码（票 #27）：**加密只在这一层发生**。
 * 连接表单（`ui/ConnectionFormSpec`）拿到与交出的一直是明文，因此编辑回填、展示名、
 * 列表渲染都不感知加密（票面 AC：解密封装只出现在存储层）。
 *
 * 落库值有两种形态：**密文**（[ENCRYPTED_PREFIX] 开头）与**旧版明文**（本票之前的库）。
 * - 读路径两种都认（[reveal]）：存量连接不迁移也能继续连；
 * - 写路径一律落密文（[protect]），加密失败就抛出（调用方提示后重试），绝不落明文；
 * - 存量明文由 v4 → v5 迁移用 [protectSecrets] 改写成密文。
 *
 * 判定「要不要加密」只看前缀，所以重复执行是幂等的：既不会二次加密，也不会把密文当明文再加密一次。
 */
internal object StoredCredential {

    /** 密文落库形态的前缀（ASCII，JSON 里无需转义；`enc` = encrypted，`v1` = 密钥与格式版本） */
    const val ENCRYPTED_PREFIX: String = "enc:v1:"

    /**
     * 加解密实现（测试接缝，同 `ServiceLocator.sourceFactory` 的做法）：
     * 生产恒为 Android Keystore 实现，单测里换成内存实现。
     */
    @Volatile
    var cipher: CredentialCipher = KeystoreCredentialCipher()

    /** 明文 → 落库文本：空值与原样已是密文的值返回，保证迁移/保存可重复执行 */
    fun protect(plaintext: String): String {
        if (plaintext.isEmpty() || looksLikeCiphertext(plaintext)) return plaintext
        val encrypted = try {
            cipher.encrypt(plaintext)
        } catch (t: Exception) {
            throw CredentialEncryptionException("凭据无法加密保存（系统密钥库不可用），请稍后重试", t)
        }
        return ENCRYPTED_PREFIX + encrypted
    }

    /**
     * 落库文本 → 明文：旧版明文原样返回（存量连接照旧可用）；
     * 密文解不出来返回 null，上层据此按「需重新填写凭据」降级（提示而不是崩溃、也不是静默连不上）。
     */
    fun reveal(stored: String): String? {
        if (stored.isEmpty() || !isEncrypted(stored)) return stored
        return runCatching { cipher.decrypt(stored.removePrefix(ENCRYPTED_PREFIX)) }.getOrNull()
    }

    /** 落库文本是否是密文（有前缀即认；旧版明文返回 false） */
    fun isEncrypted(stored: String): Boolean = stored.startsWith(ENCRYPTED_PREFIX)

    /**
     * 落库文本是否是「已经装好的密文」——[protect] 的跳过条件，比 [isEncrypted] 严：
     * 除了前缀，还要求后的载荷形态像密文（合法 Base64 且不短于 IV+tag）。
     *
     * 为什么不能只看前缀：用户口令若恰好以 `enc:v1:` 开头，只看前缀会让 [protect] 原样返回它
     * → **明文落库**（正是本票要消灭的形态），随后 [reveal] 又把它当密文去解 → 解不出来 → 刚存好
     * 就被提示「密钥失效，请重新填写」。判据收紧后这种口令会被真正加密，读回来仍是原值。
     */
    fun looksLikeCiphertext(stored: String): Boolean =
        isEncrypted(stored) && CredentialEnvelope.isPlausiblePayload(stored.removePrefix(ENCRYPTED_PREFIX))

    /**
     * 存量迁移（票 #27，v4 → v5 用）：把 JSON 里 [keys] 指名的敏感字段改写成密文。
     *
     * 已是密文或空值的字段原样保留（幂等：重跑结果一致）；解不出 JSON 或加密不可用时返回 null，
     * 由调用方保留原行——旧明文读路径照样认，连接不丢，也不让升级失败。
     *
     * 注意（已知边界）：迁移失败而保留了明文的行，**目前没有自动补救**——库版本已 bump 到 5，
     * 迁移不会重跑，只有用户下次编辑该连接并保存时才会重新落密文（旧明文读路径照常可用，故不影响连接）。
     * 真机上若 Keystore 瞬时不可用，可让用户重开该连接的编辑框保存一次。
     */
    fun protectSecrets(json: String, keys: List<String>): String? = runCatching {
        val obj = JSONObject(json)
        var changed = false
        for (key in keys) {
            val stored = obj.optString(key, "")
            val protectedValue = protect(stored)
            if (protectedValue != stored) {
                obj.put(key, protectedValue)
                changed = true
            }
        }
        if (changed) obj.toString() else json
    }.getOrNull()
}

/**
 * 按来源类型把 configJson 里的凭据字段改写成密文（票 #27，[com.cc3301.comicviewer.core.data.AppDatabase.MIGRATION_4_5] 用）。
 *
 * 放在 `core/source` 而不是数据层："哪个来源的 JSON 里哪些字段是凭据"是**来源自己的契约**
 * （字段名见各 `XxxConnectionConfig`），数据层的迁移不应该知道它们；将来新增带凭据的来源也只需改这里。
 * 返回 null 表示「这行不用改」或「改不了」（LOCAL/未知类型、JSON 损坏、加密不可用），由调用方保留原行。
 */
internal fun protectStoredCredentials(sourceType: String, json: String): String? = when (sourceType) {
    SourceType.SMB.name -> SmbConnectionConfig.protectSecrets(json)
    SourceType.WEBDAV.name -> WebDavConnectionConfig.protectSecrets(json)
    SourceType.KOMGA.name -> KomgaConnectionConfig.protectSecrets(json)
    else -> null
}
