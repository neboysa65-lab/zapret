import 'dart:async';
import 'package:flutter/foundation.dart';
import 'package:flutter/services.dart';
import 'package:flutter_v2ray_plus/flutter_v2ray.dart';
import '../models/vless_config_model.dart';

class AwgEngineService {
  static const control = MethodChannel('app.vless.vpn/awg');
  static const events = EventChannel('app.vless.vpn/awg/status');
  StreamSubscription<dynamic>? _subscription;
  final _status = StreamController<VlessStatus>.broadcast();
  Stream<VlessStatus> get statusStream => _status.stream;
  int _generation = 0;

  Future<void> initialize() async {
    if (kIsWeb || defaultTargetPlatform != TargetPlatform.android) return;
    await control.invokeMethod<bool>('initialize');
    _subscription ??= events.receiveBroadcastStream().listen((event) {
      if (event is! Map) return;
      int number(String key) => (event[key] as num?)?.toInt() ?? 0;
      _status.add(
        VlessStatus(
          state: event['state']?.toString() ?? 'DISCONNECTED',
          download: number('download'),
          upload: number('upload'),
          downloadSpeed: number('downloadSpeed'),
          uploadSpeed: number('uploadSpeed'),
        ),
      );
    }, onError: (_) => _status.add(VlessStatus(state: 'ERROR')));
  }

  Future<bool> connect(VlessConfig config, {List<String>? blockedApps}) async {
    if (kIsWeb || defaultTargetPlatform != TargetPlatform.android) {
      throw UnsupportedError('AWG поддерживается только на Android');
    }
    if (!config.canConnect) return false;
    final ticket = ++_generation;
    final granted =
        await control.invokeMethod<bool>('requestPermission') ?? false;
    if (!granted || ticket != _generation) return false;
    return await control.invokeMethod<bool>('connect', {
          'awgConfig': config.awgConfig,
          'blockedApps': blockedApps ?? <String>[],
        }) ??
        false;
  }

  Future<void> disconnect() async {
    _generation++;
    final stopped = await control.invokeMethod<bool>('disconnect') ?? false;
    if (!stopped) throw StateError('Не удалось остановить AWG');
  }

  void dispose() {
    _subscription?.cancel();
    _status.close();
  }
}
