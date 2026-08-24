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

    fun clearEntropy() {
        sharedPreferences.edit {
            remove(ENTROPY_STR)
            clear()
        }
    }
    fun clearMnemonic() {
        sharedPreferences.edit {
            remove(ENTROPY_MNEMONIC_STR)
            clear()
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