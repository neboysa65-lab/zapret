package hev.htproxy

import android.net.VpnService
import android.util.Log

/**
 * This class must exist because the hev-socks5-tunnel native library
 * looks for it during initialization (JNI_OnLoad).
 *
 * The three methods below are registered by libhev-socks5-tunnel.so from its
 * JNI_OnLoad, so that library MUST be loaded before any of them is called.
 * Loading it here — in the companion initializer of the class that declares
 * them — guarantees exactly that: the JVM initialises a superclass before any
 * subclass, so every TProxyService subclass (GwVpnService, VyomVpnService)
 * gets the library no matter which tunnel the user starts first.
 *
 * Previously the only System.loadLibrary("hev-socks5-tunnel") in the whole app
 * lived in the lazy initializer of `internal object NativeEngine`, which the GW
 * module cannot even reference (it is internal to vyom-tun-sdk). A GW-only
 * session therefore called TProxyStartService with the library never loaded and
 * died with UnsatisfiedLinkError — an Error, not an Exception, so it slipped
 * through `catch (e: Exception)` and left the UI spinning on CONNECTING with no
 * log line, while the already-established tun routed every packet into a void.
 */
open class TProxyService : VpnService() {
    external fun TProxyStartService(configPath: String, fd: Int)
    external fun TProxyStopService()
    external fun TProxyGetStats(): LongArray

    companion object {
        private const val TAG = "TProxyService"

        /** libhev-socks5-tunnel.so is present and its natives are registered. */
        @JvmStatic
        var isTProxyLoaded: Boolean = false
            private set

        /** Human-readable reason loading failed, for surfacing in the UI. */
        @JvmStatic
        var tproxyLoadError: String? = null
            private set

        init {
            try {
                System.loadLibrary("hev-socks5-tunnel")
                isTProxyLoaded = true
                Log.i(TAG, "loaded libhev-socks5-tunnel.so")
            } catch (t: Throwable) {
                // Never let a missing .so abort class initialization: that would
                // surface as ExceptionInInitializerError and take the service
                // down before it can report anything useful.
                tproxyLoadError = t.message ?: t.toString()
                Log.e(TAG, "failed to load libhev-socks5-tunnel.so", t)
            }
        }
    }
}
