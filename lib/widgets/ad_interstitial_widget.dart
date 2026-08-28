import 'dart:async';
import 'package:flutter/material.dart';
import 'package:yandex_mobileads/mobile_ads.dart';

/// Yandex Interstitial Ad Manager (Межстраничная реклама)
/// Показывает полноэкранную рекламу ПЕРЕД подключением VPN
class AdInterstitialManager {
  static InterstitialAdLoader? _adLoader;
  static InterstitialAd? _interstitialAd;
  static bool _isAdLoaded = false;

  /// Инициализирует загрузчик межстраничной рекламы
  static Future<void> initialize() async {
    if (_adLoader != null) return;

    _adLoader = await InterstitialAdLoader.create(
      onAdLoaded: (InterstitialAd interstitialAd) {
        _interstitialAd = interstitialAd;
        _isAdLoaded = true;
        debugPrint('✅ Interstitial Ad loaded');
      },
      onAdFailedToLoad: (error) {
        _interstitialAd = null;
        _isAdLoaded = false;
        debugPrint('❌ Ad failed to load: ${error.description}');
      },
    );
  }

  /// Загружает межстраничную рекламу
  static Future<void> loadAd(String adUnitId) async {
    if (_adLoader == null) await initialize();
    if (_isAdLoaded) return;

    await _adLoader?.loadAd(
      adRequestConfiguration: AdRequestConfiguration(adUnitId: adUnitId),
    );
  }

  /// Показывает рекламу и ЖДЁТ закрытия через Completer (работает в release)
  /// Возвращает true если реклама была показана и закрыта
  static Future<bool> showAndWait() async {
    final ad = _interstitialAd;
    if (ad == null || !_isAdLoaded) {
      debugPrint('⚠️ No ad loaded');
      return false;
    }

    final completer = Completer<bool>();

    ad.setAdEventListener(
      eventListener: InterstitialAdEventListener(
        onAdShown: () {
          debugPrint('👁️ Ad shown');
        },
        onAdFailedToShow: (error) {
          debugPrint('❌ Ad failed to show: ${error.description}');
          if (!completer.isCompleted) completer.complete(false);
        },
        onAdDismissed: () {
          debugPrint('🔽 Ad dismissed');
          if (!completer.isCompleted) completer.complete(true);
        },
        onAdClicked: () {},
        onAdImpression: (data) {
          debugPrint('📊 Ad impression');
        },
      ),
    );

    try {
      _isAdLoaded = false;
      _interstitialAd = null;

      await ad.show();

      // Ждём пока пользователь закроет рекламу (максимум 5 минут)
      final shown = await completer.future.timeout(
        const Duration(minutes: 5),
        onTimeout: () => true, // считаем показанной если зависло
      );

      debugPrint('✅ Ad completed, shown=$shown');
      return shown;
    } catch (e) {
      debugPrint('❌ Ad show error: $e');
      if (!completer.isCompleted) completer.complete(false);
      return false;
    }
  }

  static bool get isAdLoaded => _isAdLoaded;

  static void dispose() {
    _interstitialAd = null;
    _adLoader = null;
    _isAdLoaded = false;
  }
}
