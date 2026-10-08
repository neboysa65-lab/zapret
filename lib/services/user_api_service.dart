import 'dart:convert';
import 'package:http/http.dart' as http;
import '../models/user_model.dart';
import '../models/tariff_model.dart';
import 'api_host_service.dart';

class UserApiService {
  final String _seedHash;

  UserApiService(this._seedHash);

  Map<String, String> get _headers => {
        'X-Seed-Hash': _seedHash,
        'Content-Type': 'application/json',
      };

  /// Регистрация — возвращает {user_id, seed_phrase}
  static Future<Map<String, String>> register() async {
    final resp = await ApiHostService.instance.withFailover(
      (base) => http
          .post(Uri.parse('$base/api/v1/users/register'))
          .timeout(const Duration(seconds: 10)),
      idempotent: false,
    );
    if (resp.statusCode == 200) {
      final data = jsonDecode(resp.body) as Map<String, dynamic>;
      return {
        'user_id': data['user_id'] as String,
        'seed_phrase': data['seed_phrase'] as String,
      };
    }
    throw Exception('Register failed: ${resp.statusCode}');
  }

  /// Профиль — также используется для проверки seed-фразы при логине
  Future<UserModel> getMe() async {
    final resp = await ApiHostService.instance.withFailover(
      (base) => http
          .get(Uri.parse('$base/api/v1/users/me'), headers: _headers)
          .timeout(const Duration(seconds: 10)),
    );
    if (resp.statusCode == 200) {
      return UserModel.fromJson(jsonDecode(resp.body) as Map<String, dynamic>);
    }
    if (resp.statusCode == 401) throw UnauthorizedException();
    throw Exception('getMe failed: ${resp.statusCode}');
  }

  /// QR-код — возвращает PNG bytes
  Future<List<int>> getQrBytes() async {
    final resp = await ApiHostService.instance.withFailover(
      (base) => http
          .get(Uri.parse('$base/api/v1/users/me/qr'), headers: _headers)
          .timeout(const Duration(seconds: 10)),
    );
    if (resp.statusCode == 200) return resp.bodyBytes;
    throw Exception('getQr failed: ${resp.statusCode}');
  }

  /// Список тарифов — не требует авторизации
  static Future<List<TariffModel>> getTariffs() async {
    final resp = await ApiHostService.instance.withFailover(
      (base) => http
          .get(Uri.parse('$base/api/v1/tariffs'))
          .timeout(const Duration(seconds: 10)),
    );
    if (resp.statusCode == 200) {
      final list = jsonDecode(resp.body) as List<dynamic>;
      return list.map((e) => TariffModel.fromJson(e as Map<String, dynamic>)).toList();
    }
    throw Exception('getTariffs failed: ${resp.statusCode}');
  }

  /// Создание платежа — возвращает {payment_id, status}
  /// Не требует авторизации (user_id передаётся в теле)
  static Future<Map<String, String>> initiatePayment({
    required String userId,
    required String tariffId,
  }) async {
    final resp = await ApiHostService.instance.withFailover(
      (base) => http
          .post(
            Uri.parse('$base/api/v1/payments/initiate'),
            headers: {'Content-Type': 'application/json'},
            body: jsonEncode({'user_id': userId, 'tariff_id': tariffId}),
          )
          .timeout(const Duration(seconds: 10)),
      idempotent: false,
    );
    if (resp.statusCode == 201) {
      final data = jsonDecode(resp.body) as Map<String, dynamic>;
      return {
        'payment_id': data['payment_id'] as String,
        'status': data['status'] as String,
      };
    }
    throw Exception('initiatePayment failed: ${resp.statusCode}');
  }

  /// Поллинг: ждать активации премиума (до 10 мин)
  Future<bool> waitForPremium({
    Duration interval = const Duration(seconds: 3),
    Duration timeout = const Duration(minutes: 10),
  }) async {
    final deadline = DateTime.now().add(timeout);
    while (DateTime.now().isBefore(deadline)) {
      final user = await getMe();
      if (user.isPremium) return true;
      await Future.delayed(interval);
    }
    return false;
  }
}

class UnauthorizedException implements Exception {
  @override
  String toString() => 'UnauthorizedException';
}
