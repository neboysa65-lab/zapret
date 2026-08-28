class TariffModel {
  final String id;
  final String name;
  final int price; // в копейках
  final String currency;
  final int durationDays;

  TariffModel({
    required this.id,
    required this.name,
    required this.price,
    required this.currency,
    required this.durationDays,
  });

  factory TariffModel.fromJson(Map<String, dynamic> json) {
    return TariffModel(
      id: json['id'] as String,
      name: json['name'] as String,
      price: json['price'] as int,
      currency: json['currency'] as String,
      durationDays: json['duration_days'] as int,
    );
  }

  String get formattedPrice {
    final rubles = price / 100;
    final formatted = rubles == rubles.truncateToDouble()
        ? rubles.toInt().toString()
        : rubles.toStringAsFixed(2);
    final symbol = currency == 'RUB' ? '₽' : currency;
    return '$formatted $symbol';
  }
}
