import 'dart:async';
import 'package:flutter/material.dart';
import 'package:flutter/services.dart'; // Add this for SystemChrome
import 'package:provider/provider.dart';
import '../config/app_theme.dart';
import '../config/ad_config.dart';
import '../providers/app_provider.dart';
import '../providers/auth_provider.dart';
import '../services/vpn_service.dart';
import '../widgets/pixel_world_map.dart';
import '../widgets/server_info_card.dart';
import '../widgets/connection_status.dart';
import '../widgets/power_button.dart';
import '../widgets/stats_grid.dart';
import '../widgets/action_buttons.dart';
import '../widgets/ad_interstitial_widget.dart';

class HomeScreen extends StatefulWidget {
  const HomeScreen({
    super.key,
    this.showAd = AdInterstitialManager.showAndWait,
    this.loadAd = _loadInterstitialAd,
  });

  final Future<bool> Function() showAd;
  final Future<void> Function() loadAd;

  static Future<void> _loadInterstitialAd() =>
      AdInterstitialManager.loadAd(AdConfig.currentInterstitialAdUnitId);

  @override
  State<HomeScreen> createState() => _HomeScreenState();
}

class _HomeScreenState extends State<HomeScreen> {
  Timer? _adReload;
  @override
  void initState() {
    super.initState();
    // Инициализируем и загружаем рекламу заранее
    _initializeAd();
  }

  Future<void> _initializeAd() async {
    try {
      await widget.loadAd();
    } catch (_) {
      debugPrint('Interstitial ad unavailable');
    }
  }

  /// Подключает VPN — с рекламой для бесплатных, без рекламы для премиум
  Future<void> _connectWithAd(
    AppProvider appProvider, {
    bool isPremium = false,
  }) async {
    debugPrint(
      '🔌 User requested VPN connection (isPremium: $isPremium, adLoaded: ${AdInterstitialManager.isAdLoaded})...',
    );

    await appProvider.connectVpn(
      beforeConnect: () async {
        if (!isPremium) {
          // 1. Показываем рекламу и ЖДЁМ её закрытия
          final adShown = await widget.showAd();

          if (adShown) {
            debugPrint('✅ Ad was shown and closed, connecting VPN...');
          } else {
            debugPrint(
              '⚠️ Ad was not shown (not loaded or error), connecting VPN anyway...',
            );
          }
        } else {
          debugPrint('⭐ Premium user — skipping ad');
        }

        if (!mounted) throw StateError('Screen disposed');
      },
    );
    if (mounted && !isPremium) {
      _adReload?.cancel();
      _adReload = Timer(const Duration(seconds: 2), _initializeAd);
    }
  }

  @override
  void dispose() {
    _adReload?.cancel();
    AdInterstitialManager.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    // Явно задаем стиль статус-бара для гарантии отображения на EMUI/MIUI
    SystemChrome.setSystemUIOverlayStyle(
      const SystemUiOverlayStyle(
        statusBarColor: Colors.transparent,
        statusBarIconBrightness:
            Brightness.light, // Белые иконки (время, батарея)
        systemNavigationBarColor: AppColors.darkBackground,
        systemNavigationBarIconBrightness: Brightness.light,
      ),
    );

    return Consumer2<AppProvider, AuthProvider>(
      builder: (context, appProvider, authProvider, child) {
        final isPremium = authProvider.isPremium;
        final currentStatus = appProvider.vpnStatus;
        final isConnected = currentStatus == VpnStatus.connected;
        final isConnecting = currentStatus == VpnStatus.connecting;
        final isDisconnecting = currentStatus == VpnStatus.disconnecting;
        final isProcessing = isConnecting || isDisconnecting;

        return Scaffold(
          backgroundColor: AppColors.darkBackground,
          body: Stack(
            children: [
              // 1. World Map Background
              PixelWorldMap(isConnected: isConnected),

              // 2. Main Content
              SafeArea(
                child: LayoutBuilder(
                  builder: (context, constraints) {
                    final h = constraints.maxHeight;
                    // Scale sizes based on available height
                    // Reference: 800px = "comfortable" height
                    final scale = (h / 800).clamp(0.55, 1.2);
                    final horizontalPad = h < 700 ? 16.0 : 24.0;
                    final verticalPad = h < 700 ? 8.0 : 16.0;

                    return SingleChildScrollView(
                      physics: const ClampingScrollPhysics(),
                      child: ConstrainedBox(
                        constraints: BoxConstraints(minHeight: h),
                        child: Padding(
                          padding: EdgeInsets.only(
                            left: horizontalPad,
                            right: horizontalPad,
                            top:
                                16.0, // Увеличил верхний отступ для гарантии от "челки"
                            bottom: verticalPad,
                          ),
                          child: Column(
                            mainAxisAlignment: MainAxisAlignment.spaceBetween,
                            children: [
                              // Status & Timer
                              ConnectionStatus(
                                isConnected: isConnected,
                                isConnecting: isConnecting,
                                isDisconnecting: isDisconnecting,
                                duration: appProvider.connectionDuration,
                                scale: scale,
                              ),

                              SizedBox(height: 8 * scale),

                              // Power Button
                              PowerButton(
                                isConnected: isConnected,
                                isProcessing: isProcessing,
                                canCancel: isConnecting,
                                scale: scale,
                                onTap: () async {
                                  if (appProvider.vpnStatus ==
                                      VpnStatus.disconnecting)
                                    return;
                                  if (appProvider.vpnStatus ==
                                          VpnStatus.connected ||
                                      appProvider.vpnStatus ==
                                          VpnStatus.connecting) {
                                    // Отключаем VPN без рекламы
                                    await appProvider.disconnectVpn();
                                  } else {
                                    // Подключаем VPN ПОСЛЕ просмотра рекламы
                                    await _connectWithAd(
                                      appProvider,
                                      isPremium: isPremium,
                                    );
                                  }
                                },
                              ),

                              SizedBox(height: 16 * scale),

                              // Server Info Card (Moved below Power Button)
                              ServerInfoCard(scale: scale),

                              SizedBox(height: 8 * scale),

                              // Stats Grid
                              StatsGrid(
                                download: appProvider.downloadSpeedBytes,
                                upload: appProvider.uploadSpeedBytes,
                                isConnected: isConnected,
                                scale: scale,
                              ),

                              SizedBox(height: 12 * scale),

                              // Action Buttons
                              ActionButtons(
                                isConnected: isConnected,
                                isProcessing: isProcessing,
                                scale: scale,
                                onTap: () async {
                                  if (isConnected) {
                                    // Отключаем VPN без рекламы
                                    await appProvider.disconnectVpn();
                                  } else {
                                    // Подключаем VPN ПОСЛЕ просмотра рекламы
                                    await _connectWithAd(
                                      appProvider,
                                      isPremium: isPremium,
                                    );
                                  }
                                },
                              ),
                            ],
                          ),
                        ),
                      ),
                    );
                  },
                ),
              ),

              // Error Toast (if any)
              if (appProvider.errorMessage != null)
                Positioned(
                  top: MediaQuery.of(context).padding.top + 8,
                  left: 16,
                  right: 16,
                  child: Material(
                    color: Colors.transparent,
                    child: Container(
                      padding: const EdgeInsets.all(14),
                      decoration: BoxDecoration(
                        color: AppColors.error,
                        borderRadius: BorderRadius.circular(12),
                      ),
                      child: Row(
                        children: [
                          const Icon(
                            Icons.error_outline,
                            color: Colors.white,
                            size: 20,
                          ),
                          const SizedBox(width: 10),
                          Expanded(
                            child: Text(
                              appProvider.errorMessage!,
                              style: const TextStyle(
                                color: Colors.white,
                                fontSize: 13,
                              ),
                              maxLines: 2,
                              overflow: TextOverflow.ellipsis,
                            ),
                          ),
                          IconButton(
                            icon: const Icon(
                              Icons.close,
                              color: Colors.white,
                              size: 20,
                            ),
                            padding: EdgeInsets.zero,
                            constraints: const BoxConstraints(),
                            onPressed: () => appProvider.clearError(),
                          ),
                        ],
                      ),
                    ),
                  ),
                ),
            ],
          ),
        );
      },
    );
  }
}
