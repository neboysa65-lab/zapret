package app.vless.vpn

import java.io.File
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Assert.*
import org.junit.Test

class NativeLibraryProbeTest {
    @Test fun acceptsUniversalSplitAndExtractedLibraryButRejectsIncompleteInstall() {
        val dir = Files.createTempDirectory("native-probe").toFile()
        try {
            val base = apk(dir, "base.apk", null)
            val split = apk(dir, "split.apk", "arm64-v8a")
            val abis = listOf("arm64-v8a", "armeabi-v7a")
            assertFalse(NativeLibraryProbe.hasFlutter(dir, listOf(base), abis))
            assertTrue(NativeLibraryProbe.hasFlutter(dir, listOf(base, split), abis))
            assertTrue(NativeLibraryProbe.hasFlutter(dir, listOf(split), abis))
            assertFalse(NativeLibraryProbe.hasFlutter(dir, listOf(split), listOf("x86_64")))
            File(dir, "libflutter.so").writeBytes(byteArrayOf(1))
            assertTrue(NativeLibraryProbe.hasFlutter(dir, listOf(base), abis))
        } finally { dir.deleteRecursively() }
    }

    private fun apk(dir: File, name: String, abi: String?): File = File(dir, name).apply {
        ZipOutputStream(outputStream()).use { zip ->
            zip.putNextEntry(ZipEntry(if (abi == null) "AndroidManifest.xml" else "lib/$abi/libflutter.so"))
            zip.write(1)
            zip.closeEntry()
        }
    }
}
