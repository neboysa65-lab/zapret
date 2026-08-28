import 'dart:io';
import 'package:installed_apps/installed_apps.dart';
import 'package:installed_apps/app_info.dart';
import 'package:shared_preferences/shared_preferences.dart';

/// Режим Split Tunneling
enum SplitTunnelMode {
  /// VPN для ВСЕХ приложений (по умолчанию)
  allApps,

  /// VPN ТОЛЬКО для выбранных приложений
  selectedOnly,

  /// VPN для всех, КРОМЕ выбранных
  excludeSelected,
}

/// Сервис для управления Split Tunneling
class SplitTunnelService {
  static const String _keyEnabled = 'split_tunnel_enabled';
  static const String _keyMode = 'split_tunnel_mode';
  static const String _keySelectedApps = 'split_tunnel_selected_apps';

  /// Включён ли Split Tunneling
  static Future<bool> isEnabled() async {
    final prefs = await SharedPreferences.getInstance();
    return prefs.getBool(_keyEnabled) ?? false;
  }

  /// Установить состояние Split Tunneling
  static Future<void> setEnabled(bool enabled) async {
    final prefs = await SharedPreferences.getInstance();
    await prefs.setBool(_keyEnabled, enabled);
  }

  /// Получить текущий режим
  static Future<SplitTunnelMode> getMode() async {
    final prefs = await SharedPreferences.getInstance();
    final index = prefs.getInt(_keyMode) ?? 0;
    if (index >= 0 && index < SplitTunnelMode.values.length) {
      return SplitTunnelMode.values[index];
    }
    return SplitTunnelMode.allApps;
  }

  /// Установить режим
  static Future<void> setMode(SplitTunnelMode mode) async {
    final prefs = await SharedPreferences.getInstance();
    await prefs.setInt(_keyMode, mode.index);
  }

  /// Получить список выбранных package names
  static Future<List<String>> getSelectedApps() async {
    final prefs = await SharedPreferences.getInstance();
    return prefs.getStringList(_keySelectedApps) ?? [];
  }

  /// Сохранить список выбранных package names
  static Future<void> setSelectedApps(List<String> packageNames) async {
    final prefs = await SharedPreferences.getInstance();
    await prefs.setStringList(_keySelectedApps, packageNames);
  }

  /// Добавить приложение в выбранные
  static Future<void> addApp(String packageName) async {
    final apps = await getSelectedApps();
    if (!apps.contains(packageName)) {
      apps.add(packageName);
      await setSelectedApps(apps);
    }
  }

  /// Убрать приложение из выбранных
  static Future<void> removeApp(String packageName) async {
    final apps = await getSelectedApps();
    apps.remove(packageName);
    await setSelectedApps(apps);
  }

  /// Получить все package names (включая системные) — для blockedApps в selectedOnly режиме
  /// Без иконок для скорости
  static Future<List<String>> _getAllPackageNames() async {
    if (!Platform.isAndroid) return [];
    try {
      final apps = await InstalledApps.getInstalledApps(
        false, // НЕ исключать системные приложения
        false, // без иконок (быстрее)
      );
      return apps.map((a) => a.packageName).toList();
    } catch (e) {
      print('Ошибка получения всех приложений: $e');
      return [];
    }
  }

  /// Получить список установленных приложений (только пользовательские, не системные)
  /// Работает только на Android
  static Future<List<AppInfo>> getInstalledApps() async {
    if (!Platform.isAndroid) return [];
    try {
      final apps = await InstalledApps.getInstalledApps(
        true,  // excludeSystemApps
        true,  // withIcon
      );
      // Сортируем по имени
      apps.sort((a, b) => a.name.toLowerCase().compareTo(b.name.toLowerCase()));
      return apps;
    } catch (e) {
      print('Ошибка получения установленных приложений: $e');
      return [];
    }
  }

  /// Главный метод: получить список blockedApps для передачи в startVless
  /// Возвращает null если Split Tunneling выключен
  static Future<List<String>?> getBlockedApps() async {
    if (!Platform.isAndroid) return null;

    final enabled = await isEnabled();
    if (!enabled) return null;

    final mode = await getMode();
    final selectedApps = await getSelectedApps();

    if (selectedApps.isEmpty) return null;

    switch (mode) {
      case SplitTunnelMode.allApps:
        // Обычный режим — без блокировок
        return null;

      case SplitTunnelMode.selectedOnly:
        // VPN только для выбранных → заблокировать ВСЕ (включая системные) кроме выбранных
        final allPackageNames = await _getAllPackageNames();
        final blocked = allPackageNames
            .where((pkg) => !selectedApps.contains(pkg))
            .toList();
        return blocked.isNotEmpty ? blocked : null;

      case SplitTunnelMode.excludeSelected:
        // VPN для всех кроме выбранных → выбранные = blockedApps
        return selectedApps.isNotEmpty ? selectedApps : null;
    }
  }

  /// Очистить все настройки Split Tunneling
  static Future<void> clearSettings() async {
    final prefs = await SharedPreferences.getInstance();
    await prefs.remove(_keyEnabled);
    await prefs.remove(_keyMode);
    await prefs.remove(_keySelectedApps);
  }
}
