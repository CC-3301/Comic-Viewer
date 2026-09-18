package com.cc3301.comicviewer.core.source

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

    /** 明文 → 落库文本：空值与已是密文的值原样返回（后者保证迁移/保存可重复执行） */
    fun protect(plaintext: String): String {
        if (plaintext.isEmpty() || isEncrypted(plaintext)) return plaintext
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

    /** 落库文本是否是密文（存量迁移「这行要不要处理」的唯一依据） */
    fun isEncrypted(stored: String): Boolean = stored.startsWith(ENCRYPTED_PREFIX)

    /**
     * 存量迁移（票 #27，v4 → v5 用）：把 JSON 里 [keys] 指名的敏感字段改写成密文。
     *
     * 已是密文或空值的字段原样保留（幂等：重跑结果一致）；解不出 JSON 或加密不可用时返回 null，
     * 由调用方保留原行——旧明文读路径照样认，连接不丢，也不让升级失败。
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
