package io.github.vyomtunnel.sdk.utils

import android.content.Context
import java.io.File

object AssetUtils {
    fun copyAssets(context: Context) {
        val assets = listOf("geoip.dat", "geosite.dat")
        assets.forEach { fileName ->
            val destFile = File(context.filesDir, fileName)
            if (!destFile.exists()) {
                try {
                    context.assets.open(fileName).use { input ->
                        destFile.outputStream().use { output ->
                            input.copyTo(output)
                        }
                    }
                } catch (e: Exception) {
                    // Asset file not found in APK — skip silently
                    android.util.Log.w("AssetUtils", "Failed to copy asset: $fileName", e)
                }
            }
        }
    }
}