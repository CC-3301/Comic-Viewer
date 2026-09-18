package com.cc3301.comicviewer.core.source

import org.junit.rules.TestRule
import org.junit.runner.Description
import org.junit.runners.model.Statement
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * 单测用的凭据加解密（票 #27）：Robolectric 的 JVM 里没有 Android Keystore
 * （`KeyStore.getInstance("AndroidKeyStore")` 抛 `KeyStoreException: AndroidKeyStore not found`），
 * 所以用同一套算法语义在内存里顶上：AES-256-GCM + 随机 IV + `Base64(IV ‖ 密文)`。
 *
 * 存储层的断言（落库不含明文、迁移幂等、解不出来不崩）因此跑在真实路径上；
 * 只有「密钥由系统密钥库不可导出地保管」这一条属于真机验收（票 #27 真机清单）。
 */
class InMemoryCredentialCipher(private val keyBytes: ByteArray) : CredentialCipher {

    override fun encrypt(plaintext: String): String {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(keyBytes, KEY_ALGORITHM))
        val body = cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8))
        return Base64.getEncoder().withoutPadding().encodeToString(cipher.iv + body)
    }

    override fun decrypt(ciphertext: String): String? = runCatching {
        val bytes = Base64.getDecoder().decode(ciphertext)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(
            Cipher.DECRYPT_MODE,
            SecretKeySpec(keyBytes, KEY_ALGORITHM),
            GCMParameterSpec(TAG_BITS, bytes.copyOfRange(0, IV_LENGTH)),
        )
        String(cipher.doFinal(bytes.copyOfRange(IV_LENGTH, bytes.size)), Charsets.UTF_8)
    }.getOrNull()

    private companion object {
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val KEY_ALGORITHM = "AES"
        const val IV_LENGTH = 12
        const val TAG_BITS = 128
    }
}

/** 测试期间装到 [StoredCredential] 上的实现（固定密钥） */
val TestCredentialCipher = InMemoryCredentialCipher(ByteArray(32) { index -> index.toByte() })

/** 换机 / 密钥失效的仿真：另一把密钥加密出来的密文，[TestCredentialCipher] 解不出来 */
val ForeignKeyCredentialCipher = InMemoryCredentialCipher(ByteArray(32) { index -> (index + 7).toByte() })

/** Keystore 不可用的仿真：加密必失败，用来锁「失败绝不落明文」 */
object FailingCredentialCipher : CredentialCipher {
    override fun encrypt(plaintext: String): String = throw IllegalStateException("keystore 不可用")

    override fun decrypt(ciphertext: String): String? = null
}

/**
 * 测试期间把 [StoredCredential.cipher] 换成 [TestCredentialCipher]，结束后还原：
 * Robolectric 的沙箱类加载器跨测试类复用，装了不还，后面的测试会莫名其妙地拿着假实现跑。
 */
class TestCredentialCipherRule : TestRule {
    override fun apply(base: Statement, description: Description): Statement = object : Statement() {
        override fun evaluate() {
            val previous = StoredCredential.cipher
            StoredCredential.cipher = TestCredentialCipher
            try {
                base.evaluate()
            } finally {
                StoredCredential.cipher = previous
            }
        }
    }
}
