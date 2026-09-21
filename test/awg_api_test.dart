import 'dart:convert';
import 'package:flutter_test/flutter_test.dart';
import 'package:flutter_secure_storage/flutter_secure_storage.dart';
import 'package:http/http.dart' as http;
import 'package:http/testing.dart';
import 'package:shared_preferences/shared_preferences.dart';
import 'package:vpn_subscription_app/services/api_service.dart';
import 'awg_config_test.dart' show awgFixture;

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();
  setUp(() {
    FlutterSecureStorage.setMockInitialValues({
      'seed_phrase': 'test-account-a',
    });
    SharedPreferences.setMockInitialValues({});
  });
  const vless = 'vless://test-id@127.0.0.1:443?security=tls#Test';
  final awg = {
    'id': 'awg-test',
    'ip_address': '192.0.2.1',
    'version': '3.1',
    'config': base64.encode(utf8.encode(awgFixture)),
  };

  test(
    'merges protocols; malformed AWG entry does not remove valid nodes',
    () async {
      final service = ApiService(
        client: MockClient((request) async {
          expect(request.headers['X-Seed-Hash'], isNotEmpty);
          return http.Response(
            jsonEncode(
              request.url.path.contains('/awg/')
                  ? {
                      'data': [
                        awg,
                        {...awg, 'id': 'broken', 'config': 'bad'},
                      ],
                    }
                  : {
                      'configs': [vless],
                    },
            ),
            200,
          );
        }),
      );
      final servers = await service.fetchServers();
      expect(servers.map((s) => s.protocol), ['vless', 'awg']);
      final prefs = await SharedPreferences.getInstance();
      expect(
        prefs.getKeys().map(prefs.get).join(),
        isNot(contains(awg['config'])),
      );
    },
  );
  test('AWG outage preserves VLESS and VLESS outage preserves AWG', () async {
    for (final failAwg in [true, false]) {
      SharedPreferences.setMockInitialValues({});
      final service = ApiService(
        client: MockClient((request) async {
          final isAwg = request.url.path.contains('/awg/');
          if (isAwg == failAwg) return http.Response('unavailable', 503);
          return http.Response(
            jsonEncode(
              isAwg
                  ? {
                      'data': [awg],
                    }
                  : {
                      'configs': [vless],
                    },
            ),
            200,
          );
        }),
      );
      final servers = await service.fetchServers();
      expect(servers.single.protocol, failAwg ? 'vless' : 'awg');
    }
  });
  test(
    'empty entitlement response clears old cache; accounts never share cache',
    () async {
      var mode = 'populated';
      final service = ApiService(
        client: MockClient((request) async {
          if (mode == 'offline') throw http.ClientException('offline');
          return http.Response(
            jsonEncode({
              'configs': mode == 'empty' ? [] : [vless],
            }),
            200,
          );
        }),
      );
      expect(await service.fetchVlessConfigs(), hasLength(1));
      mode = 'offline';
      expect(await service.fetchVlessConfigs(), hasLength(1));
      FlutterSecureStorage.setMockInitialValues({
        'seed_phrase': 'test-account-b',
      });
      await expectLater(
        service.fetchVlessConfigs(),
        throwsA(isA<http.ClientException>()),
      );
      FlutterSecureStorage.setMockInitialValues({
        'seed_phrase': 'test-account-a',
      });
      mode = 'empty';
      expect(await service.fetchVlessConfigs(), isEmpty);
      mode = 'offline';
      await expectLater(
        service.fetchVlessConfigs(),
        throwsA(isA<http.ClientException>()),
      );
    },
  );
  test('old backend without AWG route remains usable', () async {
    final service = ApiService(
      client: MockClient((_) async => http.Response('', 404)),
    );
    expect(await service.fetchAwgConfigs(), isEmpty);
  });
}
