package com.baton.app.security

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.io.File
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * 앱 전용 폴더의 파일을 AES-256-GCM으로 암호화해 읽고 쓴다.
 * - 키는 Android Keystore 안에서 생성·보관되어 앱 밖으로 꺼낼 수 없다 (하드웨어 지원 시 TEE/StrongBox).
 * - GCM 인증 태그로 변조를 감지한다: 한 바이트라도 바뀌면 복호화가 실패하고 내용은 버려진다.
 * - 쓰기는 임시 파일 → 교체(rename)로 해서, 쓰는 도중 전원이 꺼져도 이전 파일이 남는다.
 * 파일 형식: "BTN1"(4) + IV(12) + 암호문+태그(16)
 */
class SecureFile(private val file: File, private val keyAlias: String = DEFAULT_ALIAS) {

    fun write(plain: ByteArray) {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val out = MAGIC + cipher.iv + cipher.doFinal(plain)
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeBytes(out)
        if (!tmp.renameTo(file)) {
            file.delete()
            tmp.renameTo(file)
        }
    }

    /** 파일이 없거나, 형식이 다르거나, 변조됐거나, 너무 크면 null */
    fun read(): ByteArray? {
        if (!file.exists() || file.length() > MAX_BYTES) return null
        val data = file.readBytes()
        val header = MAGIC.size + IV_BYTES
        if (data.size < header + TAG_BYTES || !data.copyOfRange(0, MAGIC.size).contentEquals(MAGIC)) return null
        return runCatching {
            val iv = data.copyOfRange(MAGIC.size, header)
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(TAG_BYTES * 8, iv))
            cipher.doFinal(data, header, data.size - header)
        }.getOrNull()
    }

    fun delete() {
        file.delete()
    }

    private fun key(): SecretKey {
        val ks = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        try {
            (ks.getKey(keyAlias, null) as? SecretKey)?.let { return it }
        } catch (e: Exception) {
            // 키가 손상·무효화된 경우(시스템 업데이트 등): 폐기하고 새로 만든다.
            // 이전 기록은 읽을 수 없게 되지만(read → null) 앱은 계속 동작한다.
            runCatching { ks.deleteEntry(keyAlias) }
        }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE)
        gen.init(
            KeyGenParameterSpec.Builder(keyAlias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .setRandomizedEncryptionRequired(true) // IV는 매번 시스템이 무작위 생성
                .build()
        )
        return gen.generateKey()
    }

    companion object {
        const val DEFAULT_ALIAS = "baton_store_v1"
        private const val KEYSTORE = "AndroidKeyStore"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private val MAGIC = "BTN1".toByteArray()
        private const val IV_BYTES = 12
        private const val TAG_BYTES = 16

        /** 인계 50건이 수십 KB 수준. 비정상적으로 큰 파일은 읽지 않는다 (메모리 고갈 방지) */
        const val MAX_BYTES = 1_000_000L
    }
}
