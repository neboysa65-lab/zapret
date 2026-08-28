import 'package:flutter_test/flutter_test.dart';
import 'package:vpn_subscription_app/models/user_model.dart';

void main() {
  group('UserModel premium', () {
    test('active when is_premium and future premium_until', () {
      final user = UserModel.fromJson({
        'user_id': 'u1',
        'is_premium': true,
        'premium_until': DateTime.now()
            .toUtc()
            .add(const Duration(days: 7))
            .toIso8601String(),
      });
      expect(user.isActivePremium, isTrue);
    });

    test('active when is_premium without date', () {
      final user = UserModel.fromJson({
        'user_id': 'u1',
        'is_premium': true,
        'premium_until': null,
      });
      expect(user.isActivePremium, isTrue);
    });

    test('inactive when premium_until is in the past', () {
      final user = UserModel.fromJson({
        'user_id': 'u1',
        'is_premium': true,
        'premium_until': DateTime.now()
            .toUtc()
            .subtract(const Duration(days: 1))
            .toIso8601String(),
      });
      expect(user.isActivePremium, isFalse);
    });

    test('inactive when is_premium is false', () {
      final user = UserModel.fromJson({
        'user_id': 'u1',
        'is_premium': false,
        'premium_until': DateTime.now()
            .toUtc()
            .add(const Duration(days: 7))
            .toIso8601String(),
      });
      expect(user.isActivePremium, isFalse);
    });

    test('does not crash on malformed premium_until', () {
      final user = UserModel.fromJson({
        'user_id': 'u1',
        'is_premium': true,
        'premium_until': 'not-a-date',
      });
      expect(user.premiumUntil, isNull);
      expect(user.isActivePremium, isTrue);
    });
  });
}
