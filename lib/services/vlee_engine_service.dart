import 'dart:async';

import 'package:flutter/services.dart';

/// Connection states reported by the native VLEE engine
/// (mirrors VleeManager.State in the Android module).
enum VleeState {
  idle,
  connecting,
  connected,
  disconnecting,
  disconnected,
}

/// Dart bridge to the VLEE engine (android/vlee_engine — SSH-tunnel protocol,
/// 1:1 port of gwvpn-new's gw-tunnel module).
///
/// The engine owns the whole stack natively: SSH (sshj) over an HTTP-payload
/// injected socket -> local SOCKS5 -> hev-socks5-tunnel -> TUN. It accepts the
/// decrypted server envelope (k0..ka JSON) produced by VleeApiService.
class VleeEngineService {
  static const MethodChannel _channel = MethodChannel('app.vless.vpn/vlee');
  static const EventChannel _statusChannel =
      EventChannel('app.vless.vpn/vlee/status');

  Stream<String>? _rawStream;
  Stream<VleeState>? _statusStream;
  Stream<VleeTraffic>? _trafficStream;
  Stream<bool>? _reconnectingStream;

  /// Start the VLEE tunnel for the given decrypted server config (k0..ka JSON).
  ///
  /// The native side requests the VPN permission itself when needed and
  /// starts the tunnel after the grant; progress arrives via [statusStream].
  ///
  /// [excludedApps] — пакеты, исключённые из туннеля (split tunneling);
  /// [allowedApps] — режим «только выбранные приложения».
  Future<bool> connect(
    String configJson, {
    List<String> excludedApps = const [],
    List<String> allowedApps = const [],
  }) async {
    try {
      return await _channel.invokeMethod<bool>(
            'connect',
            {
              'config': configJson,
              'excludedApps': excludedApps,
              'allowedApps': allowedApps,
            },
          ) ??
          false;
    } on PlatformException {
      return false;
    } on MissingPluginException {
      return false;
    }
  }

  /// Stop the VLEE tunnel.
  Future<void> disconnect() async {
    try {
      await _channel.invokeMethod('disconnect');
    } on PlatformException {
      // Engine already stopped — nothing to do.
    }
  }

  Future<bool> isConnected() async {
    try {
      return await _channel.invokeMethod<bool>('isConnected') ?? false;
    } on PlatformException {
      return false;
    }
  }

  Stream<String> get _events {
    _rawStream ??= _statusChannel
        .receiveBroadcastStream()
        .map((event) => event?.toString() ?? '')
        .asBroadcastStream();
    return _rawStream!;
  }

  /// Broadcast stream of engine states (kept alive across subscriptions).
  Stream<VleeState> get statusStream {
    _statusStream ??= _events
        .where((e) => e.startsWith('STATE:'))
        .map((e) => _mapState(e.substring('STATE:'.length)))
        .asBroadcastStream();
    return _statusStream!;
  }

  /// Cumulative traffic totals (bytes since tunnel start), ~1 event/second.
  Stream<VleeTraffic> get trafficStream {
    _trafficStream ??= _events
        .where((e) => e.startsWith('TRAFFIC:'))
        .map((e) {
          final parts = e.split(':');
          return VleeTraffic(
            uploadTotal: parts.length > 1 ? int.tryParse(parts[1]) ?? 0 : 0,
            downloadTotal: parts.length > 2 ? int.tryParse(parts[2]) ?? 0 : 0,
          );
        })
        .asBroadcastStream();
    return _trafficStream!;
  }

  /// SSH-транспорт пересобирается под живым туннелем (обрыв Wi-Fi, переезд
  /// на LTE). Состояние остаётся CONNECTED.
  Stream<bool> get reconnectingStream {
    _reconnectingStream ??= _events
        .where((e) => e.startsWith('RECONNECTING:'))
        .map((e) => e.substring('RECONNECTING:'.length) == 'true')
        .asBroadcastStream();
    return _reconnectingStream!;
  }

  static VleeState _mapState(String raw) {
    switch (raw.toUpperCase()) {
      case 'CONNECTING':
        return VleeState.connecting;
      case 'CONNECTED':
        return VleeState.connected;
      case 'DISCONNECTING':
        return VleeState.disconnecting;
      case 'DISCONNECTED':
        return VleeState.disconnected;
      default:
        return VleeState.idle;
    }
  }
}

/// Cumulative byte counters reported by the engine's TUN layer.
class VleeTraffic {
  final int uploadTotal;
  final int downloadTotal;

  const VleeTraffic({required this.uploadTotal, required this.downloadTotal});
}
