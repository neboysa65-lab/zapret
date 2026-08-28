import 'package:flutter/material.dart';
import 'package:package_info_plus/package_info_plus.dart';
import 'package:provider/provider.dart';
import 'package:url_launcher/url_launcher.dart';
import '../config/api_config.dart';
import '../config/app_theme.dart';
import '../providers/auth_provider.dart';
import '../services/auth_service.dart';
import '../services/split_tunnel_service.dart';
import 'pay_web_screen.dart';
import 'privacy_policy_screen.dart';
import 'dart:io';
import 'split_tunnel_screen.dart';

class ProfileScreen extends StatefulWidget {
  const ProfileScreen({super.key});

  @override
  State<ProfileScreen> createState() => _ProfileScreenState();
}

class _ProfileScreenState extends State<ProfileScreen> {
  String _version = '';
  bool _splitTunnelEnabled = false;
  int _splitTunnelAppCount = 0;

  @override
  void initState() {
    super.initState();
    _loadVersion();
    _loadSplitTunnelStatus();
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

  Future<void> _loadVersion() async {
    final info = await PackageInfo.fromPlatform();
    if (mounted) setState(() => _version = info.version);
  }

  Future<void> _launchTelegram() async {
    const String inviteHash = 's_ba-OsbMJ4xNjQ6';
    final Uri appUri = Uri.parse('tg://join?invite=$inviteHash');
    final Uri webUri = Uri.parse('https://t.me/+$inviteHash');
    try {
      if (await canLaunchUrl(appUri)) {
        await launchUrl(appUri, mode: LaunchMode.externalApplication);
      } else {
        await launchUrl(webUri, mode: LaunchMode.externalApplication);
      }
    } catch (_) {
      try {
        await launchUrl(webUri, mode: LaunchMode.externalApplication);
      } catch (_) {}
    }
  }

  @override
  Widget build(BuildContext context) {
    final auth = context.watch<AuthProvider>();

    return Scaffold(
      backgroundColor: AppColors.darkBackground,
      body: CustomScrollView(
        slivers: [
          // Custom header with neon accent
          SliverToBoxAdapter(
            child: _ProfileHeader(auth: auth),
          ),

          // Subscription card
          SliverToBoxAdapter(
            child: Padding(
              padding: const EdgeInsets.fromLTRB(16, 8, 16, 4),
              child: _SubscriptionCard(
                auth: auth,
                onUpgrade: () => _openPayment(context, auth),
              ),
            ),
          ),

          // Section: Account
          SliverToBoxAdapter(
            child: _SectionLabel('Аккаунт'),
          ),
          SliverToBoxAdapter(
            child: Padding(
              padding: const EdgeInsets.symmetric(horizontal: 16),
              child: Column(
                children: [
                  _ProfileTile(
                    icon: Icons.workspace_premium_rounded,
                    iconColor: Colors.amber,
                    title: 'Купить подписку',
                    subtitle: 'Premium — безлимитный доступ',
                    onTap: () => _openPayment(context, auth),
                  ),
                  const SizedBox(height: 8),
                  _ProfileTile(
                    icon: Icons.key_rounded,
                    iconColor: AppColors.primaryGreen,
                    title: 'Seed-фраза',
                    subtitle: 'Просмотр ключа восстановления',
                    onTap: () => _showSeedPhrase(context),
                  ),
                ],
              ),
            ),
          ),

          // Section: Settings
          if (Platform.isAndroid) ...[
            SliverToBoxAdapter(child: _SectionLabel('Настройки')),
            SliverToBoxAdapter(
              child: Padding(
                padding: const EdgeInsets.symmetric(horizontal: 16),
                child: Column(
                  children: [
                    _buildSplitTunnelTile(context, auth),
                  ],
                ),
              ),
            ),
          ],

          // Section: Support
          SliverToBoxAdapter(child: _SectionLabel('Поддержка')),
          SliverToBoxAdapter(
            child: Padding(
              padding: const EdgeInsets.symmetric(horizontal: 16),
              child: Column(
                children: [
                  _ProfileTile(
                    customIcon: Image.asset(
                      'assets/images/tg.png',
                      width: 22,
                      height: 22,
                    ),
                    iconColor: const Color(0xFF2AABEE),
                    title: 'Telegram',
                    subtitle: 'Связаться с нами',
                    onTap: _launchTelegram,
                  ),
                  const SizedBox(height: 8),
                  _ProfileTile(
                    icon: Icons.privacy_tip_outlined,
                    iconColor: AppColors.textSecondary,
                    title: 'Политика конфиденциальности',
                    onTap: () => Navigator.push(
                      context,
                      MaterialPageRoute(
                          builder: (_) => const PrivacyPolicyScreen()),
                    ),
                  ),
                  const SizedBox(height: 8),
                  _ProfileTile(
                    icon: Icons.info_outline_rounded,
                    iconColor: AppColors.textTertiary,
                    title: 'Версия приложения',
                    subtitle: _version.isNotEmpty ? _version : '—',
                    onTap: null,
                    showChevron: false,
                  ),
                ],
              ),
            ),
          ),

          // Section: Danger zone
          SliverToBoxAdapter(child: _SectionLabel('Аккаунт')),
          SliverToBoxAdapter(
            child: Padding(
              padding: const EdgeInsets.symmetric(horizontal: 16),
              child: Column(
                children: [
                  _ProfileTile(
                    icon: Icons.logout_rounded,
                    iconColor: AppColors.textSecondary,
                    title: 'Выйти',
                    subtitle: 'Seed-фраза останется на устройстве',
                    onTap: () => _confirmLogout(context, auth),
                  ),
                  const SizedBox(height: 8),
                  _ProfileTile(
                    icon: Icons.delete_forever_rounded,
                    iconColor: AppColors.error,
                    title: 'Удалить аккаунт',
                    titleColor: AppColors.error,
                    subtitle: 'Удалить локальные данные',
                    onTap: () => _confirmDelete(context, auth),
                  ),
                ],
              ),
            ),
          ),

          const SliverToBoxAdapter(child: SizedBox(height: 32)),
        ],
      ),
    );
  }

  Future<void> _openPayment(BuildContext context, AuthProvider auth) async {
    final userId = auth.user?.userId ?? '';
    if (userId.isEmpty) {
      if (!context.mounted) return;
      ScaffoldMessenger.of(context).showSnackBar(
        const SnackBar(content: Text('Не удалось определить аккаунт. Войдите заново.')),
      );
      return;
    }
    final url = '${ApiConfig.baseUrl}/pay?uid=$userId';
    final activated = await Navigator.push<bool>(
      context,
      MaterialPageRoute(builder: (_) => PayWebScreen(url: url)),
    );
    if (activated == true && context.mounted) {
      ScaffoldMessenger.of(context).showSnackBar(
        const SnackBar(content: Text('Premium активирован!')),
      );
    }
  }

  Future<void> _showSeedPhrase(BuildContext context) async {
    final phrase = await AuthService.loadSeedPhrase();
    if (!context.mounted) return;
    showDialog(
      context: context,
      builder: (_) => AlertDialog(
        backgroundColor: AppColors.cardBackground,
        title: const Text('Seed-фраза'),
        content: SelectableText(
          phrase ?? 'Не найдена',
          style: const TextStyle(
            fontFamily: 'monospace',
            fontSize: 14,
            color: AppColors.primaryGreen,
            height: 1.6,
          ),
        ),
        actions: [
          TextButton(
            onPressed: () => Navigator.pop(context),
            child: const Text('Закрыть'),
          ),
        ],
      ),
    );
  }

  Future<void> _confirmLogout(BuildContext context, AuthProvider auth) async {
    final ok = await showDialog<bool>(
      context: context,
      builder: (_) => AlertDialog(
        backgroundColor: AppColors.cardBackground,
        title: const Text('Выйти?'),
        content: const Text('Seed-фраза останется на устройстве.'),
        actions: [
          TextButton(
              onPressed: () => Navigator.pop(context, false),
              child: const Text('Отмена')),
          FilledButton(
              onPressed: () => Navigator.pop(context, true),
              child: const Text('Выйти')),
        ],
      ),
    );
    if (ok == true && context.mounted) {
      await auth.logout();
      if (context.mounted) Navigator.pushReplacementNamed(context, '/welcome');
    }
  }

  Future<void> _confirmDelete(BuildContext context, AuthProvider auth) async {
    final ok = await showDialog<bool>(
      context: context,
      builder: (_) => AlertDialog(
        backgroundColor: AppColors.cardBackground,
        title: const Text('Удалить аккаунт?'),
        content: const Text(
          'Данные аккаунта будут удалены с устройства. '
          'Для полного удаления с сервера обратитесь в поддержку.',
        ),
        actions: [
          TextButton(
              onPressed: () => Navigator.pop(context, false),
              child: const Text('Отмена')),
          TextButton(
            style: TextButton.styleFrom(foregroundColor: AppColors.error),
            onPressed: () => Navigator.pop(context, true),
            child: const Text('Удалить'),
          ),
        ],
      ),
    );
    if (ok == true && context.mounted) {
      await auth.logout();
      if (context.mounted) Navigator.pushReplacementNamed(context, '/welcome');
    }
  }

  Widget _buildSplitTunnelTile(BuildContext context, AuthProvider auth) {
    final isPremium = auth.isPremium;
    
    String subtitleText;
    if (!_splitTunnelEnabled) {
      subtitleText = 'Выключен';
    } else if (_splitTunnelAppCount == 0) {
      subtitleText = 'Включён · нет выбранных приложений';
    } else {
      subtitleText = 'Включён · $_splitTunnelAppCount приложений';
    }

    return _ProfileTile(
      icon: Icons.call_split_rounded,
      iconColor: isPremium ? AppColors.primaryGreen : Colors.white38,
      title: 'Split Tunneling',
      subtitle: subtitleText,
      customIcon: Stack(
        clipBehavior: Clip.none,
        children: [
          Icon(
            Icons.call_split_rounded,
            color: isPremium ? AppColors.primaryGreen : Colors.white38,
            size: 18,
          ),
          if (!isPremium)
            Positioned(
              right: -6,
              bottom: -6,
              child: Container(
                padding: const EdgeInsets.all(1),
                decoration: BoxDecoration(
                  color: AppColors.cardBackground,
                  borderRadius: BorderRadius.circular(6),
                ),
                child: const Icon(
                  Icons.lock,
                  color: Colors.amber,
                  size: 10,
                ),
              ),
            ),
        ],
      ),
      onTap: () {
        if (!isPremium) {
          _showPremiumRequiredDialog();
          return;
        }
        Navigator.push(
          context,
          MaterialPageRoute(builder: (context) => const SplitTunnelScreen()),
        ).then((_) => _loadSplitTunnelStatus());
      },
    );
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
              _openPayment(context, context.read<AuthProvider>());
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
}

// ─── Widgets ────────────────────────────────────────────────────────────────

class _ProfileHeader extends StatelessWidget {
  final AuthProvider auth;
  const _ProfileHeader({required this.auth});

  @override
  Widget build(BuildContext context) {
    return Container(
      decoration: BoxDecoration(
        gradient: LinearGradient(
          begin: Alignment.topCenter,
          end: Alignment.bottomCenter,
          colors: [
            AppColors.primaryGreen.withOpacity(0.08),
            AppColors.darkBackground,
          ],
        ),
      ),
      child: SafeArea(
        bottom: false,
        child: Padding(
          padding: const EdgeInsets.fromLTRB(20, 20, 20, 24),
          child: Row(
            children: [
              // Avatar
              Container(
                width: 60,
                height: 60,
                decoration: BoxDecoration(
                  shape: BoxShape.circle,
                  color: AppColors.primaryGreen.withOpacity(0.12),
                  border: Border.all(
                      color: AppColors.primaryGreen.withOpacity(0.35), width: 1.5),
                ),
                child: const Icon(
                  Icons.person_rounded,
                  color: AppColors.primaryGreen,
                  size: 30,
                ),
              ),
              const SizedBox(width: 16),
              Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  const Text(
                    'Профиль',
                    style: TextStyle(
                      fontSize: 22,
                      fontWeight: FontWeight.bold,
                      color: AppColors.textPrimary,
                    ),
                  ),
                  if (auth.user?.userId != null)
                    Text(
                      'ID: ${_shortId(auth.user!.userId)}',
                      style: const TextStyle(
                        fontSize: 12,
                        color: AppColors.textTertiary,
                        fontFamily: 'monospace',
                      ),
                    ),
                ],
              ),
            ],
          ),
        ),
      ),
    );
  }

  String _shortId(String id) {
    if (id.length <= 16) return id;
    return '${id.substring(0, 8)}…${id.substring(id.length - 8)}';
  }
}

class _SubscriptionCard extends StatelessWidget {
  final AuthProvider auth;
  final VoidCallback onUpgrade;
  const _SubscriptionCard({required this.auth, required this.onUpgrade});

  @override
  Widget build(BuildContext context) {
    final isPremium = auth.isPremium;
    final until = auth.user?.premiumUntil;

    return Container(
      decoration: BoxDecoration(
        borderRadius: BorderRadius.circular(20),
        gradient: LinearGradient(
          begin: Alignment.topLeft,
          end: Alignment.bottomRight,
          colors: isPremium
              ? [
                  const Color(0xFF1A1600),
                  const Color(0xFF2A2000),
                ]
              : [
                  AppColors.cardBackground,
                  const Color(0xFF1A1D1F),
                ],
        ),
        border: Border.all(
          color: isPremium
              ? Colors.amber.withOpacity(0.4)
              : AppColors.primaryGreen.withOpacity(0.15),
          width: 1,
        ),
        boxShadow: isPremium
            ? [
                BoxShadow(
                  color: Colors.amber.withOpacity(0.08),
                  blurRadius: 20,
                  spreadRadius: 2,
                ),
              ]
            : [],
      ),
      padding: const EdgeInsets.all(20),
      child: Row(
        children: [
          Container(
            width: 48,
            height: 48,
            decoration: BoxDecoration(
              shape: BoxShape.circle,
              color: isPremium
                  ? Colors.amber.withOpacity(0.15)
                  : AppColors.primaryGreen.withOpacity(0.1),
            ),
            child: Icon(
              isPremium
                  ? Icons.workspace_premium_rounded
                  : Icons.vpn_lock_outlined,
              color: isPremium ? Colors.amber : AppColors.primaryGreen,
              size: 24,
            ),
          ),
          const SizedBox(width: 16),
          Expanded(
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Text(
                  isPremium ? 'Premium активен' : 'Бесплатный план',
                  style: TextStyle(
                    fontSize: 16,
                    fontWeight: FontWeight.w600,
                    color: isPremium ? Colors.amber : AppColors.textPrimary,
                  ),
                ),
                const SizedBox(height: 2),
                Text(
                  isPremium && until != null
                      ? 'Действует до ${_fmt(until)}'
                      : 'Обновитесь для полного доступа',
                  style: const TextStyle(
                    fontSize: 12,
                    color: AppColors.textSecondary,
                  ),
                ),
              ],
            ),
          ),
          if (!isPremium)
            GestureDetector(
              onTap: onUpgrade,
              child: Container(
                padding:
                    const EdgeInsets.symmetric(horizontal: 14, vertical: 8),
                decoration: BoxDecoration(
                  borderRadius: BorderRadius.circular(20),
                  gradient: const LinearGradient(
                    colors: [Color(0xFFA3FF00), Color(0xFF76B900)],
                  ),
                ),
                child: const Text(
                  'Купить',
                  style: TextStyle(
                    fontSize: 13,
                    fontWeight: FontWeight.w600,
                    color: Colors.black,
                  ),
                ),
              ),
            ),
        ],
      ),
    );
  }

  String _fmt(DateTime dt) {
    final l = dt.toLocal();
    return '${l.day.toString().padLeft(2, '0')}.${l.month.toString().padLeft(2, '0')}.${l.year}';
  }
}

class _SectionLabel extends StatelessWidget {
  final String text;
  const _SectionLabel(this.text);

  @override
  Widget build(BuildContext context) {
    return Padding(
      padding: const EdgeInsets.fromLTRB(20, 20, 16, 8),
      child: Text(
        text.toUpperCase(),
        style: const TextStyle(
          fontSize: 11,
          fontWeight: FontWeight.w600,
          letterSpacing: 1.2,
          color: AppColors.textTertiary,
        ),
      ),
    );
  }
}

class _ProfileTile extends StatelessWidget {
  final IconData? icon;
  final Widget? customIcon;
  final Color iconColor;
  final String title;
  final Color? titleColor;
  final String? subtitle;
  final VoidCallback? onTap;
  final bool showChevron;

  const _ProfileTile({
    this.icon,
    this.customIcon,
    required this.iconColor,
    required this.title,
    this.titleColor,
    this.subtitle,
    this.onTap,
    this.showChevron = true,
  });

  @override
  Widget build(BuildContext context) {
    return Material(
      color: AppColors.cardBackground,
      borderRadius: BorderRadius.circular(14),
      child: InkWell(
        onTap: onTap,
        borderRadius: BorderRadius.circular(14),
        splashColor: AppColors.primaryGreen.withOpacity(0.06),
        highlightColor: AppColors.primaryGreen.withOpacity(0.04),
        child: Padding(
          padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 14),
          child: Row(
            children: [
              Container(
                width: 36,
                height: 36,
                decoration: BoxDecoration(
                  shape: BoxShape.circle,
                  color: iconColor.withOpacity(0.12),
                ),
                child: Center(
                  child: customIcon ??
                      Icon(icon, size: 18, color: iconColor),
                ),
              ),
              const SizedBox(width: 14),
              Expanded(
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    Text(
                      title,
                      style: TextStyle(
                        fontSize: 15,
                        fontWeight: FontWeight.w500,
                        color: titleColor ?? AppColors.textPrimary,
                      ),
                    ),
                    if (subtitle != null) ...[
                      const SizedBox(height: 2),
                      Text(
                        subtitle!,
                        style: const TextStyle(
                          fontSize: 12,
                          color: AppColors.textTertiary,
                        ),
                      ),
                    ],
                  ],
                ),
              ),
              if (showChevron && onTap != null)
                const Icon(
                  Icons.chevron_right_rounded,
                  size: 20,
                  color: AppColors.textTertiary,
                ),
            ],
          ),
        ),
      ),
    );
  }
}
