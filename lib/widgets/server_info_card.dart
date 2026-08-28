import 'package:flutter/material.dart';
import 'package:provider/provider.dart';
import '../config/app_theme.dart';

import '../providers/app_provider.dart';
import '../screens/server_list_screen.dart';
import 'package:vpn_subscription_app/l10n/generated/app_localizations.dart';

class ServerInfoCard extends StatelessWidget {
  final double scale;

  const ServerInfoCard({super.key, this.scale = 1.0});

  @override
  Widget build(BuildContext context) {
    // Scale values
    final p = 16.0 * scale.clamp(0.8, 1.0);
    final iconSize = 48.0 * scale;
    final fontSizeTitle = 16.0 * scale;
    final fontSizeSubtitle = 12.0 * scale;
    final fontSizeFlag = 24.0 * scale;

    return Consumer<AppProvider>(
      builder: (context, provider, child) {
        final server = provider.selectedServer;
        final isAuto = provider.isAutoSelect;
        final l10n = AppLocalizations.of(context)!;

        String title = server?.serverName ?? l10n.selectServer;
        String subtitle = server?.country ?? l10n.clickToSelect;
        String flag = server?.flag ?? '🌍';

        if (isAuto && server != null) {
          title = l10n.autoSelectBest;
          subtitle = "${l10n.optimized} • ${server.country}";
        } else if (!isAuto && server != null) {
          title = server.country != 'Unknown'
              ? server.country
              : server.cleanName;
          subtitle = l10n.manualSelection;
        }

        return GestureDetector(
          onTap: () => ServerListScreen.show(context),
          child: Container(
            padding: EdgeInsets.all(p),
            decoration: BoxDecoration(
              color: AppColors.cardBackground,
              borderRadius: BorderRadius.circular(16 * scale),
              border: Border.all(
                color: Colors.white.withValues(alpha: 0.05),
                width: 1,
              ),
              boxShadow: [
                BoxShadow(
                  color: Colors.black.withValues(alpha: 0.2),
                  blurRadius: 10,
                  offset: const Offset(0, 4),
                ),
              ],
            ),
            child: Row(
              children: [
                // Flag Container
                Container(
                  width: iconSize,
                  height: iconSize,
                  decoration: BoxDecoration(
                    color: Colors.black.withValues(alpha: 0.3),
                    borderRadius: BorderRadius.circular(12 * scale),
                  ),
                  child: Center(
                    child: Text(flag, style: TextStyle(fontSize: fontSizeFlag)),
                  ),
                ),
                SizedBox(width: 16 * scale),

                // Info
                Expanded(
                  child: Column(
                    crossAxisAlignment: CrossAxisAlignment.start,
                    children: [
                      Text(
                        title,
                        style: TextStyle(
                          color: Colors.white,
                          fontWeight: FontWeight.bold,
                          fontSize: fontSizeTitle,
                        ),
                        maxLines: 1,
                        overflow: TextOverflow.ellipsis,
                      ),
                      SizedBox(height: 4 * scale),
                      Text(
                        subtitle,
                        style: TextStyle(
                          color: Colors.grey[400],
                          fontSize: fontSizeSubtitle,
                        ),
                        maxLines: 1,
                        overflow: TextOverflow.ellipsis,
                      ),
                    ],
                  ),
                ),

                // Ping & Chevron
                Row(
                  children: [
                    Icon(
                      Icons.chevron_right,
                      color: Colors.grey[600],
                      size: 24 * scale,
                    ),
                  ],
                ),
              ],
            ),
          ),
        );
      },
    );
  }
}
