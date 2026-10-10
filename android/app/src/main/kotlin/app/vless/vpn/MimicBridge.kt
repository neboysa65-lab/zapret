package app.vless.vpn

import android.os.Handler
import android.os.Looper
import io.flutter.plugin.common.BinaryMessenger
import io.flutter.plugin.common.MethodChannel
import java.util.concurrent.Executors
import mimicmobile.Mimicmobile

/** Bridges the Mimic MTP transport engine (gomobile classes) to Dart. */
class MimicBridge(messenger: BinaryMessenger) {
    private val main = Handler(Looper.getMainLooper())
    private val executor = Executors.newSingleThreadExecutor()

    init {
        MethodChannel(messenger, "app.vless.vpn/mimic").setMethodCallHandler { call, result ->
            when (call.method) {
                "start" -> {
                    val server = call.argument<String>("server")
                    val secret = call.argument<String>("secret")
                    val domains = call.argument<String>("domains") ?: ""
                    val transport = call.argument<String>("transport") ?: "udp"
                    val pin = call.argument<String>("pin") ?: ""
                    val socksPort = call.argument<Int>("socksPort") ?: MimicPorts.SOCKS
                    val httpPort = call.argument<Int>("httpPort") ?: MimicPorts.HTTP
                    if (server.isNullOrEmpty() || secret.isNullOrEmpty()) {
                        result.error("BAD_ARGS", "server and secret are required", null)
                    } else {
                        // Handshake блокирует на секунды — не на main-потоке.
                        executor.execute {
                            val error = try {
                                Mimicmobile.start(
                                    server, secret, domains, transport, pin,
                                    socksPort.toLong(), httpPort.toLong(),
                                )
                                null
                            } catch (t: Throwable) {
                                t.message ?: "mimic engine start failed"
                            }
                            main.post {
                                if (error != null) result.error("ENGINE", error, null)
                                else result.success(true)
                            }
                        }
                    }
                }

                "stop" -> {
                    executor.execute {
                        try {
                            Mimicmobile.stop(
                                call.argument<String>("transport") ?: "udp",
                            )
                        } catch (_: Throwable) {
                        }
                    }
                    result.success(true)
                }

                "isConnected" -> result.success(
                    try {
                        Mimicmobile.isConnected(
                            call.argument<String>("transport") ?: "udp",
                        )
                    } catch (_: Throwable) {
                        false
                    }
                )

                else -> result.notImplemented()
            }
        }
    }
}

object MimicPorts {
    const val SOCKS = 7101
    const val HTTP = 7102
}
