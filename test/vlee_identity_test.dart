import 'dart:convert';

import 'package:crypto/crypto.dart';

import 'package:flutter_test/flutter_test.dart';
import 'package:vpn_subscription_app/services/vlee_identity.dart';

void main() {
  // Вектор сгенерирован серверной стороной (tgbot_src, crypto_vlee.py —
  // точная копия продовой crypto_gw.py): secp256k1 ECDH + AES-256-GCM,
  // приватный ключ = SHA-256("vlee test phrase").
  const privHex =
      'b9788d7d69bdbab15cc18b668262656c62d7b45b73ae0859d96474644b6f831a';
  const pubXy =
      '32e25348f73fd9fa95de2186510d962e4c734d1725adefc9e0bf27dcc8d6b4a1'
      'cd31f86e5a1dc6a526c6d9b5aeaac304b89e0afe96327e8b4f205de98b49c0a3';
  const eph =
      '02eff1c97ee9362375970b607539af90a48ef00d12e5a9f2356152449a6c42961f';
  const ct =
      '96bM7WGuv6nlgK9F6BTP9XNLG1OUsZYgZ5OgfSx/BXXLN8KC78jGCb2GFsf7GplhERn'
      '5OaVz1IBVWQlmODM4HJ5e3c9s1dool7OeuDLFNU2zBfKVwYX88rWVOl1sOVHNWpHwtq'
      'wHPYqk6927MxRgVTPC4GYuRmJkHr6naRiK5wXIjg7M18R/g//ViwktFNDaU4gNTovIm'
      'H8pA+zLj2DnPD6bb/cQJJmZj6k+7dLnFh/+wem83xEwsPke+PuyotXcTuLD8Yngxu7y'
      'rzjxP+G8vaYCVfDQYLD0ZBwvUQyib6cm7RES/IIZo8++90A=';
  const iv = 'vkvz05iL47qaF6Gs';

  const expected = <String, String>{
    'k0': '5.6.7.8',
    'k1': '5.6.7.8',
    'k2': '22',
    'k3': 'gw',
    'k4': 'S3cret!pass',
    'k5': 'cdn.example.com',
    'k6': '443',
    'k7': 'https',
    'k8': 'GET / HTTP/1.1[crlf]Host: [host][crlf]Upgrade: websocket[crlf][crlf]',
    'k9': 'cdn.example.com',
    'ka': '',
  };

  test('VleeIdentity: pubkey из seed-фразы детерминирован, 128 hex', () {
    final id1 = VleeIdentity.fromSeedPhrase('vlee test phrase');
    final id2 = VleeIdentity.fromSeedPhrase('  vlee test phrase  ');
    expect(id1.publicKeyXyHex.length, 128);
    expect(id1.publicKeyXyHex, id2.publicKeyXyHex);
    // совпадает с серверным вектором
    expect(sha256.convert(utf8.encode('vlee test phrase')).toString(),
        privHex);
    expect(id1.publicKeyXyHex, pubXy);
  });

  test('VleeIdentity: расшифровка конверта совпадает с серверным plaintext',
      () {
    final identity = VleeIdentity.fromSeedPhrase('vlee test phrase');
    final plain = identity.decryptEnvelope(
      ephHex: eph,
      ctBase64: ct,
      ivBase64: iv,
    );
    final map = jsonDecode(plain) as Map<String, dynamic>;
    expected.forEach((key, value) {
      expect(map[key]?.toString(), value, reason: 'key $key');
    });
  });

  test('VleeIdentity: битый конверт бросает исключение', () {
    final identity = VleeIdentity.fromSeedPhrase('vlee test phrase');
    expect(
      () => identity.decryptEnvelope(
        ephHex: eph,
        ctBase64: ct,
        ivBase64: base64Encode(List.filled(12, 0)),
      ),
      throwsA(anything),
    );
  });
}
