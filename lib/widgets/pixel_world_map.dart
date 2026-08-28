import 'dart:math';
import 'package:flutter/material.dart';
import '../config/app_theme.dart';

class PixelWorldMap extends StatefulWidget {
  final bool isConnected;

  const PixelWorldMap({super.key, required this.isConnected});

  @override
  State<PixelWorldMap> createState() => _PixelWorldMapState();
}

class _PixelWorldMapState extends State<PixelWorldMap>
    with SingleTickerProviderStateMixin {
  late List<Point> _points;
  late AnimationController _controller;

  @override
  void initState() {
    super.initState();
    _points = _generatePoints();
    _controller = AnimationController(
      vsync: this,
      duration: const Duration(seconds: 4),
    )..repeat();
  }

  @override
  void dispose() {
    _controller.dispose();
    super.dispose();
  }

  List<Point> _generatePoints() {
    final pts = <Point>[];
    const width = 120;
    const height = 60;

    // Shapes: x, y (percentage), rx, ry
    final shapes = [
      {'x': 22.0, 'y': 25.0, 'rx': 18.0, 'ry': 12.0}, // North America
      {'x': 28.0, 'y': 65.0, 'rx': 7.0, 'ry': 14.0}, // South America
      {'x': 52.0, 'y': 22.0, 'rx': 8.0, 'ry': 6.0}, // Europe
      {'x': 56.0, 'y': 45.0, 'rx': 11.0, 'ry': 12.0}, // Africa
      {'x': 78.0, 'y': 25.0, 'rx': 18.0, 'ry': 12.0}, // Asia
      {'x': 88.0, 'y': 70.0, 'rx': 8.0, 'ry': 7.0}, // Australia
      {'x': 40.0, 'y': 10.0, 'rx': 8.0, 'ry': 4.0}, // Greenland
      {'x': 95.0, 'y': 30.0, 'rx': 3.0, 'ry': 6.0}, // Japan
      {'x': 80.0, 'y': 55.0, 'rx': 6.0, 'ry': 4.0}, // Indonesia
      {'x': 65.0, 'y': 60.0, 'rx': 2.0, 'ry': 4.0}, // Madagascar
    ];

    double noise(int x, int y) {
      return sin(x * 0.5) * cos(y * 0.5);
    }

    final random = Random();

    for (int y = 0; y < height; y++) {
      for (int x = 0; x < width; x++) {
        bool isLand = false;
        final px = (x / width) * 100;
        final py = (y / height) * 100;

        for (final s in shapes) {
          final dx = (px - s['x']!) / s['rx']!;
          final dy = (py - s['y']!) / s['ry']!;
          final dist = dx * dx + dy * dy;
          final n = noise(x, y) * 0.3;

          if (dist + n < 1.0) {
            isLand = true;
            break;
          }
        }

        if (isLand && random.nextDouble() > 0.85) isLand = false;
        if (!isLand && random.nextDouble() > 0.992) isLand = true;

        if (isLand) {
          pts.add(
            Point(
              x: x.toDouble(),
              y: y.toDouble(),
              delay: random.nextDouble() * 5,
              opacity: 0.3 + random.nextDouble() * 0.7,
            ),
          );
        }
      }
    }
    return pts;
  }

  @override
  Widget build(BuildContext context) {
    // React implementation uses min-w-[800px] and w-[140%]
    // The map is essentially zoomed in or large, centered on screen.
    return Stack(
      children: [
        // Map Layer
        OverflowBox(
          maxWidth: double.infinity,
          maxHeight: double.infinity,
          alignment: Alignment.center,
          child: SizedBox(
            // We force a large width to replicate "min-w-[800px]"
            // On mobile (e.g. 390px width), 800px is roughly 2x zoom
            width: 800,
            height: 400, // Aspect ratio 2:1 (120x60 grid)
            child: CustomPaint(
              painter: WorldMapPainter(
                points: _points,
                isConnected: widget.isConnected,
                animationValue: _controller,
              ),
            ),
          ),
        ),

        // Vignette Overlay
        Positioned.fill(
          child: Container(
            decoration: BoxDecoration(
              gradient: RadialGradient(
                center: Alignment.center,
                radius:
                    0.7, // Slightly tighter radius to match React "transparent_30%" roughly
                colors: [
                  Colors.transparent,
                  AppColors.darkBackground.withOpacity(
                    0.5,
                  ), // Softer transition
                  AppColors.darkBackground,
                ],
                stops: const [0.3, 0.65, 1.0],
              ),
            ),
          ),
        ),
      ],
    );
  }
}

class Point {
  final double x;
  final double y;
  final double delay;
  final double opacity;

  Point({
    required this.x,
    required this.y,
    required this.delay,
    required this.opacity,
  });
}

class WorldMapPainter extends CustomPainter {
  final List<Point> points;
  final bool isConnected;
  final Animation<double> animationValue;

  WorldMapPainter({
    required this.points,
    required this.isConnected,
    required this.animationValue,
  }) : super(repaint: animationValue);

  @override
  void paint(Canvas canvas, Size size) {
    final paint = Paint();
    final pixelW = size.width / 120;
    final pixelH = size.height / 60;

    // React uses rect width="0.35" in a 1.0 grid.
    final dotSizeW = pixelW * 0.35;
    final dotSizeH = pixelH * 0.35;

    for (final p in points) {
      double opacity = p.opacity;

      if (isConnected) {
        // Twinkle effect
        // Simple approx of the CSS animation logic:
        // 0% -> 0.2, 50% -> 0.8, 100% -> 0.2
        // We use a sine wave based on time + delay
        final t = (animationValue.value + p.delay) % 1.0;
        // 0..1
        final wave = sin(t * 2 * pi); // -1..1
        // Map to 0.2 .. 0.8
        // (wave + 1) / 2 => 0..1
        final normalized = (wave + 1) / 2;
        opacity = 0.2 + (normalized * 0.6);
      } else {
        opacity = 0.05 + (p.opacity * 0.1); // Dim when not connected
      }

      paint.color = (isConnected ? AppColors.primaryGreen : Colors.grey)
          .withOpacity(opacity.clamp(0.0, 1.0));

      // Draw rect
      canvas.drawRect(
        Rect.fromLTWH(p.x * pixelW, p.y * pixelH, dotSizeW, dotSizeH),
        paint,
      );
    }
  }

  @override
  bool shouldRepaint(covariant WorldMapPainter oldDelegate) {
    return isConnected != oldDelegate.isConnected ||
        oldDelegate.animationValue != animationValue;
  }
}
