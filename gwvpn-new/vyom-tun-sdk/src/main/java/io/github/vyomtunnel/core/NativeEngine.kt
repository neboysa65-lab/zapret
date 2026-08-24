package io.github.vyomtunnel.core

import android.util.Log

internal object NativeEngine {
    private const val TAG = "NativeEngine"

    var isLoaded = false
        private set

    init {
        try {
            System.loadLibrary("xray")
            Log.i(TAG, "Loaded xray")
            System.loadLibrary("hev-socks5-tunnel")
            Log.i(TAG, "Loaded hev-socks5-tunnel")
            System.loadLibrary("vyom-v2ray")
            Log.i(TAG, "Loaded vyom-v2ray")
            isLoaded = true
        } catch (e: UnsatisfiedLinkError) {
            Log.e(TAG, "Failed to load native library", e)
        }
    }

    external fun startXray(config: String, assetPath: String): Int

    external fun stopXray()

    external fun validateConfig(config: String, assetPath: String): String?
}