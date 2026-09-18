package com.cc3301.comicviewer.core.source

import java.util.Base64

/**
 * 密文载荷的封装格式（票 #27）：`IV ‖ 密文+tag` 的拼接与长度常量**只在这里定义**。
 *
 * 为什么单独抽出来：生产实现 [KeystoreCredentialCipher] 依赖 `AndroidKeyStore`，而 Robolectric
 * 的 JVM 里没有它（`KeyStore.getInstance("AndroidKeyStore")` 抛异常），所以单测跑的是内存替身。
 * 若布局在两个实现里各写一份，把拼接顺序改成 `密文 ‖ IV`、把 tag 位改成 96、漏掉 `withoutPadding()`
 * 这类错误全量套件都抓不到；放到这里就被本文件的纯 JVM 用例钉住。
 *
 * 生产侧真正只能在真机验的只剩「密钥由系统密钥库不可导出地保管」这一条（见票 #27 真机清单）。
 */
internal object CredentialEnvelope {

    /** GCM 随机 IV 长度（字节）：12 是 GCM 的推荐值 */
    const val IV_LENGTH: Int = 12

    /** GCM 认证 tag 位数 */
    const val TAG_BITS: Int = 128

    /** 合法载荷至少要有 IV + tag 这么长（判定「像不像密文」用） */
    const val MIN_PAYLOAD_BYTES: Int = IV_LENGTH + TAG_BITS / 8

    /** IV 前置拼上密文体 */
    fun pack(iv: ByteArray, body: ByteArray): ByteArray = iv + body

    /**
     * 拆开载荷：返回 `(iv, body)`；长度不足或解码失败返回 null
     * （调用方据此走「解不出来 → 需重新填写凭据」，而不是崩）。
     */
    fun unpack(payload: ByteArray): Pair<ByteArray, ByteArray>? {
        if (payload.size < MIN_PAYLOAD_BYTES) return null
        return payload.copyOfRange(0, IV_LENGTH) to payload.copyOfRange(IV_LENGTH, payload.size)
    }

    /** 落库文本（不含前缀）→ 载荷字节；不是合法 Base64 返回 null */
    fun fromBase64(text: String): ByteArray? = runCatching { Base64.getDecoder().decode(text) }.getOrNull()

    /** 载荷字节 → 落库文本（无填充，JSON 里更短且无需转义） */
    fun toBase64(payload: ByteArray): String = Base64.getEncoder().withoutPadding().encodeToString(payload)

    /** 文本是否「看起来真的装了密文」（[StoredCredential.protect] 的跳过条件，见那里的 KDoc） */
    fun isPlausiblePayload(text: String): Boolean =
        fromBase64(text)?.let { it.size >= MIN_PAYLOAD_BYTES } == true
}
