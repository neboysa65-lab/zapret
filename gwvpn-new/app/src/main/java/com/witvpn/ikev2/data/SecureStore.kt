package com.witvpn.ikev2.data

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import android.util.Log
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Шифрование секретов приложения (ENTROPY — мастер-ключ GW-идентичности)
 * ключом из Android Keystore. Значение хранится в prefs в виде "ENC1:<iv>.<ct>",
 * ключ не покидает Keystore.
 */
object SecureStore {

    private const val ANDROID_KEYSTORE = "AndroidKeyStore"
    private const val KEY_ALIAS = "gw_entropy_key_1"
    private const val PREFIX = "ENC1:"
    private const val GCM_TAG_BITS = 128
    private const val TAG = "SecureStore"

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

    fun seal(plain: String): String {
        return try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, obtainKey())
            val iv = cipher.iv
            val ct = cipher.doFinal(plain.toByteArray(Charsets.ISO_8859_1))
            PREFIX + Base64.encodeToString(iv, Base64.NO_WRAP) + "." +
                Base64.encodeToString(ct, Base64.NO_WRAP)
        } catch (e: Exception) {
            // Фолбэк на plaintext намеренный: иначе устройство с недоступным
            // Keystore потеряло бы аккаунт целиком. Но это ДЕГРАДАЦИЯ, и она
            // не должна быть бесшумной — раньше здесь не было ни строки лога.
            Log.e(TAG, "Keystore seal failed, storing value UNENCRYPTED: $e")
            plain
        }
    }

    /** Расшифровывает sealed-значение; legacy-plaintext возвращается как есть. */
    fun unseal(value: String?): String? {
        if (value == null) return null
        if (!value.startsWith(PREFIX)) return value
        return try {
            val body = value.removePrefix(PREFIX)
            val dot = body.indexOf('.')
            if (dot <= 0) return null
            val iv = Base64.decode(body.substring(0, dot), Base64.NO_WRAP)
            val ct = Base64.decode(body.substring(dot + 1), Base64.NO_WRAP)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, obtainKey(), GCMParameterSpec(GCM_TAG_BITS, iv))
            String(cipher.doFinal(ct), Charsets.ISO_8859_1)
        } catch (_: Exception) {
            null
        }
    }

    fun isSealed(value: String?): Boolean = value != null && value.startsWith(PREFIX)
}
