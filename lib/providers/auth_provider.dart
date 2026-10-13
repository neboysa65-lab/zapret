import 'package:flutter/foundation.dart';
import '../models/user_model.dart';
import '../services/remote_config_service.dart';
import '../services/auth_service.dart';
import '../services/user_api_service.dart';

enum AuthState { unknown, unauthenticated, authenticated }

class AuthProvider extends ChangeNotifier {
  AuthState _state = AuthState.unknown;
  UserModel? _user;
  String? _seedPhrase; // только для показа на экране регистрации
  String? _error;

  AuthState get state => _state;
  UserModel? get user => _user;
  String? get seedPhrase => _seedPhrase;
  String? get error => _error;
  bool get isPremium => _user?.isActivePremium ?? false;

  /// Вызывается при старте приложения (SplashScreen)
  Future<void> tryAutoLogin() async {
    final seedHash = await AuthService.loadSeedHash();
    if (seedHash == null) {
      _state = AuthState.unauthenticated;
      notifyListeners();
      return;
    }
    try {
      final api = UserApiService(seedHash);
      _user = await api.getMe();
      _state = AuthState.authenticated;
    } catch (e) {
      _state = AuthState.unauthenticated;
    }
    notifyListeners();
  }

  /// Регистрация нового аккаунта
  Future<void> register() async {
    _error = null;
    notifyListeners();
    // Конфиг (baseUrl) приезжает асинхронно: на чистой установке запрос без
    // ожидания уходил на пустой URL — отсюда красная «Ошибка подключения».
    await RemoteConfigService.instance.ensureReady();
    try {
      final result = await UserApiService.register();
      final phrase = result['seed_phrase']!;
      final userId = result['user_id']!;
      await AuthService.saveCredentials(seedPhrase: phrase, userId: userId);
      _seedPhrase = phrase;
      final seedHash = AuthService.computeSeedHash(phrase);
      _user = await UserApiService(seedHash).getMe();
      _state = AuthState.authenticated;
    } catch (e) {
      _error = 'Ошибка регистрации. Проверьте подключение.';
    }
    notifyListeners();
  }

  /// Вход по seed-фразе
  Future<bool> loginWithSeedPhrase(String phrase) async {
    _error = null;
    await RemoteConfigService.instance.ensureReady();
    try {
      final seedHash = AuthService.computeSeedHash(phrase);
      final api = UserApiService(seedHash);
      _user = await api.getMe();
      await AuthService.saveCredentials(seedPhrase: phrase, userId: _user!.userId);
      _state = AuthState.authenticated;
      notifyListeners();
      return true;
    } on UnauthorizedException {
      _error = 'Seed-фраза не найдена. Проверьте правильность слов.';
      notifyListeners();
      return false;
    } catch (e) {
      _error = 'Ошибка подключения к серверу.';
      notifyListeners();
      return false;
    }
  }

  /// Обновить данные пользователя (после оплаты)
  Future<void> refreshUser() async {
    final seedHash = await AuthService.loadSeedHash();
    if (seedHash == null) return;
    try {
      _user = await UserApiService(seedHash).getMe();
      notifyListeners();
    } catch (_) {}
  }

  /// Выход
  Future<void> logout() async {
    await AuthService.clearCredentials();
    _user = null;
    _seedPhrase = null;
    _state = AuthState.unauthenticated;
    notifyListeners();
  }

  void clearSeedPhrase() {
    _seedPhrase = null;
    notifyListeners();
  }
}
