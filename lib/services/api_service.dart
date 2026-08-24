import 'dart:convert';
import 'package:http/http.dart' as http;
import '../config/api_config.dart';
import '../models/vless_config_model.dart';
import '../services/auth_service.dart';

import 'package:shared_preferences/shared_preferences.dart';

class ApiService {
  static const String _cacheKey = 'vless_configs_cache';

  /// Fetch VLESS configs from our backend (backend proxies subscription, avoids device limit)
  Future<List<VlessConfig>> fetchVlessConfigs() async {
    try {
      final seedHash = await AuthService.loadSeedHash();
      if (seedHash == null) throw Exception('Not authenticated');

      print('🌐 Fetching configs from backend proxy...');
      final response = await http.get(
        Uri.parse('${ApiConfig.baseUrl}/api/v1/configs/'),
        headers: {'X-Seed-Hash': seedHash},
      ).timeout(const Duration(seconds: 15));

      print('📡 HTTP Status: ${response.statusCode}');

      if (response.statusCode != 200) {
        throw Exception('Failed to load configs: ${response.statusCode}');
      }

      final Map<String, dynamic> body = jsonDecode(response.body);
      final List<dynamic> lines = body['configs'] as List<dynamic>;
      print('📋 Got ${lines.length} configs from backend');

      final List<String> stringLines =
          lines.whereType<String>().toList(growable: false);
      final allConfigs = _parseConfigs(stringLines);

      if (allConfigs.isNotEmpty) {
        // Cache both raw URLs and JSON configs as one entry per line.
        final cacheLines = allConfigs.map((c) {
          if (c.hasJsonConfig) return c.rawJsonConfig!;
          if (c.originalUrl.isNotEmpty) return c.originalUrl;
          // Hysteria2 extracted from backend JSON: cache as canonical URL.
          if (c.isHysteria2) return c.hysteria2Url;
          return '';
        }).where((s) => s.isNotEmpty).toList();
        await _saveToCache(cacheLines.join('\n'));
      }

      print('✅ Total configs loaded: ${allConfigs.length}');
      return allConfigs;
    } catch (e) {
      print('💥 Error fetching configs: $e');
      print('📂 Attempting to load from cache...');

      final cached = await _loadFromCache();
      if (cached.isNotEmpty) {
        print('✅ Loaded ${cached.length} configs from cache');
        return cached;
      }

      rethrow;
    }
  }

  Future<void> _saveToCache(String content) async {
    try {
      final prefs = await SharedPreferences.getInstance();
      await prefs.setString(_cacheKey, content);
    } catch (e) {
      print('⚠️ Failed to save cache: $e');
    }
  }

  Future<List<VlessConfig>> _loadFromCache() async {
    try {
      final prefs = await SharedPreferences.getInstance();
      final content = prefs.getString(_cacheKey);
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
