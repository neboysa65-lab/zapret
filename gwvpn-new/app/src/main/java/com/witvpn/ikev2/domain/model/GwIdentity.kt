package com.witvpn.ikev2.domain.model

import android.content.Context
import com.witvpn.ikev2.data.SecureStore
import ru.zoommax.Crypto

object GwIdentity {

    private const val PREFS = "GLOBAL_APP_SHARES"
    private const val KEY_ENTROPY_STR = "app.greywebs.vpn.ENTROPY_STR"

    fun privateKeyHex(context: Context): String? {
        return try {
            val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val stored = prefs.getString(KEY_ENTROPY_STR, null)
            if (stored.isNullOrBlank()) return null
            // ENTROPY хранится sealed (Android Keystore); legacy-plaintext
            // читается и сразу мигрируется в sealed-вид.
            val entropyStr = SecureStore.unseal(stored) ?: return null
            if (!SecureStore.isSealed(stored)) {
                prefs.edit().putString(KEY_ENTROPY_STR, SecureStore.seal(entropyStr)).apply()
            }
            if (entropyStr.isBlank()) return null
            val entropy = entropyStr.toByteArray(Charsets.ISO_8859_1)
            val raw = Crypto.getPrivkeyAccount(entropy)
            if (raw.isNullOrBlank()) null else raw
        } catch (_: Exception) {
            null
        }
    }
}
