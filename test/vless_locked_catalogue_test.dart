import 'dart:convert';

import 'package:flutter_secure_storage/flutter_secure_storage.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:http/http.dart' as http;
import 'package:http/testing.dart';
import 'package:shared_preferences/shared_preferences.dart';
import 'package:vpn_subscription_app/models/vless_config_model.dart';
import 'package:vpn_subscription_app/services/api_service.dart';
import 'package:vpn_subscription_app/services/auth_service.dart';

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();
  setUp(() {
    FlutterSecureStorage.setMockInitialValues({
      'seed_phrase': 'test-account-a',
    });
    SharedPreferences.setMockInitialValues({});
  });

  const vless = 'vless://test-id@127.0.0.1:443?security=tls#Test%20node';
  final lockedObject = {
    'locked': true,
    'premium': true,
    'remarks': 'VLESSAPP RU 01',
    'address': 'vleesvpn-app-ru01.gw-vpn.click',
    'port': 443,
    'country': 'Russia',
    'country_code': 'ru',
    'priority': 5,
  };
  // Same entry delivered as a JSON string — both transport forms must work.
  final lockedJsonString = jsonEncode(lockedObject);

  ApiService apiFor(Object Function(dynamic) body) => ApiService(
        client: MockClient((request) async {
          if (request.url.path.contains('/awg/')) {
            return http.Response(jsonEncode({'data': <String>[]}), 200);
          }
          return http.Response(jsonEncode(body(null)), 200);
        }),
      );

  test('object entries in configs[] become locked VLESS rows', () async {
    final service = apiFor((_) => {'configs': [lockedObject, vless]});
    final servers = await service.fetchServers();

    final locked = servers.singleWhere((s) => s.locked);
    expect(locked.protocol, 'vless');
    expect(locked.serverName, 'VLESSAPP RU 01');
    expect(locked.country, 'Russia');
    expect(locked.flag, '🇷🇺');
    expect(locked.premium, isTrue);
    expect(locked.available, isFalse);
    expect(locked.canConnect, isFalse);
    expect(locked.uuid, isEmpty); // nothing diallable leaks to free users
    expect(locked.originalUrl, isEmpty);
    expect(locked.id.startsWith('locked|'), isTrue);

    // The real node still arrives next to the locked one.
    expect(servers.where((s) => !s.locked).map((s) => s.protocol), ['vless']);
  });

  test('locked entry delivered as a JSON string parses the same way', () async {
    final service = apiFor((_) => {'configs': [lockedJsonString]});
    final servers = await service.fetchServers();

    final locked = servers.single;
    expect(locked.serverName, 'VLESSAPP RU 01');
    expect(locked.locked, isTrue);
    expect(locked.canConnect, isFalse);
  });

  test('dedupe: a locked row never collapses into a real node', () {
    final fromObject = VlessConfig.fromLockedEntry(lockedObject);
    final fromString = VlessConfig.fromJsonConfig(
      jsonDecode(lockedJsonString) as Map<String, dynamic>,
    );
    expect(fromObject.id, fromString.id);

    final real = VlessConfig.fromUrl(vless);
    final merged = VlessConfig.dedupe([fromObject, real]);
    expect(merged.length, 2);
    expect(merged.where((s) => s.locked), isNotEmpty);
  });

  test('name/country/flag fall back to remarks hints', () {
    final bare = VlessConfig.fromLockedEntry({
      'locked': true,
      'remarks': '🇩🇪 Germany 2',
    });
    expect(bare.serverName, '🇩🇪 Germany 2');
    expect(bare.country, 'Germany');
    expect(bare.flag, '🇩🇪');
    expect(bare.port, 0);
    expect(bare.id, 'locked|:0:🇩🇪 Germany 2');
  });

  test('locked rows are not cached for offline reuse', () async {
    final service = apiFor((_) => {'configs': [lockedObject, vless]});
    await service.fetchServers();

    final prefs = await SharedPreferences.getInstance();
    final cached = prefs.getString(
      'vless_configs_cache_${AuthService.computeSeedHash('test-account-a')}',
    );
    // Only the real node is persisted: the lock is server-driven state.
    expect(cached, isNotNull);
    expect(cached, contains('vless://'));
    expect(cached, isNot(contains('locked')));
  });
}
