import 'dart:io';
import 'package:flutter/material.dart';
import 'package:package_info_plus/package_info_plus.dart';
import 'package:provider/provider.dart';
import 'package:url_launcher/url_launcher.dart';
import 'package:vpn_subscription_app/l10n/generated/app_localizations.dart';
import '../config/app_theme.dart';
import '../providers/auth_provider.dart';
import '../services/split_tunnel_service.dart';
import '../services/rating_service.dart';
import 'privacy_policy_screen.dart';
import 'split_tunnel_screen.dart';

class SettingsScreen extends StatefulWidget {
  const SettingsScreen({super.key});

  @override
  State<SettingsScreen> createState() => _SettingsScreenState();
}

class _SettingsScreenState extends State<SettingsScreen> {
  String _version = '';
  bool _splitTunnelEnabled = false;
  int _splitTunnelAppCount = 0;

  @override
  void initState() {
    super.initState();
    _loadAppVersion();
    _loadSplitTunnelStatus();
  }

  Future<void> _loadAppVersion() async {
    final packageInfo = await PackageInfo.fromPlatform();
    setState(() {
      _version = packageInfo.version;
    });
  }

  Future<void> _loadSplitTunnelStatus() async {
    if (!Platform.isAndroid) return;
    final enabled = await SplitTunnelService.isEnabled();
    final apps = await SplitTunnelService.getSelectedApps();
    if (mounted) {
      setState(() {
        _splitTunnelEnabled = enabled;
        _splitTunnelAppCount = apps.length;
      });
    }
  }

  Future<void> _launchTelegram() async {
    // Хэш вашей группы из ссылки после знака "+"
    const String inviteHash = 's_ba-OsbMJ4xNjQ6';

    // Ссылка для открытия внутри приложения (схема tg://join?invite=)
    final Uri appUri = Uri.parse('tg://join?invite=$inviteHash');

    // Стандартная ссылка для браузера (https://t.me/+)
    final Uri webUri = Uri.parse('https://t.me/+$inviteHash');

    try {
      // Проверяем, может ли устройство открыть ссылку tg:// (установлен ли Telegram)
      if (await canLaunchUrl(appUri)) {
        // Открываем приложение напрямую
        await launchUrl(appUri, mode: LaunchMode.externalApplication);
      } else {
        // Если приложения нет или не удалось определить, открываем браузерную версию
        // LaunchMode.externalApplication откроет браузер по умолчанию
        await launchUrl(webUri, mode: LaunchMode.externalApplication);
      }
    } catch (e) {
      debugPrint("Error launching Telegram: $e");
      // В случае любой ошибки пробуем открыть веб-версию
      try {
        await launchUrl(webUri, mode: LaunchMode.externalApplication);
      } catch (e2) {
        debugPrint("Error launching Web Telegram: $e2");
      }
    }
  }

  void _openSplitTunnel() {
    final auth = context.read<AuthProvider>();
    if (!auth.isPremium) {
      _showPremiumRequiredDialog();
      return;
    }
    Navigator.push(
      context,
      MaterialPageRoute(builder: (context) => const SplitTunnelScreen()),
    ).then((_) => _loadSplitTunnelStatus());
  }

  void _showPremiumRequiredDialog() {
    showDialog(
      context: context,
      builder: (context) => AlertDialog(
        backgroundColor: AppColors.cardBackground,
        shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(20)),
        title: Row(
          children: [
            Icon(Icons.workspace_premium, color: AppColors.primaryGreen, size: 28),
            const SizedBox(width: 10),
            const Expanded(
              child: Text(
                'Premium функция',
                style: TextStyle(color: Colors.white, fontSize: 18),
              ),
            ),
          ],
        ),
        content: const Text(
          'Split Tunneling доступен только для пользователей с подпиской Premium.\n\n'
          'С этой функцией вы сможете выбирать, какие приложения будут работать через VPN.',
          style: TextStyle(color: Colors.white70, fontSize: 14, height: 1.5),
        ),
        actions: [
          TextButton(
            onPressed: () => Navigator.pop(context),
            child: Text('Закрыть',
                style: TextStyle(color: Colors.white.withOpacity(0.5))),
          ),
          FilledButton(
            onPressed: () {
              Navigator.pop(context);
              Navigator.pushNamed(context, '/subscription');
            },
            style: FilledButton.styleFrom(
              backgroundColor: AppColors.primaryGreen,
              foregroundColor: Colors.black,
            ),
            child: const Text('Купить Premium'),
          ),
        ],
      ),
    );
  }

  String get _splitTunnelSubtitle {
    if (!Platform.isAndroid) return 'Только для Android';
    if (!_splitTunnelEnabled) return 'Выключен';
    if (_splitTunnelAppCount == 0) return 'Включён · нет выбранных приложений';
    return 'Включён · $_splitTunnelAppCount приложений';
  }

  @override
  Widget build(BuildContext context) {
    final l10n = AppLocalizations.of(context)!;
    final auth = context.watch<AuthProvider>();
    final isPremium = auth.isPremium;
    
    return Scaffold(
      backgroundColor: AppColors.darkBackground,
      appBar: AppBar(
        backgroundColor: Colors.transparent,
        elevation: 0,
        leading: IconButton(
          icon: const Icon(Icons.arrow_back_ios, color: Colors.white),
          onPressed: () => Navigator.pop(context),
        ),
          title: Text(
            l10n.settings,
            style: const TextStyle(color: Colors.white),
          ),
      ),
      body: ListView(
        children: [
          // Split Tunneling — только для Android
          if (Platform.isAndroid) ...[
            _buildSplitTunnelItem(isPremium),
            const Divider(color: Colors.white10),
          ],
          _buildSettingsItem(
            context,
            imageAsset: 'assets/images/tg.png',
            title: l10n.supportTelegram,
            onTap: _launchTelegram,
          ),
          const Divider(height: 1, color: Color(0xFF2A2D35)),
          ListTile(
            leading: Icon(
              Icons.star_outline_rounded,
              color: AppColors.primaryGreen,
            ),
            title: Text(
              l10n.rateUs,
              style: const TextStyle(
                color: Colors.white,
                fontSize: 16,
              ),
            ),
            trailing: const Icon(
              Icons.arrow_forward_ios,
              color: Color(0xFF6B7280),
              size: 16,
            ),
            onTap: () => RatingService().showReviewFromSettings(),
          ),
          const Divider(height: 1, color: Color(0xFF2A2D35)),
          _buildSettingsItem(
            context,
            icon: Icons.privacy_tip_outlined,
            title: l10n.privacyPolicy,
            onTap: () {
              Navigator.push(
                context,
                MaterialPageRoute(
                  builder: (context) => const PrivacyPolicyScreen(),
                ),
              );
            },
          ),
          const Divider(color: Colors.white10),
          _buildSettingsItem(
            context,
            icon: Icons.info_outline,
            title: '${l10n.version} $_version',
            onTap: null, // Версия просто отображается
          ),
        ],
      ),
    );
  }

  Widget _buildSplitTunnelItem(bool isPremium) {
    return ListTile(
      leading: Stack(
        clipBehavior: Clip.none,
        children: [
          Icon(
            Icons.call_split_rounded,
            color: isPremium ? AppColors.primaryGreen : Colors.white38,
          ),
          if (!isPremium)
            Positioned(
              right: -4,
              bottom: -4,
              child: Container(
                padding: const EdgeInsets.all(1),
                decoration: BoxDecoration(
                  color: AppColors.darkBackground,
                  borderRadius: BorderRadius.circular(6),
                ),
                child: const Icon(
                  Icons.lock,
                  color: Colors.amber,
                  size: 12,
                ),
              ),
            ),
        ],
      ),
      title: Row(
        children: [
          const Text(
            'Split Tunneling',
            style: TextStyle(color: Colors.white, fontSize: 16),
          ),
          if (!isPremium) ...[
            const SizedBox(width: 8),
            Container(
              padding: const EdgeInsets.symmetric(horizontal: 6, vertical: 2),
              decoration: BoxDecoration(
                gradient: const LinearGradient(
                  colors: [Color(0xFFFFD700), Color(0xFFFFA500)],
                ),
                borderRadius: BorderRadius.circular(4),
              ),
              child: const Text(
                'PRO',
                style: TextStyle(
                  color: Colors.black,
                  fontSize: 9,
                  fontWeight: FontWeight.w800,
                  letterSpacing: 0.5,
                ),
              ),
            ),
          ],
        ],
      ),
      subtitle: Text(
        _splitTunnelSubtitle,
        style: TextStyle(
          color: _splitTunnelEnabled && isPremium
              ? AppColors.primaryGreen.withOpacity(0.7)
              : Colors.white38,
          fontSize: 12,
        ),
      ),
      trailing: const Icon(Icons.chevron_right, color: Colors.white54),
      onTap: _openSplitTunnel,
    );
  }

  Widget _buildSettingsItem(
    BuildContext context, {
    IconData? icon,
    String? imageAsset,
    required String title,
    VoidCallback? onTap,
  }) {
    return ListTile(
      leading: imageAsset != null
          ? Image.asset(
              imageAsset,
              width: 24,
              height: 24,
            )
          : Icon(icon, color: AppColors.primaryGreen),
      title: Text(
        title,
        style: const TextStyle(
          color: Colors.white,
          fontSize: 16,
        ),
      ),
      trailing: onTap != null
          ? const Icon(Icons.chevron_right, color: Colors.white54)
          : null,
      onTap: onTap,
    );
  }
}

