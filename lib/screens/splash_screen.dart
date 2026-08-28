import 'package:flutter/material.dart';
import 'package:provider/provider.dart';
import '../config/app_theme.dart';
import '../providers/auth_provider.dart';

class SplashScreen extends StatefulWidget {
  const SplashScreen({super.key});

  @override
  State<SplashScreen> createState() => _SplashScreenState();
}

class _SplashScreenState extends State<SplashScreen>
    with TickerProviderStateMixin {
  late AnimationController _pulseController;
  late AnimationController _progressController;
  late Animation<double> _pulseAnimation;
  late Animation<double> _progressAnimation;

  @override
  void initState() {
    super.initState();

    _pulseController = AnimationController(
      duration: const Duration(milliseconds: 1500),
      vsync: this,
    )..repeat(reverse: true);

    _pulseAnimation = Tween<double>(begin: 1.0, end: 1.1).animate(
      CurvedAnimation(parent: _pulseController, curve: Curves.easeInOut),
    );

    _progressController = AnimationController(
      duration: const Duration(seconds: 2),
      vsync: this,
    )..repeat();

    _progressAnimation = Tween<double>(begin: -1.0, end: 1.0).animate(
      CurvedAnimation(parent: _progressController, curve: Curves.easeInOut),
    );

    _navigateAfterAuth();
  }

  Future<void> _navigateAfterAuth() async {
    await Future.delayed(const Duration(milliseconds: 2500));
    if (!mounted) return;
    final auth = context.read<AuthProvider>();
    await auth.tryAutoLogin();
    if (!mounted) return;
    if (auth.state == AuthState.authenticated) {
      Navigator.pushReplacementNamed(context, '/main');
    } else {
      Navigator.pushReplacementNamed(context, '/welcome');
    }
  }

  @override
  void dispose() {
    _pulseController.dispose();
    _progressController.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      backgroundColor: AppColors.darkBackground,
      body: Stack(
        children: [
          Center(
            child: Column(
              mainAxisAlignment: MainAxisAlignment.center,
              children: [
                // Logo image — black pixels → transparent via ColorFilter.matrix,
                // glow applied as an ellipse behind the image (not a rectangle)
                Stack(
                  alignment: Alignment.center,
                  children: [
                    // Elliptical neon glow behind logo
                    AnimatedBuilder(
                      animation: _pulseController,
                      builder: (context, child) => Container(
                        width: 260,
                        height: 110,
                        decoration: BoxDecoration(
                          borderRadius: BorderRadius.circular(55),
                          boxShadow: [
                            BoxShadow(
                              color: AppColors.primaryGreen.withOpacity(
                                  0.08 + 0.14 * _pulseController.value),
                              blurRadius: 90,
                              spreadRadius: 0,
                            ),
                          ],
                        ),
                      ),
                    ),
                    // Logo: converts black (#000000) → transparent
                    ScaleTransition(
                      scale: _pulseAnimation,
                      child: const ColorFiltered(
                        colorFilter: ColorFilter.matrix(<double>[
                          1, 0, 0, 0, 0,  // R' = R
                          0, 1, 0, 0, 0,  // G' = G
                          0, 0, 1, 0, 0,  // B' = B
                          // A' = luminance(R,G,B): black→0, bright green→~0.9
                          0.35, 0.55, 0.1, 0, 0,
                        ]),
                        child: Image(
                          image: AssetImage('assets/images/logo.png'),
                          width: 220,
                          filterQuality: FilterQuality.high,
                        ),
                      ),
                    ),
                  ],
                ),

                const SizedBox(height: 72),

                // Loading bar with rounded corners
                Container(
                  width: 140,
                  height: 4,
                  decoration: BoxDecoration(
                    color: Colors.grey.shade800,
                    borderRadius: BorderRadius.circular(4),
                  ),
                  clipBehavior: Clip.hardEdge,
                  child: AnimatedBuilder(
                    animation: _progressAnimation,
                    builder: (context, child) {
                      return FractionallySizedBox(
                        alignment: Alignment(_progressAnimation.value, 0),
                        widthFactor: 0.45,
                        child: Container(
                          decoration: BoxDecoration(
                            gradient: LinearGradient(
                              colors: [
                                AppColors.primaryGreen.withOpacity(0.3),
                                AppColors.primaryGreen,
                                AppColors.primaryGreen.withOpacity(0.3),
                              ],
                            ),
                            borderRadius: BorderRadius.circular(4),
                          ),
                        ),
                      );
                    },
                  ),
                ),
              ],
            ),
          ),

          const Positioned(
            bottom: 32,
            left: 0,
            right: 0,
            child: Text(
              'INITIALIZING SECURE CORE',
              textAlign: TextAlign.center,
              style: TextStyle(
                color: Colors.grey,
                fontSize: 10,
                letterSpacing: 2.0,
              ),
            ),
          ),
        ],
      ),
    );
  }
}
