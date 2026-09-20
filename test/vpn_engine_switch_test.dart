import 'dart:async';
import 'package:flutter_test/flutter_test.dart';
import 'package:flutter_v2ray_plus/flutter_v2ray.dart';
import 'package:vpn_subscription_app/models/vless_config_model.dart';
import 'package:vpn_subscription_app/services/awg_engine_service.dart';
import 'package:vpn_subscription_app/services/vpn_service.dart';

class FakeXray extends FlutterV2ray {
  FakeXray(this.calls);
  final List<String> calls;
  Completer<bool>? permission;
  @override
  Future<bool> requestPermission() async =>
      permission == null ? true : permission!.future;
  @override
  Future<void> stopVless() async {
    calls.add('xray.stop');
  }

  @override
  Future<void> startVless({
    required String remark,
    required String config,
    List<String>? blockedApps,
    List<String>? bypassSubnets,
    List<String>? dnsServers,
    bool proxyOnly = false,
    String notificationDisconnectButtonName = 'DISCONNECT',
    bool showNotificationDisconnectButton = true,
    AutoDisconnect? autoDisconnect,
  }) async {
    calls.add('xray.start');
    expect(config, contains('"protocol":"vless"'));
    expect(config, isNot(contains('fixture-awg-key')));
  }
}

class FakeAwg extends AwgEngineService {
  FakeAwg(this.calls);
  final List<String> calls;
  @override
  Future<bool> connect(VlessConfig config, {List<String>? blockedApps}) async {
    calls.add('awg.start');
    expect(config.awgConfig, 'fixture-awg-key');
    return true;
  }

  @override
  Future<void> disconnect() async {
    calls.add('awg.stop');
  }
}

void main() {
  final vless = VlessConfig(
    id: 'vless:test',
    serverName: 'VLESS',
    address: '192.0.2.1',
    port: 443,
    uuid: 'test',
  );
  final awg = VlessConfig(
    id: 'awg:test',
    serverName: 'AWG',
    address: '192.0.2.1',
    port: 51820,
    uuid: '',
    protocol: 'awg',
    awgConfig: 'fixture-awg-key',
  );
  test(
    'AWG and VLESS use different engines and stop before switching',
    () async {
      final calls = <String>[];
      final service = VpnService(
        v2ray: FakeXray(calls),
        awgEngine: FakeAwg(calls),
      );
      expect(await service.connect(vless), isTrue);
      expect(await service.connect(awg), isTrue);
      expect(await service.connect(vless), isTrue);
      expect(calls, [
        'xray.start',
        'xray.stop',
        'awg.start',
        'awg.stop',
        'xray.start',
      ]);
      await service.disconnect();
      expect(calls.last, 'xray.stop');
      service.dispose();
    },
  );
  test(
    'cancelled Xray permission cannot start over a new AWG tunnel',
    () async {
      final calls = <String>[];
      final xray = FakeXray(calls)..permission = Completer<bool>();
      final service = VpnService(v2ray: xray, awgEngine: FakeAwg(calls));
      final connecting = service.connect(vless);
      await Future<void>.delayed(Duration.zero);
      await service.disconnect();
      expect(await service.connect(awg), isTrue);
      xray.permission!.complete(true);
      expect(await connecting, isFalse);
      expect(calls, ['xray.stop', 'awg.start']);
      expect(service.currentConfig?.isAwg, isTrue);
      service.dispose();
    },
  );
}
