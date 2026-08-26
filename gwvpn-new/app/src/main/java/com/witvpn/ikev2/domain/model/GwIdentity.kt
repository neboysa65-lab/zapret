package com.witvpn.ikev2.domain.model

import android.content.Context
import android.util.Log
import com.witvpn.ikev2.data.SecureStore
import ru.zoommax.Crypto

/**
 * GW-идентичность устройства: приватный ключ для расшифровки серверных
 * конвертов и соответствующий ему публичный ключ, который уходит на сервер
 * как параметр `pubkey`.
 *
 * Инвариант, который здесь держится: pubkey, отправленный на сервер, ВСЕГДА
 * выведен из той же энтропии, что и приватный ключ, которым конверт потом
 * расшифровывается. Раньше клиент не присылал pubkey вообще, бэкенд падал
 * обратно на `userId`, и при любом расхождении userId с локальной энтропией
 * не расшифровывался НИ ОДИН конверт — GW-серверы «пропадали» из списка до
 * выхода из аккаунта и переустановки.
 */
object GwIdentity {

    private const val TAG = "GwIdentity"
    private const val PREFS = "GLOBAL_APP_SHARES"
    private const val KEY_ENTROPY_STR = "app.greywebs.vpn.ENTROPY_STR"
    private const val KEY_MNEMONIC_STR = "app.greywebs.vpn.ENTROPY_MNEMONIC_STR"

    private class Keys(val storedBlob: String, val priv: String, val pub: String)

    // Ключи выводятся для КАЖДОГО сервера в ответе. Раньше на каждый вызов шли
    // чтение prefs + AES-GCM в Android Keystore + вывод secp256k1-ключа, и всё
    // это на главном потоке. Кэшируем по сырому значению из prefs: попадание в
    // кэш стоит одно чтение prefs, а смена/восстановление аккаунта меняет blob
    // и кэш инвалидируется сам.
    @Volatile private var cached: Keys? = null

    fun privateKeyHex(context: Context): String? = keys(context)?.priv

    /** Публичный ключ (128 hex, x||y) для параметра `pubkey` в запросе списка. */
    fun publicKeyHex(context: Context): String? = keys(context)?.pub

    private fun keys(context: Context): Keys? {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val stored = prefs.getString(KEY_ENTROPY_STR, null) ?: ""

        cached?.let { if (it.storedBlob == stored) return it }

        val entropyStr = entropyString(prefs, stored) ?: return null
        if (entropyStr.isBlank()) return null

        return try {
            val entropy = entropyStr.toByteArray(Charsets.ISO_8859_1)
            val priv = Crypto.getPrivkeyAccount(entropy)
            val pub = Crypto.getPubkeyAccount(entropy)
            if (priv.isNullOrBlank() || pub.isNullOrBlank()) {
                Log.e(TAG, "key derivation produced empty keys")
                null
            } else {
                // Кэш ключуем по значению, которое лежит в prefs СЕЙЧАС: после
                // восстановления из мнемоники это уже новый sealed-blob.
                val blobNow = prefs.getString(KEY_ENTROPY_STR, null) ?: stored
                Keys(blobNow, priv, pub).also { cached = it }
            }
        } catch (e: Exception) {
            Log.e(TAG, "key derivation failed: $e")
            null
        }
    }

    /**
     * Достаёт энтропию, восстанавливая её при потере Keystore-ключа.
     *
     * `SecureStore.obtainKey()` при пропаже алиаса `gw_entropy_key_1`
     * (восстановление устройства из бэкапа, смена способа блокировки, апдейт
     * ОС) молча генерирует НОВЫЙ ключ. Старый sealed-blob после этого не
     * расшифровывается, `unseal` возвращает null — и раньше на этом всё
     * заканчивалось: приватный ключ было не восстановить, GW-список оставался
     * пустым навсегда, «помогали» только выход из аккаунта и переустановка.
     *
     * Мнемоника хранится отдельно и не шифруется, поэтому энтропию можно
     * восстановить из неё БЕЗ потери аккаунта — pubkey остаётся тем же.
     */
    private fun entropyString(
        prefs: android.content.SharedPreferences,
        stored: String,
    ): String? {
        if (stored.isNotBlank()) {
            val unsealed = SecureStore.unseal(stored)
            if (!unsealed.isNullOrBlank()) {
                if (!SecureStore.isSealed(stored)) {
                    // legacy-plaintext -> sealed
                    prefs.edit().putString(KEY_ENTROPY_STR, SecureStore.seal(unsealed)).apply()
                }
                return unsealed
            }
            Log.w(TAG, "sealed ENTROPY is undecryptable (Keystore key lost?), recovering from mnemonic")
        }

        val mnemonic = prefs.getString(KEY_MNEMONIC_STR, null)?.trim()
        if (mnemonic.isNullOrEmpty()) {
            Log.e(TAG, "no ENTROPY and no mnemonic — GW configs cannot be decrypted")
            return null
        }

        return try {
            val entropy = Crypto.getEntropy(mnemonic)
            if (entropy == null || entropy.isEmpty()) {
                Log.e(TAG, "mnemonic produced empty entropy")
                return null
            }
            val restored = String(entropy, Charsets.ISO_8859_1)
            // Перезапечатываем уже НОВЫМ ключом Keystore, чтобы следующий
            // запуск не платил за восстановление снова.
            prefs.edit().putString(KEY_ENTROPY_STR, SecureStore.seal(restored)).apply()
            Log.i(TAG, "ENTROPY restored from mnemonic, account preserved")
            restored
        } catch (e: Exception) {
            Log.e(TAG, "mnemonic recovery failed: $e")
            null
        }
    }
}
