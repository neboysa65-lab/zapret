import 'dart:async';

import 'package:flutter/services.dart';

/// Connection states reported by the native Hysteria2 engine
/// (mirrors VyomState in the Android module).
enum HysteriaState {
  idle,
  connecting,
  connected,
  stopping,
  disconnected,
  error,
}

/// Dart bridge to the dedicated Hysteria2 engine
/// (android/hysteria2_engine, ported from gwapk's vyom-tun-sdk).
///
/// This engine is fully separate from flutter_v2ray_plus:
/// - its own VpnService (`:hysteria2_process`) with TUN + hev-socks5-tunnel;
/// - its own Xray core (libxray.so with the `hysteria` outbound, version 2);
/// - it accepts a canonical `hysteria2://` URL and builds the Xray JSON
///   natively (LinkParser), so local ports always match its TUN bridge.
class HysteriaEngineService {
  static const MethodChannel _channel =
      MethodChannel('app.vless.vpn/hysteria2');
  static const EventChannel _statusChannel =
      EventChannel('app.vless.vpn/hysteria2/status');

  bool _initialized = false;
  Stream<String>? _rawStream;
  Stream<HysteriaState>? _statusStream;
  Stream<HysteriaTraffic>? _trafficStream;

  /// One-time engine init (copies optional assets, sets notification branding).
  Future<bool> initialize() async {
    if (_initialized) return true;
    try {
      final ok = await _channel.invokeMethod<bool>('initialize') ?? false;
      _initialized = ok;
      return ok;
    } on PlatformException {
      return false;
    } on MissingPluginException {
      // Non-Android platform or engine not linked in this build.
      return false;
    }
  }

  /// Start the Hysteria2 tunnel for the given `hysteria2://` URL.
  ///
  /// The native side requests the VPN permission itself when needed
  /// (VyomPermissionActivity) and restarts the tunnel after the grant.
  Future<bool> connect(String hysteria2Url) async {
    await initialize();
    try {
      return await _channel.invokeMethod<bool>(
            'connect',
            {'url': hysteria2Url},
          ) ??
          false;
    } on PlatformException {
      return false;
    }
  }

  /// Stop the Hysteria2 tunnel.
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
  Stream<HysteriaState> get statusStream {
    _statusStream ??= _events
        .where((e) => e.startsWith('STATE:'))
        .map((e) => _mapState(e.substring('STATE:'.length)))
        .asBroadcastStream();
    return _statusStream!;
  }

  /// Cumulative traffic totals (bytes since tunnel start), ~1 event/second.
  Stream<HysteriaTraffic> get trafficStream {
    _trafficStream ??= _events
        .where((e) => e.startsWith('TRAFFIC:'))
        .map((e) {
          final parts = e.split(':');
          return HysteriaTraffic(
            uploadTotal: parts.length > 1 ? int.tryParse(parts[1]) ?? 0 : 0,
            downloadTotal: parts.length > 2 ? int.tryParse(parts[2]) ?? 0 : 0,
          );
        })
        .asBroadcastStream();
    return _trafficStream!;
  }

  static HysteriaState _mapState(String raw) {
    switch (raw.toUpperCase()) {
      case 'CONNECTING':
        return HysteriaState.connecting;
      case 'CONNECTED':
        return HysteriaState.connected;
      case 'STOPPING':
        return HysteriaState.stopping;
      case 'DISCONNECTED':
        return HysteriaState.disconnected;
      case 'ERROR':
        return HysteriaState.error;
      default:
        return HysteriaState.idle;
    }
  }
}

/// Cumulative byte counters reported by the engine's TUN layer.
class HysteriaTraffic {
  final int uploadTotal;
  final int downloadTotal;

  const HysteriaTraffic({required this.uploadTotal, required this.downloadTotal});
}
