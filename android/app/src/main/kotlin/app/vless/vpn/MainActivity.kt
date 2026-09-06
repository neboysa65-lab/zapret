package app.vless.vpn

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.VpnService
import android.os.Build
import com.witvpn.vlee.VleeManager
import com.witvpn.vlee.model.VleeServerConfig
import io.flutter.embedding.android.FlutterActivity
import io.flutter.embedding.engine.FlutterEngine
import io.flutter.plugin.common.EventChannel
import io.flutter.plugin.common.MethodChannel
import io.github.vyomtunnel.sdk.VyomState
import io.github.vyomtunnel.sdk.VyomVpnManager

/**
 * Hosts the Flutter UI and bridges the protocol engines to Dart:
 *  - Hysteria2 engine (ported from gwapk's vyom-tun-sdk) via [METHOD_CHANNEL];
 *  - VLEE engine (SSH tunnel, 1:1 port of gwvpn-new's gw-tunnel) via
 *    [VLEE_METHOD_CHANNEL].
 *
 * The existing VLESS/VMESS path keeps using flutter_v2ray_plus untouched.
 */
class MainActivity : FlutterActivity() {

    companion object {
        private const val METHOD_CHANNEL = "app.vless.vpn/hysteria2"
        private const val EVENT_CHANNEL = "app.vless.vpn/hysteria2/status"
        private const val VLEE_METHOD_CHANNEL = "app.vless.vpn/vlee"
        private const val VLEE_EVENT_CHANNEL = "app.vless.vpn/vlee/status"
        private const val VLEE_VPN_REQUEST = 10021
    }

    private var statusSink: EventChannel.EventSink? = null
    private var stateReceiver: BroadcastReceiver? = null

    private var vleeStatusSink: EventChannel.EventSink? = null
    private var vleeListener: VleeManager.StateListener? = null
    /** Конфиг, ожидающий grant'а VpnService.prepare(). */
    private var pendingVleeConfig: VleeServerConfig? = null
    private var pendingVleeExcluded: List<String> = emptyList()
    private var pendingVleeAllowed: List<String> = emptyList()

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

        // --- VLEE engine ---
        MethodChannel(flutterEngine.dartExecutor.binaryMessenger, VLEE_METHOD_CHANNEL)
            .setMethodCallHandler { call, result ->
                when (call.method) {
                    "connect" -> {
                        val configJson = call.argument<String>("config")
                        val excluded = call.argument<List<String>>("excludedApps") ?: emptyList()
                        val allowed = call.argument<List<String>>("allowedApps") ?: emptyList()
                        val config = configJson?.let { VleeManager.configFromIntentExtra(it) }
                        if (config == null || config.ipAddress.isBlank()) {
                            result.error("BAD_ARGS", "Missing vlee config", null)
                        } else {
                            val prepare = VpnService.prepare(this)
                            if (prepare != null) {
                                // Сначала разрешение: конфиг ждёт в pendingVleeConfig,
                                // старт происходит в onActivityResult.
                                pendingVleeConfig = config
                                pendingVleeExcluded = excluded
                                pendingVleeAllowed = allowed
                                startActivityForResult(prepare, VLEE_VPN_REQUEST)
                                result.success(true)
                            } else {
                                VleeManager.startVpn(this, config, excluded, allowed)
                                result.success(true)
                            }
                        }
                    }

                    "disconnect" -> {
                        VleeManager.stopVpn(applicationContext)
                        result.success(true)
                    }

                    "isConnected" -> result.success(VleeManager.isConnected())

                    else -> result.notImplemented()
                }
            }

        EventChannel(flutterEngine.dartExecutor.binaryMessenger, VLEE_EVENT_CHANNEL)
            .setStreamHandler(object : EventChannel.StreamHandler {
                override fun onListen(arguments: Any?, events: EventChannel.EventSink?) {
                    vleeStatusSink = events
                    val listener = object : VleeManager.StateListener {
                        override fun onStateChange(state: VleeManager.State) {
                            emitVlee("STATE:${state.name}")
                        }

                        override fun onTrafficUpdate(rx: Long, tx: Long) {
                            // GW-модуль шлёт (rx=download, tx=upload); формат события
                            // TRAFFIC:up:down — как у Hysteria2-канала.
                            emitVlee("TRAFFIC:$tx:$rx")
                        }

                        override fun onReconnecting(active: Boolean) {
                            emitVlee("RECONNECTING:$active")
                        }
                    }
                    vleeListener = listener
                    VleeManager.registerListener(listener)
                    // Текущее состояние сразу, чтобы Dart был синхронен.
                    events?.success("STATE:${VleeManager.state.name}")
                }

                override fun onCancel(arguments: Any?) {
                    vleeListener?.let { VleeManager.unregisterListener(it) }
                    vleeListener = null
                    vleeStatusSink = null
                }
            })
    }

    /**
     * События VLEE приходят из фоновых потоков туннеля (воркер коннекта,
     * супервизор, stats-таймер). EventSink можно дёргать ТОЛЬКО с main-потока —
     * события из фонового потока Flutter молча отбрасывает: туннель поднят,
     * уведомление показывает "Connected", а UI навечно висел в "Подключении".
     * CONNECTING прорывался только потому, что onStartCommand — main-поток.
     */
    private fun emitVlee(event: String) {
        runOnUiThread { vleeStatusSink?.success(event) }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        if (requestCode == VLEE_VPN_REQUEST) {
            val config = pendingVleeConfig
            pendingVleeConfig = null
            if (resultCode == RESULT_OK && config != null) {
                VleeManager.startVpn(this, config, pendingVleeExcluded, pendingVleeAllowed)
            }
        }
        super.onActivityResult(requestCode, resultCode, data)
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
        // Трёхаргный registerReceiver(receiver, filter, flags) существует только
        // с API 26; на Android 7 (API 24/25) он валил приложение NoSuchMethodError
        // ещё до первого кадра UI. На API < 26 флагов экспорта не существует —
        // динамические приёмники там и так всегда экспортируемые.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val flags =
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) Context.RECEIVER_NOT_EXPORTED else 0
            registerReceiver(receiver, filter, flags)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            registerReceiver(receiver, filter)
        }
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
