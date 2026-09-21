package app.vless.vpn

import android.app.Activity
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Process
import android.view.Gravity
import android.widget.TextView
import java.io.File
import kotlinx.coroutines.*

class LaunchActivity : Activity() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        scope.launch {
            val complete = withContext(Dispatchers.IO) {
                NativeLibraryProbe.hasFlutter(
                    File(applicationInfo.nativeLibraryDir),
                    (listOf(applicationInfo.sourceDir) + applicationInfo.splitSourceDirs.orEmpty()).map(::File),
                    (if (Process.is64Bit()) Build.SUPPORTED_64_BIT_ABIS else Build.SUPPORTED_32_BIT_ABIS).toList(),
                )
            }
            if (complete) {
                startActivity(Intent(this@LaunchActivity, MainActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                })
                finish()
            } else {
                // A copied base APK cannot run without its ABI split.
                setContentView(TextView(this@LaunchActivity).apply {
                    setText(R.string.incomplete_installation)
                    setTextColor(android.graphics.Color.WHITE)
                    setBackgroundColor(android.graphics.Color.rgb(15, 17, 21))
                    textSize = 18f
                    gravity = Gravity.CENTER
                    val padding = (24 * resources.displayMetrics.density).toInt()
                    setPadding(padding, padding, padding, padding)
                })
            }
        }
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }
}
