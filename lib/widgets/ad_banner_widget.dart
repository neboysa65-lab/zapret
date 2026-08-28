import 'package:flutter/material.dart';
import 'package:yandex_mobileads/mobile_ads.dart';

class AdBannerWidget extends StatefulWidget {
  final String adUnitId;

  const AdBannerWidget({super.key, required this.adUnitId});

  @override
  State<AdBannerWidget> createState() => _AdBannerWidgetState();
}

class _AdBannerWidgetState extends State<AdBannerWidget> {
  BannerAd? _bannerAd;
  bool _isBannerCreated = false;

  @override
  void didChangeDependencies() {
    super.didChangeDependencies();
    if (_bannerAd == null) {
      _createBanner();
    }
  }

  void _createBanner() {
    if (!mounted) return;

    final adSize = BannerAdSize.sticky(
      width: MediaQuery.of(context).size.width.toInt(),
    );

    _bannerAd = BannerAd(
      adUnitId: widget.adUnitId,
      adSize: adSize,
      adRequest: const AdRequest(),
      onAdLoaded: () {
        debugPrint('✅ BannerAd loaded successfully');
        if (mounted) {
          setState(() {
            _isBannerCreated = true;
          });
        }
      },
      onAdFailedToLoad: (error) {
        debugPrint('❌ BannerAd failed to load: $error');
      },
      onAdClicked: () {
        debugPrint('🖱️ BannerAd clicked');
      },
      onImpression: (impressionData) {
        debugPrint('👁️ BannerAd impression');
      },
    );

    setState(() {
      _isBannerCreated = true;
    });
  }

  @override
  void dispose() {
    _bannerAd?.destroy();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    if (_isBannerCreated && _bannerAd != null) {
      return Align(
        alignment: Alignment.bottomCenter,
        child: AdWidget(bannerAd: _bannerAd!),
      );
    }
    return const SizedBox.shrink();
  }
}
