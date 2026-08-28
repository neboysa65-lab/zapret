import 'package:json_annotation/json_annotation.dart';

part 'subscription_model.g.dart';

/// Статус подписки
enum SubscriptionStatus {
  @JsonValue('active')
  active,
  @JsonValue('expired')
  expired,
  @JsonValue('cancelled')
  cancelled,
  @JsonValue('trial')
  trial,
}

/// Тип подписки (план)
enum SubscriptionPlan {
  @JsonValue('free')
  free,
  @JsonValue('basic')
  basic,
  @JsonValue('premium')
  premium,
  @JsonValue('ultimate')
  ultimate,
}

/// Модель подписки пользователя
@JsonSerializable()
class Subscription {
  final String id;
  final String userId;
  final SubscriptionPlan plan;
  final SubscriptionStatus status;
  final DateTime startDate;
  final DateTime expiryDate;
  final int trafficLimitGB; // Лимит трафика в GB
  final int usedTrafficGB; // Использованный трафик в GB
  final bool autoRenew;
  final double? price;
  final String? currency;

  Subscription({
    required this.id,
    required this.userId,
    required this.plan,
    required this.status,
    required this.startDate,
    required this.expiryDate,
    required this.trafficLimitGB,
    required this.usedTrafficGB,
    this.autoRenew = false,
    this.price,
    this.currency,
  });

  /// Проверка, активна ли подписка
  bool get isActive =>
      status == SubscriptionStatus.active &&
      DateTime.now().isBefore(expiryDate);

  /// Процент использованного трафика
  double get trafficUsagePercent =>
      trafficLimitGB > 0 ? (usedTrafficGB / trafficLimitGB) * 100 : 0;

  /// Оставшиеся дни подписки
  int get daysRemaining => expiryDate.difference(DateTime.now()).inDays;

  factory Subscription.fromJson(Map<String, dynamic> json) =>
      _$SubscriptionFromJson(json);
  Map<String, dynamic> toJson() => _$SubscriptionToJson(this);
}
