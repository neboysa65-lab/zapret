import 'dart:math' as math;
import 'package:flutter/material.dart';
import '../config/app_theme.dart';

class PowerButton extends StatefulWidget {
  final bool isConnected;
  final bool isProcessing;
  final bool canCancel;
  final double scale;
  final VoidCallback onTap;

  const PowerButton({
    super.key,
    required this.isConnected,
    this.isProcessing = false,
    this.canCancel = false,
    this.scale = 1.0,
    required this.onTap,
  });

  @override
  State<PowerButton> createState() => _PowerButtonState();
}

class _PowerButtonState extends State<PowerButton>
    with TickerProviderStateMixin {
  late AnimationController _pulseController;
  late AnimationController _spinController;

  @override
  void initState() {
    super.initState();
    _pulseController = AnimationController(
      vsync: this,
      duration: const Duration(seconds: 3),
    )..repeat();

    _spinController = AnimationController(
      vsync: this,
      duration: const Duration(seconds: 20),
    )..repeat();
  }

  @override
  void dispose() {
    _pulseController.dispose();
    _spinController.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    final s = widget.scale;
    final containerSize = (300 * s).clamp(180.0, 300.0);
    final outerSize = (160 * s).clamp(100.0, 160.0);
    final innerSize = (128 * s).clamp(80.0, 128.0);
    final iconSize = (64 * s).clamp(40.0, 64.0);
    final ringSize = (256 * s).clamp(160.0, 256.0);
    final particleContainerSize = (288 * s).clamp(170.0, 288.0);

    return SizedBox(
      width: containerSize,
      height: containerSize,
      child: Stack(
        alignment: Alignment.center,
        children: [
          // Pulse Rings (only when connected)
          if (widget.isConnected) ...[
            _buildPulseRing(0, ringSize),
            _buildPulseRing(1.0, ringSize),
            _buildPulseRing(2.0, ringSize),
            // Rotating Particles
            RotationTransition(
              turns: _spinController,
              child: SizedBox(
                width: particleContainerSize,
                height: particleContainerSize,
                child: Stack(
                  children: [
                    _buildParticle(Alignment.topCenter),
                    _buildParticle(Alignment.bottomCenter),
                    _buildParticle(Alignment.centerLeft),
                    _buildParticle(Alignment.centerRight),
                    _buildParticle(const Alignment(-0.5, -0.5)),
                    _buildParticle(const Alignment(0.5, 0.5)),
                  ],
                ),
              ),
            ),
          ],

          // Main Button
          Semantics(
            button: true,
            label: widget.canCancel
                ? (Localizations.localeOf(context).languageCode == 'ru'
                      ? 'Отменить подключение'
                      : 'Cancel connection')
                : null,
            child: GestureDetector(
              onTap: widget.isProcessing && !widget.canCancel
                  ? null
                  : widget.onTap,
              child: AnimatedBuilder(
                animation: _pulseController,
                builder: (context, child) {
                  final pulse =
                      0.6 +
                      (0.4 *
                          (1.0 -
                              (math
                                  .sin(_pulseController.value * 2 * math.pi)
                                  .abs())));

                  return AnimatedContainer(
                    duration: const Duration(milliseconds: 300),
                    width: outerSize,
                    height: outerSize,
                    decoration: BoxDecoration(
                      shape: BoxShape.circle,
                      gradient: widget.isConnected
                          ? const LinearGradient(
                              begin: Alignment.topCenter,
                              end: Alignment.bottomCenter,
                              colors: [
                                AppColors.primaryGreen,
                                AppColors.primaryDark,
                              ],
                            )
                          : LinearGradient(
                              begin: Alignment.topCenter,
                              end: Alignment.bottomCenter,
                              colors: [
                                Colors.grey.shade700,
                                Colors.grey.shade800,
                              ],
                            ),
                      boxShadow: widget.isConnected
                          ? [
                              BoxShadow(
                                color: AppColors.primaryGreen.withValues(
                                  alpha: 0.6 * pulse,
                                ),
                                blurRadius: 60,
                                spreadRadius: 5 * pulse,
                              ),
                            ]
                          : [
                              BoxShadow(
                                color: Colors.black.withValues(alpha: 0.3),
                                blurRadius: 10,
                                offset: const Offset(0, 4),
                              ),
                            ],
                      border: Border.all(
                        color: widget.isConnected
                            ? const Color(0xFF1F2515)
                            : Colors.grey.shade900,
                        width: 4,
                      ),
                    ),
                    child: Center(
                      child: AnimatedContainer(
                        duration: const Duration(milliseconds: 300),
                        width: innerSize,
                        height: innerSize,
                        decoration: BoxDecoration(
                          shape: BoxShape.circle,
                          color: widget.isConnected
                              ? AppColors.primaryGreen
                              : AppColors.cardBackground,
                          border: Border.all(
                            color: widget.isConnected
                                ? Colors.white.withValues(alpha: 0.2)
                                : Colors.grey.shade600,
                            width: 1,
                          ),
                          boxShadow: const [
                            BoxShadow(
                              color: Colors.black26,
                              blurRadius: 4,
                              offset: Offset(0, 2),
                            ),
                          ],
                        ),
                        child: Center(
                          child: Icon(
                            widget.canCancel
                                ? Icons.close_rounded
                                : Icons.power_settings_new_rounded,
                            size: iconSize,
                            color: widget.isConnected
                                ? Colors.black
                                : Colors.grey.shade500,
                          ),
                        ),
                      ),
                    ),
                  );
                },
              ),
            ),
          ),
        ],
      ),
    );
  }

  Widget _buildPulseRing(double delaySeconds, double size) {
    return AnimatedBuilder(
      animation: _pulseController,
      builder: (context, child) {
        double t = (_pulseController.value + (delaySeconds / 3.0)) % 1.0;
        double scale = 0.8 + (t * 0.5);
        double opacity = 0.5 * (1.0 - t);

        return Transform.scale(
          scale: scale,
          child: Container(
            width: size,
            height: size,
            decoration: BoxDecoration(
              shape: BoxShape.circle,
              border: Border.all(
                color: AppColors.primaryGreen.withValues(alpha: opacity),
                width: 1,
              ),
            ),
          ),
        );
      },
    );
  }

  Widget _buildParticle(Alignment alignment) {
    return Align(
      alignment: alignment,
      child: Container(
        width: 6,
        height: 6,
        decoration: BoxDecoration(
          color: AppColors.primaryGreen,
          shape: BoxShape.circle,
          boxShadow: [
            BoxShadow(
              color: AppColors.primaryGreen.withValues(alpha: 0.5),
              blurRadius: 1,
            ),
          ],
        ),
      ),
    );
  }
}
