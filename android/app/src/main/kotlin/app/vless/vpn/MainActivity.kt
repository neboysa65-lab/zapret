package app.vless.vpn

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import io.flutter.embedding.android.FlutterActivity
import io.flutter.embedding.engine.FlutterEngine
import io.flutter.plugin.common.EventChannel
import io.flutter.plugin.common.MethodChannel
import io.github.vyomtunnel.sdk.VyomState
import io.github.vyomtunnel.sdk.VyomVpnManager

/**
 * Hosts the Flutter UI and bridges the separate Hysteria2 engine
 * (ported from gwapk's vyom-tun-sdk) to Dart.
 *
 * The existing VLESS/VMESS path keeps using flutter_v2ray_plus untouched.
 * Only Hysteria2 servers are routed here via the [METHOD_CHANNEL].
 */
class MainActivity : FlutterActivity() {

    companion object {
        private const val METHOD_CHANNEL = "app.vless.vpn/hysteria2"
        private const val EVENT_CHANNEL = "app.vless.vpn/hysteria2/status"
    }

    private var statusSink: EventChannel.EventSink? = null
    private var stateReceiver: BroadcastReceiver? = null

    override fun configureFlutterEngine(flutterEngine: FlutterEngine) {
        super.configureFlutterEngine(flutterEngine)

        MethodChannel(flutterEngine.dartExecutor.binaryMessenger, METHOD_CHANNEL)
            .setMethodCallHandler { call, result ->
                when (call.method) {
                    "initialize" -> {
                        VyomVpnManager.initialize(applicationContext)
                        VyomVpnManager.setNotificationConfig(
                            VyomVpnManager.VyomNotificationConfig(
                                title = "Vless VPN",
                                content = "Hysteria2 connection is active",
                                iconResId = applicationInfo.icon.takeIf { it != 0 },
                                channelName = "VPN Service",
                            )
                        )
                        result.success(true)
                    }

                    "connect" -> {
                        val link = call.argument<String>("url")
                        if (link.isNullOrEmpty()) {
                            result.error("BAD_ARGS", "Missing hysteria2 url", null)
                        } else {
                            // Handles VpnService.prepare() + start internally.
                            VyomVpnManager.connectWithPermission(this, link)
                            result.success(true)
                        }
                    }

                    "disconnect" -> {
                        VyomVpnManager.stop(applicationContext)
                        result.success(true)
                    }

                    "isConnected" ->
                        result.success(VyomVpnManager.currentState == VyomState.CONNECTED)

                    "isPermissionGranted" ->
                        result.success(VyomVpnManager.isPermissionGranted(applicationContext))

                    else -> result.notImplemented()
                }
            }

        EventChannel(flutterEngine.dartExecutor.binaryMessenger, EVENT_CHANNEL)
            .setStreamHandler(object : EventChannel.StreamHandler {
                override fun onListen(arguments: Any?, events: EventChannel.EventSink?) {
                    statusSink = events
                    registerStateReceiver()
                    // Emit the current state immediately so Dart is in sync.
                    events?.success("STATE:${VyomVpnManager.currentState.name}")
                }

                override fun onCancel(arguments: Any?) {
                    unregisterStateReceiver()
                    statusSink = null
                }
            })
    }

    private fun registerStateReceiver() {
        if (stateReceiver != null) return
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                when (intent?.action) {
                    VyomVpnManager.ACTION_VPN_STATE -> {
                        val state = intent.getStringExtra("STATE") ?: return
                        statusSink?.success("STATE:$state")
                    }
                    VyomVpnManager.ACTION_VPN_TRAFFIC -> {
                        // Cumulative totals (bytes) since tunnel start.
                        val up = intent.getLongExtra("UP", 0L)
                        val down = intent.getLongExtra("DOWN", 0L)
                        statusSink?.success("TRAFFIC:$up:$down")
                    }
                }
            }
        }
        val filter = IntentFilter().apply {
            addAction(VyomVpnManager.ACTION_VPN_STATE)
            addAction(VyomVpnManager.ACTION_VPN_TRAFFIC)
        }
        val flags =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) Context.RECEIVER_NOT_EXPORTED else 0
        registerReceiver(receiver, filter, flags)
        stateReceiver = receiver
    }

    private fun unregisterStateReceiver() {
        stateReceiver?.let {
            try {
                unregisterReceiver(it)
            } catch (_: IllegalArgumentException) {
            }
        }
        stateReceiver = null
    }

    override fun onDestroy() {
        unregisterStateReceiver()
        super.onDestroy()
    }
}
