package com.witvpn.vlee.crypto

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyStore
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Шифрование чувствительных данных Vlee (сохранённый конфиг ноды) ключом
 * из Android Keystore. Ключ не покидает TEE/Keystore и недоступен ни dex,
 * ни adb backup в читаемом виде.
 */
internal object VleeVault {

    private const val ANDROID_KEYSTORE = "AndroidKeyStore"
    private const val KEY_ALIAS = "vlee_vault_1"
    private const val PREFIX = "VLET1:"
    private const val GCM_TAG_BITS = 128

    private fun obtainKey(): SecretKey {
        val ks = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (ks.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .setRandomizedEncryptionRequired(true)
                .build()
        )
        return generator.generateKey()
    }

    /** Возвращает "VLET1:<b64(iv)>.<b64(ct)>" либо исходную строку при ошибке. */
    fun seal(plain: String): String {
        return try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, obtainKey())
            val iv = cipher.iv
            val ct = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
            PREFIX + Base64.getEncoder().encodeToString(iv) + "." +
                Base64.getEncoder().encodeToString(ct)
        } catch (_: Exception) {
            plain
        }
    }

    /** Расшифровывает sealed-строку; совместим с legacy-plaintext (миграция). */
    fun unseal(value: String?): String? {
        if (value == null) return null
        if (!value.startsWith(PREFIX)) return value
        return try {
            val body = value.removePrefix(PREFIX)
            val dot = body.indexOf('.')
            if (dot <= 0) return null
            val iv = Base64.getDecoder().decode(body.substring(0, dot))
            val ct = Base64.getDecoder().decode(body.substring(dot + 1))
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, obtainKey(), GCMParameterSpec(GCM_TAG_BITS, iv))
            String(cipher.doFinal(ct), Charsets.UTF_8)
        } catch (_: Exception) {
            null
        }
    }

    fun isSealed(value: String?): Boolean = value != null && value.startsWith(PREFIX)
}
