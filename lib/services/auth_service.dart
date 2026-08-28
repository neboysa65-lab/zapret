import 'dart:convert';
import 'package:crypto/crypto.dart';
import 'package:flutter/foundation.dart';
import 'package:flutter/services.dart';
import 'package:flutter_secure_storage/flutter_secure_storage.dart';

class AuthService {
  static const _storage = FlutterSecureStorage(
    aOptions: AndroidOptions(encryptedSharedPreferences: true),
  );
  static const _seedKey = 'seed_phrase';
  static const _userIdKey = 'user_id';

  static String computeSeedHash(String seedPhrase) {
    final bytes = utf8.encode(seedPhrase.trim());
    return sha256.convert(bytes).toString();
  }

  static Future<void> saveCredentials({
    required String seedPhrase,
    required String userId,
  }) async {
    await _storage.write(key: _seedKey, value: seedPhrase.trim());
    await _storage.write(key: _userIdKey, value: userId);
  }

  /// Безопасное чтение из storage с обработкой повреждённого Keystore.
  /// При BAD_DECRYPT очищает хранилище и возвращает null.
  static Future<String?> _safeRead(String key) async {
    try {
      return await _storage.read(key: key);
    } on PlatformException catch (e) {
      if (e.message?.contains('BadPaddingException') == true ||
          e.details?.toString().contains('BAD_DECRYPT') == true) {
        debugPrint('AuthService: Keystore corrupted, clearing storage');
        await clearCredentials();
        return null;
      }
      rethrow;
    }
  }

  static Future<String?> loadSeedPhrase() async {
    return _safeRead(_seedKey);
  }

  static Future<String?> loadUserId() async {
    return _safeRead(_userIdKey);
  }

  static Future<String?> loadSeedHash() async {
    final phrase = await loadSeedPhrase();
    if (phrase == null) return null;
    return computeSeedHash(phrase);
  }

  static Future<void> clearCredentials() async {
    await _storage.deleteAll();
  }
}
