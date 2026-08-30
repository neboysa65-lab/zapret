import 'dart:async';
import 'dart:convert';

import 'package:crypto/crypto.dart';
import 'package:flutter/services.dart';
import 'package:flutter_secure_storage/flutter_secure_storage.dart';
import 'package:http/http.dart' as http;

import '../config/api_config.dart';
import '../models/vless_config_model.dart';
import '../services/auth_service.dart';
import 'vlee_identity.dart';

/// Клиент раздачи VLEE-серверов.
///
/// Контракт — 1:1 с продовым GW (`POST /user/server_gw`, перенесён в наш API
/// как `POST /api/v1/user/server_vlee`): подпись hash/time/bundleId, pubkey
/// устройства, зашифрованные конверты per-server, расшифровка ECDH secp256k1 +
/// AES-256-GCM. Ошибка расшифровки ноды = нода дропается (как в GW-клиенте).
class VleeApiService {
  static const String _secureCacheKey = 'vlee_configs_cache';
  static const String _signSecret =
      'strongVPN!@#'; // секрет идентичен проду GW (VLEE_SIGN_SECRET)
  static const String _bundleId = 'app.vless.vpn';

  static const _secureStorage = FlutterSecureStorage(
    aOptions: AndroidOptions(encryptedSharedPreferences: true),
  );

  /// Забирает и расшифровывает VLEE-ноды для текущего пользователя.
  ///
  /// Возвращает пустой список, если VLEE ещё не развёрнут (эндпоинт отдал
  /// ошибку) или пользователь не аутентифицирован — это не ошибка загрузки
  /// общего списка серверов.
  Future<List<VlessConfig>> fetchVleeConfigs() async {
    final seedPhrase = await AuthService.loadSeedPhrase();
    final userId = await AuthService.loadUserId();
    if (seedPhrase == null || userId == null || userId.isEmpty) {
      return [];
    }

    try {
      final identity = VleeIdentity.fromSeedPhrase(seedPhrase);
      final timeSeconds = DateTime.now().millisecondsSinceEpoch ~/ 1000;
      final hash = sha256
          .convert(utf8.encode('$timeSeconds|$_signSecret'))
          .toString();

      // Body формируется как key=value&... БЕЗ URL-кодирования — так же,
      // как шлёт GW-клиент и как парсит сервер (значения — hex/UUID/числа).
      final body = 'userId=$userId'
          '&v=2'
          '&pubkey=${identity.publicKeyXyHex}'
          '&hash=$hash'
          '&time=$timeSeconds'
          '&bundleId=$_bundleId';

      final response = await http
          .post(
            Uri.parse('${ApiConfig.baseUrl}/api/v1/user/server_vlee'),
            headers: {'Content-Type': 'application/x-www-form-urlencoded'},
            body: body,
          )
          .timeout(const Duration(seconds: 15));

      if (response.statusCode != 200) {
        throw Exception('VLEE servers HTTP ${response.statusCode}');
      }

      final Map<String, dynamic> body_ = jsonDecode(response.body);
      if (body_['success'] != 1) {
        // Пользователь не найден / битый pubkey / VLEE не развёрнут —
        // просто нет VLEE-серверов, общий список грузится дальше.
        print('🌐 VLEE: success=${body_['success']} (${body_['message']})');
        return [];
      }

      final List<dynamic> data = (body_['data'] as List<dynamic>? ) ?? const [];
      final pairs = <(Map<String, dynamic>, Map<String, dynamic>)>[];
      for (final entry in data) {
        if (entry is! Map<String, dynamic>) continue;
        final meta = (entry['meta'] as Map<String, dynamic>?) ?? const {};
        final enc = entry['enc'] as Map<String, dynamic>?;
        if (enc == null) continue;
        try {
          final plain = identity.decryptEnvelope(
            ephHex: enc['eph'] as String,
            ctBase64: enc['ct'] as String,
            ivBase64: enc['iv'] as String,
          );
          final config = jsonDecode(plain) as Map<String, dynamic>;
          pairs.add((meta, config));
        } catch (e) {
          // Нода, не расшифровавшаяся на этом устройстве, бесполезна — дроп.
          print('🌐 VLEE: drop node ${meta['id']}: $e');
        }
      }

      final configs = pairs
          .map((p) => VlessConfig.fromVlee(meta: p.$1, config: p.$2))
          .toList();
      if (configs.isNotEmpty) {
        await _saveCache(pairs);
      }
      print('🌐 VLEE: ${configs.length} nodes decrypted');
      return configs;
    } on MissingPluginException {
      return [];
    } on PlatformException {
      return [];
    } catch (e) {
      print('🌐 VLEE fetch error: $e');
      // Фолбэк на защищённый кэш прошлой успешной загрузки.
      return _loadCache();
    }
  }

  /// Кэш пар (meta, config) — в FlutterSecureStorage (те же гарантии, что у
  /// seed-фразы): plaintext конверта содержит ssh-пароли нод.
  Future<void> _saveCache(
    List<(Map<String, dynamic>, Map<String, dynamic>)> pairs,
  ) async {
    try {
      final lines = pairs
          .map((p) => '${jsonEncode(p.$1)}\n${jsonEncode(p.$2)}')
          .join('\n---\n');
      await _secureStorage.write(key: _secureCacheKey, value: lines);
    } catch (e) {
      print('🌐 VLEE cache save failed: $e');
    }
  }

  Future<List<VlessConfig>> _loadCache() async {
    try {
      final raw = await _secureStorage.read(key: _secureCacheKey);
      if (raw == null || raw.isEmpty) return [];
      final configs = <VlessConfig>[];
      for (final block in raw.split('\n---\n')) {
        final parts = block.split('\n');
        if (parts.length < 2) continue;
        try {
          final meta = jsonDecode(parts[0]) as Map<String, dynamic>;
          final config = jsonDecode(parts[1]) as Map<String, dynamic>;
          configs.add(VlessConfig.fromVlee(meta: meta, config: config));
        } catch (_) {}
      }
      if (configs.isNotEmpty) print('🌐 VLEE: ${configs.length} nodes from cache');
      return configs;
    } catch (e) {
      return [];
    }
  }
}
