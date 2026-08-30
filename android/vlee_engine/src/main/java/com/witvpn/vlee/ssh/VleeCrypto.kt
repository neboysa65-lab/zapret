package com.witvpn.vlee.ssh

import android.util.Log
import java.security.Security
import org.bouncycastle.jce.provider.BouncyCastleProvider

/**
 * On Android the provider slot named "BC" is occupied by the stripped-down
 * platform BouncyCastle (com.android.org.bouncycastle), which lacks X25519/XDH.
 * sshj pins every lookup to that literal name ("no such algorithm: X25519 for
 * provider BC"), so bundling bcprov-jdk18on is not enough — the full provider
 * must take over the slot before the first key exchange.
 */
object VleeCrypto {
    private const val TAG = "VleeCrypto"

    @Volatile
    private var initialized = false

    @Synchronized
    fun ensureFullBouncyCastle() {
        if (initialized) return
        val full = BouncyCastleProvider()
        val current = Security.getProvider("BC")
        if (current != null && current.javaClass == full.javaClass) {
            initialized = true
            return
        }
        // addProvider alone would fail silently: the name "BC" is taken by the
        // platform provider, so evict it first.
        runCatching { Security.removeProvider("BC") }
        val position = Security.insertProviderAt(full, 1)
        Log.i(
            TAG,
            "full BouncyCastle ${full.name} ${full.version} installed at position $position" +
                if (position == -1) " (FAILED)" else ""
        )
        initialized = true
    }
}
