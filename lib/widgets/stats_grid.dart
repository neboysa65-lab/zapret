import 'package:flutter/material.dart';
import '../config/app_theme.dart';

import 'dart:math' as math;
import 'package:vpn_subscription_app/l10n/generated/app_localizations.dart';

class StatsGrid extends StatelessWidget {
  final int download;
  final int upload;
  final bool isConnected;
  final double scale;

  const StatsGrid({
    super.key,
    required this.download,
    required this.upload,
    required this.isConnected,
    this.scale = 1.0,
  });

  @override
  Widget build(BuildContext context) {
    final l10n = AppLocalizations.of(context)!;
    return Row(
      children: [
        Expanded(
          child: _buildCard(
            title: l10n.download,
            bytes: download,
            icon: Icons.arrow_downward,
            color: Colors.greenAccent,
            barColor: Colors.green,
          ),
        ),
        const SizedBox(width: 16),
        Expanded(
          child: _buildCard(
            title: l10n.upload,
            bytes: upload,
            icon: Icons.arrow_upward,
            color: Colors.orangeAccent,
            barColor: Colors.orange,
          ),
        ),
      ],
    );
  }

  Widget _buildCard({
    required String title,
    required int bytes,
    required IconData icon,
    required Color color,
    required Color barColor,
  }) {
    // Format bytes
    final String formattedValue;
    final String unit;

    if (bytes <= 0) {
      formattedValue = "0.0";
      unit = "KB/s";
    } else {
      const suffixes = ["B/s", "KB/s", "MB/s", "GB/s"];
      var i = (math.log(bytes) / math.log(1024)).floor();
      // Prevent index out of bounds
      if (i < 0) i = 0;
      if (i >= suffixes.length) i = suffixes.length - 1;

      formattedValue = (bytes / math.pow(1024, i)).toStringAsFixed(1);
      unit = suffixes[i];
    }

    // Scale dimensions
    final double padding = 16.0 * scale.clamp(0.8, 1.0);
    // Reduced height logic for small screens as requested
    final double chartHeight = 32.0 * scale;
    final double fontSizeValue = 24.0 * scale;
    final double fontSizeUnit = 12.0 * scale;

    return Container(
      padding: EdgeInsets.all(padding),
      decoration: BoxDecoration(
        color: AppColors.cardBackground,
        borderRadius: BorderRadius.circular(24 * scale),
        border: Border.all(color: Colors.grey.shade800),
      ),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Row(
            mainAxisAlignment: MainAxisAlignment.center,
            children: [
              Icon(icon, color: color, size: 16 * scale),
              SizedBox(width: 4 * scale),
              Text(
                title,
                style: TextStyle(
                  color: Colors.grey.shade500,
                  fontSize: 10 * scale,
                  fontWeight: FontWeight.bold,
                  letterSpacing: 1.0,
                ),
              ),
            ],
          ),
          SizedBox(height: 8 * scale),
          Center(
            child: RichText(
              text: TextSpan(
                style: TextStyle(
                  fontFamily: 'Inter',
                  fontSize: fontSizeValue,
                  fontWeight: FontWeight.bold,
                  color: Colors.white,
                ),
                children: [
                  TextSpan(text: isConnected ? formattedValue : '0.0'),
                  TextSpan(
                    text: ' $unit',
                    style: TextStyle(
                      fontSize: fontSizeUnit,
                      color: Colors.grey.shade500,
                      fontWeight: FontWeight.normal,
                    ),
                  ),
                ],
              ),
            ),
          ),
          SizedBox(height: 12 * scale),
          SizedBox(
            height: chartHeight,
            child: BarChart(
              color: barColor,
              value: bytes.toDouble(), // Used for animation variance
              isConnected: isConnected,
            ),
          ),
        ],
      ),
    );
  }
}

class BarChart extends StatelessWidget {
  final Color color;
  final double value;
  final bool isConnected;

  const BarChart({
    super.key,
    required this.color,
    required this.value,
    required this.isConnected,
  });

  @override
  Widget build(BuildContext context) {
    final bars = [30, 50, 40, 70, 90, 60, 80, 40];

    return Row(
      mainAxisAlignment: MainAxisAlignment.spaceEvenly,
      crossAxisAlignment: CrossAxisAlignment.end,
      children: List.generate(bars.length, (index) {
        final baseHeight = bars[index].toDouble();
        // Modify height based on value to create movement effect if we were animating
        // Here we just use value to alter the static height slightly or use random
        // In React it was: `isConnected ? Math.min(100, Math.max(10, h + (value % 10) * (i % 2 === 0 ? 1 : -1))) : 10`

        double height = 10.0;
        if (isConnected) {
          double modifier = (value % 10) * (index % 2 == 0 ? 1 : -1);
          height = (baseHeight + modifier).clamp(10.0, 100.0);
        }

        return Flexible(
          child: FractionallySizedBox(
            heightFactor: height / 100,
            child: Container(
              width: 4,
              decoration: BoxDecoration(
                color: isConnected ? color : color.withOpacity(0.2),
                borderRadius: const BorderRadius.vertical(
                  top: Radius.circular(2),
                ),
              ),
            ),
          ),
        );
      }),
    );
  }
}
