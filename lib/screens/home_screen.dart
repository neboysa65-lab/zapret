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
  const HomeScreen({super.key});

  @override
  State<HomeScreen> createState() => _HomeScreenState();
}

class _HomeScreenState extends State<HomeScreen> {
  @override
  void initState() {
    super.initState();
    // Инициализируем и загружаем рекламу заранее
    _initializeAd();
  }

  Future<void> _initializeAd() async {
    debugPrint('📢 _initializeAd: starting...');
    await AdInterstitialManager.initialize();
    debugPrint('✅ AdLoader initialized');
    debugPrint('📥 Loading Interstitial Ad with ID: ${AdConfig.currentInterstitialAdUnitId}');
    await AdInterstitialManager.loadAd(AdConfig.currentInterstitialAdUnitId);
    debugPrint('📢 _initializeAd: loadAd call completed, isAdLoaded=${AdInterstitialManager.isAdLoaded}');
  }

  /// Подключает VPN — с рекламой для бесплатных, без рекламы для премиум
  Future<void> _connectWithAd(AppProvider appProvider, {bool isPremium = false}) async {
    debugPrint('🔌 User requested VPN connection (isPremium: $isPremium, adLoaded: ${AdInterstitialManager.isAdLoaded})...');

    if (!isPremium) {
      // 1. Показываем рекламу и ЖДЁМ её закрытия
      final adShown = await AdInterstitialManager.showAndWait();

      if (adShown) {
        debugPrint('✅ Ad was shown and closed, connecting VPN...');
      } else {
        debugPrint('⚠️ Ad was not shown (not loaded or error), connecting VPN anyway...');
      }
    } else {
      debugPrint('⭐ Premium user — skipping ad');
    }

    // 2. После закрытия рекламы (или сразу для премиум) подключаем VPN
    if (mounted) {
      await appProvider.connectVpn();

      // 3. Загружаем следующую рекламу для следующего подключения (только для free)
      if (!isPremium) {
        Future.delayed(const Duration(seconds: 2), () {
          AdInterstitialManager.loadAd(AdConfig.currentInterstitialAdUnitId);
        });
      }
    }
  }

  @override
  void dispose() {
    AdInterstitialManager.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    // Явно задаем стиль статус-бара для гарантии отображения на EMUI/MIUI
    SystemChrome.setSystemUIOverlayStyle(
      const SystemUiOverlayStyle(
        statusBarColor: Colors.transparent,
        statusBarIconBrightness: Brightness.light, // Белые иконки (время, батарея)
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
                            top: 16.0, // Увеличил верхний отступ для гарантии от "челки"
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
                                scale: scale,
                                onTap: () async {
                                  if (isConnected) {
                                    // Отключаем VPN без рекламы
                                    await appProvider.disconnectVpn();
                                  } else {
                                    // Подключаем VPN ПОСЛЕ просмотра рекламы
                                    await _connectWithAd(appProvider, isPremium: isPremium);
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
                                    await _connectWithAd(appProvider, isPremium: isPremium);
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
