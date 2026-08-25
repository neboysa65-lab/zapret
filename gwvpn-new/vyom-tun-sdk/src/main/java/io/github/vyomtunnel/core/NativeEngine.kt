package io.github.vyomtunnel.core

import android.util.Log

internal object NativeEngine {
    private const val TAG = "NativeEngine"

    /**
     * All three libraries loaded. startXray/stopXray/validateConfig are only
     * callable when this is true, because those JNI entry points live in
     * libvyom-v2ray.so (it exports
     * Java_io_github_vyomtunnel_core_NativeEngine_*), not in libxray.so.
     */
    var isLoaded = false
        private set

    /**
     * Which library failed, and why. Each library is loaded in its own
     * try/catch: a single catch around all three could not tell you whether
     * the engine, the tunnel or the JNI bridge was the missing one, and the
     * bridge going missing from jniLibs is exactly the failure that silently
     * disabled the VLESS path.
     */
    var loadErrors: Map<String, String> = emptyMap()
        private set

    private fun load(name: String, errors: MutableMap<String, String>): Boolean =
        try {
            System.loadLibrary(name)
            Log.i(TAG, "Loaded $name")
            true
        } catch (t: Throwable) {
            errors[name] = t.message ?: t.toString()
            Log.e(TAG, "Failed to load lib$name.so", t)
            false
        }

    init {
        val errors = LinkedHashMap<String, String>()
        // libvyom-v2ray.so DT_NEEDs both of the others, so load them first.
        val xray = load("xray", errors)
        val tunnel = load("hev-socks5-tunnel", errors)
        val bridge = load("vyom-v2ray", errors)
        loadErrors = errors
        isLoaded = xray && tunnel && bridge
        if (!isLoaded) {
            Log.e(
                TAG,
                "native engine unavailable; missing: ${errors.keys.joinToString()} — " +
                    "check that every lib*.so is packaged in vyom-tun-sdk/src/main/jniLibs " +
                    "for each ABI listed in the app's abiFilters"
            )
        }
    }

    external fun startXray(config: String, assetPath: String): Int

    external fun stopXray()

    external fun validateConfig(config: String, assetPath: String): String?
}
