import 'dart:convert';
import 'package:flutter_test/flutter_test.dart';
import 'package:vpn_subscription_app/models/vless_config_model.dart';

const awgFixture = '''[Interface]
PrivateKey = AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=
Address = 10.0.0.2/32
DNS = 1.1.1.1
Jc = 4
Jmin = 40
Jmax = 70
S1 = 10
S2 = 20
S3 = 30
S4 = 40
H1 = 100-200
H2 = 300-400
H3 = 500-600
H4 = 700-800
I1 = <b 0xc000><r 10>
I5 = <r 12>
[Peer]
PublicKey = AQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQE=
AllowedIPs = 0.0.0.0/0, ::/0
Endpoint = [2001:db8::1]:51820
PersistentKeepalive = 25
''';

void main() {
  Map<String, dynamic> node({String version = '3.1'}) => {
    'id': 'node',
    'ip_address': '192.0.2.1',
    'country': 'Germany',
    'state': 'Berlin',
    'country_code': 'DE',
    'version': version,
    'config': base64.encode(utf8.encode(awgFixture)),
    'available': true,
  };
  for (final version in ['2.0', '3.1']) {
    test('AWG $version preserves all parameters and IPv6 endpoint', () {
      final server = VlessConfig.fromAwg(node(version: version));
      expect(server.protocolLabel, 'AWG $version');
      expect(server.address, '2001:db8::1');
      expect(server.port, 51820);
      expect(server.flag, '🇩🇪');
      expect(server.cleanName, 'Germany • Berlin');
      expect(base64.decode(server.awgConfig), utf8.encode(awgFixture));
      expect(server.copyWith(ping: 4).awgConfig, server.awgConfig);
      expect(server.hasJsonConfig, isFalse);
      expect(server.canConnect, isTrue);
    });
  }
  test(
    'locked metadata discards credentials; unavailable nodes cannot connect',
    () {
      final locked = VlessConfig.fromAwg({
        ...node(),
        'premium': true,
        'locked': true,
      });
      expect(locked.canConnect, isFalse);
      expect(locked.awgConfig, isEmpty);
      expect(locked.copyWith(ping: 1).locked, isTrue);
      expect(
        VlessConfig.fromAwg({...node(), 'available': false}).canConnect,
        isFalse,
      );
      expect(
        VlessConfig.fromAwg({...node(), 'config': ''}).canConnect,
        isFalse,
      );
    },
  );
  test('stable identity does not depend on per-user private key', () {
    final a = VlessConfig.fromAwg(node());
    final b = VlessConfig.fromAwg({
      ...node(),
      'config': awgFixture.replaceAll('10.0.0.2', '10.0.0.3'),
    });
    expect(a.id, b.id);
    expect(VlessConfig.dedupe([a, b]).length, 1);
    expect(a.id, isNot(contains('AAAA')));
  });
  test('malformed endpoint rejected without logging credentials', () {
    expect(
      () => VlessConfig.fromAwg({...node(), 'config': '[Interface]\n[Peer]'}),
      throwsFormatException,
    );
    expect(
      () => VlessConfig.fromAwg({...node(), 'config': 'not-base64'}),
      throwsFormatException,
    );
  });
}
