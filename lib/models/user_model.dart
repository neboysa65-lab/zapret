class UserModel {
  final String userId;
  final bool isPremium;
  final DateTime? premiumUntil;

  UserModel({
    required this.userId,
    required this.isPremium,
    this.premiumUntil,
  });

  factory UserModel.fromJson(Map<String, dynamic> json) {
    DateTime? until;
    final rawUntil = json['premium_until'];
    if (rawUntil is String && rawUntil.isNotEmpty) {
      until = DateTime.tryParse(rawUntil)?.toUtc();
    }
    return UserModel(
      userId: json['user_id'] as String,
      isPremium: json['is_premium'] == true,
      premiumUntil: until,
    );
  }

  /// Активна ли подписка прямо сейчас.
  /// Если бэкенд выставил is_premium, но не прислал дату — считаем активной.
  bool get isActivePremium {
    if (!isPremium) return false;
    final until = premiumUntil;
    if (until == null) return true;
    return !until.toUtc().isBefore(DateTime.now().toUtc());
  }
}
