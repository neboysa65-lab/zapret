package com.witvpn.ikev2.domain.model

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import com.witvpn.ikev2.data.AppSettings
import com.witvpn.ikev2.data.SecureStore
import com.witvpn.ikev2.presentation.utils.EntropyHelper
import ru.zoommax.Crypto

/**
 * GW-идентичность устройства: приватный ключ для расшифровки серверных
 * конвертов и соответствующий ему публичный ключ, который уходит на сервер
 * как параметр `pubkey`.
 *
 * Два инварианта, которые здесь держатся.
 *
 * 1. pubkey, отправленный на сервер, ВСЕГДА выведен из той же энтропии, что и
 *    приватный ключ, которым конверт потом расшифровывается. Раньше клиент не
 *    присылал pubkey вообще, бэкенд падал обратно на `userId`, и при любом
 *    расхождении userId с локальной энтропией не расшифровывался НИ ОДИН
 *    конверт — GW-серверы «пропадали» из списка.
 *
 * 2. Идентичность существует ВСЕГДА. Раньше состояние «userId в prefs есть, а
 *    энтропии нет» было тупиком без выхода: SplashViewModel генерирует энтропию
 *    только в ветке `userId == null || userId.length < 128`, поэтому при живом
 *    userId она уже не создавалась никогда, а GW-раздел оставался пустым до
 *    стирания данных приложения. В это состояние попадали, например, входом по
 *    OTP после выхода из аккаунта (логин пишет userId и не создаёт энтропию) и
 *    смертью процесса между двумя чистками prefs при выходе.
 *    Бэкенд шифрует конверт на ЛЮБОЙ присланный pubkey и не сверяет его с
 *    аккаунтом, поэтому свежесгенерированная пара работает сразу — аккаунт
 *    (userId, премиум, подписка) при этом не затрагивается.
 */
object GwIdentity {

    private const val TAG = "GwIdentity"
    private const val PREFS = "GLOBAL_APP_SHARES"
    private const val KEY_ENTROPY_STR = "app.greywebs.vpn.ENTROPY_STR"
    private const val KEY_MNEMONIC_STR = "app.greywebs.vpn.ENTROPY_MNEMONIC_STR"
    private const val KEY_RSA_STR = "app.greywebs.vpn.ENTROPY_RSA_STR"
    private const val KEY_PUBKEY_STR = "app.greywebs.vpn.ENTROPY_PUBKEY_STR"

    /** 12 слов BIP39 — как в [com.witvpn.ikev2.features.entropy.EntropyUseCase]. */
    private const val ENTROPY_BITS = 128

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

    /**
     * Гарантирует, что GW-идентичность на устройстве есть, и возвращает pubkey.
     * Вызывать в точках, где меняется привязка к аккаунту (логин по OTP), чтобы
     * ключ существовал ещё до первого запроса списка серверов.
     */
    fun ensure(context: Context): String? = publicKeyHex(context)

    private fun keys(context: Context): Keys? {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

        // Быстрый путь без блокировки: сравниваем с тем, что лежит в prefs.
        cached?.let { if (it.storedBlob == prefs.getString(KEY_ENTROPY_STR, null).orEmpty()) return it }

        return resolve(prefs)
    }

    /**
     * Медленный путь: восстановление/генерация. Под замком, иначе два
     * параллельных вызова (список серверов расшифровывается на пуле) могли бы
     * сгенерировать две РАЗНЫЕ энтропии, и вторая перетёрла бы ключ, на который
     * сервер уже зашифровал конверты.
     */
    @Synchronized
    private fun resolve(prefs: SharedPreferences): Keys? {
        val stored = prefs.getString(KEY_ENTROPY_STR, null).orEmpty()
        cached?.let { if (it.storedBlob == stored) return it }

        val entropyStr = entropyString(prefs, stored)
        if (entropyStr.isNullOrBlank()) return null

        return try {
            val entropy = entropyStr.toByteArray(Charsets.ISO_8859_1)
            val priv = Crypto.getPrivkeyAccount(entropy)
            // Канонизация обязательна: без неё ~46% пар рождаются с pubkey
            // "00||x||y" (130 hex), который бэкенд отказывается парсить.
            val pub = EntropyHelper.canonicalPubKey(Crypto.getPubkeyAccount(entropy))
            if (priv.isNullOrBlank() || pub.isNullOrBlank()) {
                Log.e(TAG, "key derivation produced empty keys")
                null
            } else {
                reconcileDerived(prefs, entropy, pub)
                // Кэш ключуем по значению, которое лежит в prefs СЕЙЧАС: после
                // восстановления или генерации это уже новый sealed-blob.
                val blobNow = prefs.getString(KEY_ENTROPY_STR, null) ?: stored
                Keys(blobNow, priv, pub).also { cached = it }
            }
        } catch (e: Exception) {
            Log.e(TAG, "key derivation failed: $e")
            null
        }
    }

    /**
     * Подтягивает производные поля к фактической энтропии.
     *
     * Нужно по двум причинам. Во-первых, восстановление из мнемоники
     * перезапечатывает только саму энтропию, а `ENTROPY_PUBKEY_STR` при этом
     * может отсутствовать — а именно его читает SplashViewModel и отправляет как
     * `deviceId`, то есть аккаунт зарегистрировался бы на пустой deviceId.
     * Во-вторых, сохранённый pubkey в принципе может разойтись с реальным
     * ключом; тогда сервер и клиент считают идентичность разной. Источник
     * истины — энтропия, поэтому остальное приводится к ней.
     *
     * Мнемоника восстанавливается тем же способом: она выводится из энтропии
     * детерминированно, так что её пропажа (например, после отдельной чистки)
     * лечится без потери аккаунта — и путь восстановления снова доступен.
     */
    private fun reconcileDerived(prefs: SharedPreferences, entropy: ByteArray, pub: String) {
        try {
            val edits = prefs.edit()
            var dirty = false

            if (prefs.getString(KEY_PUBKEY_STR, null) != pub) {
                edits.putString(KEY_PUBKEY_STR, pub)
                dirty = true
            }
            if (prefs.getString(KEY_MNEMONIC_STR, null).isNullOrBlank()) {
                Crypto.getMnemonic(entropy)?.takeIf { it.isNotBlank() }?.let {
                    edits.putString(KEY_MNEMONIC_STR, it)
                    dirty = true
                    Log.i(TAG, "mnemonic re-derived from ENTROPY, recovery path restored")
                }
            }
            if (prefs.getString(KEY_RSA_STR, null).isNullOrBlank()) {
                Crypto.encrypt(entropy)?.takeIf { it.isNotBlank() }?.let {
                    edits.putString(KEY_RSA_STR, it)
                    dirty = true
                }
            }

            if (dirty) edits.apply()
        } catch (e: Exception) {
            // Сверка — вспомогательная; ключи уже выведены и работают.
            Log.w(TAG, "derived fields reconcile failed: $e")
        }
    }

    /**
     * Достаёт энтропию в порядке убывания сохранности аккаунта:
     *
     * 1. sealed ENTROPY из prefs — обычный путь;
     * 2. мнемоника — она лежит рядом и не шифруется, поэтому переживает потерю
     *    ключа Keystore (`SecureStore.obtainKey()` при пропаже алиаса
     *    `gw_entropy_key_1` — восстановление из бэкапа, смена способа
     *    блокировки, апдейт ОС — молча генерирует НОВЫЙ ключ, и старый blob
     *    перестаёт расшифровываться). Аккаунт и pubkey сохраняются;
     * 3. генерация новой пары — когда не осталось ни того, ни другого.
     *    Восстанавливать уже нечего, а без ключа GW-раздел пуст навсегда.
     */
    private fun entropyString(prefs: SharedPreferences, stored: String): String? {
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
        if (!mnemonic.isNullOrEmpty()) {
            try {
                val entropy = Crypto.getEntropy(mnemonic)
                if (entropy != null && entropy.isNotEmpty()) {
                    val restored = String(entropy, Charsets.ISO_8859_1)
                    // Перезапечатываем уже НОВЫМ ключом Keystore, чтобы
                    // следующий запуск не платил за восстановление снова.
                    prefs.edit().putString(KEY_ENTROPY_STR, SecureStore.seal(restored)).apply()
                    Log.i(TAG, "ENTROPY restored from mnemonic, account preserved")
                    return restored
                }
                Log.e(TAG, "mnemonic produced empty entropy")
            } catch (e: Exception) {
                Log.e(TAG, "mnemonic recovery failed: $e")
            }
        }

        return generate(prefs)
    }

    /**
     * Последний рубеж: своя пара ключей. Пишется тем же кодом, что и обычная
     * регистрация ([AppSettings.updateEntropy]), поэтому набор полей —
     * ENTROPY/MNEMONIC/RSA/PUBKEY — не расходится с остальным приложением:
     * экран мнемоники, QR и `deviceId` на следующем старте увидят новую пару.
     */
    private fun generate(prefs: SharedPreferences): String? {
        return try {
            val entropy = EntropyHelper.generateNewEntropy(ENTROPY_BITS)
            val entropyStr = EntropyHelper.convertEntropyTo_ISO_8859_1(entropy)
            AppSettings(prefs).updateEntropy(
                entropy = entropyStr,
                entropyMnemonic = EntropyHelper.decodeMnemonicFromEntropy(entropy),
                entropyRSA = Crypto.encrypt(entropy),
                pubKey = EntropyHelper.canonicalPubKey(Crypto.getPubkeyAccount(entropy)),
            )
            Log.w(
                TAG,
                "no ENTROPY and no mnemonic — generated a fresh GW keypair; " +
                    "account (userId/premium) untouched, GW configs are decryptable again"
            )
            entropyStr
        } catch (e: Exception) {
            Log.e(TAG, "GW keypair generation failed: $e")
            null
        }
    }
}
