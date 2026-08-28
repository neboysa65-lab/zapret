// GENERATED CODE - DO NOT MODIFY BY HAND

part of 'subscription_model.dart';

// **************************************************************************
// JsonSerializableGenerator
// **************************************************************************

Subscription _$SubscriptionFromJson(Map<String, dynamic> json) => Subscription(
  id: json['id'] as String,
  userId: json['userId'] as String,
  plan: $enumDecode(_$SubscriptionPlanEnumMap, json['plan']),
  status: $enumDecode(_$SubscriptionStatusEnumMap, json['status']),
  startDate: DateTime.parse(json['startDate'] as String),
  expiryDate: DateTime.parse(json['expiryDate'] as String),
  trafficLimitGB: (json['trafficLimitGB'] as num).toInt(),
  usedTrafficGB: (json['usedTrafficGB'] as num).toInt(),
  autoRenew: json['autoRenew'] as bool? ?? false,
  price: (json['price'] as num?)?.toDouble(),
  currency: json['currency'] as String?,
);

Map<String, dynamic> _$SubscriptionToJson(Subscription instance) =>
    <String, dynamic>{
      'id': instance.id,
      'userId': instance.userId,
      'plan': _$SubscriptionPlanEnumMap[instance.plan]!,
      'status': _$SubscriptionStatusEnumMap[instance.status]!,
      'startDate': instance.startDate.toIso8601String(),
      'expiryDate': instance.expiryDate.toIso8601String(),
      'trafficLimitGB': instance.trafficLimitGB,
      'usedTrafficGB': instance.usedTrafficGB,
      'autoRenew': instance.autoRenew,
      'price': instance.price,
      'currency': instance.currency,
    };

const _$SubscriptionPlanEnumMap = {
  SubscriptionPlan.free: 'free',
  SubscriptionPlan.basic: 'basic',
  SubscriptionPlan.premium: 'premium',
  SubscriptionPlan.ultimate: 'ultimate',
};

const _$SubscriptionStatusEnumMap = {
  SubscriptionStatus.active: 'active',
  SubscriptionStatus.expired: 'expired',
  SubscriptionStatus.cancelled: 'cancelled',
  SubscriptionStatus.trial: 'trial',
};
