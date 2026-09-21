import 'dart:async';
import 'package:flutter/foundation.dart';
import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:vpn_subscription_app/models/vless_config_model.dart';
import 'package:vpn_subscription_app/services/awg_engine_service.dart';

void main() {
  final binding = TestWidgetsFlutterBinding.ensureInitialized();
  setUp(() => debugDefaultTargetPlatformOverride = TargetPlatform.android);
  tearDown(() {
    debugDefaultTargetPlatformOverride = null;
    binding.defaultBinaryMessenger.setMockMethodCallHandler(
      AwgEngineService.control,
      null,
    );
  });
  final server = VlessConfig(
    id: 'awg:1',
    serverName: 'AWG',
    address: 'test',
    port: 1,
    uuid: '',
    protocol: 'awg',
    awgConfig: 'fixture',
  );

  test('permission and split-tunneling use AWG channel, not Xray', () async {
    final calls = <MethodCall>[];
    binding.defaultBinaryMessenger.setMockMethodCallHandler(
      AwgEngineService.control,
      (call) async {
        calls.add(call);
        return true;
      },
    );
    final service = AwgEngineService();
    expect(
      await service.connect(server, blockedApps: ['com.example.app']),
      isTrue,
    );
    expect(calls.map((c) => c.method), ['requestPermission', 'connect']);
    expect(calls.last.arguments['blockedApps'], ['com.example.app']);
    expect(calls.last.arguments['awgConfig'], 'fixture');
    await service.disconnect();
    expect(calls.last.method, 'disconnect');
    service.dispose();
  });
  test(
    'disconnect during permission dialog prevents late tunnel start',
    () async {
      final permission = Completer<bool>();
      final calls = <String>[];
      binding.defaultBinaryMessenger.setMockMethodCallHandler(
        AwgEngineService.control,
        (call) async {
          calls.add(call.method);
          return call.method == 'requestPermission' ? permission.future : true;
        },
      );
      final service = AwgEngineService();
      final connecting = service.connect(server);
      await Future<void>.delayed(Duration.zero);
      await service.disconnect();
      permission.complete(true);
      expect(await connecting, isFalse);
      expect(calls, ['requestPermission', 'disconnect']);
      service.dispose();
    },
  );
  test('permission denial never calls connect', () async {
    binding.defaultBinaryMessenger.setMockMethodCallHandler(
      AwgEngineService.control,
      (call) async {
        expect(call.method, 'requestPermission');
        return false;
      },
    );
    final service = AwgEngineService();
    expect(await service.connect(server), isFalse);
    service.dispose();
  });
}
