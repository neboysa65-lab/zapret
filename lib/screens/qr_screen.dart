import 'dart:math' as math;
import 'dart:typed_data';
import 'package:flutter/material.dart';
import 'package:provider/provider.dart';
import '../config/app_theme.dart';
import '../providers/auth_provider.dart';
import '../services/auth_service.dart';
import '../services/user_api_service.dart';

class QrScreen extends StatefulWidget {
  const QrScreen({super.key});
  @override
  State<QrScreen> createState() => _QrScreenState();
}

class _QrScreenState extends State<QrScreen>
    with SingleTickerProviderStateMixin {
  Uint8List? _qrBytes;
  bool _loading = true;
  String? _error;
  late AnimationController _glowController;
  late Animation<double> _glowAnimation;

  @override
  void initState() {
    super.initState();
    _glowController = AnimationController(
      duration: const Duration(seconds: 3),
      vsync: this,
    )..repeat(reverse: true);
    _glowAnimation = Tween<double>(begin: 0.4, end: 1.0).animate(
      CurvedAnimation(parent: _glowController, curve: Curves.easeInOut),
    );
    _loadQr();
  }

  @override
  void dispose() {
    _glowController.dispose();
    super.dispose();
  }

  Future<void> _loadQr() async {
    setState(() {
      _loading = true;
      _error = null;
    });
    try {
      final seedHash = await AuthService.loadSeedHash();
      if (seedHash == null) throw Exception('Not authenticated');
      final bytes = await UserApiService(seedHash).getQrBytes();
      setState(() {
        _qrBytes = Uint8List.fromList(bytes);
        _loading = false;
      });
    } catch (e) {
      setState(() {
        _error = 'Не удалось загрузить QR';
        _loading = false;
      });
    }
  }

  @override
  Widget build(BuildContext context) {
    final auth = context.watch<AuthProvider>();
    final userId = auth.user?.userId ?? '';

    return Scaffold(
      backgroundColor: AppColors.darkBackground,
      body: Stack(
        children: [
          // Gradient background
          Positioned.fill(
            child: CustomPaint(painter: _QrBackgroundPainter(_glowAnimation)),
          ),

          SafeArea(
            child: Column(
              children: [
                // AppBar
                Padding(
                  padding: const EdgeInsets.symmetric(
                    horizontal: 20,
                    vertical: 12,
                  ),
                  child: Row(
                    children: [
                      Text(
                        'QR-код',
                        style: Theme.of(context).textTheme.headlineMedium,
                      ),
                      const Spacer(),
                      IconButton(
                        onPressed: _loadQr,
                        icon: const Icon(
                          Icons.refresh_rounded,
                          color: AppColors.primaryGreen,
                        ),
                        style: IconButton.styleFrom(
                          backgroundColor:
                              AppColors.primaryGreen.withOpacity(0.1),
                          shape: RoundedRectangleBorder(
                            borderRadius: BorderRadius.circular(12),
                          ),
                        ),
                      ),
                    ],
                  ),
                ),

                Expanded(
                  child: Center(
                    child: Padding(
                      padding: const EdgeInsets.symmetric(horizontal: 32),
                      child: Column(
                        mainAxisAlignment: MainAxisAlignment.center,
                        children: [
                          // Subtitle
                          Text(
                            'Покажите код администратору\nдля идентификации в поддержке',
                            textAlign: TextAlign.center,
                            style: TextStyle(
                              fontSize: 13,
                              color: AppColors.textSecondary,
                              height: 1.5,
                            ),
                          ),

                          const SizedBox(height: 36),

                          // QR container
                          if (_loading)
                            const SizedBox(
                              width: 260,
                              height: 260,
                              child: Center(
                                child: CircularProgressIndicator(
                                  color: AppColors.primaryGreen,
                                  strokeWidth: 2,
                                ),
                              ),
                            )
                          else if (_error != null)
                            _ErrorBlock(onRetry: _loadQr)
                          else if (_qrBytes != null)
                            AnimatedBuilder(
                              animation: _glowAnimation,
                              builder: (context, child) {
                                return Container(
                                  width: 268,
                                  height: 268,
                                  decoration: BoxDecoration(
                                    color: Colors.white,
                                    borderRadius: BorderRadius.circular(20),
                                    boxShadow: [
                                      BoxShadow(
                                        color: AppColors.primaryGreen
                                            .withOpacity(
                                                0.25 * _glowAnimation.value),
                                        blurRadius: 40,
                                        spreadRadius: 4,
                                      ),
                                    ],
                                  ),
                                  padding: const EdgeInsets.all(14),
                                  child: ClipRRect(
                                    borderRadius: BorderRadius.circular(8),
                                    child: Image.memory(
                                      _qrBytes!,
                                      fit: BoxFit.contain,
                                      filterQuality: FilterQuality.none,
                                    ),
                                  ),
                                );
                              },
                            ),

                          const SizedBox(height: 24),

                          // User ID badge
                          if (userId.isNotEmpty)
                            Container(
                              padding: const EdgeInsets.symmetric(
                                  horizontal: 16, vertical: 8),
                              decoration: BoxDecoration(
                                color: AppColors.cardBackground,
                                borderRadius: BorderRadius.circular(24),
                                border: Border.all(
                                    color: AppColors.primaryGreen
                                        .withOpacity(0.2)),
                              ),
                              child: Row(
                                mainAxisSize: MainAxisSize.min,
                                children: [
                                  Icon(
                                    Icons.fingerprint,
                                    size: 14,
                                    color:
                                        AppColors.primaryGreen.withOpacity(0.7),
                                  ),
                                  const SizedBox(width: 6),
                                  Text(
                                    userId.length > 20
                                        ? '${userId.substring(0, 8)}...${userId.substring(userId.length - 8)}'
                                        : userId,
                                    style: const TextStyle(
                                      fontSize: 12,
                                      color: AppColors.textSecondary,
                                      fontFamily: 'monospace',
                                    ),
                                  ),
                                ],
                              ),
                            ),
                        ],
                      ),
                    ),
                  ),
                ),
              ],
            ),
          ),
        ],
      ),
    );
  }
}

class _ErrorBlock extends StatelessWidget {
  final VoidCallback onRetry;
  const _ErrorBlock({required this.onRetry});

  @override
  Widget build(BuildContext context) {
    return Column(
      children: [
        const Icon(Icons.qr_code_2, size: 64, color: AppColors.textTertiary),
        const SizedBox(height: 12),
        const Text(
          'Не удалось загрузить QR',
          style: TextStyle(color: AppColors.textSecondary),
        ),
        const SizedBox(height: 16),
        FilledButton.icon(
          onPressed: onRetry,
          icon: const Icon(Icons.refresh, size: 16),
          label: const Text('Повторить'),
        ),
      ],
    );
  }
}

class _QrBackgroundPainter extends CustomPainter {
  final Animation<double> glow;
  _QrBackgroundPainter(this.glow) : super(repaint: glow);

  @override
  void paint(Canvas canvas, Size size) {
    // Top-to-bottom gradient: dark → subtle green in middle → dark
    final rect = Offset.zero & size;
    final gradPaint = Paint()
      ..shader = LinearGradient(
        begin: Alignment.topCenter,
        end: Alignment.bottomCenter,
        colors: [
          const Color(0xFF0B0C0E),
          Color.lerp(
                  const Color(0xFF0B0C0E),
                  const Color(0xFF1A2E0A),
                  0.55 * glow.value)!,
          const Color(0xFF0B0C0E),
        ],
        stops: const [0.0, 0.5, 1.0],
      ).createShader(rect);
    canvas.drawRect(rect, gradPaint);

    // Neon globe arc at bottom
    final cx = size.width / 2;
    final cy = size.height + size.width * 0.15;
    final radius = size.width * 0.88;

    final arcPaint = Paint()
      ..style = PaintingStyle.stroke
      ..strokeWidth = 1.2
      ..maskFilter = const MaskFilter.blur(BlurStyle.normal, 4);

    // Draw 4 concentric neon arcs fading outward
    for (int i = 0; i < 4; i++) {
      final r = radius + i * 28.0;
      final opacity = (0.18 - i * 0.04) * glow.value;
      arcPaint.color = const Color(0xFFA3FF00).withOpacity(opacity.clamp(0, 1));
      canvas.drawArc(
        Rect.fromCircle(center: Offset(cx, cy), radius: r),
        math.pi * 1.15,
        math.pi * 0.7,
        false,
        arcPaint,
      );
    }

    // Latitude lines (horizontal curves) across the globe
    final latPaint = Paint()
      ..style = PaintingStyle.stroke
      ..strokeWidth = 0.7
      ..maskFilter = const MaskFilter.blur(BlurStyle.normal, 2);

    for (int i = 1; i <= 3; i++) {
      final yOffset = size.height * 0.72 + i * 22.0;
      if (yOffset > size.height) break;
      final opacity = (0.1 - i * 0.025) * glow.value;
      latPaint.color =
          const Color(0xFFA3FF00).withOpacity(opacity.clamp(0, 1));
      // approximate arc as bezier to span width
      final path = Path();
      path.moveTo(cx - size.width * 0.42, yOffset);
      path.quadraticBezierTo(
          cx, yOffset + 6 * (i * 0.5), cx + size.width * 0.42, yOffset);
      canvas.drawPath(path, latPaint);
    }
  }

  @override
  bool shouldRepaint(_QrBackgroundPainter old) => true;
}
