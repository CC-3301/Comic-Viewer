package com.cc3301.comicviewer.core.source

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * 存储层凭据编解码（票 #27）：加密只在这一层发生，明文只进不出——
 * 落库不含明文、旧明文照读、重复执行幂等、解不出来返回 null 而不是抛。
 */
class StoredCredentialTest {

    private val previous = StoredCredential.cipher

    @Before
    fun installCipher() {
        StoredCredential.cipher = TestCredentialCipher
    }

    @After
    fun restoreCipher() {
        StoredCredential.cipher = previous
    }

    @Test
    fun `加密后落库值不含明文 解回来一致`() {
        val stored = StoredCredential.protect("s3cret")

        assertTrue("要带密文前缀：" + stored, stored.startsWith(StoredCredential.ENCRYPTED_PREFIX))
        assertFalse("落库值不得含明文：" + stored, stored.contains("s3cret"))
        assertEquals("s3cret", StoredCredential.reveal(stored))
    }

    @Test
    fun `同一明文两次加密得到不同密文 都能解开`() {
        val first = StoredCredential.protect("s3cret")
        val second = StoredCredential.protect("s3cret")

        // 随机 IV：密文不相等（否则相同密码可以从落库值反查）
        assertNotEquals(first, second)
        assertEquals("s3cret", StoredCredential.reveal(first))
        assertEquals("s3cret", StoredCredential.reveal(second))
    }

    @Test
    fun `空密码不加密也不解密`() {
        assertEquals("", StoredCredential.protect(""))
        assertEquals("", StoredCredential.reveal(""))
    }

    @Test
    fun `已是密文的值再进一次不改动 迁移可重复执行`() {
        val stored = StoredCredential.protect("s3cret")

        assertEquals(stored, StoredCredential.protect(stored))
        assertEquals("s3cret", StoredCredential.reveal(StoredCredential.protect(stored)))
    }

    @Test
    fun `票前的明文原样读出 存量连接不动它也能连`() {
        assertEquals("s3cret", StoredCredential.reveal("s3cret"))
        assertFalse(StoredCredential.isEncrypted("s3cret"))
        assertTrue(StoredCredential.isEncrypted(StoredCredential.protect("s3cret")))
    }

    @Test
    fun `密文解不出来返回 null 不抛`() {
        // 换机/密钥失效：另一把密钥的密文
        val foreign = ForeignKeyCredentialCipher.encrypt("s3cret")
        assertEquals("另一把密钥能解开（证明用例有效）", "s3cret", ForeignKeyCredentialCipher.decrypt(foreign))
        assertNull(StoredCredential.reveal(StoredCredential.ENCRYPTED_PREFIX + foreign))

        // 密文损坏：前缀之后不是合法密文
        assertNull(StoredCredential.reveal(StoredCredential.ENCRYPTED_PREFIX + "不是-base64"))
        assertNull(StoredCredential.reveal(StoredCredential.ENCRYPTED_PREFIX))
    }

    @Test
    fun `口令本身以密文前缀开头时照常加密 不会明文落库`() {
        // 票 #27 评审 P2：只看前缀会把这种口令当成「已是密文」而原样落库（明文），
        // 随后又被当密文去解 → 刚存好就提示「密钥失效」。判据收紧后它会真的被加密。
        val literal = StoredCredential.ENCRYPTED_PREFIX + "not-a-real-ciphertext"

        val stored = StoredCredential.protect(literal)

        assertNotEquals("以 enc 前缀开头的口令也必须真的加密：" + stored, literal, stored)
        assertFalse("落库值不得含口令本体：" + stored, stored.contains("not-a-real-ciphertext"))
        assertEquals(literal, StoredCredential.reveal(stored))
    }

    @Test
    fun `旧行里带前缀但载荷不像密文的值 迁移会重新加密`() {
        val legacyCorrupt = StoredCredential.ENCRYPTED_PREFIX + "abc"
        assertFalse("太短的载荷不算密文", StoredCredential.looksLikeCiphertext(legacyCorrupt))

        val encrypted = StoredCredential.protect(legacyCorrupt)

        assertTrue(StoredCredential.looksLikeCiphertext(encrypted))
        assertEquals(legacyCorrupt, StoredCredential.reveal(encrypted))
    }

    @Test
    fun `加密不可用时抛出中文提示 绝不回退成明文`() {
        StoredCredential.cipher = FailingCredentialCipher

        val thrown = assertThrows(CredentialEncryptionException::class.java) { StoredCredential.protect("s3cret") }
        assertTrue("提示要能直接给用户看：" + thrown.message, thrown.message!!.contains("无法加密保存"))
        // 提示里不得带凭据本身
        assertFalse(thrown.message!!.contains("s3cret"))
    }
}
