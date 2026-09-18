package com.cc3301.comicviewer.core.source

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 密文载荷布局（票 #27）：把「IV 前置拼接、长度常量、Base64 形态」钉在纯 JVM 用例里。
 *
 * 为什么需要它：生产实现 [KeystoreCredentialCipher] 依赖 `AndroidKeyStore`，单测跑不到；
 * 而单测用的替身与生产共用 [CredentialEnvelope]，所以把拼接顺序改反、把 tag 位改小、漏掉
 * `withoutPadding()` 这类错误会被本文件抓住，而不是等到真机上才炸。
 */
class CredentialEnvelopeTest {

    @Test
    fun `载荷按 IV 前置拼接 拆开后与原件一致`() {
        val iv = ByteArray(CredentialEnvelope.IV_LENGTH) { index -> index.toByte() }
        // 密文体按 GCM 现实至少含 tag（16 字节），这里取 20 字节，避免撞上「太短不算密文」的下限
        val body = ByteArray(20) { index -> (index + 100).toByte() }

        val packed = CredentialEnvelope.pack(iv, body)

        assertEquals("IV 在前：长度 = IV + 密文", iv.size + body.size, packed.size)
        assertTrue("前 12 字节必须是 IV", packed.copyOfRange(0, iv.size).contentEquals(iv))
        val (unpackedIv, unpackedBody) = CredentialEnvelope.unpack(packed)!!
        assertTrue(unpackedIv.contentEquals(iv))
        assertTrue(unpackedBody.contentEquals(body))
    }

    @Test
    fun `载荷短于 IV 加 tag 不算密文`() {
        assertEquals("IV + tag = 28 字节", 28, CredentialEnvelope.MIN_PAYLOAD_BYTES)
        assertNull("长度不足时拆不出来", CredentialEnvelope.unpack(ByteArray(27)))
        assertFalse("太短不算密文", CredentialEnvelope.isPlausiblePayload(CredentialEnvelope.toBase64(ByteArray(27))))
        assertTrue("刚好够长可以", CredentialEnvelope.isPlausiblePayload(CredentialEnvelope.toBase64(ByteArray(28))))
    }

    @Test
    fun `不是 Base64 的文本不算密文`() {
        assertFalse(CredentialEnvelope.isPlausiblePayload("not-a-real-ciphertext"))
        assertNull(CredentialEnvelope.fromBase64("not-a-real-ciphertext"))
    }

    @Test
    fun `Base64 往返无填充`() {
        val payload = ByteArray(64) { index -> (index * 7).toByte() }

        val text = CredentialEnvelope.toBase64(payload)

        assertFalse("无填充（JSON 里更短且无需转义）：" + text, text.contains("="))
        assertTrue(CredentialEnvelope.fromBase64(text)!!.contentEquals(payload))
    }
}
