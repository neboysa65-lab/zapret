package app.vless.vpn

import java.io.File
import java.util.zip.ZipFile

internal object NativeLibraryProbe {
    fun hasFlutter(nativeDir: File, apks: List<File>, abis: List<String>): Boolean {
        if (File(nativeDir, "libflutter.so").let { it.isFile && it.length() > 0 }) return true
        return apks.any { apk ->
            runCatching {
                ZipFile(apk).use { zip ->
                    abis.any { abi -> (zip.getEntry("lib/$abi/libflutter.so")?.size ?: 0) > 0 }
                }
            }.getOrDefault(false)
        }
    }
}
