import 'dart:async';
import 'package:flutter/material.dart';
import 'package:provider/provider.dart';
import '../config/app_theme.dart';
import '../providers/app_provider.dart';
import '../models/vless_config_model.dart';
import 'package:vpn_subscription_app/l10n/generated/app_localizations.dart';

// Цвета тиров нагрузки
Color _tierColor(ServerLoadTier tier) {
  switch (tier) {
    case ServerLoadTier.low:
      return const Color(0xFFA3FF00); // green
    case ServerLoadTier.medium:
      return const Color(0xFFFFBF00); // amber
    case ServerLoadTier.high:
      return const Color(0xFFFF3B30); // red
  }
}

String _tierLabel(ServerLoadTier tier) {
  switch (tier) {
    case ServerLoadTier.low:
      return 'Низкая нагрузка';
    case ServerLoadTier.medium:
      return 'Средняя нагрузка';
    case ServerLoadTier.high:
      return 'Высокая нагрузка';
  }
}

class ServerListScreen extends StatefulWidget {
  const ServerListScreen({super.key});

  /// Show as a modal bottom sheet
  static Future<void> show(BuildContext context) {
    return showModalBottomSheet(
      context: context,
      isScrollControlled: true,
      backgroundColor: Colors.transparent,
      builder: (_) => const ServerListScreen(),
    );
  }

  @override
  State<ServerListScreen> createState() => _ServerListScreenState();
}

class _ServerListScreenState extends State<ServerListScreen> {
  Timer? _refreshTimer;

  @override
  void initState() {
    super.initState();
    _refreshTimer = Timer.periodic(const Duration(seconds: 20), (_) {
      if (mounted) {
        context.read<AppProvider>().refreshMetrics();
      }
    });
  }

  @override
  void dispose() {
    _refreshTimer?.cancel();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    return Consumer<AppProvider>(
      builder: (context, provider, child) {
        final l10n = AppLocalizations.of(context)!;
        return Container(
          height: MediaQuery.of(context).size.height * 0.7,
          decoration: const BoxDecoration(
            color: AppColors.cardBackground,
            borderRadius: BorderRadius.vertical(top: Radius.circular(24)),
          ),
          child: Column(
            children: [
              // Handle bar
              Container(
                margin: const EdgeInsets.only(top: 12),
                width: 40,
                height: 4,
                decoration: BoxDecoration(
                  color: Colors.grey[700],
                  borderRadius: BorderRadius.circular(2),
                ),
              ),

              // Title
              Padding(
                padding: const EdgeInsets.all(20),
                child: Row(
                  children: [
                    const Icon(
                      Icons.public,
                      color: AppColors.primaryGreen,
                      size: 24,
                    ),
                    const SizedBox(width: 12),
                    Expanded(
                      child: Text(
                        l10n.selectLocation,
                        maxLines: 1,
                        overflow: TextOverflow.ellipsis,
                        style: const TextStyle(
                          color: Colors.white,
                          fontSize: 20,
                          fontWeight: FontWeight.bold,
                        ),
                      ),
                    ),
                    const SizedBox(width: 8),
                    Text(
                      l10n.serversCount(provider.servers.length),
                      style: TextStyle(color: Colors.grey[500], fontSize: 13),
                    ),
                  ],
                ),
              ),

              // Divider
              Container(height: 1, color: Colors.white.withValues(alpha: 0.05)),

              // Auto Select Option
              _buildAutoSelectTile(context, provider),

              // Divider
              Padding(
                padding: const EdgeInsets.symmetric(horizontal: 20),
                child: Container(
                  height: 1,
                  color: Colors.white.withValues(alpha: 0.05),
                ),
              ),

              // Server List
              Expanded(
                child: provider.isLoading
                    ? const Center(
                        child: CircularProgressIndicator(
                          color: AppColors.primaryGreen,
                        ),
                      )
                    : provider.servers.isEmpty
                    ? Center(
                        child: Column(
                          mainAxisSize: MainAxisSize.min,
                          children: [
                            Icon(
                              Icons.cloud_off,
                              color: Colors.grey[600],
                              size: 48,
                            ),
                            const SizedBox(height: 12),
                            Text(
                              l10n.serversNotFound,
                              style: TextStyle(color: Colors.grey[500]),
                            ),
                            const SizedBox(height: 16),
                            TextButton.icon(
                              onPressed: () => provider.loadServers(),
                              icon: const Icon(Icons.refresh),
                              label: Text(l10n.refresh),
                            ),
                          ],
                        ),
                      )
                    : RefreshIndicator(
                        color: AppColors.primaryGreen,
                        onRefresh: () => provider.loadServers(),
                        child: ListView.builder(
                          padding: const EdgeInsets.symmetric(vertical: 8),
                          itemCount: provider.servers.length,
                          itemBuilder: (context, index) {
                            final server = provider.servers[index];
                            final isSelected =
                                !provider.isAutoSelect &&
                                provider.selectedServer?.id == server.id;
                            // Identity-based key: rows get re-sorted whenever
                            // ping or load metrics refresh, and without a stable
                            // key Flutter would recycle element state onto a
                            // different server.
                            return KeyedSubtree(
                              key: ValueKey<String>(server.id),
                              child: _buildServerTile(
                                context,
                                server,
                                isSelected,
                                provider,
                              ),
                            );
                          },
                        ),
                      ),
              ),
            ],
          ),
        );
      },
    );
  }

  Widget _buildAutoSelectTile(BuildContext context, AppProvider provider) {
    final isAutoSelected = provider.isAutoSelect;
    final l10n = AppLocalizations.of(context)!;

    return Material(
      color: Colors.transparent,
      child: InkWell(
        onTap: () {
          provider.toggleAutoSelect();
          Navigator.of(context).pop();
        },
        child: Container(
          padding: const EdgeInsets.symmetric(horizontal: 20, vertical: 16),
          decoration: isAutoSelected
              ? BoxDecoration(
                  color: AppColors.primaryGreen.withValues(alpha: 0.08),
                  border: Border(
                    left: BorderSide(color: AppColors.primaryGreen, width: 3),
                  ),
                )
              : null,
          child: Row(
            children: [
              // Icon
              Container(
                width: 44,
                height: 44,
                decoration: BoxDecoration(
                  color: isAutoSelected
                      ? AppColors.primaryGreen.withValues(alpha: 0.15)
                      : Colors.white.withValues(alpha: 0.05),
                  borderRadius: BorderRadius.circular(12),
                ),
                child: Icon(
                  Icons.auto_awesome,
                  color: isAutoSelected
                      ? AppColors.primaryGreen
                      : Colors.grey[500],
                  size: 22,
                ),
              ),
              const SizedBox(width: 14),

              // Text
              Expanded(
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    Text(
                      l10n.autoSelect,
                      style: TextStyle(
                        color: isAutoSelected
                            ? AppColors.primaryGreen
                            : Colors.white,
                        fontWeight: FontWeight.w600,
                        fontSize: 15,
                      ),
                    ),
                    const SizedBox(height: 2),
                    Text(
                      l10n.optimalServer,
                      style: TextStyle(color: Colors.grey[500], fontSize: 12),
                    ),
                  ],
                ),
              ),

              // Check
              if (isAutoSelected)
                Container(
                  width: 24,
                  height: 24,
                  decoration: BoxDecoration(
                    color: AppColors.primaryGreen,
                    shape: BoxShape.circle,
                  ),
                  child: const Icon(Icons.check, color: Colors.black, size: 16),
                ),
            ],
          ),
        ),
      ),
    );
  }

  Widget _buildServerTile(
    BuildContext context,
    VlessConfig server,
    bool isSelected,
    AppProvider provider,
  ) {
    // Use clean country name, stripping flag emoji (already shown in icon)
    String displayName = server.isAwg
        ? server.cleanName
        : server.country != 'Unknown'
        ? server.country
        : server.cleanName
              .replaceAll(
                RegExp(
                  r'[\u{1F1E6}-\u{1F1FF}][\u{1F1E6}-\u{1F1FF}]',
                  unicode: true,
                ),
                '',
              )
              .trim();

    return Material(
      color: Colors.transparent,
      child: InkWell(
        onTap: () {
          if (!server.canConnect) {
            final ru = Localizations.localeOf(context).languageCode == 'ru';
            ScaffoldMessenger.of(context).showSnackBar(
              SnackBar(
                content: Text(
                  server.locked
                      ? (ru
                            ? 'Для этого сервера нужен Premium'
                            : 'Premium is required')
                      : (ru
                            ? 'Сервер временно недоступен'
                            : 'Server temporarily unavailable'),
                ),
              ),
            );
            return;
          }
          provider.selectServer(server);
          Navigator.of(context).pop();
        },
        child: Container(
          padding: const EdgeInsets.symmetric(horizontal: 20, vertical: 12),
          decoration: isSelected
              ? BoxDecoration(
                  color: AppColors.primaryGreen.withValues(alpha: 0.08),
                  border: Border(
                    left: BorderSide(color: AppColors.primaryGreen, width: 3),
                  ),
                )
              : null,
          child: Row(
            children: [
              // Flag
              Container(
                width: 44,
                height: 44,
                decoration: BoxDecoration(
                  color: isSelected
                      ? AppColors.primaryGreen.withValues(alpha: 0.15)
                      : Colors.white.withValues(alpha: 0.05),
                  borderRadius: BorderRadius.circular(12),
                ),
                child: Center(
                  child: Text(
                    server.flag,
                    style: const TextStyle(fontSize: 22),
                  ),
                ),
              ),
              const SizedBox(width: 14),

              // Server Info
              Expanded(
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    Text(
                      displayName,
                      maxLines: 2,
                      overflow: TextOverflow.ellipsis,
                      style: TextStyle(
                        color: !server.canConnect
                            ? Colors.grey
                            : isSelected
                            ? AppColors.primaryGreen
                            : Colors.white,
                        fontWeight: FontWeight.w600,
                        fontSize: 15,
                      ),
                    ),
                    const SizedBox(height: 3),
                    Text(
                      '${server.protocolLabel}${server.premium ? ' · Premium' : ''}',
                      style: TextStyle(
                        fontSize: 12,
                        color: server.isAwg || server.isVlee
                            ? AppColors.primaryGreen
                            : Colors.grey,
                      ),
                    ),
                  ],
                ),
              ),

              // Ping & Check
              Row(
                mainAxisSize: MainAxisSize.min,
                crossAxisAlignment: CrossAxisAlignment.center,
                children: [
                  if (server.isAwg)
                    Text(
                      'UDP',
                      style: TextStyle(color: Colors.grey[500], fontSize: 11),
                    )
                  else
                    _buildSignalBars(server.ping),
                  const SizedBox(width: 12),
                  if (!server.isAwg) _buildLoadDot(provider, server),
                  const SizedBox(width: 8),
                  if (!server.canConnect)
                    Icon(
                      server.locked ? Icons.lock_outline : Icons.cloud_off,
                      color: Colors.grey,
                      size: 20,
                    )
                  else if (isSelected)
                    Container(
                      width: 24,
                      height: 24,
                      decoration: const BoxDecoration(
                        color: AppColors.primaryGreen,
                        shape: BoxShape.circle,
                      ),
                      child: const Icon(
                        Icons.check,
                        color: Colors.black,
                        size: 16,
                      ),
                    )
                  else
                    Icon(
                      Icons.chevron_right,
                      color: Colors.grey[600],
                      size: 20,
                    ),
                ],
              ),
            ],
          ),
        ),
      ),
    );
  }

  Widget _buildSignalBars(int ping) {
    int activeBars = 0;
    Color color = Colors.grey[700]!;

    if (ping > 0) {
      if (ping < 80) {
        activeBars = 4; // Отличный
        color = Colors.greenAccent; // Ярко-зеленый
      } else if (ping < 150) {
        activeBars = 3; // Нормальный
        color = Colors.green; // Темно-зеленый
      } else if (ping < 250) {
        activeBars = 2; // Так себе
        color = Colors.yellow; // Желтый
      } else if (ping < 350) {
        activeBars = 1; // Плохой
        color = Colors.orange; // Оранжевый
      } else {
        activeBars = 1; // Очень плохой
        color = Colors.red; // Красный
      }
    }

    return Tooltip(
      message: ping > 0 ? '$ping ms' : 'Timeout',
      decoration: BoxDecoration(
        color: const Color(0xFF1E1E1E), // Темный фон
        borderRadius: BorderRadius.circular(8),
        border: Border.all(color: Colors.white12, width: 1),
      ),
      textStyle: TextStyle(
        color: ping > 0
            ? color
            : Colors.grey, // Цвет текста зависит от качества пинга
        fontWeight: FontWeight.bold,
        fontSize: 13,
      ),
      child: Row(
        mainAxisSize: MainAxisSize.min,
        crossAxisAlignment: CrossAxisAlignment.end,
        children: List.generate(4, (index) {
          // Высота палочек: 8, 12, 16, 20
          double h = 8.0 + (index * 4.0);
          bool isActive = index < activeBars;
          return Container(
            margin: const EdgeInsets.symmetric(horizontal: 1.5),
            width: 3.5,
            height: h,
            decoration: BoxDecoration(
              color: isActive ? color : Colors.white.withValues(alpha: 0.15),
              borderRadius: BorderRadius.circular(1.5),
            ),
          );
        }),
      ),
    );
  }

  Widget _buildLoadDot(AppProvider provider, VlessConfig server) {
    if (provider.isLoading) return const SizedBox(width: 10, height: 10);
    final tier = provider.getServerLoadTier(server);
    final color = _tierColor(tier);
    return Tooltip(
      message: _tierLabel(tier),
      decoration: BoxDecoration(
        color: const Color(0xFF1E1E1E),
        borderRadius: BorderRadius.circular(8),
        border: Border.all(color: Colors.white12, width: 1),
      ),
      textStyle: const TextStyle(color: Colors.white, fontSize: 12),
      child: Container(
        width: 10,
        height: 10,
        decoration: BoxDecoration(
          shape: BoxShape.circle,
          color: color,
          boxShadow: [
            BoxShadow(
              color: color.withValues(alpha: 0.6),
              blurRadius: 6,
              spreadRadius: 1,
            ),
          ],
        ),
      ),
    );
  }
}
