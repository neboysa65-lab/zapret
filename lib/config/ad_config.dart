/// Yandex Mobile Ads configuration
class AdConfig {
  // Yandex Ad Unit IDs — всегда используем реальные
  static const String bannerAdUnitId = 'R-M-18701982-2';
  static const String interstitialAdUnitId = 'R-M-18701982-1';

  static String get currentBannerAdUnitId => bannerAdUnitId;
  static String get currentInterstitialAdUnitId => interstitialAdUnitId;
}
