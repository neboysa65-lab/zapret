package com.witvpn.ikev2.data

import android.content.SharedPreferences
import androidx.core.content.edit
import javax.inject.Inject

class AppSettings @Inject constructor(
    private val sharedPreferences: SharedPreferences
) {
    companion object {
        const val APPROXIMATE_INT = "app.greywebs.vpn.APPROXIMATE_INT"
        const val ENTROPY_STR = "app.greywebs.vpn.ENTROPY_STR"
        const val ENTROPY_MNEMONIC_STR = "app.greywebs.vpn.ENTROPY_MNEMONIC_STR"
        const val ENTROPY_RSA_STR = "app.greywebs.vpn.ENTROPY_RSA_STR"
        const val ENTROPY_PUBKEY_STR = "app.greywebs.vpn.ENTROPY_PUBKEY_STR"
        const val LAST_USER_IS_PREMIUM = "app.greywebs.vpn.LAST_USER_IS_PREMIUM"
        /*
        энтропия: используется для генерации мнемоники
        SHA256 от энтропии: передаётся на сервер как параметр "deviceId"
        rsa* от энтропии: отображается для QR
        * используется ru.zoommax.Crypto
        */
    }

    var lastUserIsPremium: Boolean
        get() = sharedPreferences.getBoolean(LAST_USER_IS_PREMIUM, false)
        set(value) {
            sharedPreferences.edit().putBoolean(LAST_USER_IS_PREMIUM, value).apply()
        }
    fun needShowInAppReview() = sharedPreferences.getInt(APPROXIMATE_INT, 0) >= 0

    fun approximateAppReviewCounter() {
        val current = sharedPreferences.getInt(APPROXIMATE_INT, 0)
        sharedPreferences.edit {
            putInt(APPROXIMATE_INT, current + 1)
            apply()
        }
    }

    fun resetAppReviewCounter() {
        sharedPreferences.edit {
            putInt(APPROXIMATE_INT, 0)
            apply()
        }
    }

    fun updateEntropy(
        entropy: String,
        entropyMnemonic: String,
        entropyRSA: String,
        pubKey: String
    ) {
        sharedPreferences.edit(true) {
            // ENTROPY — мастер-ключ GW (расшифровка серверных конвертов):
            // на диск попадает только Keystore-sealed значение.
            putString(ENTROPY_STR, SecureStore.seal(entropy))
            putString(ENTROPY_MNEMONIC_STR, entropyMnemonic)
            putString(ENTROPY_RSA_STR, entropyRSA)
            putString(ENTROPY_PUBKEY_STR, pubKey)
            apply()
        }
    }

    fun getEntropy(): String {
        return SecureStore.unseal(sharedPreferences.getString(ENTROPY_STR, "")) ?: ""
    }
    fun getMnemonic(): String {
        return sharedPreferences.getString(ENTROPY_MNEMONIC_STR, "")!!
    }
    fun getEntropyRSA(): String {
        return sharedPreferences.getString(ENTROPY_RSA_STR, "")!!
    }
    fun getEntropyPubKey(): String {
        return sharedPreferences.getString(ENTROPY_PUBKEY_STR, "")!!
    }

    /**
     * Удаляет энтропию и ВСЕ производные от неё поля.
     *
     * Раньше здесь после `remove(ENTROPY_STR)` стоял `clear()`, а он в редакторе
     * означает «стереть файл prefs целиком». Два следствия, оба скверные:
     * заодно уничтожалась мнемоника — единственный способ восстановить ключ при
     * потере ключа Keystore, — и терялись посторонние настройки
     * (LAST_USER_IS_PREMIUM, счётчик отзыва).
     *
     * Производные поля (RSA, PUBKEY) удаляются вместе с энтропией намеренно:
     * оставшийся pubkey без энтропии — это ровно то состояние, в котором
     * SplashViewModel считает идентичность существующей и не создаёт её заново.
     */
    fun clearEntropy() {
        sharedPreferences.edit(commit = true) {
            remove(ENTROPY_STR)
            remove(ENTROPY_RSA_STR)
            remove(ENTROPY_PUBKEY_STR)
        }
    }
    fun clearMnemonic() {
        sharedPreferences.edit(commit = true) {
            remove(ENTROPY_MNEMONIC_STR)
        }
    }

    /** Миграция legacy-plaintext ENTROPY в sealed-вид (вызывать после чтения старых значений). */
    fun migrateEntropyToSealed() {
        val current = sharedPreferences.getString(ENTROPY_STR, null) ?: return
        if (!SecureStore.isSealed(current)) {
            sharedPreferences.edit().putString(ENTROPY_STR, SecureStore.seal(current)).apply()
        }
    }
}