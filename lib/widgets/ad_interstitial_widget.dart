import 'dart:async';
import 'package:flutter/material.dart';
import 'package:yandex_mobileads/mobile_ads.dart';

/// Yandex Interstitial Ad Manager (Межстраничная реклама)
/// Показывает полноэкранную рекламу ПЕРЕД подключением VPN
class AdInterstitialManager {
  static InterstitialAdLoader? _adLoader;
  static InterstitialAd? _interstitialAd;
  static bool _isAdLoaded = false;
  static bool _isLoading = false;
  static Future<void>? _initializing;
  static Future<bool>? _showing;
  static int _generation = 0;

  /// Инициализирует загрузчик межстраничной рекламы
  static Future<void> initialize() {
    if (_adLoader != null) return Future.value();
    return _initializing ??= _createLoader().whenComplete(
      () => _initializing = null,
    );
  }

  static Future<void> _createLoader() async {
    final generation = _generation;
    final loader = await InterstitialAdLoader.create(
      onAdLoaded: (InterstitialAd interstitialAd) {
        if (generation != _generation) {
          interstitialAd.destroy();
          return;
        }
        _interstitialAd = interstitialAd;
        _isAdLoaded = true;
        _isLoading = false;
        debugPrint('✅ Interstitial Ad loaded');
      },
      onAdFailedToLoad: (error) {
        if (generation != _generation) return;
        _interstitialAd = null;
        _isAdLoaded = false;
        _isLoading = false;
        debugPrint('❌ Ad failed to load: ${error.description}');
      },
    );
    if (generation == _generation) {
      _adLoader = loader;
    } else {
      await loader.destroy();
    }
  }

  /// Загружает межстраничную рекламу
  static Future<void> loadAd(String adUnitId) async {
    if (_adLoader == null) await initialize();
    if (_isAdLoaded || _isLoading || _adLoader == null) return;
    _isLoading = true;
    try {
      await _adLoader!.loadAd(
        adRequestConfiguration: AdRequestConfiguration(adUnitId: adUnitId),
      );
    } catch (_) {
      _isLoading = false;
      rethrow;
    }
  }

  /// Показывает рекламу и ЖДЁТ закрытия через Completer (работает в release)
  /// Возвращает true если реклама была показана и закрыта
  static Future<bool> showAndWait() {
    return _showing ??= _showAd().whenComplete(() => _showing = null);
  }

  static Future<bool> _showAd() async {
    final ad = _interstitialAd;
    if (ad == null || !_isAdLoaded) {
      debugPrint('⚠️ No ad loaded');
      return false;
    }

    final completer = Completer<bool>();

    try {
      _isAdLoaded = false;
      _interstitialAd = null;
      await ad.setAdEventListener(
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

      await ad.show();

      // Never start the tunnel behind an ad that is still on screen.
      final shown = await completer.future;

      debugPrint('✅ Ad completed, shown=$shown');
      return shown;
    } catch (e) {
      debugPrint('❌ Ad show error: $e');
      if (!completer.isCompleted) completer.complete(false);
      return false;
    } finally {
      try {
        await ad.destroy();
      } catch (_) {
        debugPrint('Interstitial cleanup failed');
      }
    }
  }

  static bool get isAdLoaded => _isAdLoaded;

  static void dispose() {
    _generation++;
    _interstitialAd?.destroy();
    _adLoader?.destroy();
    _interstitialAd = null;
    _adLoader = null;
    _isAdLoaded = false;
    _isLoading = false;
  }
}
