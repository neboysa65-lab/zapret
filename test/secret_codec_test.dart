import 'package:flutter_test/flutter_test.dart';
import 'package:vpn_subscription_app/config/api_config.dart';
import 'package:vpn_subscription_app/config/secret_codec.dart';

void main() {
  group('SecretCodec', () {
    test('decode возвращает точные URL для реальных векторов', () {
      expect(
        SecretCodec.decode(
            'gwenc1:fKtm2IhqepasY5l2zXjGzXC1YL/UabSXu2KMvA=='),
        'https://media-ru.gwapp.store',
      );
      expect(
        SecretCodec.decode('gwenc1:asmOrGvEnV6Kd89rx9p8r2Tc1Gm0l7tijLw='),
        'https://panel.gw-vpn.click',
      );
      expect(
        SecretCodec.decode('gwenc1:kJ1hgqq8kqTQoZDTsbtw2dRptJe7Yoy8'),
        'https://sub.gw-vpn.click',
      );
    });

    test('roundtrip на разных строках', () {
      const samples = <String>[
        'https://media-ru.gwapp.store',
        'https://*.api.example.com/gw/vpn/api/v1/',
        'https://example.com/pay?userId=%s',
        'a',
        'привет-мир https://x.y/z?q=1',
      ];
      for (final s in samples) {
        expect(SecretCodec.decode(SecretCodec.encode(s)), s, reason: s);
      }
    });

    test('plaintext проходит как есть (legacy passthrough)', () {
      expect(
          SecretCodec.decode('https://plain.example.com'),
          'https://plain.example.com');
      expect(SecretCodec.decode(''), '');
      expect(SecretCodec.decode(null), '');
      expect(SecretCodec.decode('  https://x.y  '), 'https://x.y');
    });

    test('битое закодированное значение не бросает исключений', () {
      const broken = 'gwenc1:!!!broken!!!';
      expect(SecretCodec.decode(broken), broken);
      expect(SecretCodec.decode('gwenc1:'), '');
      expect(SecretCodec.isEncoded(' gwenc1:abc'), isTrue);
      expect(SecretCodec.isEncoded('https://x'), isFalse);
      expect(SecretCodec.isEncoded(null), isFalse);
    });

    test('дефолты ApiConfig расшифровываются в ожидаемые домены', () {
      expect(ApiConfig.defaultBaseUrl, 'https://media-ru.gwapp.store');
      expect(ApiConfig.baseUrl, 'https://media-ru.gwapp.store');
      expect(ApiConfig.panelBaseUrl, 'https://panel.gw-vpn.click');
      expect(ApiConfig.subBaseUrl, 'https://sub.gw-vpn.click');
      expect(SecretCodec.isEncoded(ApiConfig.defaultBaseUrlEncrypted), isTrue);
    });
  });
}
