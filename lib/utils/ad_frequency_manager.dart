import 'package:shared_preferences/shared_preferences.dart';

/// Управляет частотой показа рекламы
class AdFrequencyManager {
  static const String _lastAdShownKey = 'last_ad_shown_timestamp';
  static const String _adCountKey = 'ad_count_today';
  static const String _lastDateKey = 'last_ad_date';

  // Минимальный интервал между показами рекламы (в секундах)
  static const int minIntervalSeconds = 60; // 1 минута

  // Максимальное количество показов в день
  static const int maxAdsPerDay = 10;

  /// Проверяет, можно ли показать рекламу сейчас
  static Future<bool> canShowAd() async {
    final prefs = await SharedPreferences.getInstance();

    // Проверяем интервал между показами
    final lastShown = prefs.getInt(_lastAdShownKey) ?? 0;
    final now = DateTime.now().millisecondsSinceEpoch ~/ 1000;
    final timeSinceLastAd = now - lastShown;

    if (timeSinceLastAd < minIntervalSeconds) {
      return false;
    }

    // Проверяем лимит показов в день
    final today = DateTime.now().day;
    final lastDate = prefs.getInt(_lastDateKey) ?? 0;
    int adCount = prefs.getInt(_adCountKey) ?? 0;

    // Сбрасываем счётчик, если это новый день
    if (lastDate != today) {
      adCount = 0;
      await prefs.setInt(_lastDateKey, today);
      await prefs.setInt(_adCountKey, 0);
    }

    if (adCount >= maxAdsPerDay) {
      return false;
    }

    return true;
  }

  /// Отмечает, что реклама была показана
  static Future<void> recordAdShown() async {
    final prefs = await SharedPreferences.getInstance();
    final now = DateTime.now().millisecondsSinceEpoch ~/ 1000;
    final today = DateTime.now().day;

    await prefs.setInt(_lastAdShownKey, now);

    final lastDate = prefs.getInt(_lastDateKey) ?? 0;
    int adCount = prefs.getInt(_adCountKey) ?? 0;

    // Сбрасываем счётчик, если это новый день
    if (lastDate != today) {
      adCount = 0;
      await prefs.setInt(_lastDateKey, today);
    }

    adCount++;
    await prefs.setInt(_adCountKey, adCount);
  }

  /// Сбрасывает все счётчики (для тестирования)
  static Future<void> reset() async {
    final prefs = await SharedPreferences.getInstance();
    await prefs.remove(_lastAdShownKey);
    await prefs.remove(_adCountKey);
    await prefs.remove(_lastDateKey);
  }
}
