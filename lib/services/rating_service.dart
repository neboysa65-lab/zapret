import 'package:in_app_review/in_app_review.dart';
import 'package:shared_preferences/shared_preferences.dart';

class RatingService {
  static const String _keyLastPromptDate = 'rating_last_prompt_date';
  static const String _keyHasRated = 'rating_has_rated';
  static const int _minDaysBetweenPrompts = 5;

  final InAppReview _inAppReview = InAppReview.instance;

  /// Called automatically on VPN disconnect.
  /// Shows review prompt if 5+ days have passed since last prompt
  /// and the user has not already rated via Settings.
  Future<void> maybeShowReview() async {
    try {
      final prefs = await SharedPreferences.getInstance();

      final bool hasRated = prefs.getBool(_keyHasRated) ?? false;
      if (hasRated) return;

      final String? lastDateStr = prefs.getString(_keyLastPromptDate);
      if (lastDateStr != null) {
        final DateTime lastDate = DateTime.parse(lastDateStr);
        final int daysSince = DateTime.now().difference(lastDate).inDays;
        if (daysSince < _minDaysBetweenPrompts) return;
      }

      final bool available = await _inAppReview.isAvailable();
      if (!available) return;

      await _inAppReview.requestReview();
      await prefs.setString(
        _keyLastPromptDate,
        DateTime.now().toIso8601String(),
      );
    } catch (_) {
      // Silently ignore — review prompt is non-critical
    }
  }

  /// Called from Settings "Rate Us" tap.
  /// Always attempts to show review and marks user as having rated.
  Future<void> showReviewFromSettings() async {
    try {
      final bool available = await _inAppReview.isAvailable();
      if (!available) return;

      await _inAppReview.requestReview();

      final prefs = await SharedPreferences.getInstance();
      await prefs.setBool(_keyHasRated, true);
    } catch (_) {
      // Silently ignore
    }
  }
}
