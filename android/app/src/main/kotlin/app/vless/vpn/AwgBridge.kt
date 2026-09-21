package app.vless.vpn

import android.app.Activity
import android.content.Intent
import android.net.VpnService
import io.flutter.plugin.common.BinaryMessenger
import io.flutter.plugin.common.EventChannel
import io.flutter.plugin.common.MethodChannel

class AwgBridge(private val activity: Activity, messenger: BinaryMessenger) {
    companion object { const val PERMISSION_REQUEST = 0xA631 }
    private val control = MethodChannel(messenger, "app.vless.vpn/awg")
    private val events = EventChannel(messenger, "app.vless.vpn/awg/status")
    private var pendingPermission: MethodChannel.Result? = null

    init {
        control.setMethodCallHandler { call, result ->
            when (call.method) {
                "initialize" -> result.success(true)
                "requestPermission" -> {
                    val intent = VpnService.prepare(activity)
                    if (intent == null) result.success(true)
                    else if (pendingPermission != null) result.error("BUSY", "Permission pending", null)
                    else {
                        pendingPermission = result
                        activity.startActivityForResult(intent, PERMISSION_REQUEST)
                    }
                }
                "connect" -> {
                    val config = call.argument<String>("awgConfig")
                    if (config.isNullOrBlank() || VpnService.prepare(activity) != null) {
                        result.error("INVALID", "Missing AWG config or VPN permission", null)
                    } else {
                        AwgManager.connect(activity.applicationContext, config,
                            call.argument<List<String>>("blockedApps") ?: emptyList()) { error ->
                            if (error == null) result.success(true)
                            else result.error("AWG_CONNECT", error, null)
                        }
                    }
                }
                "disconnect" -> AwgManager.disconnect { result.success(it) }
                else -> result.notImplemented()
            }
        }
        events.setStreamHandler(object : EventChannel.StreamHandler {
            override fun onListen(arguments: Any?, sink: EventChannel.EventSink) {
                AwgManager.listener = { sink.success(it) }
                sink.success(AwgManager.snapshot)
            }
            override fun onCancel(arguments: Any?) { AwgManager.listener = null }
        })
    }

    fun onActivityResult(requestCode: Int, resultCode: Int): Boolean {
        if (requestCode != PERMISSION_REQUEST) return false
        pendingPermission?.success(resultCode == Activity.RESULT_OK)
        pendingPermission = null
        return true
    }

    fun dispose() {
        pendingPermission?.success(false)
        pendingPermission = null
        AwgManager.listener = null
        control.setMethodCallHandler(null)
        events.setStreamHandler(null)
    }
}
