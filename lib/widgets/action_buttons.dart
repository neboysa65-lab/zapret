import 'package:flutter/material.dart';
import '../config/app_theme.dart';
import 'package:vpn_subscription_app/l10n/generated/app_localizations.dart';

class ActionButtons extends StatelessWidget {
  final bool isConnected;
  final bool isProcessing;
  final VoidCallback onTap;
  final double scale;

  const ActionButtons({
    super.key,
    required this.isConnected,
    this.isProcessing = false,
    required this.onTap,
    this.scale = 1.0,
  });

  @override
  Widget build(BuildContext context) {
    final l10n = AppLocalizations.of(context)!;

    String text = l10n.connectNow;
    Color bgColor = AppColors.cardBackground;
    Color textColor = AppColors.primaryGreen;
    Color borderColor = AppColors.primaryGreen.withOpacity(0.2);
    IconData icon = Icons.bolt;

    if (isConnected) {
      text = l10n.disconnectUpper;
      bgColor = AppColors.cardBackground;
      textColor = AppColors.error;
      borderColor = Colors.grey.shade800;
      icon = Icons.power_settings_new;
    } else if (isProcessing) {
      text = isConnected ? l10n.disconnectingUpper : l10n.connectingUpper;
      bgColor = Colors.grey.shade900;
      textColor = Colors.grey;
      borderColor = Colors.transparent;
      icon = Icons.sync;
    }

    // Scale values
    final vPad = 20.0 * scale.clamp(0.7, 1.0);
    final fontSize = 16.0 * scale;
    final iconSize = 24.0 * scale;

    return GestureDetector(
      onTap: isProcessing ? null : onTap,
      child: Container(
        width: double.infinity,
        padding: EdgeInsets.symmetric(vertical: vPad),
        decoration: BoxDecoration(
          color: bgColor,
          borderRadius: BorderRadius.circular(16 * scale),
          border: Border.all(color: borderColor),
          boxShadow: isConnected
              ? []
              : [
                  BoxShadow(
                    color: AppColors.primaryGreen.withOpacity(0.1),
                    blurRadius: 20,
                    offset: const Offset(0, 4),
                  ),
                ],
        ),
        child: Row(
          mainAxisAlignment: MainAxisAlignment.center,
          children: [
            if (isProcessing)
              SizedBox(
                width: iconSize,
                height: iconSize,
                child: CircularProgressIndicator(
                  strokeWidth: 2,
                  valueColor: AlwaysStoppedAnimation<Color>(textColor),
                ),
              )
            else
              Icon(icon, color: textColor, size: iconSize),
            SizedBox(width: 12 * scale),
            Text(
              text,
              style: TextStyle(
                color: textColor,
                fontSize: fontSize,
                fontWeight: FontWeight.bold,
                letterSpacing: 1.0,
              ),
            ),
          ],
        ),
      ),
    );
  }
}
