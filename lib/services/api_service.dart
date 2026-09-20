import 'dart:convert';
import 'package:http/http.dart' as http;
import '../config/api_config.dart';
import '../models/vless_config_model.dart';
import '../services/auth_service.dart';
import 'vlee_api_service.dart';

import 'package:shared_preferences/shared_preferences.dart';

class ApiService {
  ApiService({http.Client? client}) : _client = client ?? http.Client();
  final http.Client _client;
  static const String _cacheKey = 'vless_configs_cache';
  final VleeApiService _vleeApi = VleeApiService();

  Future<List<VlessConfig>> fetchServers() async {
    // Independent catalogues: an AWG agent outage must not hide VLESS.
    Object? failure;
    Future<List<VlessConfig>> safely(Future<List<VlessConfig>> request) async {
      try {
        return await request;
      } catch (error) {
        failure = error;
        return [];
      }
    }

    final lists = await Future.wait([
      safely(fetchVlessConfigs()),
      safely(fetchAwgConfigs()),
    ]);
    final result = VlessConfig.dedupe(lists.expand((list) => list));
    if (result.isEmpty && failure != null) throw failure!;
    return result;
  }

  Future<List<VlessConfig>> fetchAwgConfigs() async {
    final seedHash = await AuthService.loadSeedHash();
    if (seedHash == null) throw StateError('Not authenticated');
    final response = await _client
        .get(
          Uri.parse('${ApiConfig.baseUrl}/api/v1/awg/servers'),
          headers: {'X-Seed-Hash': seedHash},
        )
        .timeout(const Duration(seconds: 15));
    if (response.statusCode == 404) return []; // API-first rolling deployment.
    if (response.statusCode != 200) {
      throw StateError('AWG HTTP ${response.statusCode}');
    }
    final body = jsonDecode(response.body) as Map<String, dynamic>;
    final result = <VlessConfig>[];
    for (final entry in body['data'] as List) {
      try {
        if (entry is Map<String, dynamic>) {
          result.add(VlessConfig.fromAwg(entry));
        }
      } on FormatException {
        // One malformed node must not remove other protocols from the catalogue.
      }
    }
    // Personal private keys are deliberately not persisted in SharedPreferences.
    return result;
  }

  void dispose() => _client.close();

  /// Fetch VLESS configs from our backend (backend proxies subscription, avoids device limit)
  ///
  /// VLEE-ноды приходят отдельным зашифрованным конвертом
  /// (`POST /api/v1/user/server_vlee`) и вливаются в общий список здесь:
  /// протокол определяется полем конфига, как для hysteria2.
  Future<List<VlessConfig>> fetchVlessConfigs() async {
    final seedHash = await AuthService.loadSeedHash();
    if (seedHash == null) throw StateError('Not authenticated');
    final cacheKey = '${_cacheKey}_$seedHash';
    List<VlessConfig> vleeConfigs = [];
    try {
      // VLEE грузится независимо от подписок: недоступность VLEE (нет нод,
      // юзер не найден, сеть) не должна ломать основной список.
      vleeConfigs = await _vleeApi.fetchVleeConfigs();
    } catch (e) {
      print('🌐 VLEE configs unavailable: $e');
    }

    try {
      print('🌐 Fetching configs from backend proxy...');
      final response = await _client
          .get(
            Uri.parse('${ApiConfig.baseUrl}/api/v1/configs/'),
            headers: {'X-Seed-Hash': seedHash},
          )
          .timeout(const Duration(seconds: 15));

      print('📡 HTTP Status: ${response.statusCode}');

      if (response.statusCode != 200) {
        if (response.statusCode == 401 || response.statusCode == 403) {
          await _saveToCache(cacheKey, '');
          return [];
        }
        throw Exception('Failed to load configs: ${response.statusCode}');
      }

      final Map<String, dynamic> body = jsonDecode(response.body);
      final List<dynamic> lines = body['configs'] as List<dynamic>;
      print('📋 Got ${lines.length} configs from backend');

      final List<String> stringLines =
          lines.whereType<String>().toList(growable: false);
      final allConfigs = [...vleeConfigs, ..._parseConfigs(stringLines)];

      {
        // Cache both raw URLs and JSON configs as one entry per line.
        // VLEE-конфиги в общий кэш не пишутся (их plaintext содержит ssh-пароли
        // и хранится отдельно в SecureStorage внутри VleeApiService).
        final cacheLines = allConfigs.where((c) => !c.isVlee).map((c) {
          if (c.hasJsonConfig) return c.rawJsonConfig!;
          if (c.originalUrl.isNotEmpty) return c.originalUrl;
          // Hysteria2 extracted from backend JSON: cache as canonical URL.
          if (c.isHysteria2) return c.hysteria2Url;
          return '';
        }).where((s) => s.isNotEmpty).toList();
        await _saveToCache(cacheKey, cacheLines.join('\n'));
      }

      print('✅ Total configs loaded: ${allConfigs.length}');
      return allConfigs;
    } catch (e) {
      print('💥 Error fetching configs: $e');
      print('📂 Attempting to load from cache...');

      final cached = await _loadFromCache(cacheKey);
      if (cached.isNotEmpty || vleeConfigs.isNotEmpty) {
        print('✅ Loaded ${cached.length} configs from cache');
        return [...vleeConfigs, ...cached];
      }

      rethrow;
    }
  }

  Future<void> _saveToCache(String key, String content) async {
    try {
      final prefs = await SharedPreferences.getInstance();
      await prefs.remove(_cacheKey);
      await prefs.setString(key, content);
    } catch (e) {
      print('⚠️ Failed to save cache: $e');
    }
  }

  Future<List<VlessConfig>> _loadFromCache(String key) async {
    try {
      final prefs = await SharedPreferences.getInstance();
      final content = prefs.getString(key);
      if (content != null && content.isNotEmpty) {
        return _parseConfigs(content.split('\n'));
      }
    } catch (e) {
      print('⚠️ Failed to load cache: $e');
    }
    return [];
  }

  List<VlessConfig> _parseConfigs(List<String> lines) {
    final List<VlessConfig> parsedConfigs = <VlessConfig>[];

    for (final line in lines) {
      final trimmed = line.trim();
      if (trimmed.isEmpty) continue;

      try {
        if (trimmed.startsWith('{')) {
          // Full Xray JSON config (with optional balancer + multiple outbounds).
          final decoded = jsonDecode(trimmed);
          if (decoded is Map<String, dynamic>) {
            parsedConfigs.add(VlessConfig.fromJsonConfig(decoded));
          }
        } else if (trimmed.startsWith('[')) {
          // Panels (Remnawave/Happ format) deliver a JSON *array* of configs
          // when the client advertises JSON support. Silently skipping it left
          // the server list empty for such subscriptions.
          final decoded = jsonDecode(trimmed);
          if (decoded is List) {
            for (final entry in decoded) {
              if (entry is Map<String, dynamic>) {
                parsedConfigs.add(VlessConfig.fromJsonConfig(entry));
              }
            }
          }
        } else if (trimmed.startsWith('vless://') ||
            trimmed.startsWith('ss://')) {
          parsedConfigs.add(VlessConfig.fromUrl(trimmed));
        } else if (trimmed.startsWith('hysteria2://') ||
            trimmed.startsWith('hy2://')) {
          // Hysteria2 — handled by the dedicated engine, not flutter_v2ray_plus.
          parsedConfigs.add(VlessConfig.fromHysteria2Url(trimmed));
        }
      } catch (e) {
        print('❌ Error parsing config line: $e');
        continue;
      }
    }

    // The same server is routinely published twice — once as a vless:// line and
    // once as a full JSON config — which is what put duplicate rows in the
    // server list. Collapse them before anything downstream sees the list.
    final deduped = VlessConfig.dedupe(parsedConfigs);
    final removed = parsedConfigs.length - deduped.length;
    if (removed > 0) {
      print('🧹 Collapsed $removed duplicate server entries');
    }

    return deduped;
  }
}
