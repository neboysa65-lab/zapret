import 'package:flutter/material.dart';
import '../config/app_theme.dart';
import 'package:vpn_subscription_app/l10n/generated/app_localizations.dart';

class ConnectionStatus extends StatelessWidget {
  final bool isConnected;
  final bool isConnecting;
  final bool isDisconnecting;
  final Duration duration;
  final double scale;

  const ConnectionStatus({
    super.key,
    required this.isConnected,
    required this.isConnecting,
    required this.isDisconnecting,
    required this.duration,
    this.scale = 1.0,
  });

  String _statusText(BuildContext context) {
    final l10n = AppLocalizations.of(context)!;
    if (isConnected) return l10n.securelyConnected;
    if (isConnecting) return l10n.connectingUpper;
    if (isDisconnecting) return l10n.disconnectingUpper;
    return l10n.notConnected;
  }

  IconData get _statusIcon {
    if (isConnected) return Icons.check_circle_rounded;
    if (isConnecting || isDisconnecting) return Icons.sync;
    return Icons.close_rounded;
  }

  Color get _statusColor {
    if (isConnected) return AppColors.primaryGreen;
    if (isConnecting) return Colors.amber;
    if (isDisconnecting) return Colors.red;
    return Colors.grey;
  }

  Color get _backgroundColor {
    if (isConnected) return AppColors.primaryGreen.withValues(alpha: 0.1);
    if (isConnecting) return Colors.amber.withValues(alpha: 0.1);
    if (isDisconnecting) return Colors.red.withValues(alpha: 0.1);
    return Colors.white.withValues(alpha: 0.05);
  }

  String _formatDuration(Duration d) {
    String twoDigits(int n) => n.toString().padLeft(2, '0');
    final hours = twoDigits(d.inHours);
    final minutes = twoDigits(d.inMinutes.remainder(60));
    final seconds = twoDigits(d.inSeconds.remainder(60));
    return '$hours:$minutes:$seconds';
  }

  @override
  Widget build(BuildContext context) {
    final timerFontSize = (56 * scale).clamp(32.0, 56.0);
    final badgeFontSize = (12 * scale).clamp(10.0, 12.0);
    final gap = (24 * scale).clamp(12.0, 24.0);

    return Column(
      children: [
        Container(
          padding: const EdgeInsets.symmetric(horizontal: 20, vertical: 8),
          decoration: BoxDecoration(
            color: _backgroundColor,
            borderRadius: BorderRadius.circular(30),
            border: Border.all(color: _statusColor.withValues(alpha: 0.2)),
          ),
          child: Row(
            mainAxisSize: MainAxisSize.min,
            children: [
              if (isConnecting || isDisconnecting)
                SizedBox(
                  width: 16,
                  height: 16,
                  child: CircularProgressIndicator(
                    strokeWidth: 2,
                    valueColor: AlwaysStoppedAnimation<Color>(_statusColor),
                  ),
                )
              else
                Icon(_statusIcon, size: 16, color: _statusColor),
              const SizedBox(width: 8),
              Text(
                _statusText(context),
                style: TextStyle(
                  color: _statusColor,
                  fontSize: badgeFontSize,
                  fontWeight: FontWeight.bold,
                  letterSpacing: 1.0,
                ),
              ),
            ],
          ),
        ),
        SizedBox(height: gap),
        Text(
          isConnected || isConnecting || isDisconnecting
              ? _formatDuration(duration)
              : '--:--:--',
          style: TextStyle(
            fontFamily: 'Inter',
            fontSize: timerFontSize,
            fontWeight: FontWeight.w300,
            color: Colors.white,
            letterSpacing: -2.0,
          ),
        ),
      ],
    );
  }
}
