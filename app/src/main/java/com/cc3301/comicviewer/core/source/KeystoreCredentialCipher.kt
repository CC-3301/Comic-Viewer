package com.cc3301.comicviewer.core.source

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Android Keystore 实现的凭据加解密（票 #27）：AES-256-GCM。
 *
 * 密钥是 Keystore 里的**不可导出**条目（[KEY_ALIAS]），只在加解密时由系统借用：
 * 既不硬编码进代码，也不随 APK 分发，导不出 Keystore（`key.encoded` 恒为 null）。
 * 每次加密用随机 IV（GCM 要求，Keystore 的 `randomizedEncryptionRequired` 默认开），
 * 落库密文 = Base64(IV ‖ 密文+tag)，无填充；**载荷布局与常量统一在 [CredentialEnvelope]**（生产与测试替身共用，
 * 因此布局改错能被纯 JVM 用例抓到，而不只靠真机）。
 *
 * 密钥是 **app 级一条**，不按连接建条目：连接删除时其密文随 Room 行一起消失，Keystore 侧
 * 没有需要清理的每连接条目，也就不会有孤儿条目（票面真机清单第 3 项的口径）。
 * 换设备 / 清应用数据 / Keystore 条目被系统清掉后旧密文解不出来——
 * [StoredCredential.reveal] 返回 null，界面提示重新填写凭据。
 */
internal class KeystoreCredentialCipher : CredentialCipher {

    override fun encrypt(plaintext: String): String {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, secretKey())
        val body = cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8))
        return CredentialEnvelope.toBase64(CredentialEnvelope.pack(cipher.iv, body))
    }

    override fun decrypt(ciphertext: String): String? = runCatching {
        val payload = CredentialEnvelope.fromBase64(ciphertext) ?: return null
        val (iv, body) = CredentialEnvelope.unpack(payload) ?: return null
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(CredentialEnvelope.TAG_BITS, iv))
        String(cipher.doFinal(body), Charsets.UTF_8)
    }.getOrNull()

    /**
     * 取密钥（首次使用时生成）。同步是必须的：并发走两条 get-or-create 时，
     * `generateKey` 会覆盖同名条目，把刚加密的值变成另一把密钥的密文。
     * 这里抛出的异常由 [StoredCredential.protect] 转成用户可见提示。
     */
    @Synchronized
    private fun secretKey(): SecretKey {
        val keystore = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        (keystore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(KEY_SIZE_BITS)
                .build(),
        )
        return generator.generateKey()
    }

    private companion object {
        const val KEYSTORE = "AndroidKeyStore"
        const val KEY_ALIAS = "comic-viewer.credentials.v1"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val KEY_SIZE_BITS = 256
    }
}
