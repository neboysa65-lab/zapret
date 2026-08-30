import 'dart:convert';
import 'dart:typed_data';

import 'package:crypto/crypto.dart';
import 'package:pointycastle/export.dart';

/// Крипто-идентичность устройства для протокола VLEE.
///
/// Крипто-схема — 1:1 с продовой GW (gwishod-new/api/src/utils/crypto_gw.py):
///   key = SHA-256(ECDH_shared_X || "gw|key"), AES-256-GCM, AAD "gw-config-v1".
///
/// Пара ключей детерминированно выводится из seed-фразы пользователя:
///   privateKey = SHA-256(utf8(seed_phrase.trim())).
/// При переустановке приложения с той же фразой pubkey тот же — конверты
/// серверов продолжат расшифровываться. Формула наша (обе стороны стека
/// VLEE наши), от GW-приложения независима.
class VleeIdentity {
  static const String keyLabel = 'gw|key';
  static const String aad = 'gw-config-v1';

  final BigInt privateKey;
  final String publicKeyXyHex; // x||y, 128 hex — как шлёт GW-клиент

  VleeIdentity._(this.privateKey, this.publicKeyXyHex);

  /// Идентичность из seed-фразы.
  static VleeIdentity fromSeedPhrase(String seedPhrase) {
    final digest = sha256.convert(utf8.encode(seedPhrase.trim())).bytes;
    final priv = _bytesToBigInt(Uint8List.fromList(digest)) % _n;
    final pubPoint = (_G * priv)!;
    final x = _bigIntTo32(pubPoint.x!.toBigInteger()!);
    final y = _bigIntTo32(pubPoint.y!.toBigInteger()!);
    return VleeIdentity._(priv, _toHex(x) + _toHex(y));
  }

  /// Расшифровывает конверт сервера {eph, ct, iv} -> plaintext JSON строка.
  String decryptEnvelope({
    required String ephHex,
    required String ctBase64,
    required String ivBase64,
  }) {
    final ephBytes = _hexToBytes(ephHex);
    final curve = _domain.curve;
    final ECPoint ephPoint;
    if (ephBytes.length == 33) {
      ephPoint = curve.decodePoint(ephBytes)!;
    } else if (ephBytes.length == 64) {
      final uncompressed = Uint8List(65)..[0] = 4;
      uncompressed.setRange(1, 65, ephBytes);
      ephPoint = curve.decodePoint(uncompressed)!;
    } else if (ephBytes.length == 65 && ephBytes[0] == 0) {
      final uncompressed = Uint8List(65)..[0] = 4;
      uncompressed.setRange(1, 65, ephBytes.sublist(1));
      ephPoint = curve.decodePoint(uncompressed)!;
    } else {
      throw ArgumentError('unsupported eph point length ${ephBytes.length}');
    }

    // ECDH: shared = privateKey * ephPoint, берём X-координату (32 байта).
    final sharedPoint = ephPoint * privateKey;
    final sharedX = _bigIntTo32(sharedPoint!.x!.toBigInteger()!);

    // key = SHA-256(shared_X || "gw|key")
    final keyMaterial =
        Uint8List.fromList([...sharedX, ...utf8.encode(keyLabel)]);
    final aesKey = Uint8List.fromList(sha256.convert(keyMaterial).bytes);

    final iv = base64Decode(ivBase64);
    final ct = base64Decode(ctBase64);

    // AES-256-GCM, AAD = "gw-config-v1" (константы идентичны продовой GW).
    final cipher = GCMBlockCipher(AESEngine())
      ..init(
        false, // decrypt
        AEADParameters(
          KeyParameter(aesKey),
          128,
          iv,
          Uint8List.fromList(utf8.encode(aad)),
        ),
      );
    final plain = cipher.process(ct);
    return utf8.decode(plain);
  }

  // ── secp256k1 ────────────────────────────────────────────────────────────
  static final ECDomainParameters _domain =
      ECDomainParameters('secp256k1');
  static final BigInt _n = _domain.n;
  static final ECPoint _G = _domain.G;

  static BigInt _bytesToBigInt(Uint8List bytes) => BigInt.parse(
        _toHex(bytes),
        radix: 16,
      );

  static Uint8List _bigIntTo32(BigInt value) {
    var hex = value.toRadixString(16);
    if (hex.length.isOdd) hex = '0$hex';
    if (hex.length > 64) hex = hex.substring(hex.length - 64);
    final out = Uint8List(32);
    final bytes = _hexToBytes(hex.padLeft(64, '0'));
    out.setAll(0, bytes);
    return out;
  }

  static Uint8List _hexToBytes(String hex) {
    final clean = hex.length.isOdd ? '0$hex' : hex;
    final out = Uint8List(clean.length ~/ 2);
    for (var i = 0; i < out.length; i++) {
      out[i] = int.parse(clean.substring(i * 2, i * 2 + 2), radix: 16);
    }
    return out;
  }

  static String _toHex(Uint8List bytes) =>
      bytes.map((b) => b.toRadixString(16).padLeft(2, '0')).join();
}
